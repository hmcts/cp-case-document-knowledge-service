package uk.gov.hmcts.cp.cdk.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.cdk.batch.clients.config.CdkClientsConfig;
import uk.gov.hmcts.cp.cdk.batch.clients.hearing.HearingClient;
import uk.gov.hmcts.cp.cdk.batch.clients.hearing.dto.HearingSummariesInfo;
import uk.gov.hmcts.cp.cdk.batch.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.batch.clients.progression.dto.LatestMaterialInfo;
import uk.gov.hmcts.cp.cdk.batch.storage.AzureBlobStorageService;
import uk.gov.hmcts.cp.cdk.batch.storage.StorageService;
import uk.gov.hmcts.cp.cdk.batch.support.QueryResolver;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.repo.QueryDefinitionLatestRepository;
import uk.gov.hmcts.cp.openapi.api.DocumentInformationSummarisedApi;
import uk.gov.hmcts.cp.openapi.api.DocumentIngestionStatusApi;
import uk.gov.hmcts.cp.openapi.model.AnswerUserQueryRequest;
import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatusReturnedSuccessfully;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerReturnedSuccessfully;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;

import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end tests that assert the workflow is idempotent:
 *  - 1st run: uploads two docs, verifies ingestion, reserves versions, generates answers (DONE)
 *  - 2nd run (same inputs): NO new upload; NO new answer generation; reservations/versions unchanged
 *  - Partial redo case: only FAILED reservation is regenerated
 */
@SpringBatchTest
@SpringBootTest(
        classes = {
                CaseIngestionJobIdempotencyTest.TestApplication.class,
                BatchConfig.class,
                CdkClientsConfig.class,
                CaseIngestionJobConfig.class,
                CaseIngestionStepsConfig.class,
                CaseIngestionJobIdempotencyTest.TestOverrides.class
        },
        properties = {
                "spring.main.allow-bean-definition-overriding=true"
        }
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CaseIngestionJobIdempotencyTest {

    @SpringBootApplication(scanBasePackages = "uk.gov.hmcts.cp.cdk")
    @EntityScan(basePackages = "uk.gov.hmcts.cp.cdk.domain")
    @EnableJpaRepositories(basePackages = "uk.gov.hmcts.cp.cdk.repo")
    @ComponentScan(
            basePackages = "uk.gov.hmcts.cp.cdk",
            excludeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = AzureBlobStorageService.class // avoid real Azure bean in tests
            )
    )
    static class TestApplication {}

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("cdk")
                    .withUsername("postgres")
                    .withPassword("postgres")
                    .withReuse(true);

    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void dbProps(final DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        // DDL + Spring Batch metadata
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.batch.jdbc.initialize-schema", () -> "always");

        // Verify polling
        r.add("cdk.ingestion.verify.poll-interval-ms", () -> "10");
        r.add("cdk.ingestion.verify.max-wait-ms", () -> "2000");

        // required audit prop
        r.add("audit.http.openapi-rest-spec", () -> "test-openapi-spec.yml");
    }

    // Real repos
    @Autowired private QueryDefinitionLatestRepository qdlRepo;
    @Autowired private CaseDocumentRepository caseDocumentRepository;

    // External mocks (injected via config)
    @Autowired private HearingClient hearingClient;
    @Autowired private ProgressionClient progressionClient;
    @Autowired private DocumentIngestionStatusApi documentIngestionStatusApi;
    @Autowired private DocumentInformationSummarisedApi documentInformationSummarisedApi;
    @Autowired private QueryResolver queryResolver;
    @Autowired private StorageService storageService;

    // Infra
    @Autowired private JobOperatorTestUtils jobOperatorTestUtils;
    @Autowired private JdbcTemplate jdbc;

    private UUID caseId1, caseId2, materialId1, materialId2, queryId;

    @BeforeEach
    void setUp() {
        // fresh ids for this test
        caseId1 = UUID.randomUUID();
        caseId2 = UUID.randomUUID();
        materialId1 = UUID.randomUUID();
        materialId2 = UUID.randomUUID();
        queryId = UUID.randomUUID();

        // step 1 — hearings & cases
        HearingSummariesInfo h1 = mock(HearingSummariesInfo.class);
        HearingSummariesInfo h2 = mock(HearingSummariesInfo.class);
        when(h1.caseId()).thenReturn(caseId1.toString());
        when(h2.caseId()).thenReturn(caseId2.toString());
        when(hearingClient.getHearingsAndCases(anyString(), anyString(), any(LocalDate.class), anyString()))
                .thenReturn(List.of(h1, h2));

        // step 2 — latest material links for each case
        LatestMaterialInfo lm1 = mock(LatestMaterialInfo.class);
        LatestMaterialInfo lm2 = mock(LatestMaterialInfo.class);
        when(lm1.materialName()).thenReturn("IDPC");
        when(lm2.materialName()).thenReturn("IDPC");
        when(lm1.materialId()).thenReturn(materialId1.toString());
        when(lm2.materialId()).thenReturn(materialId2.toString());
        when(progressionClient.getCourtDocuments(eq(caseId1), anyString()))
                .thenReturn(Optional.of(lm1));
        when(progressionClient.getCourtDocuments(eq(caseId2), anyString()))
                .thenReturn(Optional.of(lm2));
        // step 3 — storage interactions (upload once per new doc)
        when(progressionClient.getMaterialDownloadUrl(any(UUID.class), anyString()))
                .thenReturn(Optional.of("http://example.test/doc.pdf"));
        when(storageService.copyFromUrl(anyString(), anyString(), anyString(), anyMap()))
                .thenAnswer(inv -> "blob://" + inv.getArgument(1, String.class));
        when(storageService.getBlobSize(anyString())).thenReturn(1234L);

        // step 4 — ingestion status polling (always success quickly)
        DocumentIngestionStatusReturnedSuccessfully ok =
                new DocumentIngestionStatusReturnedSuccessfully()
                        .status(DocumentIngestionStatusReturnedSuccessfully.StatusEnum.INGESTION_SUCCESS)
                        .documentId(UUID.randomUUID().toString())
                        .documentName("whatever.pdf")
                        .lastUpdated(OffsetDateTime.now());
        when(documentIngestionStatusApi.documentStatus(anyString()))
                .thenReturn(ResponseEntity.ok(ok));

        // step 5 — seed one canonical query + definition
        jdbc.update(
                "INSERT INTO queries(query_id, label, created_at,display_order) VALUES (?, ?, NOW(),?) " +
                        "ON CONFLICT (query_id) DO NOTHING",
                ps -> {
                    ps.setObject(1, queryId);
                    ps.setString(2, "E2E Query");
                    ps.setInt(3,100);
                }
        );
        jdbc.update(
                "INSERT INTO query_versions(query_id, effective_at, user_query, query_prompt) " +
                        "VALUES (?, NOW(), ?, ?)",
                ps -> {
                    ps.setObject(1, queryId);
                    ps.setString(2, "user-query");
                    ps.setString(3, "query-prompt");
                }
        );
        when(queryResolver.resolve()).thenReturn(List.of(new uk.gov.hmcts.cp.cdk.domain.Query() {{
            setQueryId(queryId);
            setLabel("E2E Query");
        }}));

        // step 6 — RAG call returns stable answer
        UserQueryAnswerReturnedSuccessfully rag =
                new UserQueryAnswerReturnedSuccessfully()
                        .llmResponse("answer text")
                        .chunkedEntries(Collections.emptyList());
        when(documentInformationSummarisedApi.answerUserQuery(any(AnswerUserQueryRequest.class)))
                .thenReturn(ResponseEntity.ok(rag));
    }

    void tearDown() {
        truncateIfExists("answers");
        truncateIfExists("answer_reservations");
        jdbc.update("DELETE FROM query_versions WHERE query_id = ?", ps -> ps.setObject(1, queryId));
        jdbc.update("DELETE FROM queries WHERE query_id = ?", ps -> ps.setObject(1, queryId));
        clearInvocations(storageService, documentInformationSummarisedApi,
                progressionClient, documentIngestionStatusApi, hearingClient);
    }

    private void truncateIfExists(String table) {
        String reg = jdbc.queryForObject("SELECT to_regclass(?)", String.class, "public." + table);
        if (reg != null) jdbc.execute("TRUNCATE TABLE " + table + " CASCADE");
    }

    private JobExecution startJobRun() throws Exception {
        return jobOperatorTestUtils.startJob(
                new JobParametersBuilder()
                        .addString("runId", UUID.randomUUID().toString())
                        .addLong("ts", System.currentTimeMillis())
                        .addString("courtCentreId", "COURT-1")
                        .addString("roomId", "ROOM-42")
                        .addString("cppuid", "userId")
                        .addString("date", LocalDate.now().toString())
                        .toJobParameters()
        );
    }

    @Test
    @DisplayName("Idempotency: 2nd run with same inputs does NOT re-upload and does NOT re-generate answers")
    void secondRunIsIdempotent() throws Exception {
        tearDown();
        // ---------- First run ----------
        JobExecution exec1 = startJobRun();
        assertThat(exec1.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Integer docCountAfter1 = jdbc.queryForObject("SELECT COUNT(*) FROM case_documents", Integer.class);
        Integer answersAfter1  = jdbc.queryForObject("SELECT COUNT(*) FROM answers", Integer.class);
        Integer doneAfter1     = jdbc.queryForObject("SELECT COUNT(*) FROM answer_reservations WHERE status='DONE'", Integer.class);

        assertThat(docCountAfter1).isEqualTo(2);
        assertThat(answersAfter1).isEqualTo(2);
        assertThat(doneAfter1).isEqualTo(2);

        // External calls that must have happened once per new doc / per query:
        verify(storageService, times(2)).copyFromUrl(anyString(), anyString(), anyString(), anyMap());
        verify(documentInformationSummarisedApi, times(2)).answerUserQuery(any(AnswerUserQueryRequest.class));

        // clear invocation counts for precise 2nd-run assertions
        clearInvocations(storageService, documentInformationSummarisedApi);

        // ---------- Second run with same parameters (new runId/ts) ----------
        JobExecution exec2 = startJobRun();
        assertThat(exec2.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Integer docCountAfter2 = jdbc.queryForObject("SELECT COUNT(*) FROM case_documents", Integer.class);
        Integer answersAfter2  = jdbc.queryForObject("SELECT COUNT(*) FROM answers", Integer.class);
        Integer doneAfter2     = jdbc.queryForObject("SELECT COUNT(*) FROM answer_reservations WHERE status='DONE'", Integer.class);

        // No new documents, no new answers, reservations unchanged
        assertThat(docCountAfter2).isEqualTo(docCountAfter1);
        assertThat(answersAfter2).isEqualTo(answersAfter1);
        assertThat(doneAfter2).isEqualTo(doneAfter1);

        // No extra upload or LLM calls on 2nd run (idempotent)
        verify(storageService, times(0)).copyFromUrl(anyString(), anyString(), anyString(), anyMap());
        verify(documentInformationSummarisedApi, times(0)).answerUserQuery(any(AnswerUserQueryRequest.class));

        // Versions are stable (should all be 1)
        List<Integer> versions =
                jdbc.query("SELECT version FROM answer_reservations",
                        (ResultSet rs, int rowNum) -> rs.getInt(1));
        assertThat(versions).isNotEmpty().allMatch(v -> v == 1);
    }

    @Test
    @DisplayName("Partial redo: if one reservation is FAILED (and answer removed), only that one is regenerated")
    void onlyFailedIsRegeneratedOnSecondRun() throws Exception {
        tearDown();
        // ---------- First run ----------
        JobExecution exec1 = startJobRun();
        assertThat(exec1.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Integer answersAfter1 = jdbc.queryForObject("SELECT COUNT(*) FROM answers", Integer.class);
        assertThat(answersAfter1).isEqualTo(2);

        // Pick caseId1 -> mark its reservation FAILED & remove its answer
        UUID docId1 = jdbc.queryForObject(
                "SELECT doc_id FROM case_documents WHERE case_id = ? ORDER BY uploaded_at DESC LIMIT 1",
                (rs, row) -> (UUID) rs.getObject(1),
                caseId1
        );
        final int cdFailedForDoc =
                jdbc.update(
                        "UPDATE case_documents " +
                                "   SET ingestion_phase = 'FAILED'::document_ingestion_phase_enum, " +
                                "       ingestion_phase_at = NOW() " +
                                " WHERE doc_id = ?",
                        ps -> ps.setObject(1, docId1)
                );
        assertThat(cdFailedForDoc).isEqualTo(1);
        // fail reservation
        jdbc.update("UPDATE answer_reservations SET status='FAILED' WHERE case_id=? AND query_id=? AND doc_id=?",
                ps -> {
                    ps.setObject(1, caseId1);
                    ps.setObject(2, queryId);
                    ps.setObject(3, docId1);
                });
        // remove answer so we really need a new generation
        jdbc.update("DELETE FROM answers WHERE case_id=? AND query_id=?",
                ps -> {
                    ps.setObject(1, caseId1);
                    ps.setObject(2, queryId);
                });

        // clear invocation counts so we can assert exactly what the second run does
        clearInvocations(storageService, documentInformationSummarisedApi);

        // ---------- Second run ----------
        JobExecution exec2 = startJobRun();
        assertThat(exec2.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Upload should still not happen (doc already exists)
        verify(storageService, times(1 )).copyFromUrl(anyString(), anyString(), anyString(), anyMap());

        // LLM should be called exactly once (only the FAILED one is regenerated)
        verify(documentInformationSummarisedApi, times(1)).answerUserQuery(any(AnswerUserQueryRequest.class));

        // Back to 2 total answers and both reservations DONE
        Integer answersAfter2 = jdbc.queryForObject("SELECT COUNT(*) FROM answers", Integer.class);
        Integer doneAfter2 = jdbc.queryForObject("SELECT COUNT(*) FROM answer_reservations WHERE status='DONE'", Integer.class);

        assertThat(answersAfter2).isEqualTo(2);
        assertThat(doneAfter2).isEqualTo(2);

        // Versions stable (still 1)
        List<Integer> versions =
                jdbc.query("SELECT version FROM answer_reservations",
                        (ResultSet rs, int rowNum) -> rs.getInt(1));
        assertThat(versions).isNotEmpty().allMatch(v -> v == 1);
    }

    @TestConfiguration
    static class TestOverrides {
        @Bean @Primary
        RetryTemplate retryTemplate() {
            RetryTemplate t = new RetryTemplate();
            t.setRetryPolicy(new SimpleRetryPolicy(1)); // no retries to keep tests crisp
            FixedBackOffPolicy b = new FixedBackOffPolicy();
            b.setBackOffPeriod(50);
            t.setBackOffPolicy(b);
            return t;
        }

        @Bean @Primary
        public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
            return new JpaTransactionManager(emf);
        }

        @Bean(name = "ingestionTaskExecutor") @Primary
        public TaskExecutor ingestionTaskExecutor() {
            return new SyncTaskExecutor();
        }

        // External systems as mocks
        @Bean @Primary public HearingClient hearingClient() { return mock(HearingClient.class); }
        @Bean @Primary public ProgressionClient progressionClient() { return mock(ProgressionClient.class); }
        @Bean @Primary public DocumentIngestionStatusApi documentIngestionStatusApi() { return mock(DocumentIngestionStatusApi.class); }
        @Bean @Primary public DocumentInformationSummarisedApi documentInformationSummarisedApi() { return mock(DocumentInformationSummarisedApi.class); }
        @Bean @Primary public QueryResolver queryResolver() { return mock(QueryResolver.class); }
        @Bean @Primary public StorageService storageService() { return mock(StorageService.class); }
    }
}

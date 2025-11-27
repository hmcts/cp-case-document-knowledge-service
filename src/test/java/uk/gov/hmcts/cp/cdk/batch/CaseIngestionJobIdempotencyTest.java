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
import uk.gov.hmcts.cp.cdk.batch.verification.DocumentVerificationScheduler;
import uk.gov.hmcts.cp.cdk.config.VerifySchedulerProperties;
import uk.gov.hmcts.cp.cdk.domain.DocumentVerificationStatus;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.repo.DocumentVerificationTaskRepository;
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

import com.azure.core.credential.TokenCredential;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
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
 * Pipeline tests across:
 * <p>
 * Case Ingestion Job (caseIngestionJob: steps 1–3, upload + enqueue verification)
 * + DocumentVerificationScheduler
 * + Answer Generation Job (answerGenerationJob: steps 5–6)
 * <p>
 * Scenarios:
 * - Full pipeline idempotency.
 * - Partial redo (FAILED reservation only).
 * - Failure path (IDPC returns FAILED).
 */
@SpringBatchTest
@SpringBootTest(
        classes = {
                CaseIngestionJobIdempotencyTest.TestApplication.class,
                BatchConfig.class,
                CdkClientsConfig.class,
                CaseIngestionJobConfig.class,
                CaseIngestionStepsConfig.class,
                AnswerGenerationJobConfig.class,
                CaseIngestionJobIdempotencyTest.TestOverrides.class
        },
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "cp.audit.enabled=false"
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
    static class TestApplication {
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("cdk")
                    .withUsername("postgres")
                    .withPassword("postgres")
                    .withReuse(true);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void dbProps(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        // DDL + Spring Batch metadata + Flyway
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.batch.jdbc.initialize-schema", () -> "always");

        // New scheduler properties (we drive scheduler manually in tests)
        registry.add("cdk.ingestion.verify.scheduler.enabled", () -> "false");
        registry.add("cdk.ingestion.verify.scheduler.delay-ms", () -> "50");
        registry.add("cdk.ingestion.verify.scheduler.batch-size", () -> "10");
        registry.add("cdk.ingestion.verify.scheduler.max-attempts", () -> "10");
        registry.add("cdk.ingestion.verify.scheduler.lock-ttl-ms", () -> "10000");

        // required audit prop
        registry.add("audit.http.openapi-rest-spec", () -> "test-openapi-spec.yml");

        // Storage
        registry.add("cdk.storage.azure.connection-string",
                () -> "DefaultEndpointsProtocol=http;AccountName=dev;AccountKey=dev;BlobEndpoint=http://localhost:10000/dev;");
        registry.add("cdk.storage.azure.container", () -> "test");
        registry.add("cdk.storage.azure.mode", () -> "connection-string");
    }

    // Real repos
    @Autowired
    private QueryDefinitionLatestRepository queryDefinitionLatestRepository;

    @Autowired
    private CaseDocumentRepository caseDocumentRepository;

    @Autowired
    private DocumentVerificationTaskRepository documentVerificationTaskRepository;

    // External mocks (via TestOverrides)
    @Autowired
    private HearingClient hearingClient;

    @Autowired
    private ProgressionClient progressionClient;

    @Autowired
    private DocumentIngestionStatusApi documentIngestionStatusApi;

    @Autowired
    private DocumentInformationSummarisedApi documentInformationSummarisedApi;

    @Autowired
    private QueryResolver queryResolver;

    @Autowired
    private StorageService storageService;

    // Batch infra / helpers
    @Autowired
    private JobOperatorTestUtils jobOperatorTestUtils;

    @Autowired
    private Job caseIngestionJob;

    @Autowired
    private Job answerGenerationJob;

    @Autowired
    private DocumentVerificationScheduler documentVerificationScheduler;

    @Autowired
    private VerifySchedulerProperties verifySchedulerProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID caseId1;
    private UUID caseId2;
    private UUID materialId1;
    private UUID materialId2;
    private UUID queryId;

    @BeforeEach
    void setUp() {
        ensureCaseIngestionStatusViewExists();
        // fresh ids for this test
        caseId1 = UUID.randomUUID();
        caseId2 = UUID.randomUUID();
        materialId1 = UUID.randomUUID();
        materialId2 = UUID.randomUUID();
        queryId = UUID.randomUUID();

        // step 1 — hearings & cases
        final HearingSummariesInfo hearingSummary1 = mock(HearingSummariesInfo.class);
        final HearingSummariesInfo hearingSummary2 = mock(HearingSummariesInfo.class);
        when(hearingSummary1.caseId()).thenReturn(caseId1.toString());
        when(hearingSummary2.caseId()).thenReturn(caseId2.toString());
        when(hearingClient.getHearingsAndCases(anyString(), anyString(), any(LocalDate.class), anyString()))
                .thenReturn(List.of(hearingSummary1, hearingSummary2));

        // step 2 — latest material links for each case
        final LatestMaterialInfo latestMaterialInfo1 = mock(LatestMaterialInfo.class);
        final LatestMaterialInfo latestMaterialInfo2 = mock(LatestMaterialInfo.class);
        when(latestMaterialInfo1.materialName()).thenReturn("IDPC");
        when(latestMaterialInfo2.materialName()).thenReturn("IDPC");
        when(latestMaterialInfo1.materialId()).thenReturn(materialId1.toString());
        when(latestMaterialInfo2.materialId()).thenReturn(materialId2.toString());
        when(progressionClient.getCourtDocuments(eq(caseId1), anyString()))
                .thenReturn(Optional.of(latestMaterialInfo1));
        when(progressionClient.getCourtDocuments(eq(caseId2), anyString()))
                .thenReturn(Optional.of(latestMaterialInfo2));

        // step 3 — storage interactions (upload once per new doc)
        when(progressionClient.getMaterialDownloadUrl(any(UUID.class), anyString()))
                .thenReturn(Optional.of("http://example.test/doc.pdf"));
        when(storageService.copyFromUrl(anyString(), anyString(), anyString(), anyMap()))
                .thenAnswer(invocation -> "blob://" + invocation.getArgument(1, String.class));
        when(storageService.getBlobSize(anyString())).thenReturn(1234L);

        // scheduler — ingestion status polling (default: always success quickly)
        final DocumentIngestionStatusReturnedSuccessfully ok =
                new DocumentIngestionStatusReturnedSuccessfully()
                        .status(DocumentIngestionStatusReturnedSuccessfully.StatusEnum.INGESTION_SUCCESS)
                        .documentId(UUID.randomUUID().toString())
                        .documentName("whatever.pdf")
                        .lastUpdated(OffsetDateTime.now());
        when(documentIngestionStatusApi.documentStatus(anyString()))
                .thenReturn(ResponseEntity.ok(ok));

        // step 5 — seed one canonical query + definition
        jdbcTemplate.update(
                "INSERT INTO queries(query_id, label, created_at, display_order) " +
                        "VALUES (?, ?, NOW(), ?) ON CONFLICT (query_id) DO NOTHING",
                ps -> {
                    ps.setObject(1, queryId);
                    ps.setString(2, "E2E Query");
                    ps.setInt(3, 100);
                }
        );
        jdbcTemplate.update(
                "INSERT INTO query_versions(query_id, effective_at, user_query, query_prompt) " +
                        "VALUES (?, NOW(), ?, ?)",
                ps -> {
                    ps.setObject(1, queryId);
                    ps.setString(2, "user-query");
                    ps.setString(3, "query-prompt");
                }
        );
        when(queryResolver.resolve()).thenReturn(
                List.of(new uk.gov.hmcts.cp.cdk.domain.Query() {{
                    setQueryId(queryId);
                    setLabel("E2E Query");
                }})
        );

        // step 6 — RAG call returns stable answer
        final UserQueryAnswerReturnedSuccessfully ragResponse =
                new UserQueryAnswerReturnedSuccessfully()
                        .llmResponse("answer text")
                        .chunkedEntries(Collections.emptyList());
        when(documentInformationSummarisedApi.answerUserQuery(any(AnswerUserQueryRequest.class)))
                .thenReturn(ResponseEntity.ok(ragResponse));
    }

    private void ensureCaseIngestionStatusViewExists() {
        jdbcTemplate.execute("""
                CREATE OR REPLACE VIEW v_case_ingestion_status AS
                SELECT DISTINCT ON (case_id)
                  case_id,
                  ingestion_phase     AS phase,
                  ingestion_phase_at  AS last_updated
                FROM case_documents
                ORDER BY case_id, ingestion_phase_at DESC
                """);
    }

    private void tearDown() {
        truncateIfExists("document_verification_task");
        truncateIfExists("answers");
        truncateIfExists("answer_reservations");
        truncateIfExists("case_documents");
        jdbcTemplate.update("DELETE FROM query_versions WHERE query_id = ?", ps -> ps.setObject(1, queryId));
        jdbcTemplate.update("DELETE FROM queries WHERE query_id = ?", ps -> ps.setObject(1, queryId));
        clearInvocations(
                storageService,
                documentInformationSummarisedApi,
                progressionClient,
                documentIngestionStatusApi,
                hearingClient
        );
    }

    private void truncateIfExists(final String tableName) {
        final String regClass = jdbcTemplate.queryForObject(
                "SELECT to_regclass(?)",
                String.class,
                "public." + tableName
        );
        if (regClass != null) {
            jdbcTemplate.execute("TRUNCATE TABLE " + tableName + " CASCADE");
        }
    }

    private JobExecution startIngestionJobRun() throws Exception {
        jobOperatorTestUtils.setJob(caseIngestionJob);
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

    private JobExecution startAnswerGenerationJobRun() throws Exception {
        jobOperatorTestUtils.setJob(answerGenerationJob);
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

    /**
     * Helper: drive the scheduler manually until there are no PENDING/IN_PROGRESS tasks
     * or we hit a small safety iteration cap.
     */
    private void runSchedulerToCompletion() {
        verifySchedulerProperties.setEnabled(true);
        int safetyCounter = 0;
        while (documentVerificationTaskRepository.countByStatus(DocumentVerificationStatus.PENDING) > 0
                || documentVerificationTaskRepository.countByStatus(DocumentVerificationStatus.IN_PROGRESS) > 0) {
            documentVerificationScheduler.pollPendingDocuments();
            safetyCounter++;
            if (safetyCounter > 20) {
                break;
            }
        }
        verifySchedulerProperties.setEnabled(false);
    }

    @Test
    @DisplayName("Full pipeline: second full run is idempotent (no re-upload, no re-generate)")
    void secondFullPipelineRunIsIdempotent() throws Exception {
        tearDown();

        // ---------- First full pipeline run: Case Ingestion Job -> scheduler -> Answer Generation Job ----------
        final JobExecution ingestionExecution1 = startIngestionJobRun();
        assertThat(ingestionExecution1.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        final long tasksAfterIngestion1 =
                documentVerificationTaskRepository.countByStatus(DocumentVerificationStatus.PENDING);
        assertThat(tasksAfterIngestion1).isEqualTo(2);

        runSchedulerToCompletion();

        final long succeededTasks =
                documentVerificationTaskRepository.countByStatus(DocumentVerificationStatus.SUCCEEDED);
        assertThat(succeededTasks).isEqualTo(2);

        final JobExecution answerExecution1 = startAnswerGenerationJobRun();
        assertThat(answerExecution1.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        final Integer docCountAfter1 =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM case_documents", Integer.class);
        final Integer answersAfter1 =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM answers", Integer.class);
        final Integer doneAfter1 =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM answer_reservations WHERE status='DONE'",
                        Integer.class
                );

        assertThat(docCountAfter1).isEqualTo(2);
        assertThat(answersAfter1).isEqualTo(2);
        assertThat(doneAfter1).isEqualTo(2);

        verify(storageService, times(2)).copyFromUrl(anyString(), anyString(), anyString(), anyMap());
        verify(documentInformationSummarisedApi, times(2)).answerUserQuery(any(AnswerUserQueryRequest.class));

        clearInvocations(storageService, documentInformationSummarisedApi);

        // ---------- Second full pipeline run ----------
        final JobExecution ingestionExecution2 = startIngestionJobRun();
        assertThat(ingestionExecution2.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        runSchedulerToCompletion();

        final JobExecution answerExecution2 = startAnswerGenerationJobRun();
        assertThat(answerExecution2.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        final Integer docCountAfter2 =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM case_documents", Integer.class);
        final Integer answersAfter2 =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM answers", Integer.class);
        final Integer doneAfter2 =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM answer_reservations WHERE status='DONE'",
                        Integer.class
                );

        assertThat(docCountAfter2).isEqualTo(docCountAfter1);
        assertThat(answersAfter2).isEqualTo(answersAfter1);
        assertThat(doneAfter2).isEqualTo(doneAfter1);

        verify(storageService, times(0)).copyFromUrl(anyString(), anyString(), anyString(), anyMap());
        verify(documentInformationSummarisedApi, times(0)).answerUserQuery(any(AnswerUserQueryRequest.class));

        final List<Integer> versions =
                jdbcTemplate.query(
                        "SELECT version FROM answer_reservations",
                        (ResultSet rs, int rowNum) -> rs.getInt(1)
                );
        assertThat(versions).isNotEmpty().allMatch(version -> version == 1);
    }

    @Test
    @DisplayName("Partial redo: if one reservation is FAILED (and answer removed), only that one is regenerated by Answer Generation Job")
    void onlyFailedReservationIsRegeneratedOnAnswerJob() throws Exception {
        tearDown();

        // ---------- First full pipeline run ----------
        final JobExecution ingestionExecution1 = startIngestionJobRun();
        assertThat(ingestionExecution1.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        runSchedulerToCompletion();

        final JobExecution answerExecution1 = startAnswerGenerationJobRun();
        assertThat(answerExecution1.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        final Integer answersAfter1 =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM answers", Integer.class);

        assertThat(answersAfter1).isEqualTo(2);

        final UUID docIdForCase1 = jdbcTemplate.queryForObject(
                "SELECT doc_id FROM case_documents WHERE case_id = ? ORDER BY uploaded_at DESC LIMIT 1",
                (ResultSet rs, int rowNum) -> (UUID) rs.getObject(1),
                caseId1
        );
        assertThat(docIdForCase1).isNotNull();

        // Mark reservation FAILED & remove answer
        jdbcTemplate.update(
                "UPDATE answer_reservations " +
                        "   SET status='FAILED' " +
                        " WHERE case_id = ? AND query_id = ? AND doc_id = ?",
                ps -> {
                    ps.setObject(1, caseId1);
                    ps.setObject(2, queryId);
                    ps.setObject(3, docIdForCase1);
                }
        );
        jdbcTemplate.update(
                "DELETE FROM answers WHERE case_id = ? AND query_id = ?",
                ps -> {
                    ps.setObject(1, caseId1);
                    ps.setObject(2, queryId);
                }
        );

        final String phaseCase1 =
                jdbcTemplate.queryForObject(
                        "SELECT ingestion_phase::text FROM case_documents WHERE doc_id = ?",
                        String.class,
                        docIdForCase1
                );
        assertThat(phaseCase1).isEqualTo("INGESTED");

        clearInvocations(storageService, documentInformationSummarisedApi);

        // ---------- Second answer-generation job run ----------
        final JobExecution answerExecution2 = startAnswerGenerationJobRun();
        assertThat(answerExecution2.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        verify(storageService, times(0)).copyFromUrl(anyString(), anyString(), anyString(), anyMap());
        verify(documentInformationSummarisedApi, times(1)).answerUserQuery(any(AnswerUserQueryRequest.class));

        final Integer answersAfter2 =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM answers", Integer.class);
        final Integer doneAfter2 =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM answer_reservations WHERE status='DONE'",
                        Integer.class
                );

        assertThat(answersAfter2).isEqualTo(2);
        assertThat(doneAfter2).isEqualTo(2);

        final List<Integer> versions =
                jdbcTemplate.query(
                        "SELECT version FROM answer_reservations",
                        (ResultSet rs, int rowNum) -> rs.getInt(1)
                );
        assertThat(versions).isNotEmpty().allMatch(version -> version == 1);
    }

    @Test
    @DisplayName("Scheduler failure path: if IDPC reports ingestion FAILED, tasks and docs are marked FAILED")
    void schedulerMarksFailedWhenIdpcFails() throws Exception {
        tearDown();

        final JobExecution ingestionExecution = startIngestionJobRun();
        assertThat(ingestionExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        final long pendingTasks =
                documentVerificationTaskRepository.countByStatus(DocumentVerificationStatus.PENDING);
        assertThat(pendingTasks).isEqualTo(2);

        final DocumentIngestionStatusReturnedSuccessfully failedBody =
                new DocumentIngestionStatusReturnedSuccessfully()
                        .status(DocumentIngestionStatusReturnedSuccessfully.StatusEnum.INGESTION_FAILED)
                        .documentId(UUID.randomUUID().toString())
                        .documentName("whatever.pdf")
                        .lastUpdated(OffsetDateTime.now());
        when(documentIngestionStatusApi.documentStatus(anyString()))
                .thenReturn(ResponseEntity.ok(failedBody));

        runSchedulerToCompletion();

        final long failedTasks =
                documentVerificationTaskRepository.countByStatus(DocumentVerificationStatus.FAILED);
        assertThat(failedTasks).isEqualTo(2);

        final List<String> phases =
                jdbcTemplate.query(
                        "SELECT DISTINCT ingestion_phase::text FROM case_documents",
                        (ResultSet rs, int rowNum) -> rs.getString(1)
                );
        assertThat(phases).containsOnly("FAILED");

        final JobExecution answerExecution = startAnswerGenerationJobRun();
        assertThat(answerExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        final Integer answersCount =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM answers", Integer.class);
        assertThat(answersCount).isZero();
    }

    @Test
    @DisplayName("Cloudy day: no queries configured -> no reservations, no answers, no RAG calls")
    void answerJobNoopsWhenNoQueriesConfigured() throws Exception {
        tearDown();

        // Override default setup: no queries resolved at all
        when(queryResolver.resolve()).thenReturn(Collections.emptyList());

        // ---------- Ingestion (Case Ingestion Job) ----------
        final JobExecution ingestionExecution = startIngestionJobRun();
        assertThat(ingestionExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Scheduler will still mark documents as INGESTED
        runSchedulerToCompletion();

        // ---------- Answer generation (Answer Generation Job) ----------
        final JobExecution answerExecution = startAnswerGenerationJobRun();
        assertThat(answerExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        final Integer reservationCount =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM answer_reservations", Integer.class);
        final Integer answersCount =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM answers", Integer.class);

        assertThat(reservationCount).isZero();
        assertThat(answersCount).isZero();

        // No RAG calls should have been made
        verify(documentInformationSummarisedApi, times(0))
                .answerUserQuery(any(AnswerUserQueryRequest.class));
    }

    @Test
    @DisplayName("Scheduler cloudy day: no tasks -> poll is a no-op")
    void schedulerNoopsWhenNoTasks() {
        tearDown(); // ensure no tasks in DB

        verifySchedulerProperties.setEnabled(true);
        documentVerificationScheduler.pollPendingDocuments();
        verifySchedulerProperties.setEnabled(false);

        // Just assert there are still no tasks in any status
        final long totalTasks = documentVerificationTaskRepository.count();
        assertThat(totalTasks).isZero();
    }

    @TestConfiguration
    static class TestOverrides {

        @Bean
        @Primary
        RetryTemplate retryTemplate() {
            final RetryTemplate retryTemplate = new RetryTemplate();
            final SimpleRetryPolicy simpleRetryPolicy = new SimpleRetryPolicy(1);
            retryTemplate.setRetryPolicy(simpleRetryPolicy);
            final FixedBackOffPolicy fixedBackOffPolicy = new FixedBackOffPolicy();
            fixedBackOffPolicy.setBackOffPeriod(50);
            retryTemplate.setBackOffPolicy(fixedBackOffPolicy);
            return retryTemplate;
        }

        @Bean
        @Primary
        public PlatformTransactionManager transactionManager(final EntityManagerFactory entityManagerFactory) {
            return new JpaTransactionManager(entityManagerFactory);
        }

        @Bean(name = "ingestionTaskExecutor")
        @Primary
        public TaskExecutor ingestionTaskExecutor() {
            return new SyncTaskExecutor();
        }

        // External systems as mocks

        @Bean
        @Primary
        public HearingClient hearingClient() {
            return mock(HearingClient.class);
        }

        @Bean
        @Primary
        public ProgressionClient progressionClient() {
            return mock(ProgressionClient.class);
        }

        @Bean
        @Primary
        public DocumentIngestionStatusApi documentIngestionStatusApi() {
            return mock(DocumentIngestionStatusApi.class);
        }

        @Bean
        @Primary
        public DocumentInformationSummarisedApi documentInformationSummarisedApi() {
            return mock(DocumentInformationSummarisedApi.class);
        }

        @Bean
        @Primary
        public QueryResolver queryResolver() {
            return mock(QueryResolver.class);
        }

        @Bean
        @Primary
        public StorageService storageService() {
            return mock(StorageService.class);
        }

        @Bean
        @Primary
        public TokenCredential tokenCredential() {
            return mock(TokenCredential.class);
        }
    }
}

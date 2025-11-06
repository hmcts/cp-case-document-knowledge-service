package uk.gov.hmcts.cp.cdk.batch;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.step.StepExecution;
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
import uk.gov.hmcts.cp.cdk.batch.clients.config.CdkClientsConfig;
import uk.gov.hmcts.cp.cdk.batch.clients.hearing.HearingClient;
import uk.gov.hmcts.cp.cdk.batch.clients.hearing.dto.HearingSummariesInfo;
import uk.gov.hmcts.cp.cdk.batch.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.batch.clients.progression.dto.LatestMaterialInfo;
import uk.gov.hmcts.cp.cdk.batch.storage.AzureBlobStorageService;
import uk.gov.hmcts.cp.cdk.batch.storage.StorageService;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.repo.QueryDefinitionLatestRepository;
import uk.gov.hmcts.cp.openapi.api.DocumentInformationSummarisedApi;
import uk.gov.hmcts.cp.openapi.api.DocumentIngestionStatusApi;
import uk.gov.hmcts.cp.openapi.model.AnswerUserQueryRequest;
import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatusReturnedSuccessfully;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerReturnedSuccessfully;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBatchTest
@SpringBootTest(
        classes = {
                CaseIngestionJobTest.TestApplication.class,
                BatchConfig.class,
                CdkClientsConfig.class,
                CaseIngestionJobConfig.class,
                CaseIngestionStepsConfig.class,
                CaseIngestionJobTest.TestOverrides.class
        },
        properties = {
                "spring.main.allow-bean-definition-overriding=true"
        }
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CaseIngestionJobTest {

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
    static class TestApplication { }

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
    static void dbProps(final DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        // Let Hibernate generate/drop entity tables for the test lifecycle
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.batch.jdbc.initialize-schema", () -> "always");

        // faster polling for any verify logic
        r.add("cdk.ingestion.verify.poll-interval-ms", () -> "10");
        r.add("cdk.ingestion.verify.max-wait-ms", () -> "2000");
    }

    // Real repos
    @Autowired private QueryDefinitionLatestRepository qdlRepo;
    @Autowired private CaseDocumentRepository caseDocumentRepository;

    // Mocks (external)
    @Autowired private HearingClient hearingClient;
    @Autowired private ProgressionClient progressionClient;
    @Autowired private DocumentIngestionStatusApi documentIngestionStatusApi;
    @Autowired private DocumentInformationSummarisedApi documentInformationSummarisedApi;
    @Autowired private QueryResolver queryResolver;
    @Autowired private StorageService storageService;

    // Infra
    @Autowired private JobOperatorTestUtils jobOperatorTestUtils;
    @Autowired private JdbcTemplate jdbc;
    @PersistenceContext private EntityManager em;

    private UUID caseId1, caseId2, materialId1, materialId2, queryId;

    @BeforeEach
    void setupSchemaAndMocks() {

        // ids for this run
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

        // step 2 — latest material links
        LatestMaterialInfo lm1 = mock(LatestMaterialInfo.class);
        LatestMaterialInfo lm2 = mock(LatestMaterialInfo.class);
        when(lm1.materialId()).thenReturn(materialId1.toString());
        when(lm2.materialId()).thenReturn(materialId2.toString());
        when(progressionClient.getCourtDocuments(eq(caseId1), anyString())).thenReturn(Optional.of(lm1));
        when(progressionClient.getCourtDocuments(eq(caseId2), anyString())).thenReturn(Optional.of(lm2));

        // step 3 — storage interactions
        when(progressionClient.getMaterialDownloadUrl(any(UUID.class), anyString()))
                .thenReturn(Optional.of("http://example.test/doc.pdf"));
        when(storageService.copyFromUrl(anyString(), anyString(), anyString(), anyMap()))
                .thenAnswer(inv -> "blob://" + inv.getArgument(1, String.class));
        when(storageService.getBlobSize(anyString())).thenReturn(1234L);

        // step 4 — ingestion status polling
        DocumentIngestionStatusReturnedSuccessfully ok =
                new DocumentIngestionStatusReturnedSuccessfully()
                        .status(DocumentIngestionStatusReturnedSuccessfully.StatusEnum.INGESTION_SUCCESS)
                        .documentId(UUID.randomUUID().toString())
                        .documentName("whatever.pdf")
                        .lastUpdated(OffsetDateTime.now());
        when(documentIngestionStatusApi.documentStatus(anyString()))
                .thenReturn(ResponseEntity.ok(ok));

        // step 5 — seed canonical query rows
        jdbc.update(
                "INSERT INTO queries(query_id, label, created_at) VALUES (?, ?, NOW()) " +
                        "ON CONFLICT (query_id) DO NOTHING",
                ps -> {
                    ps.setObject(1, queryId);
                    ps.setString(2, "E2E Query");
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

        // step 5 wiring
        when(queryResolver.resolve()).thenReturn(List.of(new uk.gov.hmcts.cp.cdk.domain.Query() {{
            setQueryId(queryId);
            setLabel("E2E Query");
        }}));

        // step 6 — RAG call
        UserQueryAnswerReturnedSuccessfully rag =
                new UserQueryAnswerReturnedSuccessfully()
                        .llmResponse("answer text")
                        .chunkedEntries(Collections.emptyList());
        when(documentInformationSummarisedApi.answerUserQuery(any(AnswerUserQueryRequest.class)))
                .thenReturn(ResponseEntity.ok(rag));
    }

    @AfterEach
    void clean() {
        truncateIfExists("answers");
        truncateIfExists("answer_reservations");
        // remove inserted canonical query rows
        jdbc.update("DELETE FROM query_versions WHERE query_id = ?", ps -> ps.setObject(1, queryId));
        jdbc.update("DELETE FROM queries WHERE query_id = ?", ps -> ps.setObject(1, queryId));
    }

    private void truncateIfExists(String table) {
        String reg = jdbc.queryForObject("SELECT to_regclass(?)", String.class, "public." + table);
        if (reg != null) {
            jdbc.execute("TRUNCATE TABLE " + table + " CASCADE");
        }
    }

    @Test
    @DisplayName("E2E/Postgres: two cases -> partitions execute; verify waits success; answers stored; job COMPLETES")
    void e2e_two_cases_full_flow_job_completes() throws Exception {
        JobExecution exec = jobOperatorTestUtils.startJob(
                new JobParametersBuilder()
                        .addString("runId", UUID.randomUUID().toString())
                        .addLong("ts", System.currentTimeMillis())
                        .addString("courtCentreId", "COURT-1")
                        .addString("roomId", "ROOM-42")
                        .addString("cppuid", "userId")
                        .addString("date", LocalDate.now().toString())
                        .toJobParameters()
        );

        assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> stepNames = exec.getStepExecutions().stream()
                .map(StepExecution::getStepName)
                .collect(Collectors.toList());
        long workers = stepNames.stream().filter(n -> n.startsWith("perCaseFlowStep:")).count();
        assertThat(workers).isEqualTo(2);

        var docs1 = caseDocumentRepository.findFirstByCaseIdOrderByUploadedAtDesc(caseId1);
        var docs2 = caseDocumentRepository.findFirstByCaseIdOrderByUploadedAtDesc(caseId2);
        assertThat(docs1).isPresent();
        assertThat(docs2).isPresent();

        Integer answersCount = jdbc.queryForObject("SELECT COUNT(*) FROM answers", Integer.class);
        Integer reservationsDone = jdbc.queryForObject(
                "SELECT COUNT(*) FROM answer_reservations WHERE status='DONE'", Integer.class);
        assertThat(answersCount).isEqualTo(2);
        assertThat(reservationsDone).isEqualTo(2);
    }

    @Test
    @DisplayName("E2E/Postgres: no eligible cases -> partitioned flow skipped; job COMPLETES")
    void e2e_no_cases_partition_skipped_job_completes() throws Exception {
        when(progressionClient.getCourtDocuments(any(UUID.class), anyString())).thenReturn(Optional.empty());

        JobExecution exec = jobOperatorTestUtils.startJob(
                new JobParametersBuilder()
                        .addString("runId", UUID.randomUUID().toString())
                        .addLong("ts", System.currentTimeMillis())
                        .addString("courtCentreId", "COURT-1")
                        .addString("roomId", "ROOM-42")
                        .addString("cppuid", "userId")
                        .addString("date", LocalDate.now().toString())
                        .toJobParameters()
        );

        assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> stepNames = exec.getStepExecutions().stream()
                .map(StepExecution::getStepName)
                .collect(Collectors.toList());
        long workers = stepNames.stream().filter(n -> n.startsWith("perCaseFlowStep:")).count();
        assertThat(workers).isEqualTo(0);

        Integer answersCount = jdbc.queryForObject("SELECT COUNT(*) FROM answers", Integer.class);
        assertThat(answersCount).isZero();
    }

    @TestConfiguration
    static class TestOverrides {
        @Bean
        @Primary
        RetryTemplate retryTemplate() {
            RetryTemplate t = new RetryTemplate();
            SimpleRetryPolicy p = new SimpleRetryPolicy(1); // try once, no retries
            FixedBackOffPolicy b = new FixedBackOffPolicy();
            b.setBackOffPeriod(50); // short backoff
            t.setRetryPolicy(p);
            t.setBackOffPolicy(b);
            return t;
        }

        @Bean
        @Primary
        public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
            return new JpaTransactionManager(emf);
        }

        @Bean(name = "ingestionTaskExecutor")
        @Primary
        public TaskExecutor ingestionTaskExecutor() {
            return new SyncTaskExecutor();
        }

        // external mocks
        @Bean @Primary public HearingClient hearingClient() { return mock(HearingClient.class); }
        @Bean @Primary public ProgressionClient progressionClient() { return mock(ProgressionClient.class); }
        @Bean @Primary public DocumentIngestionStatusApi documentIngestionStatusApi() { return mock(DocumentIngestionStatusApi.class); }
        @Bean @Primary public DocumentInformationSummarisedApi documentInformationSummarisedApi() { return mock(DocumentInformationSummarisedApi.class); }
        @Bean @Primary public QueryResolver queryResolver() { return mock(QueryResolver.class); }
        @Bean @Primary public StorageService storageService() { return mock(StorageService.class); }
    }
}

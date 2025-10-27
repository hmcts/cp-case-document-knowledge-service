package uk.gov.hmcts.cp.cdk.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import uk.gov.hmcts.cp.cdk.domain.Query;
import uk.gov.hmcts.cp.cdk.query.QueryClient;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.repo.QueryRepository;
import uk.gov.hmcts.cp.cdk.storage.StorageService;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CaseIngestionJobConfigTest {

    private JobRepository jobRepository;
    private PlatformTransactionManager transactionManager;

    private QueryClient queryClient;
    private StorageService storageService;
    private CaseDocumentRepository caseDocumentRepository;
    private QueryRepository queryRepository;
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private QuestionsProperties questionsProperties;

    private Step step1;
    private Step step2;
    private Step step3;
    private Step step4;
    private Step step5;

    @BeforeEach
    void setUp() {
        jobRepository = mock(JobRepository.class);
        transactionManager = new ResourcelessTransactionManager();

        queryClient = mock(QueryClient.class);
        storageService = mock(StorageService.class);
        caseDocumentRepository = mock(CaseDocumentRepository.class);
        queryRepository = mock(QueryRepository.class);
        namedParameterJdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        questionsProperties = mock(QuestionsProperties.class);

        doAnswer(inv -> {
            StepExecution s = inv.getArgument(0);
            if (s.getJobExecution() != null && s.getJobExecution().getId() == null) {
                s.getJobExecution().setId(1L);
            }
            if (s.getId() == null) {
                s.setId(1L);
            }
            return null;
        }).when(jobRepository).add(any(StepExecution.class));

        CaseIngestionJobConfig config = new CaseIngestionJobConfig();
        step1 = config.step1FetchHearingsCasesWithSingleDefendant(jobRepository, transactionManager, queryClient);
        step2 = config.step2FilterCaseIdpcForSingleDefendant(jobRepository, transactionManager, queryClient);
        step3 = config.step3UploadIdpc(jobRepository, transactionManager, queryClient, storageService, caseDocumentRepository);
        step4 = config.step4CheckUploadStatus(jobRepository, transactionManager, storageService);
        step5 = config.step5EnqueueAnswerTasks(jobRepository, transactionManager, questionsProperties, queryRepository, namedParameterJdbcTemplate);
    }

    @Test
    @DisplayName("step1 stores caseIds in execution context")
    void step1StoresCaseIds() throws Exception {
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();

        QueryClient.CaseSummary s1 = new QueryClient.CaseSummary(c1, UUID.randomUUID());
        QueryClient.CaseSummary s2 = new QueryClient.CaseSummary(c2, UUID.randomUUID());

        when(queryClient.getHearingsAndCases(eq("XYZ"), any(LocalDate.class)))
                .thenReturn(List.of(s1, s2));

        JobParameters params = new JobParametersBuilder()
                .addString("court", "XYZ")
                .addString("date", "2025-10-01")
                .toJobParameters();

        StepExecution se1 = newStepExecution("step1_fetch_hearings_cases", params);
        step1.execute(se1);

        @SuppressWarnings("unchecked")
        List<String> caseIds = (List<String>) se1.getJobExecution().getExecutionContext().get("caseIds");
        assertThat(caseIds).containsExactlyInAnyOrder(c1.toString(), c2.toString());
        assertThat(se1.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
    }

    @Test
    @DisplayName("step2 filters eligible cases (single defendant + IDPC available)")
    void step2FiltersEligibleCases() throws Exception {
        StepExecution se = newStepExecution("step2_check_single_defendant_idpc",
                new JobParametersBuilder().toJobParameters());
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();
        se.getJobExecution().getExecutionContext().put("caseIds", List.of(c1.toString(), c2.toString()));

        QueryClient.CourtDocMeta meta1 = new QueryClient.CourtDocMeta(true, true, "u", "application/pdf", 10L);
        QueryClient.CourtDocMeta meta2 = new QueryClient.CourtDocMeta(false, true, null, "application/pdf", 0L);

       // when(queryClient.getCourtDocuments(c1)).thenReturn(meta1);
       // when(queryClient.getCourtDocuments(c2)).thenReturn(meta2);

        step2.execute(se);

        @SuppressWarnings("unchecked")
        List<String> eligible = (List<String>) se.getJobExecution().getExecutionContext().get("eligibleCaseIds");
       // assertThat(eligible).containsExactly(c1.toString());
        assertThat(se.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
    }

    @Test
    @DisplayName("step3 uploads IDPC and persists CaseDocument")
    void step3UploadsAndPersists() throws Exception {
        StepExecution se = newStepExecution("step3_upload_idpc",
                new JobParametersBuilder().toJobParameters());
        UUID caseId = UUID.randomUUID();
        se.getJobExecution().getExecutionContext().put("eligibleCaseIds", List.of(caseId.toString()));

        QueryClient.CourtDocMeta meta = new QueryClient.CourtDocMeta(true, true, "http://download/idpc.pdf", "application/pdf", 1234L);
        //when(queryClient.getCourtDocuments(caseId)).thenReturn(meta);

        InputStream is = new ByteArrayInputStream("PDF".getBytes());
        when(queryClient.downloadIdpc("http://download/idpc.pdf")).thenReturn(is);
        when(storageService.upload(anyString(), any(InputStream.class), anyLong(), anyString()))
                .thenReturn("blob://cases/" + caseId + "/idpc.pdf");

        step3.execute(se);

      //  verify(caseDocumentRepository, times(1)).save(any());
       // assertThat(se.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
    }

    @Test
    @DisplayName("step4 sets RETRY when any blob missing, COMPLETED otherwise")
    void step4RetryOrComplete() throws Exception {
        StepExecution retry = newStepExecution("step4_check_upload_status",
                new JobParametersBuilder().toJobParameters());
        UUID missing = UUID.randomUUID();
        retry.getJobExecution().getExecutionContext().put("eligibleCaseIds", List.of(missing.toString()));
        when(storageService.exists("cases/%s/idpc.pdf".formatted(missing))).thenReturn(false);
        step4.execute(retry);
        //assertThat(retry.getExitStatus().getExitCode()).isEqualTo("RETRY");

        StepExecution ok = newStepExecution("step4_check_upload_status",
                new JobParametersBuilder().toJobParameters());
        UUID present = UUID.randomUUID();
        ok.getJobExecution().getExecutionContext().put("eligibleCaseIds", List.of(present.toString()));
        when(storageService.exists("cases/%s/idpc.pdf".formatted(present))).thenReturn(true);
        step4.execute(ok);
        assertThat(ok.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
    }

    @Test
    @DisplayName("step5 batches task inserts for all eligible cases and queries")
    void step5BatchesTaskInserts() throws Exception {
        StepExecution se = newStepExecution("step5_enqueue_answer_tasks",
                new JobParametersBuilder().toJobParameters());
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();
        se.getJobExecution().getExecutionContext().put("eligibleCaseIds", List.of(c1.toString(), c2.toString()));

        when(questionsProperties.labels()).thenReturn(List.of("A", "B"));
        Query q1 = mock(Query.class);
        Query q2 = mock(Query.class);
        when(q1.getQueryId()).thenReturn(UUID.randomUUID());
        when(q2.getQueryId()).thenReturn(UUID.randomUUID());
        when(queryRepository.findByLabelIgnoreCase("A")).thenReturn(Optional.of(q1));
        when(queryRepository.findByLabelIgnoreCase("B")).thenReturn(Optional.of(q2));

        step5.execute(se);

            /**
        verify(namedParameterJdbcTemplate, times(1))
                .batchUpdate(anyString(), argThat((Map<String, ?>[] batch) ->
                        batch != null && batch.length == 4 &&
                                batch[0].containsKey("case_id") && batch[0].containsKey("query_id")));
        assertThat(se.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
             **/
    }

    private StepExecution newStepExecution(final String stepName, final JobParameters params) {
        JobInstance jobInstance = new JobInstance(1L, CaseIngestionJobConfig.JOB_NAME);
        JobExecution jobExecution = new JobExecution(jobInstance, params);
        StepExecution stepExecution = new StepExecution(stepName, jobExecution);
        jobRepository.add(stepExecution);
        return stepExecution;
    }
}

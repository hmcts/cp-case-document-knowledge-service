package uk.gov.hmcts.cp.cdk.batch.verification;

import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.domain.DocumentVerificationStatus.FAILED;
import static uk.gov.hmcts.cp.cdk.domain.DocumentVerificationStatus.IN_PROGRESS;
import static uk.gov.hmcts.cp.cdk.domain.DocumentVerificationStatus.SUCCEEDED;
import static uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatus.INGESTION_SUCCESS;

import uk.gov.hmcts.cp.cdk.config.VerifySchedulerProperties;
import uk.gov.hmcts.cp.cdk.domain.DocumentVerificationStatus;
import uk.gov.hmcts.cp.cdk.domain.DocumentVerificationTask;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.repo.DocumentVerificationTaskRepository;
import uk.gov.hmcts.cp.openapi.api.DocumentIngestionStatusApi;
import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatusReturnedSuccessfully;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class DocumentVerificationSchedulerTest {

    @Mock
    private VerifySchedulerProperties verifySchedulerProperties;

    @Mock
    private DocumentVerificationQueueDao documentVerificationQueueDao;

    @Mock
    private DocumentVerificationTaskRepository documentVerificationTaskRepository;

    @Mock
    private DocumentIngestionStatusApi documentIngestionStatusApi;

    @Mock
    private CaseDocumentRepository caseDocumentRepository;

    @Mock
    private PlatformTransactionManager platformTransactionManager;

    @Mock
    private JobOperator jobOperator;

    @Mock
    private Job answerGenerationJob;

    private DocumentVerificationScheduler scheduler;
    @Captor
    private ArgumentCaptor<JobParameters> jobParamsCaptor;

    @BeforeEach
    void setup() {
        scheduler = new DocumentVerificationScheduler(
                verifySchedulerProperties,
                documentVerificationQueueDao,
                documentVerificationTaskRepository,
                documentIngestionStatusApi,
                caseDocumentRepository,
                platformTransactionManager,
                jobOperator,
                answerGenerationJob
        );
    }

    @Test
    void shouldReturnImmediatelyWhenSchedulerDisabled() {
        when(verifySchedulerProperties.isEnabled()).thenReturn(false);

        scheduler.pollPendingDocuments();

        verifyNoInteractions(documentVerificationQueueDao);
    }

    @Test
    void shouldReturnImmediatelyWhenNoTasks() {
        when(verifySchedulerProperties.isEnabled()).thenReturn(true);
        when(verifySchedulerProperties.getBatchSize()).thenReturn(10);
        when(documentVerificationQueueDao.claimBatch(anyString(), anyInt())).thenReturn(List.of());

        scheduler.pollPendingDocuments();

        verify(documentVerificationQueueDao).claimBatch(anyString(), eq(10));
    }

    @Test
    void shouldMarkTaskSucceededForIngestionSuccess() throws Exception {
        when(verifySchedulerProperties.isEnabled()).thenReturn(true);
        when(verifySchedulerProperties.getBatchSize()).thenReturn(10);

        final UUID docId = randomUUID();
        final UUID caseId = randomUUID();

        final DocumentVerificationTask task = new DocumentVerificationTask();
        task.setDocId(docId);
        task.setCaseId(caseId);
        task.setBlobName("test-blob");

        final DocumentIngestionStatusReturnedSuccessfully body = new DocumentIngestionStatusReturnedSuccessfully();
        body.setStatus(INGESTION_SUCCESS);
        body.setLastUpdated(OffsetDateTime.now());

        when(documentVerificationQueueDao.claimBatch(anyString(), anyInt())).thenReturn(List.of(task));
        when(documentIngestionStatusApi.documentStatus(anyString())).thenReturn(ResponseEntity.ok(body));

        // Mock jobOperator
        final JobExecution jobExecution = mock(JobExecution.class);
        when(jobExecution.getId()).thenReturn(123L);
        when(jobOperator.start(any(Job.class), any(JobParameters.class))).thenReturn(jobExecution);

        scheduler.pollPendingDocuments();

        // Task should be marked SUCCEEDED
        assertThat(task.getStatus()).isEqualTo(SUCCEEDED);
        verify(documentVerificationTaskRepository).saveAndFlush(task);
    }

    @Test
    void givenTaskSucceededForIngestion_shouldAnswerGenerationJobWithUniqueRunId() throws Exception {
        when(verifySchedulerProperties.isEnabled()).thenReturn(true);
        when(verifySchedulerProperties.getBatchSize()).thenReturn(10);

        final DocumentVerificationTask task = new DocumentVerificationTask();
        task.setDocId(randomUUID());
        task.setCaseId(randomUUID());
        task.setBlobName("test-blob");

        final DocumentIngestionStatusReturnedSuccessfully body = new DocumentIngestionStatusReturnedSuccessfully();
        body.setStatus(INGESTION_SUCCESS);
        body.setLastUpdated(OffsetDateTime.now());

        when(documentVerificationQueueDao.claimBatch(anyString(), anyInt())).thenReturn(List.of(task));
        when(documentIngestionStatusApi.documentStatus(anyString())).thenReturn(ResponseEntity.ok(body));

        // Mock jobOperator
        final JobExecution jobExecution = mock(JobExecution.class);
        when(jobExecution.getId()).thenReturn(123L);
        when(jobOperator.start(any(Job.class), jobParamsCaptor.capture())).thenReturn(jobExecution);

        scheduler.pollPendingDocuments();

        // Task should be marked SUCCEEDED
        assertThat(task.getStatus()).isEqualTo(SUCCEEDED);
        verify(documentVerificationTaskRepository).saveAndFlush(task);
        assertThat(jobParamsCaptor.getValue().getString("triggerId")).isNotNull();
        assertThat(jobParamsCaptor.getValue().getParameter("triggerId").isIdentifying()).isTrue();
        assertThat(jobParamsCaptor.getValue().getString("caseIds")).isNotNull();
        assertThat(jobParamsCaptor.getValue().getParameter("caseIds").isIdentifying()).isTrue();
    }

    @Test
    void shouldScheduleRetryOnHttp404() {
        when(verifySchedulerProperties.isEnabled()).thenReturn(true);
        when(verifySchedulerProperties.getBatchSize()).thenReturn(10);

        final DocumentVerificationTask task = new DocumentVerificationTask();
        task.setDocId(UUID.randomUUID());
        task.setCaseId(UUID.randomUUID());
        task.setAttemptCount(0);
        task.setMaxAttempts(3);
        task.setBlobName("blob-name");

        when(documentVerificationQueueDao.claimBatch(anyString(), anyInt()))
                .thenReturn(List.of(task));

        when(documentIngestionStatusApi.documentStatus(anyString()))
                .thenReturn(ResponseEntity.status(HttpStatus.NOT_FOUND).build());

        scheduler.pollPendingDocuments();

        // Task attempt count should increment
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getStatus()).isEqualTo(IN_PROGRESS);
        verify(documentVerificationTaskRepository).saveAndFlush(task);
    }

    @Test
    void shouldScheduleRetryOnException() {
        when(verifySchedulerProperties.isEnabled()).thenReturn(true);
        when(verifySchedulerProperties.getBatchSize()).thenReturn(10);

        final DocumentVerificationTask task = new DocumentVerificationTask();
        task.setDocId(UUID.randomUUID());
        task.setCaseId(UUID.randomUUID());
        task.setAttemptCount(0);
        task.setMaxAttempts(3);

        when(documentVerificationQueueDao.claimBatch(anyString(), anyInt()))
                .thenReturn(List.of(task));

        when(documentIngestionStatusApi.documentStatus(anyString()))
                .thenThrow(new RuntimeException("Error"));

        scheduler.pollPendingDocuments();

        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getStatus()).isEqualTo(IN_PROGRESS);
        verify(documentVerificationTaskRepository).saveAndFlush(task);
    }

    @Test
    void shouldScheduleRetryOnException_saveTaskWhenNumberOfAttemptsExceedMaxAttempts() {
        when(verifySchedulerProperties.isEnabled()).thenReturn(true);
        when(verifySchedulerProperties.getBatchSize()).thenReturn(10);

        final DocumentVerificationTask task = new DocumentVerificationTask();
        task.setDocId(UUID.randomUUID());
        task.setCaseId(UUID.randomUUID());
        task.setAttemptCount(3);
        task.setMaxAttempts(3);

        when(documentVerificationQueueDao.claimBatch(anyString(), anyInt()))
                .thenReturn(List.of(task));

        when(documentIngestionStatusApi.documentStatus(anyString()))
                .thenThrow(new RuntimeException("Error"));

        scheduler.pollPendingDocuments();

        assertThat(task.getAttemptCount()).isEqualTo(4);
        assertThat(task.getStatus()).isEqualTo(FAILED);
        verify(documentVerificationTaskRepository).saveAndFlush(task);
    }
}
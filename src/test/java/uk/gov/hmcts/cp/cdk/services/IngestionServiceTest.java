package uk.gov.hmcts.cp.cdk.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.cdk.repo.IngestionStatusViewRepository;
import uk.gov.hmcts.cp.openapi.model.cdk.DocumentIngestionPhase;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessResponse;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionStatusResponse;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.core.task.SyncTaskExecutor;

@DisplayName("Ingestion Service tests (asynchronous start)")
class IngestionServiceTest {

    @Test
    @DisplayName("submits job asynchronously and returns STARTED with requestId")
    void launchesAsyncAndReturnsStarted() throws Exception {
        // given
        final String cppuid = "u-123";

        final JobOperator operator = mock(JobOperator.class);
        final Job job = mock(Job.class);

        final JobExecution mockedExecution = mock(JobExecution.class);
        when(mockedExecution.getId()).thenReturn(321L);

        final ArgumentCaptor<JobParameters> paramsCaptor = ArgumentCaptor.forClass(JobParameters.class);
        when(operator.start(eq(job), paramsCaptor.capture())).thenReturn(mockedExecution);

        final IngestionStatusViewRepository repo = mock(IngestionStatusViewRepository.class);

        // Use a synchronous executor so the async Runnable executes immediately in the test thread
        final var syncExecutor = new SyncTaskExecutor();
        final IngestionService svc = new IngestionService(repo, operator, job, syncExecutor);

        final IngestionProcessRequest req = new IngestionProcessRequest();
        req.setCourtCentreId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        req.setRoomId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
        req.setDate(LocalDate.parse("2025-10-01"));

        // when
        final IngestionProcessResponse resp = svc.startIngestionProcess(cppuid, req);

        // then (params used for the async start)
        verify(operator, times(1)).start(eq(job), any(JobParameters.class));
        final JobParameters p = paramsCaptor.getValue();

        assertThat(p.getString("courtCentreId")).isEqualTo("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        assertThat(p.getString("roomId")).isEqualTo("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        assertThat(p.getString("date")).isEqualTo("2025-10-01");
        assertThat(p.getString("cppuid")).isEqualTo("u-123");
        assertThat(p.getLong("run")).isNotNull();
        assertThat(p.getString("requestId")).isNotBlank();

        // response is immediate and contains a requestId (not executionId)
        assertThat(resp.getPhase().getValue()).isEqualTo("STARTED");
        assertThat(resp.getMessage()).contains("requestId=");
    }


    @Test
    @DisplayName("getStatus valid row found for the caseId")
    void getStatus() {
        final JobOperator operator = mock(JobOperator.class);
        final Job job = mock(Job.class);
        final IngestionStatusViewRepository repo = mock(IngestionStatusViewRepository.class);

        final IngestionService service = new IngestionService(repo, operator, job, new SyncTaskExecutor());
        final UUID caseId = UUID.randomUUID();
        final OffsetDateTime lastUpdated = OffsetDateTime.now();

        when(repo.findByCaseId(caseId)).thenReturn(Optional.of(new IngestionStatusViewRepository.Row(caseId, "UPLOADING", lastUpdated)));

        final IngestionStatusResponse response = service.getStatus(caseId);

        assertThat(response.getScope()).isNotNull();
        assertThat(response.getScope().getCaseId()).isEqualTo(caseId);
        assertThat(response.getScope().getIsIdpcAvailable()).isNull();
        assertThat(response.getLastUpdated()).isEqualTo(lastUpdated);
        assertThat(response.getPhase()).isEqualTo(DocumentIngestionPhase.UPLOADING);
        assertThat(response.getMessage()).isNull();
    }

    @Test
    @DisplayName("getStatus no row found for the caseId")
    void getStatus_rowNotFound() {
        final JobOperator operator = mock(JobOperator.class);
        final Job job = mock(Job.class);
        final IngestionStatusViewRepository repo = mock(IngestionStatusViewRepository.class);

        final IngestionService service = new IngestionService(repo, operator, job, new SyncTaskExecutor());
        final UUID caseId = UUID.randomUUID();
        final OffsetDateTime lastUpdated = OffsetDateTime.now();

        when(repo.findByCaseId(caseId)).thenReturn(Optional.empty());

        final IngestionStatusResponse response = service.getStatus(caseId);

        assertThat(response.getScope()).isNotNull();
        assertThat(response.getScope().getCaseId()).isEqualTo(caseId);
        assertThat(response.getScope().getIsIdpcAvailable()).isNull();
        assertThat(response.getLastUpdated()).isNull();
        assertThat(response.getPhase()).isEqualTo(DocumentIngestionPhase.NOT_FOUND);
        assertThat(response.getMessage()).isEqualTo("No uploads seen for this case");
    }
}

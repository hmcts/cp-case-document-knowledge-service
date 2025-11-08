package uk.gov.hmcts.cp.cdk.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.cdk.repo.IngestionStatusViewRepository;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessResponse;

import java.time.LocalDate;
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

        JobOperator operator = mock(JobOperator.class);
        Job job = mock(Job.class);

        JobExecution mockedExecution = mock(JobExecution.class);
        when(mockedExecution.getId()).thenReturn(321L);

        ArgumentCaptor<JobParameters> paramsCaptor = ArgumentCaptor.forClass(JobParameters.class);
        when(operator.start(eq(job), paramsCaptor.capture())).thenReturn(mockedExecution);

        IngestionStatusViewRepository repo = mock(IngestionStatusViewRepository.class);

        // Use a synchronous executor so the async Runnable executes immediately in the test thread
        var syncExecutor = new SyncTaskExecutor();
        IngestionService svc = new IngestionService(repo, operator, job, syncExecutor);

        IngestionProcessRequest req = new IngestionProcessRequest();
        req.setCourtCentreId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        req.setRoomId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
        req.setDate(LocalDate.parse("2025-10-01"));

        // when
        IngestionProcessResponse resp = svc.startIngestionProcess(cppuid, req);

        // then (params used for the async start)
        verify(operator, times(1)).start(eq(job), any(JobParameters.class));
        JobParameters p = paramsCaptor.getValue();

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
}

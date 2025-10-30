// src/test/java/uk/gov/hmcts/cp/cdk/services/IngestionProcessServiceTest.java
package uk.gov.hmcts.cp.cdk.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import uk.gov.hmcts.cp.cdk.repo.IngestionStatusViewRepository;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessResponse;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("Ingestion Service tests")
class IngestionServiceTest {

    @Test
    @DisplayName("uses JobOperator.start(Job, JobParameters) and returns STARTED")
    void launchesWithJobOperator() throws Exception {
        JobOperator operator = mock(JobOperator.class);
        Job job = mock(Job.class);

        JobExecution mockedExecution = mock(JobExecution.class);
        when(mockedExecution.getId()).thenReturn(321L);

        ArgumentCaptor<JobParameters> paramsCaptor = ArgumentCaptor.forClass(JobParameters.class);
        when(operator.start(eq(job), paramsCaptor.capture())).thenReturn(mockedExecution);
        IngestionStatusViewRepository repo = new IngestionStatusViewRepository();
        IngestionService svc = new IngestionService(repo,operator, job);

        IngestionProcessRequest req = new IngestionProcessRequest();
        req.setCourtCentreId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        req.setRoomId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
        req.setDate(LocalDate.parse("2025-10-01"));

        IngestionProcessResponse resp = svc.startIngestionProcess(req);

        JobParameters p = paramsCaptor.getValue();
        assertThat(p.getString("courtCentreId")).isEqualTo("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        assertThat(p.getString("roomId")).isEqualTo("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        assertThat(p.getString("date")).isEqualTo("2025-10-01");
        assertThat(p.getLong("run")).isNotNull();

        assertThat(resp.getPhase().getValue()).isEqualTo("STARTED");
        assertThat(resp.getMessage()).contains("executionId=321");

        verify(operator, times(1)).start(eq(job), any(JobParameters.class));
    }
}
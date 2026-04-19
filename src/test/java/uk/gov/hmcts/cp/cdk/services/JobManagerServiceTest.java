package uk.gov.hmcts.cp.cdk.services;

import static java.time.LocalDate.now;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessPhase.FAILED;

import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessPhase;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessResponse;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JobManagerServiceTest {

    @Mock
    private ExecutionService executor;

    @InjectMocks
    private JobManagerService jobManagerService;

    private IngestionProcessRequest request;

    @Captor
    private ArgumentCaptor<ExecutionInfo> captor;

    @BeforeEach
    void setUp() {
        request = new IngestionProcessRequest();
        request.setCourtCentreId(randomUUID());
        request.setRoomId(randomUUID());
        request.setDate(now());
    }

    @Test
    void shouldStartIngestionProcessSuccessfully() {
        // given
        final String cppuid = "test-cppuid";

        // when
        final IngestionProcessResponse response = jobManagerService.startIngestionProcess(cppuid, request);

        // then
        assertThat(response).isNotNull();
        assertThat(response.getPhase()).isEqualTo(IngestionProcessPhase.STARTED);
        assertThat(response.getLastUpdated()).isNotNull();
        assertThat(response.getMessage().contains("requestId=")).isTrue();
        verify(executor).executeWith(any(ExecutionInfo.class));
    }

    @Test
    void shouldPopulateExecutionInfoCorrectly() {
        // given
        final String cppuid = "test-cppuid";

        // when
        jobManagerService.startIngestionProcess(cppuid, request);

        // then
        verify(executor).executeWith(captor.capture());
        ExecutionInfo executionInfo = captor.getValue();
        assertThat(executionInfo.getExecutionStatus()).isEqualTo(ExecutionStatus.STARTED);
        assertThat(executionInfo.getAssignedTaskStartTime()).isNotNull();
        assertThat(executionInfo.getJobData()).isNotNull();

        final String jobDataString = executionInfo.getJobData().toString();
        assertThat(jobDataString.contains("cppuid")).isTrue();
        assertThat(jobDataString.contains("courtCentreId")).isTrue();
        assertThat(jobDataString.contains("roomId")).isTrue();
        assertThat(jobDataString.contains("date")).isTrue();
    }

    @Test
    void shouldReturnFailedResponseWhenExecutorThrowsException() {

        // given
        final String cppuid = "test-cppuid";
        doThrow(new RuntimeException("boom")).when(executor).executeWith(any(ExecutionInfo.class));

        // when
        final IngestionProcessResponse response = jobManagerService.startIngestionProcess(cppuid, request);

        // then
        assertThat(response).isNotNull();
        assertThat(response.getPhase()).isEqualTo(FAILED);
        assertThat(response.getMessage().contains("Failed to submit ingestion workflow")).isTrue();
        assertThat(response.getMessage().contains("boom")).isTrue();
        verify(executor).executeWith(any());
    }

    @Test
    void shouldThrowExceptionWhenRequestIsNull() {
        // expect
        final NullPointerException exception = assertThrows(NullPointerException.class,
                () -> jobManagerService.startIngestionProcess("cppuid", null));

        assertThat(exception.getMessage()).isEqualTo("request must not be null");
        verifyNoInteractions(executor);
    }

    @Test
    void shouldSetLastUpdatedTimestamp() {
        // given
        final String cppuid = "test-cppuid";

        // when
        final IngestionProcessResponse response = jobManagerService.startIngestionProcess(cppuid, request);

        // then
        assertThat(response.getLastUpdated()).isNotNull();
        assertThat(response.getLastUpdated().isBefore(OffsetDateTime.now().plusSeconds(1))).isTrue();
    }
}
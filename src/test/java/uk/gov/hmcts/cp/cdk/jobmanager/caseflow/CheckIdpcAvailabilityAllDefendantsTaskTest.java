package uk.gov.hmcts.cp.cdk.jobmanager.caseflow;

import static jakarta.json.Json.createObjectBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.RETRIEVE_MATERIAL_AND_UPLOAD;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOCIDS_ARRAY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_LATEST_DEFENDANT;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_MATERIAL_NAME;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.CPPUID;

import uk.gov.hmcts.cp.cdk.jobmanager.JobManagerRetryProperties;
import uk.gov.hmcts.cp.cdk.jobmanager.support.JobPriority;
import uk.gov.hmcts.cp.cdk.services.IdpcAvailabilityService;
import uk.gov.hmcts.cp.cdk.services.NewIdpcDocument;
import uk.gov.hmcts.cp.cdk.services.RetrieveMaterialAndUploadJobDataService;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CheckIdpcAvailabilityAllDefendantsTaskTest {

    private CheckIdpcAvailabilityAllDefendantsTask task;

    @Mock
    private IdpcAvailabilityService idpcAvailabilityService;
    @Mock
    private ExecutionService executionService;
    @Mock
    private JobManagerRetryProperties retryProperties;
    @Captor
    private ArgumentCaptor<ExecutionInfo> captor;

    private UUID caseId;
    private String userId;

    private final RetrieveMaterialAndUploadJobDataService retrievalJobDataService = new RetrieveMaterialAndUploadJobDataService();

    @BeforeEach
    void setUp() {
        task = new CheckIdpcAvailabilityAllDefendantsTask(
                idpcAvailabilityService, retrievalJobDataService, executionService, retryProperties);

        caseId = UUID.randomUUID();
        userId = "cppuid-123";
    }

    private ExecutionInfo executionInfo(JsonObject jobData) {
        return ExecutionInfo.executionInfo()
                .withJobData(jobData)
                .withAssignedTaskName(CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS)
                .withAssignedTaskStartTime(ZonedDateTime.now())
                .withExecutionStatus(ExecutionStatus.INPROGRESS)
                .build();
    }

    private JsonObject jobData() {
        return createObjectBuilder()
                .add(CTX_CASE_ID_KEY, caseId.toString())
                .add(CPPUID, userId)
                .build();
    }

    @Test
    void shouldComplete_whenNoNewDocuments() {
        when(idpcAvailabilityService.retrieveDocuments(caseId, userId)).thenReturn(List.of());

        ExecutionInfo result = task.execute(executionInfo(jobData()));

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verifyNoInteractions(executionService);
    }

    @Test
    void shouldDispatchRetrievalTask_perNewDocument() {
        NewIdpcDocument doc1 = new NewIdpcDocument(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), "Material1.pdf",
                "def-1", UUID.randomUUID().toString(), false);
        NewIdpcDocument doc2 = new NewIdpcDocument(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), "Material2.pdf",
                "def-2", UUID.randomUUID().toString(), true);

        when(idpcAvailabilityService.retrieveDocuments(caseId, userId)).thenReturn(List.of(doc1, doc2));

        ExecutionInfo result = task.execute(executionInfo(jobData()));

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);

        verify(executionService, times(2)).executeWith(captor.capture());
        List<ExecutionInfo> dispatched = captor.getAllValues();

        for (ExecutionInfo exec : dispatched) {
            assertThat(exec.getAssignedTaskName()).isEqualTo(RETRIEVE_MATERIAL_AND_UPLOAD);
            assertThat(exec.getExecutionStatus()).isEqualTo(ExecutionStatus.STARTED);
            assertThat(exec.getJobData().containsKey(CTX_DOC_ID_KEY)).isTrue();
            assertThat(exec.getJobData().getJsonArray(CTX_DOCIDS_ARRAY)).hasSize(2);
        }

        assertThat(dispatched.stream().anyMatch(e -> e.getJobData().getBoolean(CTX_LATEST_DEFENDANT))).isTrue();
    }

    @Test
    void shouldPropagatePriority_toDispatchedRetrievalTask() {
        NewIdpcDocument doc = new NewIdpcDocument(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), "Material.pdf",
                "def-1", UUID.randomUUID().toString(), true);

        when(idpcAvailabilityService.retrieveDocuments(caseId, userId)).thenReturn(List.of(doc));

        ExecutionInfo incoming = ExecutionInfo.executionInfo()
                .withJobData(jobData())
                .withAssignedTaskName(CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS)
                .withAssignedTaskStartTime(ZonedDateTime.now())
                .withExecutionStatus(ExecutionStatus.STARTED)
                .withPriority(JobPriority.HIGH)
                .build();

        task.execute(incoming);

        verify(executionService).executeWith(captor.capture());
        assertThat(captor.getValue().getPriority()).isEqualTo(JobPriority.HIGH);
    }

    @Test
    void shouldNotTruncateMaterialName_whenServiceAlreadyTruncatedIt() {
        String materialName = "already-truncated-by-service.pdf";
        NewIdpcDocument doc = new NewIdpcDocument(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), materialName,
                "def-1", UUID.randomUUID().toString(), true);

        when(idpcAvailabilityService.retrieveDocuments(caseId, userId)).thenReturn(List.of(doc));

        task.execute(executionInfo(jobData()));

        verify(executionService).executeWith(captor.capture());
        assertThat(captor.getValue().getJobData().getString(CTX_MATERIAL_NAME)).isEqualTo(materialName);
    }

    @Test
    void shouldRetry_whenServiceThrows() {
        when(idpcAvailabilityService.retrieveDocuments(any(), any()))
                .thenThrow(new RuntimeException("Downstream service failure"));

        ExecutionInfo result = task.execute(executionInfo(jobData()));

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(result.isShouldRetry()).isTrue();
        verifyNoInteractions(executionService);
    }

    @Test
    void shouldRetry_whenCaseIdMissing() {
        JsonObject jobData = createObjectBuilder()
                .add(CPPUID, userId)
                .build();

        ExecutionInfo result = task.execute(executionInfo(jobData));

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(result.isShouldRetry()).isTrue();
        verifyNoInteractions(executionService, idpcAvailabilityService);
    }

    @Test
    void shouldReturnRetryDurations() {
        final JobManagerRetryProperties.RetryConfig retryConfig = new JobManagerRetryProperties.RetryConfig();
        retryConfig.setMaxAttempts(3);
        retryConfig.setDelaySeconds(10);
        when(retryProperties.getDefaultRetry()).thenReturn(retryConfig);

        final List<Long> durations = task.getRetryDurationsInSecs().orElseThrow();

        assertThat(durations).isEqualTo(List.of(10L, 10L, 10L));
    }
}

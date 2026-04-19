package uk.gov.hmcts.cp.cdk.jobmanager.caseflow;

import static jakarta.json.Json.createObjectBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.RETRIEVE_FROM_MATERIAL;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOCIDS_ARRAY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_LATEST_DEFENDANT;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.CPPUID;

import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.LatestMaterialInfo;
import uk.gov.hmcts.cp.cdk.jobmanager.IngestionProperties;
import uk.gov.hmcts.cp.cdk.jobmanager.JobManagerRetryProperties;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.repo.DocumentIdResolver;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
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
    private ProgressionClient progressionClient;
    @Mock
    private ExecutionService executionService;
    @Mock
    private DocumentIdResolver documentIdResolver;
    @Mock
    private JobManagerRetryProperties retryProperties;
    @Mock
    private CaseDocumentRepository caseDocumentRepository;
    @Mock
    private IngestionProperties ingestionProperties;
    @Mock
    private IngestionProperties.Feature feature;

    @Captor
    private ArgumentCaptor<ExecutionInfo> captor;

    private String caseId;
    private String userId;

    @BeforeEach
    void setUp() {
        task = new CheckIdpcAvailabilityAllDefendantsTask(
                progressionClient,
                executionService,
                documentIdResolver,
                retryProperties,
                caseDocumentRepository,
                ingestionProperties
        );

        caseId = UUID.randomUUID().toString();
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

    @Test
    void shouldRetry_whenCaseIdMissing() {
        JsonObject jobData = createObjectBuilder()
                .add(CPPUID, userId)
                .build();

        ExecutionInfo result = task.execute(executionInfo(jobData));

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(result.isShouldRetry()).isTrue();
    }

    @Test
    void shouldComplete_whenNoMaterials() {
        JsonObject jobData = createObjectBuilder()
                .add(CTX_CASE_ID_KEY, caseId)
                .add(CPPUID, userId)
                .build();

        when(progressionClient.getCourtDocumentsForAllDefendants(any(), any()))
                .thenReturn(List.of());

        ExecutionInfo result = task.execute(executionInfo(jobData));

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verifyNoInteractions(executionService);
    }

    @Test
    void shouldSkipExistingDocuments() {
        UUID materialId = UUID.randomUUID();
        UUID defendantId = UUID.randomUUID();

        LatestMaterialInfo info = new LatestMaterialInfo(
                List.of(caseId),
                "doc-type",
                "desc",
                materialId.toString(),
                "Material",
                ZonedDateTime.now(),
                UUID.randomUUID().toString(),
                defendantId.toString()
        );

        JsonObject jobData = createObjectBuilder()
                .add(CTX_CASE_ID_KEY, caseId)
                .add(CPPUID, userId)
                .build();

        when(progressionClient.getCourtDocumentsForAllDefendants(any(), any()))
                .thenReturn(List.of(info));
        when(documentIdResolver.resolveExistingDocIdForDefendant(any(), any(), any()))
                .thenReturn(Optional.of(UUID.randomUUID()));
        ExecutionInfo result = task.execute(executionInfo(jobData));
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verifyNoInteractions(executionService);
    }

    @Test
    void shouldScheduleTasks_forMultipleDefendants() {
        UUID materialId = UUID.randomUUID();
        UUID def1 = UUID.randomUUID();
        UUID def2 = UUID.randomUUID();

        LatestMaterialInfo m1 = new LatestMaterialInfo(
                List.of(caseId), "doc", "desc",
                materialId.toString(), "Material1",
                ZonedDateTime.now().minusMinutes(1),
                UUID.randomUUID().toString(),
                def1.toString()
        );

        LatestMaterialInfo m2 = new LatestMaterialInfo(
                List.of(caseId), "doc", "desc",
                materialId.toString(), "Material2",
                ZonedDateTime.now(),
                UUID.randomUUID().toString(),
                def2.toString()
        );

        JsonObject jobData = createObjectBuilder()
                .add(CTX_CASE_ID_KEY, caseId)
                .add(CPPUID, userId)
                .build();

        when(ingestionProperties.getFeature()).thenReturn(feature);
        when(feature.isUseMultiDefendant()).thenReturn(false);

        when(progressionClient.getCourtDocumentsForAllDefendants(any(), any()))
                .thenReturn(List.of(m1, m2));

        when(documentIdResolver.resolveExistingDocIdForDefendant(any(), any(), any()))
                .thenReturn(Optional.empty());

        ExecutionInfo result = task.execute(executionInfo(jobData));

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);

        verify(executionService, times(2)).executeWith(captor.capture());

        List<ExecutionInfo> executions = captor.getAllValues();

        for (ExecutionInfo exec : executions) {
            assertThat(exec.getAssignedTaskName()).isEqualTo(RETRIEVE_FROM_MATERIAL);
            assertThat(exec.getExecutionStatus()).isEqualTo(ExecutionStatus.STARTED);
            assertThat(exec.getJobData().containsKey(CTX_DOC_ID_KEY)).isTrue();
            assertThat(exec.getJobData().containsKey(CTX_DOCIDS_ARRAY)).isTrue();
        }

        assertThat(executions.stream()
                .anyMatch(e -> e.getJobData().getBoolean(CTX_LATEST_DEFENDANT)))
                .isTrue();
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
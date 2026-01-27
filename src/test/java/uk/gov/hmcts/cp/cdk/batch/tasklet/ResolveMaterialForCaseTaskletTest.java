package uk.gov.hmcts.cp.cdk.batch.tasklet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.USERID_FOR_EXTERNAL_CALLS;
import static uk.gov.hmcts.cp.cdk.batch.support.PartitionKeys.PARTITION_CASE_ID;
import static uk.gov.hmcts.cp.cdk.batch.support.PartitionKeys.PARTITION_RESULT_MATERIAL_ID;
import static uk.gov.hmcts.cp.cdk.batch.support.PartitionKeys.PARTITION_RESULT_MATERIAL_NAME;

import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.LatestMaterialInfo;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * Tests for {@link ResolveMaterialForCaseTasklet}.
 */
@ExtendWith(MockitoExtension.class)
class ResolveMaterialForCaseTaskletTest {

    @Mock
    private ProgressionClient progressionClient;

    @Mock
    private StepContribution contribution;

    @Mock
    private ChunkContext chunkContext;

    @Mock
    private StepExecution stepExecution;

    @Mock
    private JobExecution jobExecution;

    private ResolveMaterialForCaseTasklet tasklet;

    private ExecutionContext jobCtx;
    private ExecutionContext stepCtx;

    @BeforeEach
    void setUp() {
        tasklet = new ResolveMaterialForCaseTasklet(progressionClient);

        jobCtx = new ExecutionContext();
        stepCtx = new ExecutionContext();

        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepCtx);
        when(stepExecution.getJobExecution()).thenReturn(jobExecution);
        when(jobExecution.getExecutionContext()).thenReturn(jobCtx);
    }

    @Test
    @DisplayName("Missing PARTITION_CASE_ID → skips and does not call Progression")
    void missingCaseIdSkips() {
        // no PARTITION_CASE_ID in step context
        final RepeatStatus rs = tasklet.execute(contribution, chunkContext);
        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);

        verify(progressionClient, never()).getCourtDocuments(any(UUID.class), anyString());
        assertThat(stepCtx.containsKey(PARTITION_RESULT_MATERIAL_ID)).isFalse();
        assertThat(stepCtx.containsKey(PARTITION_RESULT_MATERIAL_NAME)).isFalse();
    }

    @Test
    @DisplayName("Invalid UUID caseId → skips and does not call Progression")
    void invalidCaseIdSkips() {
        stepCtx.putString(PARTITION_CASE_ID, "not-a-uuid");
        jobCtx.putString(USERID_FOR_EXTERNAL_CALLS, "user-1");

        final RepeatStatus rs = tasklet.execute(contribution, chunkContext);
        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);

        verify(progressionClient, never()).getCourtDocuments(any(UUID.class), anyString());
        assertThat(stepCtx.containsKey(PARTITION_RESULT_MATERIAL_ID)).isFalse();
        assertThat(stepCtx.containsKey(PARTITION_RESULT_MATERIAL_NAME)).isFalse();
    }

    @Test
    @DisplayName("Happy path → sets materialId and materialName in step context")
    @SneakyThrows
    void happyPathSetsContext() {
        final UUID caseId = UUID.randomUUID();
        final String userId = "cppuid-123";
        final String materialId = UUID.randomUUID().toString();
        final String materialName = "IDPC";

        stepCtx.putString(PARTITION_CASE_ID, caseId.toString());
        jobCtx.putString(USERID_FOR_EXTERNAL_CALLS, userId);

        final LatestMaterialInfo info = new LatestMaterialInfo(
                List.of(caseId.toString()),
                "DT01",
                "IDPC Document",
                materialId,
                materialName,
                ZonedDateTime.now(),
                UUID.randomUUID().toString()
        );

        when(progressionClient.getCourtDocuments(caseId, userId)).thenReturn(Optional.of(info));

        final RepeatStatus rs = tasklet.execute(contribution, chunkContext);
        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);

        verify(progressionClient).getCourtDocuments(caseId, userId);
        assertThat(stepCtx.getString(PARTITION_RESULT_MATERIAL_ID)).isEqualTo(materialId);
        assertThat(stepCtx.getString(PARTITION_RESULT_MATERIAL_NAME)).isEqualTo(materialName);
    }

    @Test
    @DisplayName("Missing USERID_FOR_EXTERNAL_CALLS → warns but still proceeds to call Progression")
    void missingUserIdStillCalls() {
        final UUID caseId = UUID.randomUUID();
        // userId intentionally missing
        stepCtx.putString(PARTITION_CASE_ID, caseId.toString());

        when(progressionClient.getCourtDocuments(caseId, null)).thenReturn(Optional.empty());

        final RepeatStatus rs = tasklet.execute(contribution, chunkContext);
        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);

        verify(progressionClient).getCourtDocuments(caseId, null);
        // No material fields set because client returned empty
        assertThat(stepCtx.containsKey(PARTITION_RESULT_MATERIAL_ID)).isFalse();
        assertThat(stepCtx.containsKey(PARTITION_RESULT_MATERIAL_NAME)).isFalse();
    }

    @Test
    @DisplayName("Progression returns empty → no keys set in step context")
    void emptyFromProgressionDoesNotSet() {
        final UUID caseId = UUID.randomUUID();
        final String userId = "user-x";

        stepCtx.putString(PARTITION_CASE_ID, caseId.toString());
        jobCtx.putString(USERID_FOR_EXTERNAL_CALLS, userId);

        when(progressionClient.getCourtDocuments(caseId, userId)).thenReturn(Optional.empty());

        final RepeatStatus rs = tasklet.execute(contribution, chunkContext);
        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);

        verify(progressionClient).getCourtDocuments(caseId, userId);
        assertThat(stepCtx.containsKey(PARTITION_RESULT_MATERIAL_ID)).isFalse();
        assertThat(stepCtx.containsKey(PARTITION_RESULT_MATERIAL_NAME)).isFalse();
    }

    @Test
    @DisplayName("Progression throws → safely handled as empty (no context updates)")
    void clientThrowsHandledAsEmpty() {
        final UUID caseId = UUID.randomUUID();
        final String userId = "user-y";

        stepCtx.putString(PARTITION_CASE_ID, caseId.toString());
        jobCtx.putString(USERID_FOR_EXTERNAL_CALLS, userId);

        when(progressionClient.getCourtDocuments(caseId, userId))
                .thenThrow(new RuntimeException("boom"));

        final RepeatStatus rs = tasklet.execute(contribution, chunkContext);
        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);

        verify(progressionClient).getCourtDocuments(caseId, userId);
        assertThat(stepCtx.containsKey(PARTITION_RESULT_MATERIAL_ID)).isFalse();
        assertThat(stepCtx.containsKey(PARTITION_RESULT_MATERIAL_NAME)).isFalse();

        // Clean state for subsequent tests (if class-level reuse happens)
        reset(progressionClient);
    }
}

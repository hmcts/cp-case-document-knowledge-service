package uk.gov.hmcts.cp.cdk.batch.tasklet;

import static org.springframework.util.StringUtils.hasText;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.USERID_FOR_EXTERNAL_CALLS;
import static uk.gov.hmcts.cp.cdk.batch.support.PartitionKeys.PARTITION_CASE_ID;
import static uk.gov.hmcts.cp.cdk.batch.support.PartitionKeys.PARTITION_RESULT_MATERIAL_ID;
import static uk.gov.hmcts.cp.cdk.batch.support.PartitionKeys.PARTITION_RESULT_MATERIAL_NAME;
import static uk.gov.hmcts.cp.cdk.batch.support.TaskletUtils.parseUuid;
import static uk.gov.hmcts.cp.cdk.batch.support.TaskletUtils.safeGetCourtDocuments;

import uk.gov.hmcts.cp.cdk.batch.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.batch.clients.progression.dto.LatestMaterialInfo;

import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ResolveMaterialForCaseTasklet implements Tasklet {

    private final ProgressionClient progressionClient;

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        boolean proceed = true;

        final StepExecution stepExecution = contribution.getStepExecution();
        final ExecutionContext jobContext = stepExecution.getJobExecution().getExecutionContext();
        final ExecutionContext stepContext = stepExecution.getExecutionContext();

        final String caseIdString = stepContext.getString(PARTITION_CASE_ID, null);
        final String userId = (String) jobContext.get(USERID_FOR_EXTERNAL_CALLS);

        if (!hasText(caseIdString)) {
            log.warn("Missing '{}' in partition context → skipping.", PARTITION_CASE_ID);
            proceed = false;
        }
        if (!hasText(userId)) {
            log.warn("Missing '{}' in job context; downstream call may fail.", USERID_FOR_EXTERNAL_CALLS);
        }

        Optional<UUID> caseIdUuidOptional = Optional.empty();
        if (proceed) {
            caseIdUuidOptional = parseUuid(caseIdString);
            if (caseIdUuidOptional.isEmpty()) {
                log.warn("Partition caseId '{}' is not a valid UUID → skipping.", caseIdString);
                proceed = false;
            }
        }

        if (proceed) {
            final Optional<LatestMaterialInfo> latest =
                    safeGetCourtDocuments(progressionClient, caseIdUuidOptional.get(), userId);
            latest.ifPresent(info -> {
                stepContext.putString(PARTITION_RESULT_MATERIAL_ID, info.materialId());
                stepContext.putString(PARTITION_RESULT_MATERIAL_NAME, info.materialName());
                log.debug("Resolved material for caseId {} → id={}, name={}", caseIdString, info.materialId(), info.materialName());
            });
        }

        return RepeatStatus.FINISHED;
    }
}

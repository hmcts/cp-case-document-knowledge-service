package uk.gov.hmcts.cp.cdk.batch.tasklet;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.cdk.batch.BatchKeys;
import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.LatestMaterialInfo;

import java.util.*;

import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_CASE_IDS;

@Component
@RequiredArgsConstructor
public class FilterEligibleCasesTasklet implements Tasklet {
    private final ProgressionClient progressionClient;

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final ExecutionContext jobCtx = contribution.getStepExecution().getJobExecution().getExecutionContext();
        @SuppressWarnings("unchecked") final List<String> rawCaseIds = (List<String>) jobCtx.get(CTX_CASE_IDS);
        final List<String> eligibleMaterialIds = new ArrayList<>();
        final Map<String, String> materialToCaseMap = new HashMap<>();

        for (final String idStr : rawCaseIds) {
            final UUID caseId = UUID.fromString(idStr);
            final Optional<LatestMaterialInfo> courtDocuments = progressionClient.getCourtDocuments(caseId);
            if (courtDocuments.isPresent()) {
                LatestMaterialInfo info = courtDocuments.get();
                eligibleMaterialIds.add(info.materialId());
                materialToCaseMap.put(info.materialId(), idStr);
            }
        }

        contribution.getStepExecution()
                .getJobExecution()
                .getExecutionContext()
                .put(BatchKeys.CONTEXT_KEY_ELIGIBLE_MATERIAL_IDS, eligibleMaterialIds);

        contribution.getStepExecution()
                .getJobExecution()
                .getExecutionContext()
                .put(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP, materialToCaseMap);

        return RepeatStatus.FINISHED;

    }
}


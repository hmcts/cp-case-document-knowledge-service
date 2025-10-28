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
import uk.gov.hmcts.cp.cdk.query.QueryClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_CASE_IDS;
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_ELIGIBLE_CASE_IDS;

@Component
@RequiredArgsConstructor
public class FilterEligibleCasesTasklet implements Tasklet {
    private final ProgressionClient progressionClient;

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final ExecutionContext jobCtx = contribution.getStepExecution().getJobExecution().getExecutionContext();
        @SuppressWarnings("unchecked")
        final List<String> rawCaseIds = (List<String>)jobCtx.get(CTX_CASE_IDS);
        final List<String> eligibleMaterialIds = new ArrayList<>();

        for (final String idStr : rawCaseIds) {
            final UUID caseId = UUID.fromString(idStr);
            final Optional<LatestMaterialInfo> meta = progressionClient.getCourtDocuments(caseId);
            meta.ifPresent(info -> eligibleMaterialIds.add(info.materialId()));

        }

        contribution.getStepExecution()
                .getJobExecution()
                .getExecutionContext()
                .put(BatchKeys.CONTEXT_KEY_ELIGIBLE_MATERIAL_IDS, eligibleMaterialIds);

        return RepeatStatus.FINISHED;

    }
}


package uk.gov.hmcts.cp.cdk.batch.tasklet;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.cdk.query.QueryClient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_CASE_IDS;
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_ELIGIBLE_CASE_IDS;

@Component
@RequiredArgsConstructor
public class FilterEligibleCasesTasklet implements Tasklet {
    private final QueryClient queryClient;

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final ExecutionContext jobCtx = contribution.getStepExecution().getJobExecution().getExecutionContext();

        @SuppressWarnings("unchecked")
        final List<String> raw = jobCtx.containsKey(CTX_CASE_IDS)
                ? (List<String>) jobCtx.get(CTX_CASE_IDS)
                : List.of();

        final List<String> eligible = new ArrayList<>();
        for (final String idStr : raw) {
            final UUID caseId = UUID.fromString(idStr);

        }
        jobCtx.put(CTX_ELIGIBLE_CASE_IDS, eligible);
        return RepeatStatus.FINISHED;
    }
}


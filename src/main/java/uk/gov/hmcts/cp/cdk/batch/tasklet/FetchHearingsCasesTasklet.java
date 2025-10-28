package uk.gov.hmcts.cp.cdk.batch.tasklet;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.cdk.query.QueryClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_CASE_IDS;

@Component
@RequiredArgsConstructor
public class FetchHearingsCasesTasklet implements Tasklet {
    private final QueryClient queryClient;

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) throws Exception {
        final String court = contribution.getStepExecution().getJobParameters().getString("court");
        final LocalDate date = LocalDate.parse(contribution.getStepExecution().getJobParameters().getString("date"));

        final List<QueryClient.CaseSummary> summaries = queryClient.getHearingsAndCases(court, date);
        final List<String> ids = new ArrayList<>(summaries.size());
        for (final QueryClient.CaseSummary s : summaries) {
            ids.add(s.caseId().toString());
        }
        final ExecutionContext jobCtx = contribution.getStepExecution().getJobExecution().getExecutionContext();
        jobCtx.put(CTX_CASE_IDS, ids);
        return RepeatStatus.FINISHED;
    }
}

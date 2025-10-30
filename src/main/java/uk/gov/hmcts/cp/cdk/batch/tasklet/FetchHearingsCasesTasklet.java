package uk.gov.hmcts.cp.cdk.batch.tasklet;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.cdk.clients.hearing.HearingClient;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummariesInfo;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_CASE_IDS_KEY;

@Component
@RequiredArgsConstructor
public class FetchHearingsCasesTasklet implements Tasklet {
    private final HearingClient hearingClient;

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) throws Exception {
        final String courtCentreId = contribution.getStepExecution().getJobParameters().getString("courtCentreId");
        final String roomId = contribution.getStepExecution().getJobParameters().getString("roomId");
        final LocalDate date = LocalDate.parse(contribution.getStepExecution().getJobParameters().getString("date"));
        final List<HearingSummariesInfo> summaries = hearingClient.getHearingsAndCases(courtCentreId, roomId, date);
        final List<String> caseIdStrings = new ArrayList<>(summaries.size());
        for (final HearingSummariesInfo summary : summaries) {
            caseIdStrings.add(summary.caseId());
        }

        contribution.getStepExecution()
                .getJobExecution()
                .getExecutionContext()
                .put(CTX_CASE_IDS_KEY, caseIdStrings);

        return RepeatStatus.FINISHED;

    }
}
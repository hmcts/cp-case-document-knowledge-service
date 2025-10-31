package uk.gov.hmcts.cp.cdk.batch.tasklet;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.cdk.batch.BatchKeys;
import uk.gov.hmcts.cp.cdk.batch.clients.hearing.HearingClient;
import uk.gov.hmcts.cp.cdk.batch.clients.hearing.dto.HearingSummariesInfo;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_CASE_IDS_KEY;

@Slf4j
@Component
@RequiredArgsConstructor
public class FetchHearingsCasesTasklet implements Tasklet {
    private final HearingClient hearingClient;

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) throws Exception {
        final ExecutionContext stepCtx = contribution.getStepExecution().getExecutionContext();
        final Map<String, Object> jobParams = chunkContext.getStepContext().getJobParameters();

        final String courtCentreId = str(jobParams, "courtCentreId");
        final String roomId = str(jobParams, "roomId");
        final String dateParam = str(jobParams, "date");

        if (isBlank(courtCentreId) || isBlank(roomId) || isBlank(dateParam)) {
            log.warn("Missing required job parameters (courtCentreId/roomId/date). " +
                    "courtCentreId='{}', roomId='{}', date='{}' → NOOP.", courtCentreId, roomId, dateParam);
            contribution.setExitStatus(ExitStatus.NOOP);
            stepCtx.put(BatchKeys.CTX_CASE_IDS_KEY, List.of());
            return RepeatStatus.FINISHED;
        }
        final LocalDate date = LocalDate.parse(dateParam);
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

    private static String str(final Map<String, Object> params, final String key) {
        if (params == null) return null;
        final Object object = params.get(key);
        return (object instanceof String s) ? s : null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
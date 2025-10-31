package uk.gov.hmcts.cp.cdk.batch.tasklet;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.cdk.batch.clients.hearing.HearingClient;
import uk.gov.hmcts.cp.cdk.batch.clients.hearing.dto.HearingSummariesInfo;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_CASE_IDS_KEY;
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.Params.*;
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.USERID_FOR_EXTERNAL_CALLS;

@Slf4j
@Component
@RequiredArgsConstructor
public class FetchHearingsCasesTasklet implements Tasklet {

    private final HearingClient hearingClient;

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) throws Exception {
        final JobParameters params = contribution.getStepExecution().getJobParameters();
        final ExecutionContext jobCtx = contribution.getStepExecution().getJobExecution().getExecutionContext();

        final String courtCentreId = params.getString(COURT_CENTRE_ID);
        final String roomId = params.getString(ROOM_ID);
        final String dateParam = params.getString(DATE);
        final String cppuid = params.getString(CPPUID);
        jobCtx.put(USERID_FOR_EXTERNAL_CALLS, cppuid);
        if (isBlank(courtCentreId) || isBlank(roomId) || isBlank(dateParam)) {
            log.warn("Missing required job parameters (courtCentreId/roomId/date). courtCentreId='{}', roomId='{}', date='{}' → NOOP.",
                    courtCentreId, roomId, dateParam);
            jobCtx.put(CTX_CASE_IDS_KEY, Collections.emptyList());
            contribution.setExitStatus(ExitStatus.NOOP);
            return RepeatStatus.FINISHED;
        }

        final LocalDate date;
        try {
            date = LocalDate.parse(dateParam);
        } catch (DateTimeParseException e) {
            log.warn("Invalid date format for parameter '{}': '{}' → NOOP.", DATE, dateParam);
            jobCtx.put(CTX_CASE_IDS_KEY, Collections.emptyList());
            contribution.setExitStatus(ExitStatus.NOOP);
            return RepeatStatus.FINISHED;
        }

        final List<HearingSummariesInfo> summaries = hearingClient.getHearingsAndCases(courtCentreId, roomId, date,cppuid);
        final List<String> caseIds = new ArrayList<>(summaries.size());
        for (HearingSummariesInfo s : summaries) {
            caseIds.add(s.caseId());
        }

        jobCtx.put(CTX_CASE_IDS_KEY, caseIds);
        return RepeatStatus.FINISHED;
    }

    private static boolean isBlank(final String s) {
        return s == null || s.isBlank();
    }
}

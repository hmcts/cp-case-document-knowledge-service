package uk.gov.hmcts.cp.cdk.batch.tasklet;

import static org.springframework.util.StringUtils.hasText;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_CASE_IDS_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.Params.COURT_CENTRE_ID;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.Params.CPPUID;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.Params.DATE;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.Params.ROOM_ID;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.USERID_FOR_EXTERNAL_CALLS;

import uk.gov.hmcts.cp.cdk.clients.hearing.HearingClient;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummariesInfo;
import uk.gov.hmcts.cp.cdk.util.TaskUtils;

import java.time.LocalDate;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FetchHearingsCasesTasklet implements Tasklet {

    private static final List<String> EMPTY_CASE_IDS = List.of();

    private final HearingClient hearingClient;

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) throws Exception {
        final StepExecution stepExecution = contribution.getStepExecution();
        final JobParameters params = stepExecution.getJobParameters();
        final ExecutionContext jobCtx = stepExecution.getJobExecution().getExecutionContext();

        final String courtCentreId = params.getString(COURT_CENTRE_ID);
        final String roomId = params.getString(ROOM_ID);
        final String hearingDate = params.getString(DATE);
        final String cppuid = params.getString(CPPUID);

        jobCtx.put(USERID_FOR_EXTERNAL_CALLS, cppuid);

        ExitStatus exitStatus = ExitStatus.COMPLETED;
        List<String> caseIds = EMPTY_CASE_IDS;

        if (hasText(courtCentreId) && hasText(roomId) && hasText(hearingDate)) {
            final LocalDate date = TaskUtils.parseIsoDateOrNull(hearingDate);
            if (date != null) {
                final List<HearingSummariesInfo> summaries =
                        hearingClient.getHearingsAndCases(courtCentreId, roomId, date, cppuid);
                caseIds = summaries == null
                        ? EMPTY_CASE_IDS
                        : summaries.stream().map(HearingSummariesInfo::caseId).toList();
            } else {
                log.warn("Invalid date format for parameter '{}': '{}' → NOOP.", DATE, hearingDate);
                exitStatus = ExitStatus.NOOP;
            }
        } else {
            log.warn("Missing required job parameters (courtCentreId/roomId/date). courtCentreId='{}', roomId='{}', date='{}' → NOOP.",
                    courtCentreId, roomId, hearingDate);
            exitStatus = ExitStatus.NOOP;
        }

        jobCtx.put(CTX_CASE_IDS_KEY, caseIds);
        contribution.setExitStatus(exitStatus);
        return RepeatStatus.FINISHED;
    }
}

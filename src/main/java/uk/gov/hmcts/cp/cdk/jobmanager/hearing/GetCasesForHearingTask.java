package uk.gov.hmcts.cp.cdk.jobmanager.hearing;

import static org.springframework.util.StringUtils.hasText;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_CASE_ELIGIBILITY;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.GET_CASES_FOR_HEARING;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.COURT_CENTRE_ID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.CPPUID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.DATE;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.ROOM_ID;

import uk.gov.hmcts.cp.cdk.clients.hearing.HearingClient;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummariesInfo;
import uk.gov.hmcts.cp.cdk.util.TaskUtils;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@Task(GET_CASES_FOR_HEARING)
public class GetCasesForHearingTask implements ExecutableTask {

    private static final List<String> EMPTY_CASE_IDS = List.of();

    private final HearingClient hearingClient;
    private final ExecutionService executionService;

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {

        final JsonObject jobData = executionInfo.getJobData();

        final String courtCentreId = jobData.getString(COURT_CENTRE_ID, null);
        final String roomId = jobData.getString(ROOM_ID, null);
        final String hearingDate = jobData.getString(DATE, null);
        final String cppuid = jobData.getString(CPPUID, null);
        final String requestId = jobData.getString("requestId", null);

        log.info(
                "Starting {}. requestId={}, cppuid={}, courtCentreId={}, roomId={}, date={}",
                GET_CASES_FOR_HEARING, requestId, cppuid, courtCentreId, roomId, hearingDate
        );

        if (!hasText(courtCentreId) || !hasText(roomId) || !hasText(hearingDate)) {
            log.warn(
                    "{} skipped: missing required fields. requestId={}, courtCentreId={}, roomId={}, date={}",
                    GET_CASES_FOR_HEARING, requestId, courtCentreId, roomId, hearingDate
            );
            return ExecutionInfo.executionInfo()
                    .from(executionInfo)
                    .withExecutionStatus(ExecutionStatus.COMPLETED)
                    .build();
        }

        try {
            final LocalDate date = TaskUtils.parseIsoDateOrNull(hearingDate);
            if (date == null) {
                log.warn(
                        "{} skipped: invalid date format. requestId={}, date={}",
                        GET_CASES_FOR_HEARING, requestId, hearingDate
                );
                return ExecutionInfo.executionInfo()
                        .from(executionInfo)
                        .withExecutionStatus(ExecutionStatus.COMPLETED)
                        .build();
            }

            final List<HearingSummariesInfo> summaries = hearingClient.getHearingsAndCases(courtCentreId, roomId, date, cppuid);
            final List<String> caseIds = (summaries == null) ? EMPTY_CASE_IDS :
                    summaries.stream().map(HearingSummariesInfo::caseId).toList();

            if (caseIds.isEmpty()) {
                log.info("No cases found for {}. requestId={}", GET_CASES_FOR_HEARING, requestId);
                return ExecutionInfo.executionInfo()
                        .from(executionInfo)
                        .withExecutionStatus(ExecutionStatus.COMPLETED)
                        .build();
            }

            for (String caseId : caseIds) {
                JsonObject singleCaseJobData = Json.createObjectBuilder(jobData)
                        .add(CTX_CASE_ID_KEY, caseId)
                        .build();

                ExecutionInfo newTask = ExecutionInfo.executionInfo()
                        .from(executionInfo)
                        .withAssignedTaskName(CHECK_CASE_ELIGIBILITY)
                        .withJobData(singleCaseJobData)
                        .withExecutionStatus(ExecutionStatus.STARTED)
                        .build();

                executionService.executeWith(newTask);

                log.info("Created {} for caseId={} requestId={}", CHECK_CASE_ELIGIBILITY, caseId, requestId);
            }

            return ExecutionInfo.executionInfo()
                    .from(executionInfo)
                    .withExecutionStatus(ExecutionStatus.COMPLETED)
                    .build();

        } catch (Exception ex) {
            log.error("{} failed. requestId={}, cppuid={}", GET_CASES_FOR_HEARING, requestId, cppuid, ex);

            return ExecutionInfo.executionInfo()
                    .from(executionInfo)
                    .withExecutionStatus(ExecutionStatus.INPROGRESS)
                    .withShouldRetry(true)
                    .build();
        }
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        return Optional.of(List.of(10L, 30L, 60L));
    }
}

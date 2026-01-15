package uk.gov.hmcts.cp.cdk.jobmanager.hearing;

import static org.springframework.util.StringUtils.hasText;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.COURT_CENTRE_ID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.CPPUID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.DATE;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.ROOM_ID;

import uk.gov.hmcts.cp.cdk.util.TaskUtils;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.cdk.clients.hearing.HearingClient;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummariesInfo;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;


import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_CASE_ELIGIBILITY;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.GET_CASES_FOR_HEARING;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.USERID_FOR_EXTERNAL_CALLS;

@Slf4j
@Component
@RequiredArgsConstructor
@Task(GET_CASES_FOR_HEARING)
public class GetCasesForHearingTask implements ExecutableTask {

    private static final List<String> EMPTY_CASE_IDS = List.of();

    private final HearingClient hearingClient;
    private final  ExecutionService executionService; // <-- service to create tasks

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {

        final JsonObject jobData = executionInfo.getJobData();

        final String courtCentreId = jobData.getString(COURT_CENTRE_ID, null);
        final String roomId = jobData.getString(ROOM_ID, null);
        final String hearingDate = jobData.getString(DATE, null);
        final String cppuid = jobData.getString(CPPUID, null);
        final String requestId = jobData.getString("requestId", null);

        log.info(
                "Starting FETCH_HEARINGS_CASES_TASK. requestId={}, cppuid={}, courtCentreId={}, roomId={}, date={}",
                requestId, cppuid, courtCentreId, roomId, hearingDate
        );

        try {
            List<String> caseIds = EMPTY_CASE_IDS;

            if (hasText(courtCentreId) && hasText(roomId) && hasText(hearingDate)) {
                final LocalDate date = TaskUtils.parseIsoDateOrNull(hearingDate);
                if (date != null) {
                    final List<HearingSummariesInfo> summaries =
                            hearingClient.getHearingsAndCases(courtCentreId, roomId, date, cppuid);

                    caseIds = (summaries == null)
                            ? EMPTY_CASE_IDS
                            : summaries.stream()
                            .map(HearingSummariesInfo::caseId)
                            .toList();
                }
            }


            if (!caseIds.isEmpty()) {
                for (String caseId : caseIds) {
                    JsonObjectBuilder singleCaseJobData = Json.createObjectBuilder(jobData);
                    singleCaseJobData.add(USERID_FOR_EXTERNAL_CALLS, cppuid);
                    singleCaseJobData.add(CTX_CASE_ID_KEY, caseId);

                    ExecutionInfo newTask = ExecutionInfo.executionInfo()
                            .from(executionInfo)
                            .withAssignedTaskName(CHECK_CASE_ELIGIBILITY)
                            .withJobData(singleCaseJobData.build())
                            .withExecutionStatus(ExecutionStatus.STARTED)
                            .build();


                   executionService.executeWith(newTask);

                    log.info(
                            "Created CHECK_CASE_ELIGIBILITY for caseId={} requestId={}",
                            caseId, requestId
                    );
                }

                // Parent task can mark itself completed
                return ExecutionInfo.executionInfo()
                        .from(executionInfo)
                        .withExecutionStatus(ExecutionStatus.COMPLETED)
                        .build();
            }

            log.info(
                    "No cases found {} completed. requestId={}",
                    CHECK_CASE_ELIGIBILITY,requestId
            );

            return ExecutionInfo.executionInfo()
                    .from(executionInfo)
                    .withExecutionStatus(ExecutionStatus.COMPLETED)
                    .build();

        } catch (Exception ex) {
            log.error(
                    "FETCH_HEARINGS_CASES_TASK failed. requestId={}, cppuid={}",
                    requestId, cppuid, ex
            );

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

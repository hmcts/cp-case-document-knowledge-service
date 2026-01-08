package uk.gov.hmcts.cp.cdk.tasks;

import static org.springframework.util.StringUtils.hasText;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_CASE_IDS_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.Params.COURT_CENTRE_ID;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.Params.CPPUID;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.Params.DATE;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.Params.ROOM_ID;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.USERID_FOR_EXTERNAL_CALLS;
import static uk.gov.hmcts.cp.cdk.batch.support.PartitionKeys.PARTITION_CASE_ID;

import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import edu.umd.cs.findbugs.annotations.Priority;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.cdk.batch.clients.hearing.HearingClient;
import uk.gov.hmcts.cp.cdk.batch.clients.hearing.dto.HearingSummariesInfo;
import uk.gov.hmcts.cp.cdk.batch.support.TaskletUtils;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
@Task("FETCH_HEARINGS_CASES_TASK")
public class FetchHearingsCasesTask implements ExecutableTask {

    private static final List<String> EMPTY_CASE_IDS = List.of();

    private final HearingClient hearingClient;
    private final  ExecutionService taskExecutionService; // <-- service to create tasks

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
                final LocalDate date = TaskletUtils.parseIsoDateOrNull(hearingDate);
                if (date != null) {
                    final List<HearingSummariesInfo> summaries =
                            hearingClient.getHearingsAndCases(courtCentreId, roomId, date, cppuid);


                    caseIds = summaries == null
                            ? EMPTY_CASE_IDS
                            : summaries.stream()
                            .map(HearingSummariesInfo::caseId)
                            .collect(Collectors.toList());
                }
            }

            JsonObjectBuilder updatedJobData = Json.createObjectBuilder(jobData);
            if (hasText(cppuid)) {
                updatedJobData.add(USERID_FOR_EXTERNAL_CALLS, cppuid);
            }

            JsonArrayBuilder caseIdsArray = Json.createArrayBuilder();
            caseIds.forEach(caseIdsArray::add);
            updatedJobData.add(CTX_CASE_IDS_KEY, caseIdsArray);

            if (!caseIds.isEmpty()) {

                // ==== LOOP TO CREATE TASK FOR EACH CASE ====
                for (String caseId : caseIds) {
                    JsonObjectBuilder singleCaseJobData = Json.createObjectBuilder(updatedJobData.build());
                    singleCaseJobData.add(PARTITION_CASE_ID, caseId);

                     ExecutionInfo newTask = new ExecutionInfo(
                             singleCaseJobData.build(),
                             "RESOLVE_MATERIAL_FOR_CASE_TASK",
                             ZonedDateTime.now(),
                             ExecutionStatus.STARTED,
                            false
                             );



                    // Persist the new execution (fan-out)
                   taskExecutionService.executeWith(newTask);

                    log.info(
                            "Created RESOLVE_MATERIAL_FOR_CASE_TASK for caseId={} requestId={}",
                            caseId, requestId
                    );
                }

                // Parent task can mark itself completed
                return ExecutionInfo.executionInfo()
                        .from(executionInfo)
                        .withExecutionStatus(ExecutionStatus.COMPLETED)
                        .withJobData(updatedJobData.build())
                        .build();
            }

            // No cases → finish
            log.info(
                    "No cases found. FETCH_HEARINGS_CASES_TASK completed. requestId={}",
                    requestId
            );

            return ExecutionInfo.executionInfo()
                    .from(executionInfo)
                    .withJobData(updatedJobData.build())
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

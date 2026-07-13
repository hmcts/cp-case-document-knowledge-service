package uk.gov.hmcts.cp.cdk.services;

import static java.time.ZonedDateTime.now;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_CASE_ELIGIBILITY;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_SYNCHRONOUS_INVOCATION_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.CPPUID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.REQUEST_ID;
import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;

import uk.gov.hmcts.cp.cdk.jobmanager.caseflow.CheckCaseEligibilityTask;
import uk.gov.hmcts.cp.cdk.jobmanager.caseflow.CheckIdpcAvailabilityAllDefendantsTask;
import uk.gov.hmcts.cp.cdk.jobmanager.support.JobPriority;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessByCaseRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessPhase;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessResponse;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;

import java.util.UUID;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the manual ("Process IDPC" button) ingestion flow.
 *
 * <p>Unlike the scheduled ingestion ({@link JobManagerService}, via {@link IngestionProcessor}) this
 * entry point receives a single {@code caseId} directly, so it skips {@code GET_CASES_FOR_HEARING}
 * and starts from the eligibility check. It runs the eligibility and IDPC-availability checks
 * <b>synchronously</b> because the HTTP response depends on their outcome:
 * <ul>
 *   <li>no newer IDPC available → {@link IngestionProcessPhase#NOT_REQUIRED}, nothing dispatched;</li>
 *   <li>newer IDPC available → the remaining workflow is dispatched via the JobManager at
 *       {@link JobPriority#HIGH} priority and {@link IngestionProcessPhase#STARTED} is returned;</li>
 *   <li>any unexpected error → {@link IngestionProcessPhase#FAILED}.</li>
 * </ul>
 *
 * <p>The scheduled ingestion endpoint is unaffected — it continues to dispatch at the default
 * priority.
 *
 * <p>The eligibility rule is not duplicated here — this service invokes
 * {@link CheckCaseEligibilityTask#execute(ExecutionInfo)} directly, in-process, the same
 * {@code ExecutableTask} contract method the JobManager framework itself calls for the
 * scheduled/async flow. The job data is marked with {@code CTX_SYNCHRONOUS_INVOCATION_KEY} so that
 * task returns the follow-on {@code CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS} {@link ExecutionInfo}
 * instead of dispatching it itself — this service dispatches it via
 * {@link CheckIdpcAvailabilityAllDefendantsTask#registerNewDocumentsAndDispatch(ExecutionInfo)}, so
 * the case is never processed twice.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IngestionProcessorByCaseService implements IngestionProcessorByCase {

    /* default */ static final String MSG_NOT_REQUIRED =
            "Ingestion process not started because no newer IDPC version is available "
                    + "and an Answers version already exists.";
    /* default */ static final String MSG_STARTED =
            "Ingestion workflow request accepted; task submitted via JobManager (requestId=%s).";
    /* default */ static final String MSG_FAILED =
            "Ingestion process could not be started due to an internal error.";

    private final CheckCaseEligibilityTask checkCaseEligibilityTask;
    private final CheckIdpcAvailabilityAllDefendantsTask checkIdpcAvailabilityAllDefendantsTask;

    @Override
    public IngestionProcessResponse startIngestionProcess(final String cppuid,
                                                           final IngestionProcessByCaseRequest req) {
        final UUID caseId = req.getCaseId();
        final String requestId = UUID.randomUUID().toString();

        final IngestionProcessResponse response = new IngestionProcessResponse();
        response.setLastUpdated(utcNow());

        try {
            final JsonObject jobData = Json.createObjectBuilder()
                    .add(CPPUID, cppuid)
                    .add(REQUEST_ID, requestId)
                    .add(CTX_CASE_ID_KEY, caseId.toString())
                    .add(CTX_SYNCHRONOUS_INVOCATION_KEY, true)
                    .build();

            final ExecutionInfo executionInfo = executionInfo()
                    .withJobData(jobData)
                    .withAssignedTaskName(CHECK_CASE_ELIGIBILITY)
                    .withAssignedTaskStartTime(now())
                    .withExecutionStatus(ExecutionStatus.STARTED)
                    .withPriority(JobPriority.HIGH)
                    .build();

            final ExecutionInfo eligibilityResult = checkCaseEligibilityTask.execute(executionInfo);

            if (eligibilityResult.getExecutionStatus() == ExecutionStatus.INPROGRESS) {
                log.error("Manual ingestion could not be started; eligibility check failed. "
                        + "caseId={}, requestId={}", caseId, requestId);
                return failed(response);
            }

            if (!CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS.equals(eligibilityResult.getAssignedTaskName())) {
                log.info("Manual ingestion not required (case not eligible). caseId={}, requestId={}",
                        caseId, requestId);
                return notRequired(response);
            }

            final int newIdpcDocuments =
                    checkIdpcAvailabilityAllDefendantsTask.registerNewDocumentsAndDispatch(eligibilityResult);

            if (newIdpcDocuments > 0) {
                log.info("Manual ingestion started. caseId={}, newIdpcDocuments={}, requestId={}",
                        caseId, newIdpcDocuments, requestId);
                return started(response, requestId);
            }

            log.info("Manual ingestion not required (no newer IDPC version). caseId={}, requestId={}",
                    caseId, requestId);
            return notRequired(response);

        } catch (final Exception exception) {
            log.error("Manual ingestion could not be started due to an internal error. "
                    + "caseId={}, requestId={}", caseId, requestId, exception);
            return failed(response);
        }
    }

    private IngestionProcessResponse started(final IngestionProcessResponse response, final String requestId) {
        response.setPhase(IngestionProcessPhase.STARTED);
        response.setMessage(MSG_STARTED.formatted(requestId));
        return response;
    }

    private IngestionProcessResponse notRequired(final IngestionProcessResponse response) {
        response.setPhase(IngestionProcessPhase.NOT_REQUIRED);
        response.setMessage(MSG_NOT_REQUIRED);
        return response;
    }

    private IngestionProcessResponse failed(final IngestionProcessResponse response) {
        response.setPhase(IngestionProcessPhase.FAILED);
        response.setMessage(MSG_FAILED);
        return response;
    }
}

package uk.gov.hmcts.cp.cdk.services;

import static java.time.ZonedDateTime.now;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.RETRIEVE_MATERIAL_AND_UPLOAD;
import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;

import uk.gov.hmcts.cp.cdk.domain.QueryLifecycleStatus;
import uk.gov.hmcts.cp.cdk.jobmanager.support.JobPriority;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.repo.CaseQueryStatusRepository;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessByCaseRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessPhase;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessResponse;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;

import java.util.List;
import java.util.UUID;

import jakarta.json.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the manual ("Process IDPC" button) ingestion flow.
 *
 * <p>Unlike the scheduled ingestion ({@link JobManagerService}, via {@link IngestionProcessor}) this
 * entry point receives a single {@code caseId} directly, so it skips {@code GET_CASES_FOR_HEARING}
 * and starts from the IDPC-availability check. It runs that check <b>synchronously</b> because the
 * HTTP response depends on its outcome:
 * <ul>
 *   <li>no newer IDPC available (this also covers a case that doesn't exist or has no defendants —
 *       {@link IdpcAvailabilityService} simply finds nothing to ingest either way) <b>and</b> an
 *       answer already exists for the case's latest IDPC document
 *       ({@link CaseDocumentRepository#findLatestDocId} resolves that document, and
 *       {@link CaseQueryStatusRepository#findByCaseIdAndDocId} has an entry against it with
 *       {@link QueryLifecycleStatus#ANSWER_AVAILABLE}) →
 *       {@link IngestionProcessPhase#NOT_REQUIRED}, nothing dispatched;</li>
 *   <li>no newer IDPC available and no answer exists yet for that latest document → nothing new to
 *       dispatch (the existing documents were already sent for ingestion previously), but
 *       {@link IngestionProcessPhase#STARTED} is still returned since the answer generation is
 *       presumed to be in progress;</li>
 *   <li>newer IDPC available → {@code RETRIEVE_MATERIAL_AND_UPLOAD} is dispatched via the
 *       JobManager at {@link JobPriority#HIGH} priority for the newer documents found and
 *       {@link IngestionProcessPhase#STARTED} is returned;</li>
 *   <li>any unexpected error → {@link IngestionProcessPhase#FAILED}.</li>
 * </ul>
 *
 * <p>The scheduled ingestion endpoint is unaffected — it continues to dispatch at the default
 * priority.
 *
 * <p>The IDPC-availability rule is not duplicated here — this service calls
 * {@link IdpcAvailabilityService} directly, in-process, rather than dispatching it as a JobManager
 * job (which would return before its outcome is known). That service is deliberately plain (no
 * JobManager/task-framework types) and is also used by {@code CheckIdpcAvailabilityAllDefendantsTask}
 * for the scheduled/async flow, so the business rule is defined exactly once. The per-document job
 * data is likewise built once, by the shared {@link RetrieveMaterialAndUploadJobDataService} — only the final
 * {@code ExecutionInfo}/{@code executionService.executeWith(...)} dispatch (below) is specific to
 * this entry point.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IngestionProcessorByCaseService implements IngestionProcessorByCase {

    /* default */ static final String MSG_NOT_REQUIRED_NO_NEWER_IDPC =
            "Ingestion process not started because no newer IDPC version is available "
                    + "and an Answers version already exists.";
    /* default */ static final String MSG_STARTED =
            "Ingestion workflow request accepted; task submitted via JobManager (requestId=%s).";
    /* default */ static final String MSG_STARTED_ANSWERS_IN_PROGRESS =
            "No newer IDPC version is available; previous answers are still in the process of generating.";
    /* default */ static final String MSG_FAILED =
            "Ingestion process could not be started due to an internal error.";

    private final IdpcAvailabilityService idpcAvailabilityService;
    private final RetrieveMaterialAndUploadJobDataService retrievalJobDataService;
    private final ExecutionService executionService;
    private final CaseQueryStatusRepository caseQueryStatusRepository;
    private final CaseDocumentRepository caseDocumentRepository;

    @Override
    public IngestionProcessResponse startIngestionProcess(final String cppuid,
                                                           final IngestionProcessByCaseRequest req) {
        final UUID caseId = req.getCaseId();
        final String requestId = UUID.randomUUID().toString();

        final IngestionProcessResponse response = new IngestionProcessResponse();
        response.setLastUpdated(utcNow());

        try {
            final List<NewIdpcDocument> newDocuments =
                    idpcAvailabilityService.retrieveDocuments(caseId, cppuid);

            if (newDocuments.isEmpty()) {
                if (hasAnswerAvailable(caseId)) {
                    log.info("Manual ingestion not required (no newer IDPC version and an answer already exists). "
                            + "caseId={}, requestId={}", caseId, requestId);
                    return notRequired(response, MSG_NOT_REQUIRED_NO_NEWER_IDPC);
                }

                log.info("No newer IDPC version but no answer exists yet; previous ingestion still in progress, "
                        + "nothing new to dispatch. caseId={}, requestId={}", caseId, requestId);
                return started(response, MSG_STARTED_ANSWERS_IN_PROGRESS);
            }

            dispatchRetrievalTasks(cppuid, requestId, caseId, newDocuments);

            log.info("Manual ingestion started. caseId={}, newIdpcDocuments={}, requestId={}",
                    caseId, newDocuments.size(), requestId);
            return started(response, MSG_STARTED.formatted(requestId));

        } catch (final Exception exception) {
            log.error("Manual ingestion could not be started due to an internal error. "
                    + "caseId={}, requestId={}", caseId, requestId, exception);
            return failed(response);
        }
    }

    private boolean hasAnswerAvailable(final UUID caseId) {
        return caseDocumentRepository.findLatestDocId(caseId)
                .map(docId -> caseQueryStatusRepository.findByCaseIdAndDocId(caseId, docId).stream()
                        .anyMatch(status -> status.getStatus() == QueryLifecycleStatus.ANSWER_AVAILABLE))
                .orElse(false);
    }

    private void dispatchRetrievalTasks(final String cppuid,
                                        final String requestId,
                                        final UUID caseId,
                                        final List<NewIdpcDocument> newDocuments) {
        for (final JsonObject jobData : retrievalJobDataService.enrich(cppuid, requestId, caseId, newDocuments)) {
            final ExecutionInfo executionInfo = executionInfo()
                    .withJobData(jobData)
                    .withAssignedTaskName(RETRIEVE_MATERIAL_AND_UPLOAD)
                    .withAssignedTaskStartTime(now())
                    .withExecutionStatus(ExecutionStatus.STARTED)
                    .withPriority(JobPriority.HIGH)
                    .build();

            executionService.executeWith(executionInfo);
        }
    }

    private IngestionProcessResponse started(final IngestionProcessResponse response, final String message) {
        response.setPhase(IngestionProcessPhase.STARTED);
        response.setMessage(message);
        return response;
    }

    private IngestionProcessResponse notRequired(final IngestionProcessResponse response, final String message) {
        response.setPhase(IngestionProcessPhase.NOT_REQUIRED);
        response.setMessage(message);
        return response;
    }

    private IngestionProcessResponse failed(final IngestionProcessResponse response) {
        response.setPhase(IngestionProcessPhase.FAILED);
        response.setMessage(MSG_FAILED);
        return response;
    }
}

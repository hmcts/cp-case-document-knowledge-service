package uk.gov.hmcts.cp.cdk.jobmanager.caseflow;

import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_INGESTION_STATUS_FOR_DOCUMENT;
import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;
import static uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatus.INGESTION_SUCCESS;

import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.openapi.api.DocumentIngestionStatusApi;
import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatusReturnedSuccessfully;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.json.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@Task(CHECK_INGESTION_STATUS_FOR_DOCUMENT)
public class CheckIngestionStatusForDocumentTask implements ExecutableTask {

    private final DocumentIngestionStatusApi documentIngestionStatusApi;
    private final CaseDocumentRepository caseDocumentRepository;

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {

        final JsonObject jobData = executionInfo.getJobData();

        final UUID documentId = parseUuid(jobData.getString("docId", null));
        final String blobName = jobData.getString("blobName", null);

        if (documentId == null || blobName == null) {
            log.error("{} missing required data docId={} blobName={}", CHECK_INGESTION_STATUS_FOR_DOCUMENT, documentId, blobName);
            return complete(executionInfo);
        }

        log.info("Polling ingestion status for identifier='{}', docId={}", blobName, documentId);

        final ResponseEntity<@NotNull DocumentIngestionStatusReturnedSuccessfully> response =
                documentIngestionStatusApi.documentStatus(blobName);

        if (response == null || !response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            log.info("Status not available yet for identifier='{}' → retrying", blobName);
            return retry(executionInfo);
        }

        final DocumentIngestionStatusReturnedSuccessfully body = response.getBody();
        final String rawStatus = body.getStatus() != null ? body.getStatus().getValue() : null;

        if (rawStatus == null) {
            return retry(executionInfo);
        }

        final String status = normalise(rawStatus, 255);

        if (INGESTION_SUCCESS.name().equalsIgnoreCase(status)) {
            updateIngestionPhase(documentId, DocumentIngestionPhase.INGESTED);
            log.info("INGESTION SUCCESS identifier='{}', docId={}", blobName, documentId);
            return complete(executionInfo);
        }

        log.debug("Ingestion status not complete for identifier='{}': {} → retrying", blobName, status);
        return retry(executionInfo);
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        return Optional.of(List.of(5L, 10L, 30L, 60L, 120L));
    }


    private void updateIngestionPhase(final UUID documentId, final DocumentIngestionPhase phase) {
        caseDocumentRepository.findById(documentId).ifPresent(doc -> {
            doc.setIngestionPhase(phase);
            doc.setIngestionPhaseAt(utcNow());
            caseDocumentRepository.saveAndFlush(doc);
        });
    }

    private ExecutionInfo retry(final ExecutionInfo executionInfo) {
        return ExecutionInfo.executionInfo()
                .from(executionInfo)
                .withExecutionStatus(ExecutionStatus.INPROGRESS)
                .withShouldRetry(true)
                .build();
    }

    private ExecutionInfo complete(final ExecutionInfo executionInfo) {
        return ExecutionInfo.executionInfo()
                .from(executionInfo)
                .withExecutionStatus(ExecutionStatus.COMPLETED)
                .build();
    }

    private static UUID parseUuid(final String raw) {
        try {
            return raw != null ? UUID.fromString(raw) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalise(final String value, final int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}

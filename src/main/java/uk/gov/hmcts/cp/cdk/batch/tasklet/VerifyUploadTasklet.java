package uk.gov.hmcts.cp.cdk.batch.tasklet;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.cdk.batch.BatchKeys;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.openapi.api.DocumentIngestionStatusApi;
import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatusReturnedSuccessfully;

import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class VerifyUploadTasklet implements Tasklet {

    private static final String CTX_UPLOAD_VERIFIED = BatchKeys.CTX_UPLOAD_VERIFIED_KEY;
    private static final String CTX_DOCUMENT_STATUS_JSON = BatchKeys.CTX_DOCUMENT_STATUS_JSON_KEY;

    private final DocumentIngestionStatusApi documentIngestionStatusApi;
    private final CaseDocumentRepository caseDocumentRepository;
    private final ObjectMapper objectMapper;

    @Value("${cdk.ingestion.verify.poll-interval-ms:2000}")
    private long pollIntervalMs;

    @Value("${cdk.ingestion.verify.max-wait-ms:60000}")
    private long maxWaitMs;

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final ExecutionContext stepCtx = contribution.getStepExecution().getExecutionContext();
        final String caseIdStr = stepCtx.getString(BatchKeys.CTX_CASE_ID_KEY, null);

        ExitStatus exitStatus = null;

        if (caseIdStr == null) {
            log.warn("VerifyUpload skipped: no caseId in step context.");
        } else {
            final UUID caseId = UUID.fromString(caseIdStr);

            final String documentName = caseDocumentRepository
                    .findFirstByCaseIdOrderByUploadedAtDesc(caseId)
                    .map(doc -> {
                        log.debug("Found document for case {}: {}", caseId, doc.getDocName());
                        return doc.getDocName();
                    })
                    .orElse(null);

            if (documentName == null) {
                log.warn("No document found for case {}, cannot verify upload status", caseId);
                stepCtx.put(CTX_UPLOAD_VERIFIED, false);
                exitStatus = ExitStatus.NOOP;
            } else {
                long start = System.currentTimeMillis();
                boolean success = false;
                String lastReason = null;
                OffsetDateTime lastUpdated = null;
                String lastStatus = null;
                String lastDocId = null;
                String lastDocName = null;

                while (System.currentTimeMillis() - start <= maxWaitMs) {
                    try {
                        log.debug("Polling document status via APIM for document: {}", documentName);
                        final ResponseEntity<DocumentIngestionStatusReturnedSuccessfully> resp =
                                documentIngestionStatusApi.documentStatus(documentName);

                        if (resp == null) {
                            log.warn("Null response from APIM for document: {}", documentName);
                        } else if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                            final DocumentIngestionStatusReturnedSuccessfully body = resp.getBody();

                            final DocumentIngestionStatusReturnedSuccessfully.StatusEnum statusEnum = body.getStatus();
                            final String status = (statusEnum != null) ? statusEnum.getValue() : null;
                            final String reason = body.getReason();
                            final String docId = body.getDocumentId();
                            final String docNm = body.getDocumentName();
                            final OffsetDateTime lu = body.getLastUpdated();

                            lastStatus = status;
                            lastReason = reason;
                            lastUpdated = lu;
                            lastDocId = docId;
                            lastDocName = docNm;

                            if ("INGESTION_SUCCESS".equalsIgnoreCase(status)) {
                                log.info("Document ingestion successful for {}, proceeding.", documentName);
                                // Persist JSON for downstream steps
                                try {
                                    final String responseJson = objectMapper.writeValueAsString(body);
                                    stepCtx.put(CTX_DOCUMENT_STATUS_JSON, responseJson);
                                } catch (Exception e) {
                                    log.warn("Failed to serialize document status response: {}", e.getMessage());
                                }
                                stepCtx.put(CTX_UPLOAD_VERIFIED, true);
                                success = true;
                                break;
                            } else if ("INGESTION_FAILED".equalsIgnoreCase(status)) {
                                log.error("Ingestion FAILED for {} with reason {}", documentName, reason);
                                break;
                            } // else keep polling (e.g., PENDING, IN_PROGRESS)
                        } else if (resp.getStatusCode() == HttpStatus.NOT_FOUND) {
                            log.warn("Document {} not found (404); retrying until timeout.", documentName);
                        } else {
                            log.error("Unexpected response from APIM for {}: {}", documentName, resp.getStatusCode());
                            break;
                        }
                    } catch (Exception e) {
                        log.warn("Error polling document status for {}: {}", documentName, e.getMessage());
                        // continue until timeout
                    }

                    try {
                        Thread.sleep(pollIntervalMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                if (success) {
                    // Optionally expose some fields for debugging/traceability
                    stepCtx.put("documentStatus", lastStatus);
                    if (lastReason != null && !lastReason.isBlank()) {
                        stepCtx.put("documentStatusReason", lastReason);
                    }
                    stepCtx.put("documentStatusTimestamp", lastUpdated);
                    exitStatus = ExitStatus.COMPLETED;
                } else {
                    stepCtx.put(CTX_UPLOAD_VERIFIED, false);
                    if (lastStatus != null) {
                        stepCtx.put("documentStatus", lastStatus);
                    }
                    exitStatus = ExitStatus.NOOP; // not fatal; upstream RetryTemplate can re-drive if desired
                }
            }
        }

        if (exitStatus != null) {
            contribution.setExitStatus(exitStatus);
        }
        return RepeatStatus.FINISHED;
    }
}
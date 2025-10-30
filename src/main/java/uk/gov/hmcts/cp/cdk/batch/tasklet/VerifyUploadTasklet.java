package uk.gov.hmcts.cp.cdk.batch.tasklet;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
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
    private static final String CTX_CASE_ID = BatchKeys.CTX_CASE_ID_KEY;
    private static final String CTX_DOC_ID = BatchKeys.CTX_DOC_ID_KEY;

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

        final Boolean already = (Boolean) stepCtx.get(CTX_UPLOAD_VERIFIED);
        if (Boolean.TRUE.equals(already)) {
            if (!stepCtx.containsKey(CTX_DOCUMENT_STATUS_JSON)) {
                stepCtx.putString(CTX_DOCUMENT_STATUS_JSON, "{}");
            }
            log.info("VerifyUploadTasklet: already verified for this partition; skipping poll.");
            return RepeatStatus.FINISHED; // keeps step COMPLETED
        }

        final String caseIdStr = stepCtx.getString(CTX_CASE_ID, null);
        final String docIdFromCtx = stepCtx.getString(CTX_DOC_ID, null);

        String documentName = null;
        if (caseIdStr != null) {
            try {
                final UUID caseId = UUID.fromString(caseIdStr);
                documentName = caseDocumentRepository
                        .findFirstByCaseIdOrderByUploadedAtDesc(caseId)
                        .map(doc -> {
                            log.debug("Found document via repo for case {}: {}", caseId, doc.getDocName());
                            return doc.getDocName();
                        })
                        .orElse(null);
            } catch (Exception e) {
                log.warn("Repo lookup failed for caseId={} : {}", caseIdStr, e.getMessage());
            }
        }

        final String identifierToPoll = documentName != null ? documentName : docIdFromCtx;

        if (identifierToPoll == null) {
            log.warn("VerifyUpload skipped: neither repo documentName nor context docId available (caseId={})", caseIdStr);
            stepCtx.put(CTX_UPLOAD_VERIFIED, false);
            stepCtx.putString(CTX_DOCUMENT_STATUS_JSON, "{}");
            return RepeatStatus.FINISHED;
        }

        long start = System.currentTimeMillis();
        boolean success = false;
        String lastStatus = null;
        String lastReason = null;
        OffsetDateTime lastUpdated = null;

        while (System.currentTimeMillis() - start <= maxWaitMs) {
            try {
                log.debug("Polling ingestion status for identifier={} (caseId={})", identifierToPoll, caseIdStr);
                final ResponseEntity<DocumentIngestionStatusReturnedSuccessfully> resp =
                        documentIngestionStatusApi.documentStatus(identifierToPoll);

                if (resp == null) {
                    log.warn("Null response while polling identifier={}", identifierToPoll);
                } else if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                    final var body = resp.getBody();
                    final var statusEnum = body.getStatus();
                    final String status = statusEnum != null ? statusEnum.getValue() : null;

                    lastStatus  = status;
                    lastReason  = body.getReason();
                    lastUpdated = body.getLastUpdated();

                    if ("INGESTION_SUCCESS".equalsIgnoreCase(status)) {
                        try {
                            stepCtx.put(CTX_DOCUMENT_STATUS_JSON, objectMapper.writeValueAsString(body));
                        } catch (Exception e) {
                            log.warn("Failed to serialize status JSON: {}", e.getMessage());
                            stepCtx.putString(CTX_DOCUMENT_STATUS_JSON, "{}");
                        }
                        stepCtx.put(CTX_UPLOAD_VERIFIED, true);
                        log.info("Document ingestion successful for identifier={} (caseId={})", identifierToPoll, caseIdStr);
                        success = true;
                        break;
                    } else if ("INGESTION_FAILED".equalsIgnoreCase(status)) {
                        log.error("Document ingestion FAILED for identifier={} reason={}", identifierToPoll, lastReason);
                        try {
                            stepCtx.put(CTX_DOCUMENT_STATUS_JSON, objectMapper.writeValueAsString(body));
                        } catch (Exception e) {
                            stepCtx.putString(CTX_DOCUMENT_STATUS_JSON, "{}");
                        }
                        break;
                    }
                } else if (resp.getStatusCode() == HttpStatus.NOT_FOUND) {
                    log.debug("identifier={} not found yet (404); will retry until timeout.", identifierToPoll);
                } else {
                    log.warn("Unexpected HTTP {} while polling identifier={}", resp.getStatusCode(), identifierToPoll);
                    break; // don’t fail the worker
                }
            } catch (Exception e) {
                log.warn("Error polling identifier={} : {}", identifierToPoll, e.getMessage());
            }

            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (!success) {
            stepCtx.put(CTX_UPLOAD_VERIFIED, false);
            if (!stepCtx.containsKey(CTX_DOCUMENT_STATUS_JSON)) {
                stepCtx.putString(CTX_DOCUMENT_STATUS_JSON, "{}");
            }
            if (lastStatus != null) {
                stepCtx.put("documentStatus", lastStatus);
            }
            if (lastReason != null && !lastReason.isBlank()) {
                stepCtx.put("documentStatusReason", lastReason);
            }
            if (lastUpdated != null) {
                stepCtx.put("documentStatusTimestamp", lastUpdated);
            }
            log.info("VerifyUploadTasklet finished without success for identifier={} (caseId={}). verified=false; continuing.",
                    identifierToPoll, caseIdStr);
        }
        return RepeatStatus.FINISHED;
    }
}

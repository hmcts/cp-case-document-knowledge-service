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
                try {
                    log.debug("Checking document status via APIM for document: {}", documentName);
                    final ResponseEntity<DocumentIngestionStatusReturnedSuccessfully> resp =
                            documentIngestionStatusApi.documentStatus(documentName);

                    if (resp == null) {
                        log.warn("Null response from APIM for document: {}", documentName);
                        stepCtx.put(CTX_UPLOAD_VERIFIED, false);
                        exitStatus = ExitStatus.NOOP;
                    } else if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                        final DocumentIngestionStatusReturnedSuccessfully body = resp.getBody();

                        final DocumentIngestionStatusReturnedSuccessfully.StatusEnum statusEnum = body.getStatus();
                        final String status = (statusEnum != null) ? statusEnum.getValue() : null;
                        final String reason = body.getReason();
                        final String docId = body.getDocumentId();
                        final String docNm = body.getDocumentName();
                        final OffsetDateTime lastUpdated = body.getLastUpdated();

                        log.info("Document status verified successfully for document: {}", documentName);
                        log.info("Document Status Response - documentId: {}, documentName: {}, status: {}, reason: {}, lastUpdated: {}",
                                docId, docNm, status, reason, lastUpdated);

                        stepCtx.put(CTX_UPLOAD_VERIFIED, true);

                        try {
                            final String responseJson = objectMapper.writeValueAsString(body);
                            stepCtx.put(CTX_DOCUMENT_STATUS_JSON, responseJson);
                            log.debug("Stored document status response in execution context as JSON");
                        } catch (Exception e) {
                            log.warn("Failed to serialize document status response to JSON: {}", e.getMessage());
                        }

                        stepCtx.put("documentStatus", status);
                        stepCtx.put("documentStatusTimestamp", lastUpdated);
                        if (reason != null && !reason.isBlank()) {
                            stepCtx.put("documentStatusReason", reason);
                        }

                        exitStatus = ExitStatus.COMPLETED;
                    } else if (resp.getStatusCode() == HttpStatus.NOT_FOUND) {
                        log.warn("Document {} not found in Azure Table Storage (404 response)", documentName);
                        stepCtx.put(CTX_UPLOAD_VERIFIED, false);
                        exitStatus = ExitStatus.NOOP;
                    } else {
                        log.error("Unexpected response from APIM for {}: {}", documentName, resp.getStatusCode());
                        stepCtx.put(CTX_UPLOAD_VERIFIED, false);
                        stepCtx.put("documentStatusError", "Unexpected status: " + resp.getStatusCode());
                        exitStatus = ExitStatus.FAILED;
                    }
                } catch (Exception e) {
                    log.error("Error checking document status for document {}: {}", documentName, e.getMessage(), e);
                    stepCtx.put(CTX_UPLOAD_VERIFIED, false);
                    stepCtx.put("documentStatusError", e.getMessage());
                    exitStatus = ExitStatus.FAILED;
                }
            }
        }

        if (exitStatus != null) {
            contribution.setExitStatus(exitStatus);
        }
        return RepeatStatus.FINISHED;
    }
}

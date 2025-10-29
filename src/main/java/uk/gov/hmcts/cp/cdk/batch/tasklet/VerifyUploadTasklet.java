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
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.cdk.batch.BatchKeys;
import uk.gov.hmcts.cp.cdk.clients.documentstatus.DocumentIngestionStatusApi;
import uk.gov.hmcts.cp.cdk.clients.documentstatus.DocumentStatusResponse;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class VerifyUploadTasklet implements Tasklet {

    private static final String CTX_UPLOAD_VERIFIED = BatchKeys.CTX_UPLOAD_VERIFIED;
    private static final String CTX_DOCUMENT_STATUS_JSON = BatchKeys.CTX_DOCUMENT_STATUS_JSON;

    private final DocumentIngestionStatusApi documentIngestionStatusApi;
    private final CaseDocumentRepository caseDocumentRepository;
    private final RetryTemplate storageCheckRetryTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final ExecutionContext stepCtx = contribution.getStepExecution().getExecutionContext();
        final String caseIdStr = stepCtx.getString("caseId", null);

        if (caseIdStr == null) {
            log.warn("VerifyUpload skipped: no caseId in step context.");
            return RepeatStatus.FINISHED;
        }

        final UUID caseId = UUID.fromString(caseIdStr);

        // Get the latest document for this case to retrieve the document name
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
            contribution.setExitStatus(ExitStatus.NOOP);
            return RepeatStatus.FINISHED;
        }

        // Call the APIM endpoint via DocumentStatusClient with retry
        try {
            final Optional<DocumentStatusResponse> responseOptional = storageCheckRetryTemplate.execute(context -> {
                log.debug("Checking document status via APIM for document: {}", documentName);
                return documentIngestionStatusApi.checkDocumentStatus(documentName);
            });

            if (responseOptional.isPresent()) {
                final DocumentStatusResponse response = responseOptional.get();
                
                // Log the complete response details
                log.info("Document status verified successfully for document: {}", documentName);
                log.info("Document Status Response - documentId: {}, documentName: {}, status: {}, reason: {}, timestamp: {}",
                        response.documentId(), response.documentName(), response.status(),
                        response.reason(), response.timestamp());

                // Store response in execution context for next step usage
                stepCtx.put(CTX_UPLOAD_VERIFIED, true);
                
                // Store as JSON string (ExecutionContext requires Serializable objects)
                try {
                    final String responseJson = objectMapper.writeValueAsString(response);
                    stepCtx.put(CTX_DOCUMENT_STATUS_JSON, responseJson);
                    log.debug("Stored document status response in execution context as JSON");
                } catch (Exception e) {
                    log.warn("Failed to serialize document status response to JSON: {}", e.getMessage());
                }

                // Store individual fields for easy access in next steps
                stepCtx.put("documentStatus", response.status());
                stepCtx.put("documentStatusTimestamp", response.timestamp());
                if (response.reason() != null && !response.reason().isBlank()) {
                    stepCtx.put("documentStatusReason", response.reason());
                }

                contribution.setExitStatus(ExitStatus.COMPLETED);
                return RepeatStatus.FINISHED;
            } else {
                // Document not found in Azure Table Storage
                log.warn("Document {} not found in Azure Table Storage (404 response)", documentName);
                stepCtx.put(CTX_UPLOAD_VERIFIED, false);
                contribution.setExitStatus(ExitStatus.NOOP);
                return RepeatStatus.FINISHED;
            }
        } catch (Exception e) {
            // Handle any errors during the status check
            log.error("Error checking document status for document {}: {}", documentName, e.getMessage(), e);
            stepCtx.put(CTX_UPLOAD_VERIFIED, false);
            stepCtx.put("documentStatusError", e.getMessage());
            contribution.setExitStatus(ExitStatus.FAILED);
            return RepeatStatus.FINISHED;
        }
    }
}

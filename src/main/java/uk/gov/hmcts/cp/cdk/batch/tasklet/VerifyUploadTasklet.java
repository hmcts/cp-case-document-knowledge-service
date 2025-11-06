package uk.gov.hmcts.cp.cdk.batch.tasklet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.AlwaysRetryPolicy;
import org.springframework.retry.policy.CompositeRetryPolicy;
import org.springframework.retry.policy.TimeoutRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.cdk.batch.BatchKeys;
import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.util.TimeUtils;
import uk.gov.hmcts.cp.openapi.api.DocumentIngestionStatusApi;
import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatusReturnedSuccessfully;
import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatusReturnedSuccessfully.StatusEnum;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class VerifyUploadTasklet implements Tasklet {

    private static final String CTX_UPLOAD_VERIFIED = BatchKeys.CTX_UPLOAD_VERIFIED_KEY;
    private static final String CTX_DOCUMENT_STATUS_JSON = BatchKeys.CTX_DOCUMENT_STATUS_JSON_KEY;
    private static final String CTX_CASE_ID = BatchKeys.CTX_CASE_ID_KEY;
    private static final String CTX_DOC_ID = BatchKeys.CTX_DOC_ID_KEY;

    private static final String CTX_DOCUMENT_STATUS = "documentStatus";
    private static final String CTX_DOCUMENT_STATUS_REASON = "documentStatusReason";
    private static final String CTX_DOCUMENT_STATUS_TS = "documentStatusTimestamp";

    private final DocumentIngestionStatusApi documentIngestionStatusApi;
    private final CaseDocumentRepository caseDocumentRepository;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager txManager; // NEW: short, explicit DB tx

    @Value("${cdk.ingestion.verify.poll-interval-ms:2000}")
    private long pollIntervalMs;

    @Value("${cdk.ingestion.verify.max-wait-ms:300000}")
    private long maxWaitMs;

    @Override
    @SuppressWarnings({ "PMD.OnlyOneReturn", "PMD.CyclomaticComplexity" })
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {

        final ExecutionContext stepCtx = contribution.getStepExecution().getExecutionContext();
        final JobExecution jobExecution = contribution.getStepExecution().getJobExecution();

        final Boolean alreadyVerified = (Boolean) stepCtx.get(CTX_UPLOAD_VERIFIED);
        if (Boolean.TRUE.equals(alreadyVerified)) {
            ensureStatusJson(stepCtx);
            log.info("VerifyUploadTasklet: already verified for this partition; skipping poll.");
            return RepeatStatus.FINISHED;
        }

        final String caseIdStr = stepCtx.getString(CTX_CASE_ID, null);
        final String docIdRaw = stepCtx.getString(CTX_DOC_ID, null);
        if (docIdRaw == null) {
            log.warn("VerifyUploadTasklet: CTX_DOC_ID missing; cannot poll ingestion status (caseId={}).", caseIdStr);
            markUnverified(stepCtx, null, null, null);
            return RepeatStatus.FINISHED;
        }

        final UUID docId;
        try {
            docId = UUID.fromString(docIdRaw);
        } catch (IllegalArgumentException ex) {
            log.warn("VerifyUploadTasklet: invalid CTX_DOC_ID='{}' (caseId={}) – {}", docIdRaw, caseIdStr, ex.getMessage());
            markUnverified(stepCtx, null, null, null);
            return RepeatStatus.FINISHED;
        }

        final Optional<String> documentNameOpt = caseDocumentRepository.findById(docId)
                .map(doc -> {
                    log.debug("VerifyUploadTasklet: resolved docId={} to blobName='{}' (caseId={}).",
                            docId, doc.getDocName(), caseIdStr);
                    return doc.getDocName();
                });

        if (documentNameOpt.isEmpty() || documentNameOpt.get() == null || documentNameOpt.get().isBlank()) {
            log.warn("VerifyUploadTasklet: document not found or name blank for docId={} (caseId={}); skipping.", docId, caseIdStr);
            markUnverified(stepCtx, null, null, null);
            return RepeatStatus.FINISHED;
        }

        final String documentName = documentNameOpt.get();

        final RetryTemplate pollTemplate = buildPollTemplate(pollIntervalMs, maxWaitMs);
        final AtomicReference<String> lastStatusRef = new AtomicReference<>(null);
        final AtomicReference<String> lastReasonRef = new AtomicReference<>(null);
        final AtomicReference<OffsetDateTime> lastUpdatedRef = new AtomicReference<>(null);

        final Boolean finished = pollTemplate.execute(context -> {
            log.debug("VerifyUploadTasklet: polling ingestion status for identifier='{}' (caseId={}).",
                    documentName, caseIdStr);

            final ResponseEntity<DocumentIngestionStatusReturnedSuccessfully> resp =
                    documentIngestionStatusApi.documentStatus(documentName);

            if (resp == null) {
                throw new IllegalStateException("Null HTTP response");
            }

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                final DocumentIngestionStatusReturnedSuccessfully body = resp.getBody();
                final String status = body.getStatus() != null ? body.getStatus().getValue() : null;
                lastStatusRef.set(status);
                lastReasonRef.set(body.getReason());
                lastUpdatedRef.set(body.getLastUpdated());

                if (status != null
                        && !StatusEnum.INGESTION_SUCCESS.name().equalsIgnoreCase(status)
                        && !StatusEnum.INGESTION_FAILED.name().equalsIgnoreCase(status)
                        && !StatusEnum.INVALID_METADATA.name().equalsIgnoreCase(status)) {
                    updateIngestionPhaseTx(docId, DocumentIngestionPhase.INGESTING);
                }

                if (StatusEnum.INGESTION_SUCCESS.name().equalsIgnoreCase(status)) {
                    putStatusJson(stepCtx, body);
                    updateIngestionPhaseTx(docId, DocumentIngestionPhase.INGESTED);
                    final String verifiedKey = CTX_UPLOAD_VERIFIED + ":" + docId;
                    if (jobExecution != null) {
                        jobExecution.getExecutionContext().put(verifiedKey, true);
                    }
                    stepCtx.put(CTX_UPLOAD_VERIFIED, true);
                    log.info("VerifyUploadTasklet: ingestion SUCCESS for identifier='{}' (caseId={}).",
                            documentName, caseIdStr);
                    return Boolean.TRUE; // stop retry loop (success)
                }

                if (StatusEnum.INGESTION_FAILED.name().equalsIgnoreCase(status)
                        || StatusEnum.INVALID_METADATA.name().equalsIgnoreCase(status)) {
                    putStatusJson(stepCtx, body);
                    updateIngestionPhaseTx(docId, DocumentIngestionPhase.FAILED);
                    log.error("VerifyUploadTasklet: ingestion FAILED for identifier='{}' reason='{}' (caseId={}).",
                            documentName, lastReasonRef.get(), caseIdStr);
                    return Boolean.TRUE; // stop retry loop (terminal)
                }

                throw new IllegalStateException("Not ready: status=" + status);
            }

            if (resp.getStatusCode() == HttpStatus.NOT_FOUND) {
                log.debug("VerifyUploadTasklet: identifier='{}' not found yet (404); will retry.", documentName);
                throw new IllegalStateException("404 Not Found, will retry");
            }

            log.warn("VerifyUploadTasklet: unexpected HTTP status {} for identifier='{}'. Stopping.",
                    resp.getStatusCode(), documentName);
            return Boolean.TRUE;

        }, context -> {
            return Boolean.FALSE;
        });

        if (!Boolean.TRUE.equals(finished)) {
            markUnverified(stepCtx, lastStatusRef.get(), lastReasonRef.get(), lastUpdatedRef.get());
            log.info("VerifyUploadTasklet: finished without success for identifier='{}' (caseId={}). verified=false; continuing.",
                    documentName, caseIdStr);
        }

        return RepeatStatus.FINISHED;
    }

    private RetryTemplate buildPollTemplate(final long intervalMs, final long timeoutMs) {
        final TimeoutRetryPolicy timeout = new TimeoutRetryPolicy();
        timeout.setTimeout(timeoutMs);

        final AlwaysRetryPolicy always = new AlwaysRetryPolicy();

        final CompositeRetryPolicy composite = new CompositeRetryPolicy();
        composite.setPolicies(new org.springframework.retry.RetryPolicy[]{ timeout, always });

        final FixedBackOffPolicy backoff = new FixedBackOffPolicy();
        backoff.setBackOffPeriod(intervalMs);

        final RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(composite);
        template.setBackOffPolicy(backoff);
        return template;
    }

    private void updateIngestionPhaseTx(final UUID docId, final DocumentIngestionPhase phase) {

        final TransactionTemplate transactionTemplate = new TransactionTemplate(txManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactionTemplate.setTimeout(5); // seconds
        try {
            transactionTemplate.execute(status -> {
                caseDocumentRepository.findById(docId).ifPresent(doc -> {
                    doc.setIngestionPhase(phase);
                    doc.setIngestionPhaseAt(nowUtc());
                    caseDocumentRepository.saveAndFlush(doc);
                    log.info("VerifyUploadTasklet: persisted ingestion phase {} for docId={}.", phase, docId);
                });
                return null;
            });
        } catch (Exception ex) {
            log.warn("VerifyUploadTasklet: failed to persist ingestion phase {} for docId={}: {}",
                    phase, docId, ex.getMessage());
        }
    }

    private void putStatusJson(final ExecutionContext ctx, final DocumentIngestionStatusReturnedSuccessfully body) {
        try {
            ctx.put(CTX_DOCUMENT_STATUS_JSON, objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException ex) {
            log.warn("VerifyUploadTasklet: failed to serialize status JSON: {}", ex.getMessage());
            ctx.putString(CTX_DOCUMENT_STATUS_JSON, "{}");
        }
    }

    private void ensureStatusJson(final ExecutionContext ctx) {
        if (!ctx.containsKey(CTX_DOCUMENT_STATUS_JSON)) {
            ctx.putString(CTX_DOCUMENT_STATUS_JSON, "{}");
        }
    }

    private void markUnverified(final ExecutionContext ctx,
                                final String lastStatus,
                                final String lastReason,
                                final OffsetDateTime lastUpdated) {
        ctx.put(CTX_UPLOAD_VERIFIED, false);
        ensureStatusJson(ctx);
        if (lastStatus != null) {
            ctx.put(CTX_DOCUMENT_STATUS, lastStatus);
        }
        if (lastReason != null && !lastReason.isBlank()) {
            ctx.put(CTX_DOCUMENT_STATUS_REASON, lastReason);
        }
        if (lastUpdated != null) {
            ctx.put(CTX_DOCUMENT_STATUS_TS, lastUpdated);
        }
    }
    private OffsetDateTime nowUtc() {
        return TimeUtils.utcNow();
    }
}

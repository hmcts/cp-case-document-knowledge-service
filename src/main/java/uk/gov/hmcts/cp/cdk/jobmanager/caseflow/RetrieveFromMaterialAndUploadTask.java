package uk.gov.hmcts.cp.cdk.jobmanager.caseflow;

import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.*;
import static uk.gov.hmcts.cp.cdk.batch.support.TaskletUtils.parseUuidOrNull;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_INGESTION_STATUS_FOR_DOCUMENT;
import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;

import uk.gov.hmcts.cp.cdk.batch.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.batch.storage.StorageService;
import uk.gov.hmcts.cp.cdk.batch.storage.UploadProperties;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;

import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.json.Json;
import jakarta.json.JsonObject;

import jakarta.json.JsonObjectBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.RETRIEVE_FROM_MATERIAL;

@Slf4j
@Component
@RequiredArgsConstructor
@Task(RETRIEVE_FROM_MATERIAL)
public class RetrieveFromMaterialAndUploadTask implements ExecutableTask {

    private static final String META_CASE_ID = "case_id";
    private static final String META_MATERIAL_ID = "material_id";
    private static final String META_MATERIAL_NAME = "material_name";
    private static final String META_UPLOADED_AT = "uploaded_at";
    private static final String META_DOCUMENT_ID = "document_id";
    private static final String META_METADATA = "metadata";

    private static final long UNKNOWN_SIZE_BYTES = -1L;

    private final ObjectMapper objectMapper;
    private final ProgressionClient progressionClient;
    private final StorageService storageService;
    private final CaseDocumentRepository caseDocumentRepository;
    private final UploadProperties uploadProperties;
    private final ExecutionService taskExecutionService;

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {

        final JsonObject jobData = executionInfo.getJobData();
        final String requestId = jobData.getString("requestId", "unknown");

        boolean proceed = true;

        final String userIdForExternalCalls = jobData.getString(USERID_FOR_EXTERNAL_CALLS, null);
        if (userIdForExternalCalls == null || userIdForExternalCalls.isBlank()) {
            log.warn("Missing user id for external calls (key={}); requestId={}", USERID_FOR_EXTERNAL_CALLS, requestId);
            proceed = false;
        }

        final UUID materialId = readUuid(jobData, CTX_MATERIAL_ID_KEY, "materialId", requestId);
        final UUID caseId = readUuid(jobData, CTX_CASE_ID_KEY, "caseId", requestId);
        final UUID documentId = readUuid(jobData, CTX_DOC_ID_KEY, "docId", requestId);
        final String materialName = jobData.getString(CTX_MATERIAL_NAME, "");

        if (materialId == null || caseId == null || documentId == null) {
            proceed = false;
        }

        try {
            if (proceed) {
                final String downloadUrl = fetchDownloadUrl(materialId, userIdForExternalCalls, requestId);
                log.info("downloadUrl generated :{} ", downloadUrl);
                final DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern(uploadProperties.datePattern());
                final String today = utcNow().format(dateFmt);
                final String blobName = documentId + "_" + today + uploadProperties.fileExtension();

                final Map<String, String> metadata = createBlobMetadata(documentId, materialId, caseId.toString(), today, materialName);

                final String blobUrl = storageService.copyFromUrl(downloadUrl, blobName, uploadProperties.contentType(), metadata);

                final long sizeBytes = Optional.ofNullable(storageService.getBlobSize(blobName)).orElse(UNKNOWN_SIZE_BYTES);

                final CaseDocument entity = new CaseDocument();
                entity.setDocId(documentId);
                entity.setCaseId(caseId);
                entity.setMaterialId(materialId);
                entity.setDocName(blobName);
                entity.setBlobUri(blobUrl);
                entity.setContentType(uploadProperties.contentType());
                entity.setSizeBytes(sizeBytes);
                entity.setUploadedAt(utcNow());
                entity.setIngestionPhase(DocumentIngestionPhase.UPLOADED);
                entity.setIngestionPhaseAt(utcNow());

                caseDocumentRepository.saveAndFlush(entity);

                log.info("Saved CaseDocument docId={}, caseId={}, materialId={}, sizeBytes={}, blobUri={}, requestId={}",
                        documentId, caseId, materialId, sizeBytes, blobUrl, requestId);

                JsonObjectBuilder updatedJobData = Json.createObjectBuilder(jobData);

                updatedJobData.add(CTX_DOC_ID_KEY, documentId.toString());
                updatedJobData.add(CTX_BLOB_NAME_KEY, blobName);

                ExecutionInfo newTask = ExecutionInfo.executionInfo()
                        .from(executionInfo)
                        .withAssignedTaskName(CHECK_INGESTION_STATUS_FOR_DOCUMENT)
                        .withJobData(updatedJobData.build())
                        .withExecutionStatus(ExecutionStatus.STARTED)
                        .build();

                taskExecutionService.executeWith(newTask);
            }

            return ExecutionInfo.executionInfo()
                    .from(executionInfo)
                    .withExecutionStatus(ExecutionStatus.COMPLETED)
                    .build();

        } catch (Exception ex) {
            log.error("UPLOAD_AND_PERSIST_TASK failed. requestId={}", requestId, ex);

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

    private UUID readUuid(final JsonObject jobData, final String key, final String label, final String requestId) {
        final String raw = jobData.getString(key, null);
        final UUID parsed = parseUuidOrNull(raw);

        if (raw == null) {
            log.warn("Missing required key '{}' in jobData; requestId={}", key, requestId);
        } else if (parsed == null) {
            log.warn("Invalid UUID for {} (key='{}'): '{}'; requestId={}", label, key, raw, requestId);
        }
        return parsed;
    }

    private String fetchDownloadUrl(final UUID materialId, final String userId, final String requestId) {
        return progressionClient.getMaterialDownloadUrl(materialId, userId)
                .filter(url -> !url.isBlank())
                .orElseThrow(() ->
                        new IllegalStateException("Empty download URL from Progression for materialId=" + materialId + "; requestId=" + requestId)
                );
    }

    private Map<String, String> createBlobMetadata(final UUID documentId, final UUID materialId, final String caseId,
                                                   final String uploadedDate, final String materialName) {
        try {
            final Map<String, Object> metadataJson = Map.of(
                    META_CASE_ID, caseId,
                    META_MATERIAL_ID, materialId.toString(),
                    META_MATERIAL_NAME, materialName,
                    META_UPLOADED_AT, uploadedDate
            );

            return Map.of(
                    META_DOCUMENT_ID, documentId.toString(),
                    META_METADATA, objectMapper.writeValueAsString(metadataJson)
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create blob metadata", e);
        }
    }
}

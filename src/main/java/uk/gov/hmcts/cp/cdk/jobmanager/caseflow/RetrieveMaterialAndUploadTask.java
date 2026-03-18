package uk.gov.hmcts.cp.cdk.jobmanager.caseflow;

import static io.micrometer.common.util.StringUtils.isBlank;
import static jakarta.json.Json.createObjectBuilder;
import static java.time.format.DateTimeFormatter.ofPattern;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_DOCUMENT_INGESTION_STATUS;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.RETRIEVE_MATERIAL_AND_UPLOAD;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.BlobMetadataKeys.META_CASE_ID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.BlobMetadataKeys.META_DEFENDANT_ID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.BlobMetadataKeys.META_MATERIAL_ID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.BlobMetadataKeys.META_UPLOADED_AT;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_BLOB_NAME_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DEFENDANT_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOC_REFERENCE_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_MATERIAL_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_MATERIAL_NAME;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.CPPUID;
import static uk.gov.hmcts.cp.cdk.util.TaskUtils.parseUuidOrNull;
import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;

import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.jobmanager.JobManagerRetryProperties;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.storage.DocumentBlobMetadata;
import uk.gov.hmcts.cp.cdk.storage.StorageService;
import uk.gov.hmcts.cp.cdk.storage.UploadProperties;
import uk.gov.hmcts.cp.openapi.api.DocumentIngestionInitiationApi;
import uk.gov.hmcts.cp.openapi.model.DocumentUploadRequest;
import uk.gov.hmcts.cp.openapi.model.FileStorageLocationReturnedSuccessfully;
import uk.gov.hmcts.cp.openapi.model.MetadataFilter;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@Task(RETRIEVE_MATERIAL_AND_UPLOAD)
public class RetrieveMaterialAndUploadTask implements ExecutableTask {

    private static final String UNKNOWN_BLOB_URL = "";
    private static final String UNKNOWN_BLOB_NAME = "";
    private static final long UNKNOWN_SIZE_BYTES = -1L;

    private final ObjectMapper objectMapper;
    private final ProgressionClient progressionClient;
    private final StorageService storageService;
    private final CaseDocumentRepository caseDocumentRepository;
    private final UploadProperties uploadProperties;
    private final JobManagerRetryProperties retryProperties;
    private final ExecutionService executionService;
    private final DocumentIngestionInitiationApi documentIngestionInitiationApi;

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {

        final JsonObject jobData = executionInfo.getJobData();
        final String requestId = jobData.getString("requestId", "unknown");
        final String userIdForExternalCalls = jobData.getString(CPPUID, null);

        if (isBlank(userIdForExternalCalls)) {
            log.warn("Missing '{}' in jobData; downstream call may fail, Hence closing currentTask{} requestId={}",
                    CPPUID, RETRIEVE_MATERIAL_AND_UPLOAD, requestId);

            return executionInfo()
                    .from(executionInfo)
                    .withExecutionStatus(ExecutionStatus.COMPLETED)
                    .build();
        }

        final UUID documentId = readUuid(jobData, CTX_DOC_ID_KEY, "docId", requestId);
        final UUID caseId = readUuid(jobData, CTX_CASE_ID_KEY, "caseId", requestId);
        final UUID defendantId = readUuid(jobData, CTX_DEFENDANT_ID_KEY, "defendantId", requestId);
        final UUID materialId = readUuid(jobData, CTX_MATERIAL_ID_KEY, "materialId", requestId);
        final String materialName = jobData.getString(CTX_MATERIAL_NAME, "");

        if (isNull(materialId) || isNull(caseId) || isNull(documentId)) {
            log.warn(
                    "Missing '{}' , {}, {} in jobData; downstream call may fail, Hence closing currentTask{} requestId={}",
                    CTX_MATERIAL_ID_KEY, CTX_CASE_ID_KEY, CTX_DOC_ID_KEY, RETRIEVE_MATERIAL_AND_UPLOAD, requestId
            );
            return executionInfo()
                    .from(executionInfo)
                    .withExecutionStatus(ExecutionStatus.COMPLETED)
                    .build();
        }

        try {

            final String downloadUrl = fetchDownloadUrl(materialId, userIdForExternalCalls, requestId);
            log.info("downloadUrl generated :{} ", downloadUrl);

            //materialName passed as documentName (friendly)
            final String today = utcNow().format(ofPattern(uploadProperties.datePattern()));
            final List<MetadataFilter> documentMetadata = createUploadMetadata(caseId, defendantId, materialId, today);
            final FileStorageLocationReturnedSuccessfully fileStorageLocation = initiateDocumentUpload(documentId, materialName, documentMetadata);

            final DocumentBlobMetadata documentBlobMetadata = storageService.copyFromUrl(downloadUrl, fileStorageLocation.getStorageUrl());
            final String blobUrl = nonNull(documentBlobMetadata) ? documentBlobMetadata.blobUrl() : UNKNOWN_BLOB_URL;
            final String blobName = nonNull(documentBlobMetadata) ? documentBlobMetadata.blobName() : UNKNOWN_BLOB_NAME;
            final long sizeBytes = nonNull(documentBlobMetadata) ? documentBlobMetadata.blobSize() : UNKNOWN_SIZE_BYTES;

            caseDocumentRepository.findById(documentId).ifPresent(doc -> {
                doc.setDocName(blobName);
                doc.setBlobUri(blobUrl);
                doc.setContentType(uploadProperties.contentType());
                doc.setSizeBytes(sizeBytes);
                doc.setUploadedAt(utcNow());
                doc.setIngestionPhase(DocumentIngestionPhase.UPLOADED);
                doc.setIngestionPhaseAt(utcNow());
                caseDocumentRepository.saveAndFlush(doc);
            });

            log.info("Saved CaseDocument docId={}, caseId={}, materialId={}, sizeBytes={}, blobUri={}, requestId={}",
                    documentId, caseId, materialId, sizeBytes, blobUrl, requestId);

            final JsonObjectBuilder updatedJobData = createObjectBuilder(jobData);

            updatedJobData.add(CTX_DOC_ID_KEY, documentId.toString());
            updatedJobData.add(CTX_DOC_REFERENCE_KEY, fileStorageLocation.getDocumentReference());
            updatedJobData.add(CTX_BLOB_NAME_KEY, blobName);

            final ExecutionInfo executionInfoNew = executionInfo()
                    .from(executionInfo)
                    .withAssignedTaskName(CHECK_DOCUMENT_INGESTION_STATUS)
                    .withJobData(updatedJobData.build())
                    .withExecutionStatus(ExecutionStatus.STARTED)
                    .build();

            executionService.executeWith(executionInfoNew);

            return executionInfo()
                    .from(executionInfo)
                    .withExecutionStatus(ExecutionStatus.COMPLETED)
                    .build();

        } catch (Exception ex) {
            log.error("{} failed. requestId={}", RETRIEVE_MATERIAL_AND_UPLOAD, requestId, ex);

            return executionInfo()
                    .from(executionInfo)
                    .withExecutionStatus(ExecutionStatus.INPROGRESS)
                    .withShouldRetry(true)
                    .build();
        }
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        var retry = retryProperties.getDefaultRetry();
        return Optional.of(
                IntStream.range(0, retry.getMaxAttempts())
                        .mapToLong(i -> retry.getDelaySeconds())
                        .boxed()
                        .toList()
        );
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

    private List<MetadataFilter> createUploadMetadata(final UUID caseId, final UUID defendantId, final UUID materialId, final String uploadedDate) {
        try {
            return List.of(
                    new MetadataFilter(META_CASE_ID, caseId.toString()),
                    new MetadataFilter(META_DEFENDANT_ID, defendantId.toString()),
                    new MetadataFilter(META_MATERIAL_ID, materialId.toString()),
                    new MetadataFilter(META_UPLOADED_AT, uploadedDate)
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create document upload metadata", e);
        }
    }

    private FileStorageLocationReturnedSuccessfully initiateDocumentUpload(final UUID documentId, final String materialName, final List<MetadataFilter> documentMetadata) {
        final DocumentUploadRequest documentUploadRequest = new DocumentUploadRequest();
        documentUploadRequest.setDocumentId(documentId.toString());
        documentUploadRequest.setDocumentName(materialName);
        documentUploadRequest.setMetadataFilter(documentMetadata);

        final ResponseEntity<@NotNull FileStorageLocationReturnedSuccessfully> fileStorageLocationEntity = documentIngestionInitiationApi.initiateDocumentUpload(documentUploadRequest);
        return fileStorageLocationEntity.getBody();
    }

}

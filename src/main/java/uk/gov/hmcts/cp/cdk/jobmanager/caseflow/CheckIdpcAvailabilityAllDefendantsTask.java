package uk.gov.hmcts.cp.cdk.jobmanager.caseflow;


import static jakarta.json.Json.createObjectBuilder;
import static java.util.UUID.fromString;
import static java.util.UUID.randomUUID;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.RETRIEVE_FROM_MATERIAL;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.RETRIEVE_MATERIAL_AND_UPLOAD;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_COURTDOCUMENT_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DEFENDANT_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOCIDS_ARRAY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_LATEST_DEFENDANT;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_MATERIAL_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_MATERIAL_NAME;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.CPPUID;
import static uk.gov.hmcts.cp.cdk.util.TaskUtils.parseUuid;
import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;

import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.LatestMaterialInfo;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.jobmanager.IngestionProperties;
import uk.gov.hmcts.cp.cdk.jobmanager.JobManagerRetryProperties;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.repo.DocumentIdResolver;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@Task(CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS)
public class CheckIdpcAvailabilityAllDefendantsTask implements ExecutableTask {

    public static final String DEFAULT_BLOB_URI = "default_blob_uri";
    public static final String IDPC = "IDPC";
    private final ProgressionClient progressionClient;
    private final ExecutionService executionService;
    private final DocumentIdResolver documentIdResolver;
    private final JobManagerRetryProperties retryProperties;
    private final CaseDocumentRepository caseDocumentRepository;
    private final IngestionProperties ingestionProperties;

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {

        final JsonObject jobData = executionInfo.getJobData();

        final String caseIdString = jobData.getString(CTX_CASE_ID_KEY, null);
        final String userId = jobData.getString(CPPUID, null);
        final String requestId = jobData.getString("requestId", "unknown");
        Optional<UUID> caseIdUuidOptional;

        try {
            caseIdUuidOptional = parseUuid(caseIdString);

            final List<LatestMaterialInfo> materials =
                    progressionClient.getCourtDocumentsForAllDefendants(caseIdUuidOptional.get(), userId);
            final Map<String, String> defendantToDocIdMap = new HashMap<>();

            for (LatestMaterialInfo info : materials) {
                final UUID materialUuid = fromString(info.materialId());
                final UUID defendantUuid = fromString(info.defendantId());
                final Optional<UUID> existingDocUuid =
                        documentIdResolver.resolveExistingDocIdForDefendant(
                                caseIdUuidOptional.get(),
                                materialUuid,
                                defendantUuid
                        );

                if (existingDocUuid.isPresent()) {
                    log.info("Skipping defendantId={} as doc already exists", info.defendantId());
                    continue;
                }
                final String newDocId = randomUUID().toString();
                defendantToDocIdMap.put(info.defendantId(), newDocId);
                persistCaseDocument(fromString(newDocId), caseIdUuidOptional.get(), info);
            }

            final String latestDefendantId = materials.stream()
                    .filter(m -> defendantToDocIdMap.containsKey(m.defendantId()))
                    .filter(m -> m.uploadDateTime() != null)
                    .max(Comparator.comparing(LatestMaterialInfo::uploadDateTime))
                    .map(LatestMaterialInfo::defendantId)
                    .orElse(null);
            log.info("Latest defendant identified: {}", latestDefendantId);

            final JsonArrayBuilder docIdsArrayBuilder = Json.createArrayBuilder();
            defendantToDocIdMap.forEach((defendantId, docId) -> {
                docIdsArrayBuilder.add(docId);
            });

            final JsonArray docIdsArray = docIdsArrayBuilder.build();

            for (LatestMaterialInfo info : materials) {
                final String defendantId = info.defendantId();
                if (!defendantToDocIdMap.containsKey(defendantId)) {
                    continue;
                }
                final JsonObjectBuilder updatedJobData = createObjectBuilder(jobData);
                updatedJobData.add(CTX_DOC_ID_KEY, defendantToDocIdMap.get(defendantId));
                updatedJobData.add(CTX_MATERIAL_ID_KEY, info.materialId());
                updatedJobData.add(CTX_MATERIAL_NAME, info.materialName());
                updatedJobData.add(CTX_DEFENDANT_ID_KEY, info.defendantId());
                updatedJobData.add(CTX_COURTDOCUMENT_ID_KEY, info.courtDocumentId());
                updatedJobData.add(CTX_DOCIDS_ARRAY, docIdsArray);
                boolean isLatest = defendantId.equals(latestDefendantId);
                updatedJobData.add(CTX_LATEST_DEFENDANT, isLatest);

                final String retrieveMaterialTask = ingestionProperties.getFeature().isUseMultiDefendant()
                        ? RETRIEVE_MATERIAL_AND_UPLOAD
                        : RETRIEVE_FROM_MATERIAL;

                final ExecutionInfo executionInfoNew = executionInfo()
                        .from(executionInfo)
                        .withAssignedTaskName(retrieveMaterialTask)
                        .withJobData(updatedJobData.build())
                        .withExecutionStatus(ExecutionStatus.STARTED)
                        .build();

                executionService.executeWith(executionInfoNew);

                log.debug("Resolved material for caseId {} → id={}, name={}, requestId={}",
                        caseIdString,
                        info.materialId(),
                        info.materialName(),
                        requestId
                );
            }

            return executionInfo().from(executionInfo)
                    .withExecutionStatus(ExecutionStatus.COMPLETED)
                    .build();

        } catch (Exception ex) {
            log.error("{} failed. caseId={}, requestId={}", CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS,
                    caseIdString, requestId, ex);

            return executionInfo()
                    .from(executionInfo)
                    .withExecutionStatus(ExecutionStatus.INPROGRESS)
                    .withShouldRetry(true)
                    .build();
        }
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        final JobManagerRetryProperties.RetryConfig retry = retryProperties.getDefaultRetry();
        return Optional.of(
                IntStream.range(0, retry.getMaxAttempts())
                        .mapToLong(i -> retry.getDelaySeconds())
                        .boxed()
                        .toList()
        );
    }

    private void persistCaseDocument(final UUID docId,
                                     final UUID caseId,
                                     final LatestMaterialInfo info) {

        final CaseDocument entity = new CaseDocument();
        entity.setDocId(docId);
        entity.setCaseId(caseId);
        entity.setMaterialId(fromString(info.materialId()));
        entity.setDocName(IDPC);
        entity.setBlobUri(DEFAULT_BLOB_URI);
        entity.setCreatedAt(utcNow());
        entity.setIngestionPhase(DocumentIngestionPhase.WAITING_FOR_UPLOAD);
        entity.setDefendantId(fromString(info.defendantId()));
        entity.setCourtdocId(fromString(info.courtDocumentId()));

        caseDocumentRepository.saveAndFlush(entity);
    }

}

package uk.gov.hmcts.cp.cdk.jobmanager.caseflow;

import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_IDPC_AVAILABILITY;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.RETRIEVE_FROM_MATERIAL;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_COURTDOCUMENT_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DEFENDANT_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_MATERIAL_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_MATERIAL_NAME;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.CPPUID;
import static uk.gov.hmcts.cp.cdk.util.TaskUtils.getCourtDocuments;
import static uk.gov.hmcts.cp.cdk.util.TaskUtils.parseUuid;
import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;

import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.LatestMaterialInfo;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.jobmanager.JobManagerRetryProperties;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.repo.DocumentIdResolver;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
@Task(CHECK_IDPC_AVAILABILITY)
public class CheckIdpcAvailabilityTask implements ExecutableTask {

    private final ProgressionClient progressionClient;
    private final ExecutionService executionService;
    private final DocumentIdResolver documentIdResolver;
    private final JobManagerRetryProperties retryProperties;
    private final CaseDocumentRepository caseDocumentRepository;

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {

        final JsonObject jobData = executionInfo.getJobData();

        final String caseIdString = jobData.getString(CTX_CASE_ID_KEY, null);
        final String userId = jobData.getString(CPPUID, null);
        final String defendantId = jobData.getString(CTX_DEFENDANT_ID_KEY, null);
        final String requestId = jobData.getString("requestId", "unknown");
        Optional<UUID> caseIdUuidOptional;

        try {
            caseIdUuidOptional = parseUuid(caseIdString);

            final Optional<LatestMaterialInfo> latest =
                    getCourtDocuments(progressionClient, caseIdUuidOptional.get(), userId);
            latest.ifPresent(info -> {
                JsonObjectBuilder updatedJobData = Json.createObjectBuilder(jobData);
                updatedJobData.add(CTX_MATERIAL_ID_KEY, info.materialId());
                updatedJobData.add(CTX_MATERIAL_NAME, info.materialName());
                updatedJobData.add(CTX_COURTDOCUMENT_ID_KEY, info.courtDocumentId());

                final Optional<UUID> existingDocUuid =
                        documentIdResolver.resolveExistingDocId(caseIdUuidOptional.get(), UUID.fromString(info.materialId()));


                final String existingDocId = existingDocUuid.map(UUID::toString).orElse(null);
                final String newDocId = existingDocId == null ? generateUUID(caseIdString, defendantId, info.materialId()) : null;

                if (existingDocId != null) {
                    log.info("Resolved existing docId={} for caseId={}, materialId={} , hence skipping upload: ", existingDocId, caseIdUuidOptional.get(), info.materialId());
                } else {
                    log.debug("No existing docId; generated new docId={} for caseId={}, materialId={}.",
                            newDocId, caseIdUuidOptional.get(), info.materialId());
                }

                if (newDocId != null) {
                    updatedJobData.add(CTX_DOC_ID_KEY, newDocId);
                    persistCaseDocument(
                            UUID.fromString(newDocId),
                            caseIdUuidOptional.get(),
                            UUID.fromString(defendantId),
                            info
                    );

                    ExecutionInfo executionInfoNew = ExecutionInfo.executionInfo()
                            .from(executionInfo)
                            .withAssignedTaskName(RETRIEVE_FROM_MATERIAL)
                            .withJobData(updatedJobData.build())
                            .withExecutionStatus(ExecutionStatus.STARTED)
                            .build();

                    executionService.executeWith(executionInfoNew);

                }

                log.debug(
                        "Resolved material for caseId {} → id={}, name={}, requestId={}",
                        caseIdString,
                        info.materialId(),
                        info.materialName(),
                        requestId
                );
            });


            return ExecutionInfo.executionInfo()
                    .from(executionInfo)
                    .withExecutionStatus(ExecutionStatus.COMPLETED)
                    .build();

        } catch (DataIntegrityViolationException ex) {

            log.warn(
                    "Duplicate CaseDocument detected. Another process may have inserted it for " +
                            "caseId={}, defendantId={}, materialId= {}, errorMessage = {}",
                    caseIdString, defendantId, jobData.getString(CTX_MATERIAL_ID_KEY, null), ex.getMessage()
            );

            return ExecutionInfo.executionInfo()
                    .from(executionInfo)
                    .withExecutionStatus(ExecutionStatus.COMPLETED)
                    .build();
        } catch (Exception ex) {
            log.error(
                    "{} failed. caseId={}, requestId={}", CHECK_IDPC_AVAILABILITY,
                    caseIdString, requestId, ex
            );

            return ExecutionInfo.executionInfo()
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

    public String generateUUID(String caseIdString,
                               String defendantId,
                               String materialId) {

        String combined = caseIdString + "_" + defendantId + "_" + materialId;

        UUID uuid = UUID.nameUUIDFromBytes(
                combined.getBytes(StandardCharsets.UTF_8)
        );

        return uuid.toString();
    }

    @Transactional
    private void persistCaseDocument(UUID docId,
                                     UUID caseId,
                                     UUID defendantId,
                                     LatestMaterialInfo info) {

        final CaseDocument entity = new CaseDocument();
        entity.setDocId(docId);
        entity.setCaseId(caseId);
        entity.setMaterialId(UUID.fromString(info.materialId()));
        entity.setCreatedAt(utcNow());
        entity.setIngestionPhase(DocumentIngestionPhase.WAITING_FOR_UPLOAD);
        entity.setDefendantId(defendantId);
        entity.setCourtdocId(UUID.fromString(info.courtDocumentId()));

        caseDocumentRepository.saveAndFlush(entity);

    }
}

package uk.gov.hmcts.cp.cdk.services;

import static java.util.UUID.fromString;
import static java.util.UUID.randomUUID;
import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;

import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.LatestMaterialInfo;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.repo.DocumentIdResolver;
import uk.gov.hmcts.cp.cdk.util.MaterialNameValidator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Shared IDPC-availability business logic for the ingestion workflow.
 *
 * <p>Determines, for every defendant on a case, whether a newer IDPC version exists that has not
 * yet been ingested. For each such document it persists a placeholder {@link CaseDocument} and
 * returns a {@link NewIdpcDocument} describing it. Documents that already exist are skipped.
 *
 * <p>Used by both {@code CheckIdpcAvailabilityAllDefendantsTask} (scheduled ingestion, invoked
 * asynchronously via the JobManager) and {@link IngestionProcessorByCaseService} (manual "Process
 * IDPC" ingestion, invoked synchronously). Each caller is responsible for dispatching
 * {@code RETRIEVE_MATERIAL_AND_UPLOAD} for the returned documents via its own JobManager
 * {@code ExecutionInfo}/{@code executionService.executeWith(...)} plumbing — this service never
 * touches JobManager/task-framework types, so it stays reusable regardless of how each caller talks
 * to the JobManager.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdpcAvailabilityService {

    public static final String DEFAULT_BLOB_URI = "default_blob_uri";
    public static final String IDPC = "IDPC";

    private final ProgressionClient progressionClient;
    private final DocumentIdResolver documentIdResolver;
    private final CaseDocumentRepository caseDocumentRepository;

    /**
     * Evaluates IDPC availability for the given case, persisting a placeholder document for every
     * newer IDPC version found.
     *
     * @return the newer IDPC documents that require ingestion, in no particular order. An empty
     *         list means no newer IDPC version is available.
     */
    public List<NewIdpcDocument> retrieveDocuments(final UUID caseId, final String cppuid) {
        final List<LatestMaterialInfo> materials =
                progressionClient.getCourtDocumentsForAllDefendants(caseId, cppuid);
        final Map<String, String> defendantToDocIdMap = new HashMap<>();

        for (final LatestMaterialInfo info : materials) {
            final UUID materialUuid = fromString(info.materialId());
            final UUID defendantUuid = fromString(info.defendantId());
            final Optional<UUID> existingDocUuid =
                    documentIdResolver.resolveExistingDocIdForDefendant(caseId, materialUuid, defendantUuid);

            if (existingDocUuid.isPresent()) {
                log.info("Skipping defendantId={} as doc already exists", info.defendantId());
                continue;
            }
            final String newDocId = randomUUID().toString();
            defendantToDocIdMap.put(info.defendantId(), newDocId);
            persistCaseDocument(fromString(newDocId), caseId, info);
        }

        final String latestDefendantId = materials.stream()
                .filter(m -> defendantToDocIdMap.containsKey(m.defendantId()))
                .filter(m -> m.uploadDateTime() != null)
                .max(Comparator.comparing(LatestMaterialInfo::uploadDateTime))
                .map(LatestMaterialInfo::defendantId)
                .orElse(null);
        log.info("Latest defendant identified: {}", latestDefendantId);

        final List<NewIdpcDocument> newDocuments = new ArrayList<>();
        for (final LatestMaterialInfo info : materials) {
            final String defendantId = info.defendantId();
            final String docId = defendantToDocIdMap.get(defendantId);
            if (docId == null) {
                continue;
            }
            newDocuments.add(new NewIdpcDocument(
                    docId,
                    info.materialId(),
                    MaterialNameValidator.truncateMaterialName(info.materialName()),
                    defendantId,
                    info.courtDocumentId(),
                    defendantId.equals(latestDefendantId)
            ));
        }

        return newDocuments;
    }

    private void persistCaseDocument(final UUID docId, final UUID caseId, final LatestMaterialInfo info) {
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

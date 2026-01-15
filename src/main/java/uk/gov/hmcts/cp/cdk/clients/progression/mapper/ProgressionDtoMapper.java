package uk.gov.hmcts.cp.cdk.clients.progression.mapper;

import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClientConfig;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.CourtDocumentSearchResponse;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.LatestMaterialInfo;

import java.util.Comparator;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ProgressionDtoMapper {

    private static final String DOC_NAME_DEFAULT = "IDPC";

    private final String documentTypeIdFilter;

    public ProgressionDtoMapper(final ProgressionClientConfig progressionClientConfig) {
        this.documentTypeIdFilter = progressionClientConfig.docTypeId();
    }

    public Optional<LatestMaterialInfo> mapToLatestMaterialInfo(final CourtDocumentSearchResponse.DocumentIndex documentIndex) {
        if (documentIndex == null) {
            log.debug("DocumentIndex is null. Returning empty.");
            return Optional.empty();
        }

        final CourtDocumentSearchResponse.Document document = documentIndex.document();
        if (document == null) {
            log.debug("Document is null for caseIds={}. Returning empty.", documentIndex.caseIds());
            return Optional.empty();
        }

        final String documentTypeId = document.documentTypeId();
        if (documentTypeId == null || !documentTypeId.equals(documentTypeIdFilter)) {
            log.debug("Document type does not match filter. expected={}, actual={}", documentTypeIdFilter, documentTypeId);
            return Optional.empty();
        }

        final String resolvedDocumentName = (document.name() == null || document.name().isBlank())
                ? DOC_NAME_DEFAULT
                : document.name();

        if (document.materials() == null || document.materials().isEmpty()) {
            log.debug("No materials present for documentTypeId={} caseIds={}", documentTypeId, documentIndex.caseIds());
            return Optional.empty();
        }

        final Optional<CourtDocumentSearchResponse.Material> latestMaterialOptional = document.materials()
                .stream()
                .filter(material -> material.uploadDateTime() != null)
                .max(Comparator.comparing(CourtDocumentSearchResponse.Material::uploadDateTime));

        if (latestMaterialOptional.isEmpty()) {
            log.debug("No material with uploadDateTime found for documentTypeId={} caseIds={}", documentTypeId, documentIndex.caseIds());
            return Optional.empty();
        }

        final CourtDocumentSearchResponse.Material latestMaterial = latestMaterialOptional.get();

        final LatestMaterialInfo latestMaterialInfo = new LatestMaterialInfo(
                documentIndex.caseIds(),
                documentTypeId,
                document.documentTypeDescription(),
                latestMaterial.id(),
                resolvedDocumentName,
                latestMaterial.uploadDateTime()
        );

        log.info("Resolved latest material. caseIds={} documentTypeId={} materialId={} uploadedAt={}",
                documentIndex.caseIds(), documentTypeId, latestMaterial.id(), latestMaterial.uploadDateTime());

        return Optional.of(latestMaterialInfo);
    }
}

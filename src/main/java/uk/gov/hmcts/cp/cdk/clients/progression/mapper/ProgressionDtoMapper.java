package uk.gov.hmcts.cp.cdk.clients.progression.mapper;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClientConfig;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.CourtDocumentSearchResponse;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.LatestMaterialInfo;

import java.util.Comparator;
import java.util.Optional;

/**
 * Mapper utility for transforming progression-related DTOs.
 */
@Component
public class ProgressionDtoMapper {

    private final String docTypeId;

    public ProgressionDtoMapper(ProgressionClientConfig config) {
        this.docTypeId = config.docTypeId();
    }

    /**
     * Maps a {@link CourtDocumentSearchResponse.DocumentIndex} to an optional {@link LatestMaterialInfo},
     * representing the most recent material entry for the specified document type.
     *
     * @param index The document index containing materials and case info.
     * @return The latest material info if the document type matches and materials exist.
     */
    public Optional<LatestMaterialInfo> mapToLatestMaterialInfo(CourtDocumentSearchResponse.DocumentIndex index) {
        var caseIds = index.caseIds();
        var document = index.document();

        if (document == null) {
            return Optional.empty();
        }

        // Only include document type 41be14e8-9df5-4b08-80b0-1e670bc80a5b
        if (!docTypeId.equals(document.documentTypeId())) {
            return Optional.empty();
        }

        var documentTypeId = document.documentTypeId();
        var documentTypeDescription = document.documentTypeDescription();

        var latestMaterial = document.materials() == null
                ? Optional.<CourtDocumentSearchResponse.Material>empty()
                : document.materials().stream()
                .filter(m -> m.uploadDateTime() != null)
                .max(Comparator.comparing(CourtDocumentSearchResponse.Material::uploadDateTime));

        return latestMaterial.map(material ->
                new LatestMaterialInfo(
                        caseIds,
                        documentTypeId,
                        documentTypeDescription,
                        material.id(),
                        material.uploadDateTime()
                )
        );
    }
}

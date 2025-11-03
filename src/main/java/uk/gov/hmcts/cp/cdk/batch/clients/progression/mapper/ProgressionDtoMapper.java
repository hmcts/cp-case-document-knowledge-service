package uk.gov.hmcts.cp.cdk.batch.clients.progression.mapper;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.cdk.batch.clients.progression.ProgressionClientConfig;
import uk.gov.hmcts.cp.cdk.batch.clients.progression.dto.CourtDocumentSearchResponse;
import uk.gov.hmcts.cp.cdk.batch.clients.progression.dto.LatestMaterialInfo;

import java.util.Comparator;
import java.util.Optional;


@Component
public class ProgressionDtoMapper {
    private final String docTypeId;

    public ProgressionDtoMapper(final ProgressionClientConfig config) {
        this.docTypeId = config.docTypeId();
    }

    @SuppressWarnings({"PMD.OnlyOneReturn","PMD.UseExplicitTypes"})
    public Optional<LatestMaterialInfo> mapToLatestMaterialInfo(final CourtDocumentSearchResponse.DocumentIndex index) {
        final var caseIds = index.caseIds();
        final var document = index.document();
        if (document == null || !docTypeId.equals(document.documentTypeId())) {
            return Optional.empty();
        }
        final var documentTypeId = document.documentTypeId();
        final var documentTypeDescription = document.documentTypeDescription();


        final var latestMaterial = document.materials() == null
                ? Optional.<CourtDocumentSearchResponse.Material>empty()
                : document.materials().stream()
                .filter(m -> m.uploadDateTime() != null)
                .max(Comparator.comparing(CourtDocumentSearchResponse.Material::uploadDateTime));


        return latestMaterial.map(material -> new LatestMaterialInfo(
                caseIds,
                documentTypeId,
                documentTypeDescription,
                material.id(),
                material.uploadDateTime()
        ));
    }
}
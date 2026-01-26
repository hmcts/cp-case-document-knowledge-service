package uk.gov.hmcts.cp.cdk.clients.progression;

import uk.gov.hmcts.cp.cdk.clients.common.CQRSClientProperties;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.CourtDocumentSearchResponse;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.Defendant;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.LatestMaterialInfo;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.ProsecutionCaseEligibilityInfo;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.ProsecutionCaseResponse;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.UrlResponse;
import uk.gov.hmcts.cp.cdk.clients.progression.mapper.ProgressionDtoMapper;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class ProgressionClientImpl implements ProgressionClient {


    private final RestClient restClient;
    private final String cppuidHeader;
    private final ProgressionDtoMapper mapper;
    private final String courtDocsPath;
    private final String materialContentPath;
    private final String acceptForCourtDocSearch;
    private final String acceptForMaterialContent;
    private final String acceptForProsecutionCase;
    private final String prosecutionCasePath;


    public ProgressionClientImpl(final @Qualifier("cqrsRestClient") RestClient restClient,
                                 final CQRSClientProperties rootProps,
                                 final ProgressionClientConfig props,
                                 final ProgressionDtoMapper mapper) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.cppuidHeader = Objects.requireNonNull(rootProps.headers().cjsCppuid(), "cjsCppuidHeader");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.courtDocsPath = Objects.requireNonNull(props.courtDocsPath(), "courtDocsPath");
        this.materialContentPath = Objects.requireNonNull(props.materialContentPath(), "materialContentPath");
        this.acceptForCourtDocSearch = Objects.requireNonNull(props.acceptForCourtDocSearch(), "acceptForCourtDocSearch");
        this.acceptForMaterialContent = Objects.requireNonNull(props.acceptForMaterialContent(), "acceptForMaterialContent");
        this.acceptForProsecutionCase = Objects.requireNonNull(props.acceptForProsecutionCase(), "acceptForProsecutionCase");
        this.prosecutionCasePath = Objects.requireNonNull(props.prosecutionCasePath(), "prosecutionCasePath");
    }

    @Override
    @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.UseExplicitTypes"})
    public Optional<LatestMaterialInfo> getCourtDocuments(final UUID caseId, final String userId) {
        final URI uri = UriComponentsBuilder
                .fromPath(courtDocsPath)
                .queryParam("caseId", caseId)
                .build()
                .toUri();

        final CourtDocumentSearchResponse response = restClient.get()
                .uri(uri)
                .header(cppuidHeader, userId)
                .header(HttpHeaders.ACCEPT, acceptForCourtDocSearch)
                .retrieve()
                .body(CourtDocumentSearchResponse.class);

        if (response == null || response.documentIndices() == null || response.documentIndices().isEmpty()) {
            return Optional.empty();
        }

        return response.documentIndices().stream()
                .map(mapper::mapToLatestMaterialInfo)
                .flatMap(Optional::stream)
                .max(Comparator.comparing(LatestMaterialInfo::uploadDateTime));
    }

    @Override
    public Optional<String> getMaterialDownloadUrl(final UUID materialId, final String userId) {
        final String path = materialContentPath.replace("{materialId}", materialId.toString());
        final URI uri = UriComponentsBuilder.fromPath(path).build().toUri();

        final UrlResponse response = restClient.get()
                .uri(uri)
                .header(cppuidHeader, userId)
                .header(HttpHeaders.ACCEPT, acceptForMaterialContent)
                .retrieve()
                .body(UrlResponse.class);

        return Optional.ofNullable(response)
                .map(UrlResponse::url)
                .filter(u -> !u.isBlank());
    }

    @Override
    @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.UseExplicitTypes"})
    public Optional<ProsecutionCaseEligibilityInfo> getProsecutionCaseEligibilityInfo(
            final UUID caseId,
            final String userId
    ) {
        final String path = prosecutionCasePath.replace("{caseId}", caseId.toString());
        final URI uri = UriComponentsBuilder
                .fromPath(path)
                .build()
                .toUri();

        final ProsecutionCaseResponse response = restClient.get()
                .uri(uri)
                .header(cppuidHeader, userId)
                .header(HttpHeaders.ACCEPT, acceptForProsecutionCase)
                .retrieve()
                .body(ProsecutionCaseResponse.class);

        if (response == null || response.prosecutionCase() == null) {
            return Optional.empty();
        }

        final var prosecutionCase = response.prosecutionCase();
        final var defendants = prosecutionCase.defendants();

        if (defendants == null || defendants.isEmpty()) {
            return Optional.of(
                    new ProsecutionCaseEligibilityInfo(prosecutionCase.id(), List.of())
            );
        }

        final var defendantIds = defendants.stream()
                .map(Defendant::id)
                .filter(Objects::nonNull)
                .toList();

        return Optional.of(
                new ProsecutionCaseEligibilityInfo(prosecutionCase.id(), defendantIds)
        );
    }

}
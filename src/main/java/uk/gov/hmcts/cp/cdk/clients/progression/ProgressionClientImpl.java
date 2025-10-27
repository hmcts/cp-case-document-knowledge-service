package uk.gov.hmcts.cp.cdk.clients.progression;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.CourtDocumentSearchResponse;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.LatestMaterialInfo;
import uk.gov.hmcts.cp.cdk.clients.progression.mapper.ProgressionDtoMapper;
import uk.gov.hmcts.cp.cdk.query.QueryClientProperties;

import java.net.URI;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

@Component
public class ProgressionClientImpl implements ProgressionClient {


    private static final String SYSTEM_ACTOR = "system";

    private final RestClient restClient;
    private final String cppuidHeader;
    private final ProgressionDtoMapper mapper;
    private final String courtDocsPath;
    private final String materialContentPath;
    private final String acceptForCourtDocSearch;
    private final String acceptForMaterialContent;

    public ProgressionClientImpl(ProgressionClientConfig props, ProgressionDtoMapper mapper) {
        this.cppuidHeader = props.cjsCppuidHeader();
        this.mapper = mapper;
        this.courtDocsPath = props.courtDocsPath();
        this.materialContentPath = props.materialContentPath();
        this.acceptForCourtDocSearch = props.acceptForCourtDocSearch();
        this.acceptForMaterialContent = props.acceptForMaterialContent();
        this.restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, props.acceptHeader())
                .build();
    }

    @Override
    public Optional<LatestMaterialInfo> getCourtDocuments(final UUID caseId) {
        final URI uri = UriComponentsBuilder
                .fromPath(courtDocsPath)
                .queryParam("caseId", caseId)
                .build()
                .toUri();

        CourtDocumentSearchResponse response = restClient.get()
                .uri(uri)
                .header(cppuidHeader, SYSTEM_ACTOR)
                .header(HttpHeaders.ACCEPT, acceptForCourtDocSearch)
                .retrieve()
                .body(CourtDocumentSearchResponse.class);

        if (response == null || response.documentIndices() == null || response.documentIndices().isEmpty()) {
            return Optional.empty();
        }

        return response.documentIndices().stream()
                .map(mapper::mapToLatestMaterialInfo) // <-- delegate to mapper
                .flatMap(Optional::stream)
                .max(Comparator.comparing(LatestMaterialInfo::uploadDateTime));
    }

    @Override
    public Optional<String> getMaterialDownloadUrl(final UUID materialId) {
        final String path = materialContentPath.replace("{materialId}", materialId.toString());
        final URI uri = UriComponentsBuilder.fromPath(path).build().toUri();

        record UrlResponse(String url) {}

        UrlResponse response = restClient.get()
                .uri(uri)
                .header(cppuidHeader, SYSTEM_ACTOR)
                .header(HttpHeaders.ACCEPT, acceptForMaterialContent)
                .retrieve()
                .body(UrlResponse.class);

        return Optional.ofNullable(response)
                .map(UrlResponse::url)
                .filter(u -> !u.isBlank());
    }
}

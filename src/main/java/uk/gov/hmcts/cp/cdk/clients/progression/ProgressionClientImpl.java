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

    private static final String COURT_DOCS_PATH =
            "/progression-query-api/query/api/rest/progression/courtdocumentsearch";
    private static final String MATERIAL_CONTENT_PATH =
            "/progression-query-api/query/api/rest/progression/material/{materialId}/content";

    private static final String ACCEPT_FOR_COURTDOCSEARCH =
            "application/vnd.progression.query.courtdocuments+json";
    private static final String ACCEPT_FOR_MATERIAL_CONTENT =
            "application/vnd.progression.query.material-content+json";
    private static final String SYSTEM_ACTOR = "system";

    private final RestClient restClient;
    private final String cppuidHeader;
    private final ProgressionDtoMapper mapper;

    public ProgressionClientImpl(QueryClientProperties props, ProgressionDtoMapper mapper) {
        this.cppuidHeader = props.cjsCppuidHeader();
        this.mapper = mapper;
        this.restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, props.acceptHeader())
                .build();
    }

    @Override
    public Optional<LatestMaterialInfo> getCourtDocuments(final UUID caseId) {
        final URI uri = UriComponentsBuilder
                .fromPath(COURT_DOCS_PATH)
                .queryParam("caseId", caseId)
                .build()
                .toUri();

        CourtDocumentSearchResponse response = restClient.get()
                .uri(uri)
                .header(cppuidHeader, SYSTEM_ACTOR)
                .header(HttpHeaders.ACCEPT, ACCEPT_FOR_COURTDOCSEARCH)
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
        final String path = MATERIAL_CONTENT_PATH.replace("{materialId}", materialId.toString());
        final URI uri = UriComponentsBuilder.fromPath(path).build().toUri();

        record UrlResponse(String url) {}

        UrlResponse response = restClient.get()
                .uri(uri)
                .header(cppuidHeader, SYSTEM_ACTOR)
                .header(HttpHeaders.ACCEPT, ACCEPT_FOR_MATERIAL_CONTENT)
                .retrieve()
                .body(UrlResponse.class);

        return Optional.ofNullable(response)
                .map(UrlResponse::url)
                .filter(u -> !u.isBlank());
    }
}

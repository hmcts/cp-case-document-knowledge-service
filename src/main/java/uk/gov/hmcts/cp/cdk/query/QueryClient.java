package uk.gov.hmcts.cp.cdk.query;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummaries;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.ProsecutionCaseSummaries;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.CourtDocumentSearchResponse;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.LatestMaterialInfo;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class QueryClient {

    private static final String HEARINGS_PATH = "/hearing-query-api/query/api/rest/hearing/hearings";
    private static final String ACCEPT_FOR_HEARINGS = "application/vnd.hearing.get.hearings+json";
    private static final String COURT_DOCS_PATH = "/progression-query-api/query/api/rest/progression/courtdocumentsearch";
    private static final String ACCEPT_FOR_COURTDOCSEARCH = "application/vnd.progression.query.courtdocuments+json";
    private static final String MATERIAL_CONTENT_PATH =
            "/progression-query-api/query/api/rest/progression/material/{materialId}/content";
    private static final String ACCEPT_FOR_MATERIAL_CONTENT = "application/vnd.progression.query.material-content+json";


    private static final String DEFAULT_CONTENT_TYPE = "application/pdf";
    private static final String SYSTEM_ACTOR = "system";

    private final RestClient restClient;
    private final String acceptHeader;
    private final String cppuidHeader;


    public QueryClient(final QueryClientProperties props) {
        this.acceptHeader = props.acceptHeader();
        this.cppuidHeader = props.cjsCppuidHeader();
        this.restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, this.acceptHeader)
                .build();
    }

    private static boolean asBoolean(final Object value) {
        boolean result = false;
        if (value != null) {
            if (value instanceof Boolean booleanValue) {
                result = booleanValue;
            } else {
                result = Boolean.parseBoolean(String.valueOf(value));
            }
        }
        return result;
    }

    private static String asString(final Object value) {
        String result = null;
        if (value != null) {
            result = String.valueOf(value);
        }
        return result;
    }

    private static Long asLong(final Object value) {
        Long result = null;
        if (value != null) {
            if (value instanceof Number number) {
                result = number.longValue();
            } else {
                try {
                    result = Long.parseLong(String.valueOf(value));
                } catch (NumberFormatException ex) {
                    // swallow and return null to indicate "unknown"
                    result = null;
                }
            }
        }
        return result;
    }

    private static String defaultIfBlank(final String text, final String fallback) {
        String result = fallback;
        if (text != null && !text.isBlank()) {
            result = text;
        }
        return result;
    }

    public List<CaseSummary> getHearingsAndCases(final String courtLocation, final LocalDate hearingDate) {
        final URI uri = UriComponentsBuilder
                .fromPath(HEARINGS_PATH)
                .queryParam("courtLocation", courtLocation)
                .queryParam("date", hearingDate)
                .build()
                .toUri();

        final CaseSummary[] response = restClient.get()
                .uri(uri)
                .header(cppuidHeader, SYSTEM_ACTOR)
                .retrieve()
                .body(CaseSummary[].class);

        final List<CaseSummary> result;
        if (response == null) {
            result = List.of();
        } else {
            result = Arrays.asList(response);
        }
        return result;
    }

    public List<String> getHearingsAndCases(final String courtId, final String roomId, final LocalDate date) {
        final URI uri_hearing = UriComponentsBuilder
                .fromPath(HEARINGS_PATH)
                .queryParam("courtId", courtId)
                .queryParam("roomId", roomId)
                .queryParam("date", date)
                .build()
                .toUri();

        final HearingSummaries[] response = restClient.get()
                .uri(uri_hearing)
                .header(cppuidHeader, SYSTEM_ACTOR)
                .header(HttpHeaders.ACCEPT, ACCEPT_FOR_HEARINGS)
                .retrieve()
                .body(HearingSummaries[].class);

        final List<HearingSummaries> result;
        if (response == null) {
            result = List.of();
        } else {
            result = Arrays.asList(response);
        }
        List<String> resultIds = result.stream()
                // for each HearingSummaries
                .flatMap(hearingSummaries -> {
                    if (hearingSummaries.getProsecutionCaseSummaries() == null) {
                        return java.util.stream.Stream.empty();
                    }
                    return hearingSummaries.getProsecutionCaseSummaries().stream()
                            // filter ProsecutionCaseSummaries objects where person list size == 1
                            .filter(prosecutionCaseSummaries -> prosecutionCaseSummaries.getDefendants() != null && prosecutionCaseSummaries.getDefendants().size() == 1)
                            // map to id
                            .map(ProsecutionCaseSummaries::getId);
                })
                .distinct() // optional: ensure unique ids
                .collect(Collectors.toList());

        return resultIds;
    }

    // ---------- helpers (PMD: single exit point + braces + readable names) ----------

    public Optional<LatestMaterialInfo> getCourtDocuments(final UUID caseId) {

        // Build the URL from template in properties
        final URI uri = UriComponentsBuilder
                .fromPath(COURT_DOCS_PATH)
                .queryParam("caseId", caseId)
                .build()
                .toUri();


        // Build request
        CourtDocumentSearchResponse response = restClient.get()
                .uri(uri)
                .header(cppuidHeader, SYSTEM_ACTOR)
                .header(HttpHeaders.ACCEPT, ACCEPT_FOR_COURTDOCSEARCH)
                .retrieve()
                .body(CourtDocumentSearchResponse.class);


        if (response == null || response.documentIndices() == null || response.documentIndices().isEmpty()) {
            return Optional.empty(); // empty list if no data
        }

        // Process each DocumentIndex and extract latest Material
        return response.documentIndices().stream()
                .map(this::mapToLatestMaterialInfo)
                .flatMap(Optional::stream) // remove empty optionals
                .max(Comparator.comparing(LatestMaterialInfo::uploadDateTime));
    }

    private Optional<LatestMaterialInfo> mapToLatestMaterialInfo(CourtDocumentSearchResponse.DocumentIndex index) {
        List<String> caseIds = index.caseIds();
        CourtDocumentSearchResponse.Document document = index.document();

        if (document == null) return Optional.empty();

        String documentTypeId = document.documentTypeId();
        String documentTypeDescription = document.documentTypeDescription();

        // Find latest material
        Optional<CourtDocumentSearchResponse.Material> latestMaterial = document.materials() == null ? Optional.empty() :
                document.materials().stream()
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


    public Optional<String> getMaterialDownloadUrl(final UUID materialId) {


        final String url = MATERIAL_CONTENT_PATH.replace("{materialId}", materialId.toString());
        // Build the full URI for material content
        // Build the URL from template in properties
        final URI uri = UriComponentsBuilder
                .fromPath(url)
                .build()
                .toUri();

        record UrlResponse(String url) {
        }

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


    public InputStream downloadIdpc(final String url) {
        byte[] bytes = restClient.get()
                .uri(URI.create(url))
                .header(cppuidHeader, SYSTEM_ACTOR)
                .accept(MediaType.APPLICATION_OCTET_STREAM)
                .retrieve()
                .body(byte[].class);

        if (bytes == null) {
            bytes = new byte[0];
        }
        return new ByteArrayInputStream(bytes);
    }

    public record CaseSummary(UUID caseId, UUID hearingId) {
    }

    public record CourtDocMeta(
            boolean singleDefendant,
            boolean idpcAvailable,
            String idpcDownloadUrl,
            String contentType,
            Long sizeBytes
    ) {
    }
}

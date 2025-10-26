package uk.gov.hmcts.cp.cdk.query;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class QueryClient {

    private static final String HEARINGS_PATH = "/hearings";
    private static final String COURT_DOCS_PATH = "/progression.query.courtdocuments";
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

    // ---------- helpers (PMD: single exit point + braces + readable names) ----------

    public CourtDocMeta getCourtDocuments(final UUID caseId) {
        final URI uri = UriComponentsBuilder
                .fromPath(COURT_DOCS_PATH)
                .queryParam("caseId", caseId)
                .build()
                .toUri();

        @SuppressWarnings("unchecked") final Map<String, Object> map = restClient.get()
                .uri(uri)
                .header(cppuidHeader, SYSTEM_ACTOR)
                .retrieve()
                .body(Map.class);

        final boolean single = asBoolean(map == null ? null : map.get("singleDefendant"));
        final boolean idpc = asBoolean(map == null ? null : map.get("idpcAvailable"));
        final String url = asString(map == null ? null : map.get("idpcDownloadUrl"));
        final String contentType = defaultIfBlank(
                asString(map == null ? null : map.get("contentType")),
                DEFAULT_CONTENT_TYPE
        );
        final Long size = asLong(map == null ? null : map.get("sizeBytes"));

        return new CourtDocMeta(single, idpc, url, contentType, size);
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

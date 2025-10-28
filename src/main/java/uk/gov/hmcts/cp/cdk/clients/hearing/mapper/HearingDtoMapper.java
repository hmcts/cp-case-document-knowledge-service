package uk.gov.hmcts.cp.cdk.clients.hearing.mapper;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummaries;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.ProsecutionCaseSummaries;
import uk.gov.hmcts.cp.cdk.query.QueryClientProperties;

import java.net.URI;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class HearingDtoMapper {

    private static final String HEARINGS_PATH = "/hearing-query-api/query/api/rest/hearing/hearings";
    private static final String ACCEPT_FOR_HEARINGS = "application/vnd.hearing.get.hearings+json";

    private static final String SYSTEM_ACTOR = "system";

    private final RestClient restClient;
    private final String acceptHeader;
    private final String cppuidHeader;

    public HearingDtoMapper(final QueryClientProperties props) {
        this.acceptHeader = props.acceptHeader();
        this.cppuidHeader = props.cjsCppuidHeader();
        this.restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, this.acceptHeader)
                .build();
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
}

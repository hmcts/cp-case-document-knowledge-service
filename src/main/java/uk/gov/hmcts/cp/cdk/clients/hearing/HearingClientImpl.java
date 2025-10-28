package uk.gov.hmcts.cp.cdk.clients.hearing;


import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import uk.gov.hmcts.cp.cdk.clients.common.CdkClientProperties;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummaries;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummariesInfo;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummariesListRequest;
import uk.gov.hmcts.cp.cdk.clients.hearing.mapper.HearingDtoMapper;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


@Component
public class HearingClientImpl implements HearingClient {
    private static final String SYSTEM_ACTOR = "system";
    private final RestClient restClient;
    private final String acceptHeader;
    private final String cppuidHeaderName;
    private final String hearingsPath;
    private final HearingDtoMapper mapper;


    public HearingClientImpl(final RestClient restClient,
                             final CdkClientProperties rootProps,
                             final HearingClientConfig hearingProps,
                             final HearingDtoMapper mapper) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.acceptHeader = Objects.requireNonNull(hearingProps.acceptHeader(), "acceptHeader");
        this.cppuidHeaderName = Objects.requireNonNull(rootProps.headers().cjsCppuid(), "cjsCppuidHeader");
        this.hearingsPath = Objects.requireNonNull(hearingProps.hearingsPath(), "hearingsPath");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public List<HearingSummariesInfo> getHearingsAndCases(final String courtId, final String roomId, final LocalDate date) {
        final URI uriHearing = UriComponentsBuilder
                .fromPath(hearingsPath)
                .queryParam("courtCentreId", courtId)
                .queryParam("roomId", roomId)
                .queryParam("date", date)
                .build()
                .toUri();


        final HearingSummariesListRequest summariesList = restClient.get()
                .uri(uriHearing)
                .header(cppuidHeaderName, SYSTEM_ACTOR)
                .header(HttpHeaders.ACCEPT, acceptHeader)
                .retrieve()
                .body(HearingSummariesListRequest.class);


        if (summariesList == null || summariesList.hearingSummaries() == null) {
            return List.of();
        }


        final List<String> resultIds = new ArrayList<>();
        for (final HearingSummaries hs : summariesList.hearingSummaries()) {
            resultIds.addAll(mapper.collectProsecutionCaseIds(hs));
        }
        return mapper.toHearingSummariesInfo(resultIds);
    }
}
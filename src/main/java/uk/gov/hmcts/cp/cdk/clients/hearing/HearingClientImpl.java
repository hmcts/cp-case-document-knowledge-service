package uk.gov.hmcts.cp.cdk.clients.hearing;


import static java.util.Objects.isNull;

import uk.gov.hmcts.cp.cdk.clients.common.CQRSClientProperties;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingCaseForDay;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingCasesForDayResponse;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummaries;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummariesInfo;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummariesListRequest;
import uk.gov.hmcts.cp.cdk.clients.hearing.mapper.HearingDtoMapper;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;


@Component
public class HearingClientImpl implements HearingClient {

    private final RestClient restClient;
    private final String acceptHeader;
    private final String cppuidHeaderName;
    private final String hearingsPath;
    private final String hearingCasesForDayAcceptHeader;
    private final String hearingCasesForDayPath;
    private final HearingDtoMapper mapper;


    public HearingClientImpl(final @Qualifier("cqrsRestClient") RestClient restClient,
                             final CQRSClientProperties rootProps,
                             final HearingClientConfig hearingProps,
                             final HearingDtoMapper mapper) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.acceptHeader = Objects.requireNonNull(hearingProps.getHearingsAcceptHeader(), "acceptHeader");
        this.cppuidHeaderName = Objects.requireNonNull(rootProps.headers().cjsCppuid(), "cjsCppuidHeader");
        this.hearingsPath = Objects.requireNonNull(hearingProps.getHearingsPath(), "hearingsPath");
        this.hearingCasesForDayAcceptHeader = Objects.requireNonNull(
                hearingProps.getHearingCasesForDayAcceptHeader(), "hearingCasesForDayAcceptHeader");
        this.hearingCasesForDayPath = Objects.requireNonNull(
                hearingProps.getHearingCasesForDayPath(), "hearingCasesForDayPath");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.UseExplicitTypes"})
    public List<HearingSummariesInfo> getHearingsAndCases(final String courtId, final String roomId, final LocalDate date, final String userId) {
        final URI uriHearing = UriComponentsBuilder
                .fromPath(hearingsPath)
                .queryParam("courtCentreId", courtId)
                .queryParam("roomId", roomId)
                .queryParam("date", date)
                .build()
                .toUri();


        final HearingSummariesListRequest summariesList = restClient.get()
                .uri(uriHearing)
                .header(cppuidHeaderName, userId)
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

    @Override
    @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.UseExplicitTypes"})
    public List<HearingCaseForDay> getHearingCasesForDay(final LocalDate date, final String userId) {
        final URI uriHearingCasesForDay = UriComponentsBuilder
                .fromPath(hearingCasesForDayPath)
                .queryParam("date", date)
                .build()
                .toUri();

        final HearingCasesForDayResponse response = restClient.get()
                .uri(uriHearingCasesForDay)
                .header(cppuidHeaderName, userId)
                .header(HttpHeaders.ACCEPT, hearingCasesForDayAcceptHeader)
                .retrieve()
                .body(HearingCasesForDayResponse.class);

        if (isNull(response) || isNull(response.hearingCases())) {
            return List.of();
        }

        return response.hearingCases();
    }
}
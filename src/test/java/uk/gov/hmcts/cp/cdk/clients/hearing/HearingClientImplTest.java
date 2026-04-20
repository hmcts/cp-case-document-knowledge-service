package uk.gov.hmcts.cp.cdk.clients.hearing;

import static java.time.LocalDate.now;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.cdk.clients.common.CQRSClientProperties;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummaries;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummariesInfo;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummariesListRequest;
import uk.gov.hmcts.cp.cdk.clients.hearing.mapper.HearingDtoMapper;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class HearingClientImplTest {

    @Mock
    private RestClient restClient;

    @Mock
    private HearingDtoMapper mapper;

    @Mock
    private CQRSClientProperties rootProps;

    @Mock
    private CQRSClientProperties.Headers headers;

    @Mock
    private HearingClientConfig hearingProps;

    @Mock
    private RestClient.RequestHeadersUriSpec uriSpec;
    @Mock
    private RestClient.RequestHeadersSpec headersSpec;
    @Mock
    private RestClient.ResponseSpec responseSpec;

    private HearingClientImpl client;

    private final String acceptHeader = "application/json";
    private final String cppuidHeader = "cppuid";
    private final String hearingsPath = "/hearings";

    @BeforeEach
    void setUp() {
        when(rootProps.headers()).thenReturn(headers);
        when(headers.cjsCppuid()).thenReturn(cppuidHeader);
        when(hearingProps.acceptHeader()).thenReturn(acceptHeader);
        when(hearingProps.hearingsPath()).thenReturn(hearingsPath);

        client = new HearingClientImpl(restClient, rootProps, hearingProps, mapper);
    }

    @Test
    void shouldReturnMappedResults_whenValidResponse() {
        final HearingSummaries hs1 = mock(HearingSummaries.class);
        final HearingSummaries hs2 = mock(HearingSummaries.class);
        final HearingSummariesListRequest response = mock(HearingSummariesListRequest.class);

        mockRestClient();
        when(response.hearingSummaries()).thenReturn(List.of(hs1, hs2));
        when(responseSpec.body(HearingSummariesListRequest.class)).thenReturn(response);

        when(mapper.collectProsecutionCaseIds(hs1)).thenReturn(List.of("id1"));
        when(mapper.collectProsecutionCaseIds(hs2)).thenReturn(List.of("id2"));

        final List<HearingSummariesInfo> mapped = List.of(new HearingSummariesInfo("id1"),
                new HearingSummariesInfo("id2"));

        when(mapper.toHearingSummariesInfo(List.of("id1", "id2")))
                .thenReturn(mapped);

        final List<HearingSummariesInfo> result = client.getHearingsAndCases("court1", "room1", now(), "user1");

        assertThat(result.size()).isEqualTo(2);
        verify(mapper).collectProsecutionCaseIds(hs1);
        verify(mapper).collectProsecutionCaseIds(hs2);
        verify(mapper).toHearingSummariesInfo(List.of("id1", "id2"));
    }

    @Test
    void shouldReturnEmptyList_whenResponseIsNull() {
        mockRestClient();
        when(responseSpec.body(HearingSummariesListRequest.class)).thenReturn(null);

        final List<HearingSummariesInfo> result = client.getHearingsAndCases("court", "room", now(), "user");

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void shouldReturnEmptyList_whenHearingSummariesNull() {
        final HearingSummariesListRequest response = mock(HearingSummariesListRequest.class);
        when(response.hearingSummaries()).thenReturn(null);

        mockRestClient();
        when(responseSpec.body(HearingSummariesListRequest.class)).thenReturn(response);

        final List<HearingSummariesInfo> result = client.getHearingsAndCases("court", "room", now(), "user");

        assertThat(result.isEmpty()).isTrue();
    }

    private void mockRestClient() {
        doReturn(uriSpec).when(restClient).get();
        when(uriSpec.uri(any(URI.class))).thenReturn(uriSpec);
        when(uriSpec.header(any(), any())).thenReturn(uriSpec);
        when(uriSpec.header(any(), any())).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenReturn(responseSpec);
    }

}
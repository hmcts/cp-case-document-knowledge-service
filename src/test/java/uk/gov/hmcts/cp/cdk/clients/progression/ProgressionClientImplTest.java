package uk.gov.hmcts.cp.cdk.clients.progression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.cdk.clients.common.CQRSClientProperties;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.CourtDocumentSearchResponse;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.Defendant;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.LatestMaterialInfo;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.ProsecutionCase;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.ProsecutionCaseEligibilityInfo;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.ProsecutionCaseResponse;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.UrlResponse;
import uk.gov.hmcts.cp.cdk.clients.progression.mapper.ProgressionDtoMapper;

import java.net.URI;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class ProgressionClientImplTest {
    @Mock
    private RestClient restClient;
    @Mock
    private ProgressionDtoMapper mapper;
    @Mock
    private CQRSClientProperties rootProps;
    @Mock
    private CQRSClientProperties.Headers headers;
    @Mock
    private ProgressionClientConfig props;

    @Mock
    private RestClient.RequestHeadersUriSpec uriSpec;
    @Mock
    private RestClient.RequestHeadersSpec headersSpec;
    @Mock
    private RestClient.ResponseSpec responseSpec;

    private ProgressionClientImpl client;

    private final String cppuidHeader = "cppuid";
    private final String courtDocsPath = "/court-docs";
    private final String materialPath = "/materials/{materialId}";
    private final String prosecutionPath = "/cases/{caseId}";

    @BeforeEach
    void setUp() {
        when(rootProps.headers()).thenReturn(headers);
        when(headers.cjsCppuid()).thenReturn(cppuidHeader);

        when(props.courtDocsPath()).thenReturn(courtDocsPath);
        when(props.materialContentPath()).thenReturn(materialPath);
        when(props.prosecutionCasePath()).thenReturn(prosecutionPath);

        when(props.acceptForCourtDocSearch()).thenReturn("json");
        when(props.acceptForMaterialContent()).thenReturn("json");
        when(props.acceptForProsecutionCase()).thenReturn("json");

        client = new ProgressionClientImpl(restClient, rootProps, props, mapper);
    }

    @Test
    void shouldReturnEmpty_whenNoDocuments() {
        final CourtDocumentSearchResponse response = mock(CourtDocumentSearchResponse.class);
        when(response.documentIndices()).thenReturn(List.of());
        mockRestClient();
        when(responseSpec.body(CourtDocumentSearchResponse.class)).thenReturn(response);

        final Optional<LatestMaterialInfo> result = client.getCourtDocuments(UUID.randomUUID(), "user");

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void shouldReturnLatestDocument_whenMultipleExist() {
        final UUID caseId = UUID.randomUUID();
        final CourtDocumentSearchResponse response = mock(CourtDocumentSearchResponse.class);

        final CourtDocumentSearchResponse.Document document = mock(CourtDocumentSearchResponse.Document.class);
        final CourtDocumentSearchResponse.Document document2 = mock(CourtDocumentSearchResponse.Document.class);
        final CourtDocumentSearchResponse.DocumentIndex documentIndex1 = new CourtDocumentSearchResponse.DocumentIndex(List.of(), document, List.of());
        final CourtDocumentSearchResponse.DocumentIndex documentIndex2 = new CourtDocumentSearchResponse.DocumentIndex(List.of(), document2, List.of());
        when(response.documentIndices()).thenReturn(List.of(documentIndex1, documentIndex2));

        final LatestMaterialInfo older = mock(LatestMaterialInfo.class);
        final LatestMaterialInfo newer = mock(LatestMaterialInfo.class);

        when(older.uploadDateTime()).thenReturn(ZonedDateTime.now(ZoneId.of("UTC")).minusDays(1));
        when(newer.uploadDateTime()).thenReturn(ZonedDateTime.now(ZoneId.of("UTC")));
        when(mapper.mapToLatestMaterialInfo(documentIndex1)).thenReturn(Optional.of(older));
        when(mapper.mapToLatestMaterialInfo(documentIndex2)).thenReturn(Optional.of(newer));

        mockRestClient();
        when(responseSpec.body(CourtDocumentSearchResponse.class)).thenReturn(response);

        final Optional<LatestMaterialInfo> result = client.getCourtDocuments(caseId, "user");

        assertThat(result.isPresent()).isTrue();
        assertThat(result.get()).isEqualTo(newer);
    }

    @Test
    void shouldReturnEmpty_whenResponseNull() {
        mockRestClient();
        when(responseSpec.body(CourtDocumentSearchResponse.class)).thenReturn(null);

        final Optional<LatestMaterialInfo> result = client.getCourtDocuments(UUID.randomUUID(), "user");

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void shouldReturnUrl_whenValidResponse() {
        final UrlResponse response = mock(UrlResponse.class);
        when(response.url()).thenReturn("http://download");
        mockRestClient();
        when(responseSpec.body(UrlResponse.class)).thenReturn(response);

        final Optional<String> result = client.getMaterialDownloadUrl(UUID.randomUUID(), "user");

        assertThat(result.isPresent()).isTrue();
        assertThat(result.get()).isEqualTo("http://download");
    }

    @Test
    void shouldReturnEmpty_whenUrlBlank() {
        final UrlResponse response = mock(UrlResponse.class);
        when(response.url()).thenReturn("   ");
        mockRestClient();
        when(responseSpec.body(UrlResponse.class)).thenReturn(response);

        final Optional<String> result =
                client.getMaterialDownloadUrl(UUID.randomUUID(), "user");

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void shouldReturnEmpty_whenResponseNull_getProsecutionCaseEligibilityInfo() {
        mockRestClient();
        when(responseSpec.body(ProsecutionCaseResponse.class)).thenReturn(null);

        final Optional<ProsecutionCaseEligibilityInfo> result = client.getProsecutionCaseEligibilityInfo(UUID.randomUUID(), "user");

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void shouldReturnEmptyDefendants_whenNoDefendants() {
        final ProsecutionCaseResponse response = mock(ProsecutionCaseResponse.class);
        final ProsecutionCase prosecutionCase = mock(ProsecutionCase.class);

        when(response.prosecutionCase()).thenReturn(prosecutionCase);
        when(prosecutionCase.defendants()).thenReturn(List.of());
        when(prosecutionCase.id()).thenReturn("case-1");

        mockRestClient();
        when(responseSpec.body(ProsecutionCaseResponse.class)).thenReturn(response);

        final Optional<ProsecutionCaseEligibilityInfo> result = client.getProsecutionCaseEligibilityInfo(UUID.randomUUID(), "user");

        assertThat(result.isPresent()).isTrue();
        assertThat(result.get().defendantIds().size()).isEqualTo(0);
    }

    @Test
    void shouldMapDefendants_whenPresent() {
        final ProsecutionCaseResponse response = mock(ProsecutionCaseResponse.class);
        final ProsecutionCase prosecutionCase = mock(ProsecutionCase.class);

        final Defendant d1 = mock(Defendant.class);
        final Defendant d2 = mock(Defendant.class);

        when(d1.id()).thenReturn("d1");
        when(d2.id()).thenReturn("d2");
        when(response.prosecutionCase()).thenReturn(prosecutionCase);
        when(prosecutionCase.id()).thenReturn("case-1");
        when(prosecutionCase.defendants()).thenReturn(List.of(d1, d2));

        mockRestClient();
        when(responseSpec.body(ProsecutionCaseResponse.class)).thenReturn(response);

        final Optional<ProsecutionCaseEligibilityInfo> result = client.getProsecutionCaseEligibilityInfo(UUID.randomUUID(), "user");

        assertThat(result.isPresent()).isTrue();
        assertThat(result.get().defendantIds()).isEqualTo(List.of("d1", "d2"));
    }

    @Test
    void shouldReturnEmptyList_whenNoDocs() {
        final CourtDocumentSearchResponse response = mock(CourtDocumentSearchResponse.class);
        when(response.documentIndices()).thenReturn(List.of());
        mockRestClient();
        when(responseSpec.body(CourtDocumentSearchResponse.class)).thenReturn(response);

        final List<LatestMaterialInfo> result = client.getCourtDocumentsForAllDefendants(UUID.randomUUID(), "user");

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void shouldReturnOnePerDefendant_withLatestUpload() {
        final CourtDocumentSearchResponse response = mock(CourtDocumentSearchResponse.class);
        final CourtDocumentSearchResponse.Document document = mock(CourtDocumentSearchResponse.Document.class);
        final CourtDocumentSearchResponse.Document document2 = mock(CourtDocumentSearchResponse.Document.class);
        final CourtDocumentSearchResponse.DocumentIndex documentIndex1 = new CourtDocumentSearchResponse.DocumentIndex(List.of(), document, List.of());
        final CourtDocumentSearchResponse.DocumentIndex documentIndex2 = new CourtDocumentSearchResponse.DocumentIndex(List.of(), document2, List.of());
        when(response.documentIndices()).thenReturn(List.of(documentIndex1, documentIndex2));

        final LatestMaterialInfo d1_old = mock(LatestMaterialInfo.class);
        final LatestMaterialInfo d1_new = mock(LatestMaterialInfo.class);

        when(d1_old.defendantId()).thenReturn("D1");
        when(d1_new.defendantId()).thenReturn("D1");

        when(d1_old.uploadDateTime()).thenReturn(ZonedDateTime.now().minusDays(1));
        when(d1_new.uploadDateTime()).thenReturn(ZonedDateTime.now());
        when(mapper.mapToLatestMaterialInfo(documentIndex1)).thenReturn(Optional.of(d1_old));
        when(mapper.mapToLatestMaterialInfo(documentIndex2)).thenReturn(Optional.of(d1_new));

        mockRestClient();
        when(responseSpec.body(CourtDocumentSearchResponse.class)).thenReturn(response);

        final List<LatestMaterialInfo> result = client.getCourtDocumentsForAllDefendants(UUID.randomUUID(), "user");

        assertThat(result.size()).isEqualTo(1);
        assertThat(result.get(0)).isEqualTo(d1_new);
    }

    private void mockRestClient() {
        doReturn(uriSpec).when(restClient).get();
        when(uriSpec.uri(any(URI.class))).thenReturn(uriSpec);
        when(uriSpec.header(any(), any())).thenReturn(uriSpec);
        when(uriSpec.header(any(), any())).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenReturn(responseSpec);
    }

}
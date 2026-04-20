package uk.gov.hmcts.cp.cdk.clients.progression.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClientConfig;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.CourtDocumentSearchResponse;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.LatestMaterialInfo;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProgressionDtoMapperTest {

    private ProgressionDtoMapper mapper;

    private static final String FILTER = "IDPC_TYPE";

    @BeforeEach
    void setUp() {
        ProgressionClientConfig config = mock(ProgressionClientConfig.class);
        when(config.docTypeId()).thenReturn(FILTER);
        mapper = new ProgressionDtoMapper(config);
    }

    @Test
    void shouldReturnEmpty_whenDocumentIndexNull() {
        assertThat(mapper.mapToLatestMaterialInfo(null).isEmpty()).isTrue();
    }

    @Test
    void shouldReturnEmpty_whenDocumentNull() {
        final CourtDocumentSearchResponse.DocumentIndex index = mock(CourtDocumentSearchResponse.DocumentIndex.class);
        when(index.document()).thenReturn(null);

        assertThat(mapper.mapToLatestMaterialInfo(index).isEmpty()).isTrue();
    }

    @Test
    void shouldReturnEmpty_whenDocumentTypeDoesNotMatch() {
        final CourtDocumentSearchResponse.DocumentIndex index = mockIndexWithDocument("OTHER_TYPE", "doc", List.of());
        assertThat(mapper.mapToLatestMaterialInfo(index).isEmpty()).isTrue();
    }

    @Test
    void shouldReturnEmpty_whenDocumentTypeNull() {
        final CourtDocumentSearchResponse.DocumentIndex index = mockIndexWithDocument(null, "doc", List.of());
        assertThat(mapper.mapToLatestMaterialInfo(index).isEmpty()).isTrue();
    }

    @Test
    void shouldReturnEmpty_whenNoMaterials() {
        final CourtDocumentSearchResponse.DocumentIndex index = mockIndexWithDocument(FILTER, "doc", List.of());
        assertThat(mapper.mapToLatestMaterialInfo(index).isEmpty()).isTrue();
    }

    @Test
    void shouldReturnEmpty_whenNoMaterialWithUploadDate() {
        final CourtDocumentSearchResponse.Material material = mockMaterial(null);
        final CourtDocumentSearchResponse.DocumentIndex index = mockIndexWithDocument(FILTER, "doc", List.of(material));

        assertThat(mapper.mapToLatestMaterialInfo(index).isEmpty()).isTrue();
    }

    @Test
    void shouldReturnLatestMaterial() {
        final CourtDocumentSearchResponse.Material older = mockMaterial(ZonedDateTime.now().minusDays(1));
        final CourtDocumentSearchResponse.Material newer = mockMaterial(ZonedDateTime.now());

        final CourtDocumentSearchResponse.DocumentIndex index = mockIndexWithDocument(FILTER, "doc-name", List.of(older, newer));

        final Optional<LatestMaterialInfo> result = mapper.mapToLatestMaterialInfo(index);

        assertThat(result.isPresent()).isTrue();
        assertThat(result.get().materialId()).isEqualTo(newer.id());
    }

    @Test
    void shouldUseDefaultName_whenNameNull() {
        final CourtDocumentSearchResponse.Material material = mockMaterial(ZonedDateTime.now());
        final CourtDocumentSearchResponse.DocumentIndex index = mockIndexWithDocument(FILTER, null, List.of(material));

        final LatestMaterialInfo result = mapper.mapToLatestMaterialInfo(index).orElseThrow();

        assertThat(result.materialName()).isEqualTo("IDPC");
    }

    @Test
    void shouldUseDefaultName_whenNameBlank() {
        final CourtDocumentSearchResponse.Material material = mockMaterial(ZonedDateTime.now());
        final CourtDocumentSearchResponse.DocumentIndex index = mockIndexWithDocument(FILTER, "   ", List.of(material));

        final LatestMaterialInfo result = mapper.mapToLatestMaterialInfo(index).orElseThrow();

        assertThat(result.materialName()).isEqualTo("IDPC");
    }

    @Test
    void shouldSetDefendantId_whenSinglePresent() {
        final CourtDocumentSearchResponse.Material material = mockMaterial(ZonedDateTime.now());
        final CourtDocumentSearchResponse.DocumentIndex index = mockIndexWithDocument(FILTER, "doc", List.of(material));
        when(index.defendantIds()).thenReturn(List.of("D1"));

        final LatestMaterialInfo result = mapper.mapToLatestMaterialInfo(index).orElseThrow();

        assertThat(result.defendantId()).isEqualTo("D1");
    }

    @Test
    void shouldUseFirstDefendant_whenMultiplePresent() {
        final CourtDocumentSearchResponse.Material material = mockMaterial(ZonedDateTime.now());
        final CourtDocumentSearchResponse.DocumentIndex index = mockIndexWithDocument(FILTER, "doc", List.of(material));
        when(index.defendantIds()).thenReturn(List.of("D1", "D2"));

        final LatestMaterialInfo result = mapper.mapToLatestMaterialInfo(index).orElseThrow();

        assertThat(result.defendantId()).isEqualTo("D1");
    }

    @Test
    void shouldSetDefendantNull_whenNonePresent() {
        final CourtDocumentSearchResponse.Material material = mockMaterial(ZonedDateTime.now());
        final CourtDocumentSearchResponse.DocumentIndex index = mockIndexWithDocument(FILTER, "doc", List.of(material));
        when(index.defendantIds()).thenReturn(null);

        final LatestMaterialInfo result = mapper.mapToLatestMaterialInfo(index).orElseThrow();

        assertThat(result.defendantId()).isNull();
    }

    @Test
    void shouldStillReturn_whenCourtDocumentIdNull() {
        final CourtDocumentSearchResponse.Material material = mockMaterial(ZonedDateTime.now());
        final CourtDocumentSearchResponse.DocumentIndex index = mockIndexWithDocument(FILTER, "doc", List.of(material));
        when(index.document().courtDocumentId()).thenReturn(null);

        final Optional<LatestMaterialInfo> result = mapper.mapToLatestMaterialInfo(index);

        assertThat(result.isPresent()).isTrue();
    }

    private CourtDocumentSearchResponse.DocumentIndex mockIndexWithDocument(final String docType, final String name,
                                                                            final List<CourtDocumentSearchResponse.Material> materials) {
        final CourtDocumentSearchResponse.DocumentIndex index = mock(CourtDocumentSearchResponse.DocumentIndex.class);
        final CourtDocumentSearchResponse.Document doc = mock(CourtDocumentSearchResponse.Document.class);

        when(index.document()).thenReturn(doc);
        when(index.caseIds()).thenReturn(List.of("case1"));

        when(doc.documentTypeId()).thenReturn(docType);
        when(doc.name()).thenReturn(name);
        when(doc.materials()).thenReturn(materials);
        when(doc.documentTypeDescription()).thenReturn("desc");
        when(doc.courtDocumentId()).thenReturn("courtDocId");

        return index;
    }

    private CourtDocumentSearchResponse.Material mockMaterial(final ZonedDateTime time) {
        final CourtDocumentSearchResponse.Material material = mock(CourtDocumentSearchResponse.Material.class);
        when(material.uploadDateTime()).thenReturn(time);
        when(material.id()).thenReturn("mat-" + (time != null ? time.toString() : "null"));
        return material;
    }
}
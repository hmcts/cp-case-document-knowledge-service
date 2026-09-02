package uk.gov.hmcts.cp.cdk.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.LatestMaterialInfo;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.repo.DocumentIdResolver;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("IdpcAvailabilityService tests")
class IdpcAvailabilityServiceTest {

    public static final int EXPECTED_SIZE = 50;

    @Mock
    private ProgressionClient progressionClient;
    @Mock
    private DocumentIdResolver documentIdResolver;
    @Mock
    private CaseDocumentRepository caseDocumentRepository;

    @Captor
    private ArgumentCaptor<CaseDocument> caseDocumentCaptor;

    private IdpcAvailabilityService service;
    private UUID caseId;
    private String userId;

    @BeforeEach
    void setUp() {
        service = new IdpcAvailabilityService(progressionClient, documentIdResolver, caseDocumentRepository);

        caseId = UUID.randomUUID();
        userId = "cppuid-123";
    }

    @Test
    @DisplayName("Returns empty list when there are no materials")
    void returnsEmpty_whenNoMaterials() {
        when(progressionClient.getCourtDocumentsForAllDefendants(any(), any()))
                .thenReturn(List.of());

        final List<NewIdpcDocument> result = service.retrieveDocuments(caseId, userId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Skips defendants whose document already exists")
    void skipsExistingDocuments() {
        UUID materialId = UUID.randomUUID();
        UUID defendantId = UUID.randomUUID();

        LatestMaterialInfo info = new LatestMaterialInfo(
                List.of(caseId.toString()),
                "doc-type",
                "desc",
                materialId.toString(),
                "Material",
                ZonedDateTime.now(),
                UUID.randomUUID().toString(),
                defendantId.toString()
        );

        when(progressionClient.getCourtDocumentsForAllDefendants(any(), any()))
                .thenReturn(List.of(info));
        when(documentIdResolver.resolveExistingDocIdForDefendant(any(), any(), any()))
                .thenReturn(Optional.of(UUID.randomUUID()));

        final List<NewIdpcDocument> result = service.retrieveDocuments(caseId, userId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Returns a document per new defendant material, flagging the most recently uploaded as latest")
    void returnsNewDocuments_forMultipleDefendants() {
        UUID materialId = UUID.randomUUID();
        UUID def1 = UUID.randomUUID();
        UUID def2 = UUID.randomUUID();

        LatestMaterialInfo m1 = new LatestMaterialInfo(
                List.of(caseId.toString()), "doc", "desc",
                materialId.toString(), "Material1",
                ZonedDateTime.now().minusMinutes(1),
                UUID.randomUUID().toString(),
                def1.toString()
        );

        LatestMaterialInfo m2 = new LatestMaterialInfo(
                List.of(caseId.toString()), "doc", "desc",
                materialId.toString(), "Material2",
                ZonedDateTime.now(),
                UUID.randomUUID().toString(),
                def2.toString()
        );

        when(progressionClient.getCourtDocumentsForAllDefendants(any(), any()))
                .thenReturn(List.of(m1, m2));
        when(documentIdResolver.resolveExistingDocIdForDefendant(any(), any(), any()))
                .thenReturn(Optional.empty());

        final List<NewIdpcDocument> result = service.retrieveDocuments(caseId, userId);

        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(doc -> assertThat(doc.docId()).isNotBlank());
        assertThat(result.stream().filter(NewIdpcDocument::latestDefendant)).hasSize(1);
        assertThat(result.stream().filter(NewIdpcDocument::latestDefendant).findFirst().orElseThrow().defendantId())
                .isEqualTo(def2.toString());
    }

    @Test
    @DisplayName("Leaves rag_document_reference null when persisting a new WAITING_FOR_UPLOAD document")
    void shouldLeaveRagDocumentReferenceNull_whenPersistingWaitingForUploadRow() {
        UUID materialId = UUID.randomUUID();
        UUID defendantId = UUID.randomUUID();

        LatestMaterialInfo info = new LatestMaterialInfo(
                List.of(caseId.toString()), "doc", "desc",
                materialId.toString(), "Material",
                ZonedDateTime.now(),
                UUID.randomUUID().toString(),
                defendantId.toString()
        );

        when(progressionClient.getCourtDocumentsForAllDefendants(any(), any()))
                .thenReturn(List.of(info));
        when(documentIdResolver.resolveExistingDocIdForDefendant(any(), any(), any()))
                .thenReturn(Optional.empty());

        final List<NewIdpcDocument> result = service.retrieveDocuments(caseId, userId);

        assertThat(result).hasSize(1);
        verify(caseDocumentRepository).saveAndFlush(caseDocumentCaptor.capture());

        final CaseDocument saved = caseDocumentCaptor.getValue();
        assertThat(saved.getIngestionPhase()).isEqualTo(DocumentIngestionPhase.WAITING_FOR_UPLOAD);
        assertThat(saved.getRagDocumentReference()).isNull();
    }

    @Test
    @DisplayName("Truncates material names longer than 50 characters, preserving .pdf extension")
    void truncatesMaterialName_preservingPdfExtension() {
        String materialName = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.pdf";

        UUID materialId = UUID.randomUUID();
        UUID defendantId = UUID.randomUUID();

        LatestMaterialInfo info = new LatestMaterialInfo(
                List.of(caseId.toString()), "doc", "desc",
                materialId.toString(), materialName,
                ZonedDateTime.now(),
                UUID.randomUUID().toString(),
                defendantId.toString()
        );

        when(progressionClient.getCourtDocumentsForAllDefendants(any(), any()))
                .thenReturn(List.of(info));
        when(documentIdResolver.resolveExistingDocIdForDefendant(any(), any(), any()))
                .thenReturn(Optional.empty());

        final List<NewIdpcDocument> result = service.retrieveDocuments(caseId, userId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().materialName())
                .endsWith(".pdf")
                .hasSize(EXPECTED_SIZE);
    }

    @Test
    @DisplayName("Does not truncate material names of exactly 50 characters")
    void doesNotTruncate_whenExactly50Characters() {
        String materialName = "12345678901234567890123456789012345678901234567890"; // 50 chars

        UUID materialId = UUID.randomUUID();
        UUID defendantId = UUID.randomUUID();

        LatestMaterialInfo info = new LatestMaterialInfo(
                List.of(caseId.toString()), "doc", "desc",
                materialId.toString(), materialName,
                ZonedDateTime.now(),
                UUID.randomUUID().toString(),
                defendantId.toString()
        );

        when(progressionClient.getCourtDocumentsForAllDefendants(any(), any()))
                .thenReturn(List.of(info));
        when(documentIdResolver.resolveExistingDocIdForDefendant(any(), any(), any()))
                .thenReturn(Optional.empty());

        final List<NewIdpcDocument> result = service.retrieveDocuments(caseId, userId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().materialName()).isEqualTo(materialName);
    }
}

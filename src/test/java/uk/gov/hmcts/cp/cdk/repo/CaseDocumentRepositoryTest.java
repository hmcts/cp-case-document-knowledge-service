package uk.gov.hmcts.cp.cdk.repo;

import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase.EXCEEDED_FILE_SIZE_LIMIT;
import static uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase.FAILED;
import static uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase.INGESTED;
import static uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase.INGESTING;
import static uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase.NOT_FOUND;
import static uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase.UPLOADED;
import static uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase.UPLOADING;
import static uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase.WAITING_FOR_UPLOAD;

import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CaseDocumentRepositoryTest {

    @jakarta.annotation.Resource
    private CaseDocumentRepository repository;

    @jakarta.annotation.Resource
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager em;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("cdk")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @Test
    @DisplayName("Should return distinct doc_ids for matching caseId, defendantId and INGESTED phase")
    void findSupersededDocuments_success() {
        final UUID caseId = randomUUID();
        final UUID defendantId = randomUUID();
        final UUID docId1 = randomUUID();
        final UUID docId2 = randomUUID();

        // matching records
        persist(docId1, caseId, defendantId, INGESTED);
        persist(docId2, caseId, defendantId, INGESTED);

        // non-matching records
        persist(randomUUID(), caseId, defendantId, UPLOADED);
        persist(randomUUID(), defendantId, randomUUID(), INGESTED);

        final List<UUID> result = repository.findSupersededDocuments(caseId, defendantId);

        assertThat(result).hasSize(2).containsExactlyInAnyOrder(docId1, docId2);
    }

    @Test
    @DisplayName("Should return empty list when no records match")
    void findSupersededDocuments_noMatch() {
        final UUID caseId = randomUUID();
        final UUID defendantId = randomUUID();

        persist(randomUUID(), randomUUID(), randomUUID(), INGESTED);

        final List<UUID> result = repository.findSupersededDocuments(caseId, defendantId);

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnSupersededDocumentsByCaseId() {
        final UUID caseId = UUID.randomUUID();
        final UUID docId1 = UUID.randomUUID();
        final UUID docId2 = UUID.randomUUID();

        persist(docId1, caseId, null, INGESTED);
        persist(docId2, caseId, null, INGESTED);

        final List<UUID> result = repository.findSupersededDocuments(caseId);

        assertThat(result).hasSize(2).containsExactlyInAnyOrder(docId1, docId2);
    }

    @Test
    void shouldReturnSupersededDocumentsByCaseIdAndExcludeRowsWithDefendantId() {
        final UUID caseId = UUID.randomUUID();
        persist(randomUUID(), caseId, randomUUID(), INGESTED);

        final List<UUID> result = repository.findSupersededDocuments(caseId);

        assertThat(result).isEmpty();
    }


    @Test
    @DisplayName("Should leave rag_document_reference NULL when a row is inserted without the column")
    void shouldLeaveRagDocumentReferenceNull_whenRowInsertedWithoutTheColumn() {
        final UUID docId = randomUUID();
        persist(docId, randomUUID(), randomUUID(), UPLOADED);

        final String value = jdbc.queryForObject(
                "SELECT rag_document_reference FROM case_documents WHERE doc_id = ?", String.class, docId);

        assertThat(value).isNull();
    }

    @Test
    @DisplayName("Should round-trip rag_document_reference verbatim when saved through the entity")
    void shouldRoundTripRagDocumentReference_whenSavedThroughTheEntity() {
        final String mixedCaseReference = "B181B0b0-628e-4491-9CCD-2ea93d70cb2f";

        final CaseDocument entity = new CaseDocument();
        entity.setDocId(randomUUID());
        entity.setCaseId(randomUUID());
        entity.setMaterialId(randomUUID());
        entity.setDocName("doc-name");
        entity.setBlobUri("http://blob_uri");
        entity.setUploadedAt(OffsetDateTime.now());
        entity.setIngestionPhase(UPLOADED);
        entity.setIngestionPhaseAt(OffsetDateTime.now());
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setRagDocumentReference(mixedCaseReference);

        repository.saveAndFlush(entity);
        em.clear();

        final CaseDocument reloaded = repository.findById(entity.getDocId()).orElseThrow();

        assertThat(reloaded.getRagDocumentReference()).isEqualTo(mixedCaseReference);
    }

    private void persist(final UUID docId, final UUID caseId, final UUID defendantId, final DocumentIngestionPhase phase) {
        jdbc.update("""
                    INSERT INTO case_documents
                    (doc_id, case_id, material_id, source, doc_name,
                     blob_uri, content_type, size_bytes, sha256_hex,
                     uploaded_at, ingestion_phase, ingestion_phase_at, defendant_id, courtdoc_id, created_at)
                    VALUES(?, ?, ?, 'IDPC', '', 'http://blob_uri', '', 0, null, now(), ?::document_ingestion_phase_enum, now(), ?, ?, now())
                """, docId, caseId, randomUUID(), phase.name(), defendantId, randomUUID());
    }

    private void persistWithPhaseAt(final DocumentIngestionPhase phase, final OffsetDateTime ingestionPhaseAt) {
        jdbc.update("""
                    INSERT INTO case_documents
                    (doc_id, case_id, material_id, source, doc_name,
                     blob_uri, content_type, size_bytes, sha256_hex,
                     uploaded_at, ingestion_phase, ingestion_phase_at, defendant_id, courtdoc_id, created_at)
                    VALUES(?, ?, ?, 'IDPC', '', 'http://blob_uri', '', 0, null, now(), ?::document_ingestion_phase_enum, ?, ?, ?, now())
                """, randomUUID(), randomUUID(), randomUUID(), phase.name(), ingestionPhaseAt, randomUUID(), randomUUID());
    }

    @Test
    @DisplayName("countStalledByPhase returns one row per monitored phase, counting only rows older than the cutoff")
    void countStalledByPhase_returnsOnlyMonitoredPhasesOlderThanCutoff() {
        final OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(30);
        final OffsetDateTime old = cutoff.minusMinutes(5);
        final OffsetDateTime fresh = cutoff.plusMinutes(5);

        // monitored phases, older than cutoff -> counted, with DISTINCT counts per phase
        // (Scenario 3.2) so a wrong-phase attribution (e.g. UPLOADING rows counted under
        // UPLOADED) cannot pass unnoticed.
        persistWithPhaseAt(WAITING_FOR_UPLOAD, old);
        persistWithPhaseAt(WAITING_FOR_UPLOAD, old);
        persistWithPhaseAt(WAITING_FOR_UPLOAD, old);
        persistWithPhaseAt(UPLOADING, old);
        persistWithPhaseAt(UPLOADED, old);
        persistWithPhaseAt(UPLOADED, old);
        persistWithPhaseAt(UPLOADED, old);
        persistWithPhaseAt(UPLOADED, old);
        persistWithPhaseAt(UPLOADED, old);
        persistWithPhaseAt(INGESTING, old);
        persistWithPhaseAt(INGESTING, old);

        // monitored phases, newer than cutoff -> excluded (AC-002)
        persistWithPhaseAt(WAITING_FOR_UPLOAD, fresh);
        persistWithPhaseAt(UPLOADED, fresh);

        // terminal / non-monitored phases, any age -> excluded (AC-002)
        persistWithPhaseAt(INGESTED, old);
        persistWithPhaseAt(FAILED, old);
        persistWithPhaseAt(EXCEEDED_FILE_SIZE_LIMIT, old);
        persistWithPhaseAt(NOT_FOUND, old);

        final List<PhaseCount> result = repository.countStalledByPhase(cutoff);
        final Map<String, Long> byPhase = result.stream()
                .collect(Collectors.toMap(PhaseCount::getPhase, PhaseCount::getTotal));

        assertThat(byPhase)
                .containsEntry("WAITING_FOR_UPLOAD", 3L)
                .containsEntry("UPLOADING", 1L)
                .containsEntry("UPLOADED", 5L)
                .containsEntry("INGESTING", 2L)
                .doesNotContainKey("INGESTED")
                .doesNotContainKey("FAILED")
                .doesNotContainKey("EXCEEDED_FILE_SIZE_LIMIT")
                .doesNotContainKey("NOT_FOUND");
    }

    @Test
    @DisplayName("countStalledByPhase excludes a row exactly at the cutoff — strict <, not <= (Scenario 3.3 boundary)")
    void countStalledByPhase_shouldExcludeRowExactlyAtCutoff() {
        final OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(30);
        persistWithPhaseAt(WAITING_FOR_UPLOAD, cutoff);

        final List<PhaseCount> result = repository.countStalledByPhase(cutoff);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("countStalledByPhase returns no rows when nothing is stalled")
    void countStalledByPhase_returnsEmpty_whenNothingStalled() {
        final OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(30);
        persistWithPhaseAt(WAITING_FOR_UPLOAD, OffsetDateTime.now());

        final List<PhaseCount> result = repository.countStalledByPhase(cutoff);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("countStalledByPhase carries a 5-second statement timeout (AC-006) and no @Transactional "
            + "(Scenario 3.8 — the property Story 4's per-aggregate degradation depends on)")
    void countStalledByPhase_carriesStatementTimeoutAndNoSharedTransaction() throws NoSuchMethodException {
        final Method method = CaseDocumentRepository.class.getMethod("countStalledByPhase", OffsetDateTime.class);

        final QueryHints hints = method.getAnnotation(QueryHints.class);
        assertThat(hints).isNotNull();
        assertThat(hints.value()).hasSize(1);
        assertThat(hints.value()[0].name()).isEqualTo("jakarta.persistence.query.timeout");
        assertThat(hints.value()[0].value()).isEqualTo("5000");

        assertThat(method.getAnnotation(Transactional.class))
                .as("must not share a transaction with the other aggregate — a shared transaction "
                        + "would be marked rollback-only by the first failure and take the other "
                        + "aggregate down with it")
                .isNull();
    }
}
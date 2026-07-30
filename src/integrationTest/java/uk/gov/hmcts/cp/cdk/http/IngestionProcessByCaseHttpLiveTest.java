package uk.gov.hmcts.cp.cdk.http;

import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.cdk.stub.DocumentIngestionInitiationApiStub.stubInitiateDocumentUpload;

import uk.gov.hmcts.cp.cdk.testsupport.AbstractHttpLiveTest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * End-to-end tests for the manual ("Process IDPC") ingestion endpoint:
 * POST /ingestions/start-by-case
 *
 * <p>Covers all response phases:
 * <ul>
 *   <li>STARTED — a newer IDPC exists, so the remaining workflow is dispatched;</li>
 *   <li>STARTED (no newer IDPC, answer still pending) — the IDPC has already been ingested but no
 *       answer has been generated yet for the case, so nothing new is dispatched but the phase is
 *       still STARTED, not NOT_REQUIRED;</li>
 *   <li>NOT_REQUIRED — the IDPC has already been ingested <b>and</b> an answer already exists
 *       (seeded), so nothing is dispatched;</li>
 *   <li>FAILED — the downstream court-document-search call errors (case-specific 500 stub).</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestionProcessByCaseHttpLiveTest extends AbstractHttpLiveTest {

    private static final MediaType VND_BY_CASE =
            MediaType.valueOf("application/vnd.casedocumentknowledge-service.ingestion-process-by-case+json");

    private static final String CJSCPPUID = "CJSCPPUID";
    private static final String CPPUID_VALUE = "a085e359-6069-4694-8820-7810e7dfe762";

    // Values as returned by wiremock/__files/court_document_search_response.json
    private static final UUID STUB_MATERIAL_ID = UUID.fromString("d2d37964-8139-4713-97d2-62dd2d1419f4");
    private static final UUID STUB_DEFENDANT_ID = UUID.fromString("3bdcb43e-01d3-4c43-a530-b7aa55b2a3bb");
    private static final UUID STUB_COURTDOC_ID = UUID.fromString("89b05baa-75e0-4a87-b144-9d824ec9e61a");

    // Reserved case id wired to a 500 court-document-search response — see court_document_search_failed.json
    private static final UUID FAILING_CASE_ID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    private HttpEntity<String> requestFor(final UUID caseId) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(VND_BY_CASE);
        headers.setAccept(List.of(VND_BY_CASE));
        headers.set(CJSCPPUID, CPPUID_VALUE);
        return new HttpEntity<>("{ \"caseId\": \"%s\" }".formatted(caseId), headers);
    }

    private ResponseEntity<String> postByCase(final UUID caseId) {
        return http.exchange(
                baseUrl + "/ingestions/start-by-case",
                HttpMethod.POST,
                requestFor(caseId),
                String.class
        );
    }

    @Test
    @DisplayName("Returns STARTED when a newer IDPC version is available")
    void startByCase_returnsStarted() {
        configureFor("localhost", 8089);
        stubInitiateDocumentUpload("documents-new", "destination.pdf", 3);

        final UUID caseId = UUID.randomUUID();

        final ResponseEntity<String> response = postByCase(caseId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"phase\":\"STARTED\"");
        assertThat(response.getBody()).contains("requestId=");
    }

    @Test
    @DisplayName("Returns STARTED (not NOT_REQUIRED) when the IDPC has already been ingested "
            + "but no answer has been generated yet")
    void startByCase_returnsStarted_whenIngestedButAnswerNotYetAvailable() throws Exception {
        final UUID caseId = UUID.randomUUID();
        seedExistingCaseDocument(caseId);

        final ResponseEntity<String> response = postByCase(caseId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"phase\":\"STARTED\"");
        assertThat(response.getBody()).contains("previous answers are still in the process of generating");
    }

    @Test
    @DisplayName("Returns NOT_REQUIRED when the IDPC has already been ingested and an answer already exists")
    void startByCase_returnsNotRequired() throws Exception {
        final UUID caseId = UUID.randomUUID();
        final UUID docId = seedExistingCaseDocument(caseId);
        seedAnswerAvailable(caseId, docId);

        final ResponseEntity<String> response = postByCase(caseId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"phase\":\"NOT_REQUIRED\"");
        assertThat(response.getBody()).contains("no newer IDPC version is available");
    }

    @Test
    @DisplayName("Returns FAILED when the downstream IDPC-availability check errors")
    void startByCase_returnsFailed() {
        final ResponseEntity<String> response = postByCase(FAILING_CASE_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"phase\":\"FAILED\"");
        assertThat(response.getBody()).contains("internal error");
    }

    /**
     * Seeds an already-ingested case document matching the material/defendant returned by the
     * court-document search stub, so the IDPC-availability check finds no newer version to ingest.
     */
    private UUID seedExistingCaseDocument(final UUID caseId) throws Exception {
        final UUID docId = UUID.randomUUID();
        final OffsetDateTime now = OffsetDateTime.now();
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "INSERT INTO case_documents "
                             + "(doc_id, case_id, material_id, source, doc_name, blob_uri, uploaded_at, "
                             + "ingestion_phase, ingestion_phase_at, defendant_id, courtdoc_id, created_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::document_ingestion_phase_enum, ?, ?, ?, ?)"
             )) {
            ps.setObject(1, docId);
            ps.setObject(2, caseId);
            ps.setObject(3, STUB_MATERIAL_ID);
            ps.setString(4, "IDPC");
            ps.setString(5, "IDPC");
            ps.setString(6, "seeded_blob_uri");
            ps.setObject(7, now);
            ps.setString(8, "WAITING_FOR_UPLOAD");
            ps.setObject(9, now);
            ps.setObject(10, STUB_DEFENDANT_ID);
            ps.setObject(11, STUB_COURTDOC_ID);
            ps.setObject(12, now);
            ps.executeUpdate();
        }
        return docId;
    }

    /**
     * Seeds a canonical query plus a {@code case_query_status} row with status
     * {@code ANSWER_AVAILABLE} against the given (latest) doc_id, so the IDPC-availability check
     * finds an answer already exists for the case's latest document.
     */
    private void seedAnswerAvailable(final UUID caseId, final UUID docId) throws Exception {
        final UUID queryId = UUID.randomUUID();
        try (Connection connection = openConnection()) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO queries (query_id, label) VALUES (?, ?)")) {
                ps.setObject(1, queryId);
                ps.setString(2, "Test query " + queryId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO case_query_status (case_id, query_id, status, doc_id) "
                            + "VALUES (?, ?, 'ANSWER_AVAILABLE'::query_lifecycle_status_enum, ?)")) {
                ps.setObject(1, caseId);
                ps.setObject(2, queryId);
                ps.setObject(3, docId);
                ps.executeUpdate();
            }
        }
    }
}

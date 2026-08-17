package uk.gov.hmcts.cp.cdk.jobmanager.caseflow;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static jakarta.json.Json.createObjectBuilder;
import static org.apache.http.HttpStatus.SC_ACCEPTED;
import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.cdk.http.AzureSasUtil.generateSasUrl;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.RETRIEVE_MATERIAL_AND_UPLOAD;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DEFENDANT_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_MATERIAL_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_MATERIAL_NAME;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.CPPUID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.REQUEST_ID;

import uk.gov.hmcts.cp.cdk.testsupport.AbstractHttpLiveTest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.json.JsonObject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that RetrieveMaterialAndUploadTask persists the RAG-issued {@code documentReference}
 * (returned by {@code POST /document-upload}) into {@code case_documents.rag_document_reference}
 * (DD-43083 / DD-43138).
 *
 * <p>RETRIEVE_MATERIAL_AND_UPLOAD is only ever dispatched internally, deep in the ingestion
 * pipeline — there is no HTTP entry point that reaches it directly. Task Manager's jobs table
 * (polled every {@code job.executor.poll-interval}) is the seam this test drives instead: seed a
 * {@code case_documents} row in phase WAITING_FOR_UPLOAD (as {@code IdpcAvailabilityService}
 * would), seed a job row addressed to RETRIEVE_MATERIAL_AND_UPLOAD with the job-data shape its
 * predecessor produces, let the live app's scheduled JobExecutor run the real task against the
 * real database, the real Azurite blob copy and the RAG {@code POST /document-upload} stub, then
 * assert the persisted column via JDBC.
 *
 * <p><b>Two-stub ordering hazard (design §Testing, "Stub caveat"):</b> {@code POST /document-upload}
 * is already answered in the shared WireMock container by a static mapping with a fixed
 * {@code documentReference} and by {@code DocumentIngestionInitiationApiStub}'s scenario stub
 * (random {@code documentReference} per call) registered by the {@code IngestionProcess*HttpLiveTest}
 * suites. Both run at the WireMock default priority (5). This test therefore registers its own
 * stub at {@code withPriority(1)} (lower number wins) matched narrowly on this test's own
 * {@code documentId} via a JSON body path, so it can only ever answer this test's own request and
 * cannot shadow (or be shadowed by) either of the other two stubs.
 */
class RetrieveMaterialAndUploadRagDocumentReferenceLiveTest extends AbstractHttpLiveTest {

    private static final String INSERT_JOB_SQL =
            "INSERT INTO jobs (job_id, assigned_task_name, assigned_task_start_time, job_data, "
                    + "priority, retry_attempts_remaining, worker_id, worker_lock_time) "
                    + "VALUES (?, ?, NOW(), ?, 10, 3, NULL, NULL)";

    private static final String CJSCPPUID_VALUE = "a085e359-6069-4694-8820-7810e7dfe762";

    @Test
    @DisplayName("Persists the RAG documentReference into case_documents.rag_document_reference "
            + "when the real upload task runs against a WAITING_FOR_UPLOAD row")
    void retrieveMaterialAndUploadTask_persistsRagDocumentReference_onCaseDocumentsRow() throws Exception {
        configureFor("localhost", 8089);

        final UUID caseId = UUID.randomUUID();
        final UUID defendantId = UUID.randomUUID();
        final UUID materialId = UUID.randomUUID();
        final UUID docId = UUID.randomUUID();
        final String expectedReference = UUID.randomUUID().toString();
        final String blobName = "rag-doc-reference-" + docId + ".pdf";

        seedWaitingForUploadCaseDocument(docId, caseId, defendantId, materialId);
        assertThat(fetchRagDocumentReference(docId))
                .as("row must start with rag_document_reference IS NULL, so the assertion below is a genuine before/after")
                .isNull();

        stubDocumentUploadForThisTestOnly(docId, blobName, expectedReference);
        seedRetrieveMaterialAndUploadJob(caseId, defendantId, materialId, docId);

        try {
            final String persisted = awaitRagDocumentReference(docId);
            assertThat(persisted).isEqualTo(expectedReference);
        } finally {
            cleanup(docId);
        }
    }

    private void stubDocumentUploadForThisTestOnly(final UUID docId, final String blobName, final String documentReference) {
        final String sasStorageUrl = generateSasUrl("documents-new", blobName);
        final JsonObject responseJson = createObjectBuilder()
                .add("storageUrl", sasStorageUrl)
                .add("documentReference", documentReference)
                .build();

        stubFor(post(urlPathEqualTo("/document-upload"))
                .atPriority(1)
                .withRequestBody(matchingJsonPath("$.documentId", equalTo(docId.toString())))
                .willReturn(aResponse()
                        .withStatus(SC_ACCEPTED)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseJson.toString())));
    }

    private void seedWaitingForUploadCaseDocument(final UUID docId, final UUID caseId, final UUID defendantId,
                                                  final UUID materialId) throws SQLException {
        final OffsetDateTime now = OffsetDateTime.now();
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "INSERT INTO case_documents "
                             + "(doc_id, case_id, material_id, source, doc_name, blob_uri, uploaded_at, "
                             + "ingestion_phase, ingestion_phase_at, defendant_id, created_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::document_ingestion_phase_enum, ?, ?, ?)"
             )) {
            ps.setObject(1, docId);
            ps.setObject(2, caseId);
            ps.setObject(3, materialId);
            ps.setString(4, "IDPC");
            ps.setString(5, "IDPC");
            ps.setString(6, "default_blob_uri");
            ps.setObject(7, now);
            ps.setString(8, "WAITING_FOR_UPLOAD");
            ps.setObject(9, now);
            ps.setObject(10, defendantId);
            ps.setObject(11, now);
            ps.executeUpdate();
        }
    }

    private void seedRetrieveMaterialAndUploadJob(final UUID caseId, final UUID defendantId, final UUID materialId,
                                                  final UUID docId) throws SQLException {
        final JsonObject jobData = createObjectBuilder()
                .add(CTX_CASE_ID_KEY, caseId.toString())
                .add(CTX_DEFENDANT_ID_KEY, defendantId.toString())
                .add(CTX_MATERIAL_ID_KEY, materialId.toString())
                .add(CTX_DOC_ID_KEY, docId.toString())
                .add(CTX_MATERIAL_NAME, "Material A.pdf")
                .add(CPPUID, CJSCPPUID_VALUE)
                .add(REQUEST_ID, "req-" + docId)
                .build();

        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement(INSERT_JOB_SQL)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, RETRIEVE_MATERIAL_AND_UPLOAD);
            ps.setString(3, jobData.toString());
            ps.executeUpdate();
        }
    }

    private String awaitRagDocumentReference(final UUID docId) {
        final AtomicReference<String> found = new AtomicReference<>();
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> {
                    found.set(fetchRagDocumentReference(docId));
                    return found.get() != null;
                });
        return found.get();
    }

    private String fetchRagDocumentReference(final UUID docId) throws SQLException {
        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT rag_document_reference FROM case_documents WHERE doc_id = ?")) {
            ps.setObject(1, docId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("rag_document_reference");
                }
                return null;
            }
        }
    }

    private void cleanup(final UUID docId) throws SQLException {
        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM case_documents WHERE doc_id = ?")) {
            ps.setObject(1, docId);
            ps.executeUpdate();
        }
    }
}

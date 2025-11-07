package uk.gov.hmcts.cp.cdk.http;

import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class DocumentHttpLiveTest {
    public final MediaType VND_TYPE_MATERIAL = MediaType.valueOf("application/vnd.casedocumentknowledge-service.document-content+json");

    private final String baseUrl = System.getProperty(
            "app.baseUrl",
            "http://localhost:8082/casedocumentknowledge-service"
    );

    private final String jdbcUrl  = System.getProperty("it.db.url",  "jdbc:postgresql://localhost:55432/casedocumentknowledgeDatabase");
    private final String jdbcUser = System.getProperty("it.db.user", "casedocumentknowledge");
    private final String jdbcPass = System.getProperty("it.db.pass", "casedocumentknowledge");

    private final RestTemplate http = new RestTemplate();
    private UUID queryId;
    private static final String HEADER_NAME = "CJSCPPUID";
    private static final String HEADER_VALUE = "u-123";


    @Test
    void getMaterialContentUrl_returns_expected_url() throws Exception {

        UUID docId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();

        try (Connection c = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPass);
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO case_documents (doc_id, case_id, material_id, source,doc_name ,blob_uri,uploaded_at) " +
                             "VALUES (?, ?, ?, ?, ?,?,?)"
             )) {
            ps.setObject(1, docId);
            ps.setObject(2, UUID.randomUUID());
            ps.setObject(3, materialId);
            ps.setString(4, "IDPC");
            ps.setString(5, "docnmae");
            ps.setString(6, "IDPC");
            ps.setObject(7, OffsetDateTime.now());
            ps.executeUpdate();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(VND_TYPE_MATERIAL);
        headers.set(HEADER_NAME, HEADER_VALUE); // add required header

        // --- Act: perform GET request to the endpoint ---
        ResponseEntity<String> res = http.exchange(
                baseUrl + "/document/" + docId + "/content",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );


        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("\"url\"");
        assertThat(res.getBody()).contains("http"); // the URL should look like a URI


        try (Connection c = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPass);
             PreparedStatement del = c.prepareStatement("DELETE FROM case_documents WHERE doc_id = ?")) {
            del.setObject(1, docId);
            del.executeUpdate();
        }
    }
}

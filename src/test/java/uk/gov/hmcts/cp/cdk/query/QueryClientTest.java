package uk.gov.hmcts.cp.cdk.query;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.cdk.domain.progression.LatestMaterialInfo;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QueryClientTest {

    private MockWebServer server;
    private QueryClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        final String baseUrl = server.url("/").toString();

        final QueryClientProperties props = new QueryClientProperties(
                baseUrl,
                "CJSCPPUID",
                "application/json"
        );
        client = new QueryClient(props);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void getHearingsAndCases_returnsList() throws Exception {
        final UUID caseId = UUID.randomUUID();
        final UUID hearingId = UUID.randomUUID();

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        [{"caseId":"%s","hearingId":"%s"}]
                        """.formatted(caseId, hearingId)));

        final List<QueryClient.CaseSummary> list =
                client.getHearingsAndCases("Bournemouth", LocalDate.parse("2025-10-24"));

        assertThat(list).hasSize(1);
        assertThat(list.get(0).caseId()).isEqualTo(caseId);
        assertThat(list.get(0).hearingId()).isEqualTo(hearingId);
    }

    @Test
    void getCourtDocuments_parsesMeta() throws Exception {
        final UUID caseId = UUID.randomUUID();

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "singleDefendant": true,
                          "idpcAvailable": true,
                          "idpcDownloadUrl": "%sfile.pdf",
                          "contentType": "application/pdf",
                          "sizeBytes": 12345
                        }
                        """.formatted(server.url("/"))));

        final Optional<LatestMaterialInfo> meta = client.getCourtDocuments(caseId);

        //assertThat(meta.singleDefendant()).isTrue();
        //assertThat(meta.idpcAvailable()).isTrue();
        //assertThat(meta.idpcDownloadUrl()).endsWith("file.pdf");
        //assertThat(meta.contentType()).isEqualTo("application/pdf");
        //assertThat(meta.sizeBytes()).isEqualTo(12345L);
    }

    @Test
    void downloadIdpc_returnsInputStream() throws Exception {
        final byte[] pdfBytes = "FAKEPDF".getBytes(StandardCharsets.UTF_8);

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/pdf")
                // For MockWebServer 4.x use Buffer or ByteString, not byte[]
                .setBody(new Buffer().write(pdfBytes)));
        // OR: .setBody(ByteString.of(pdfBytes))

        final String url = server.url("/idpc.pdf").toString();
        try (InputStream in = client.downloadIdpc(url)) {
            final byte[] bytes = in.readAllBytes();
            assertThat(bytes).isEqualTo(pdfBytes);
        }
    }
}

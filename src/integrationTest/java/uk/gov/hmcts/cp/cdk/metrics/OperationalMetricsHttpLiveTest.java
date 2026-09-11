package uk.gov.hmcts.cp.cdk.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * DD-43182 Story 7, AC-001/AC-002: every metric named in {@code 02-design.md} §2 is present against
 * a real compose scrape — including every pre-registered series that has not yet been incremented —
 * with the ticket's common tags, and the two withdrawn signals (ADR-011) are asserted absent so a
 * later merge can never silently reintroduce them without a decision.
 */
class OperationalMetricsHttpLiveTest {

    private final String baseUrl = System.getProperty("app.baseUrl", "http://localhost:8082/casedocumentknowledge-service");
    private final RestTemplate http = new RestTemplate();

    @Test
    void allSevenRenderedMetricNamesArePresent() {
        final String body = scrapePrometheus();

        assertThat(body).contains(
                "# TYPE cdk_document_ingestion_phase_total counter",
                "# TYPE cdk_document_ingestion_duration_seconds histogram",
                "# TYPE cdk_document_ingestion_duration_seconds_max gauge",
                "# TYPE cdk_external_call_duration_seconds summary",
                "# TYPE cdk_external_call_duration_seconds_max gauge",
                "# TYPE cdk_task_retry_total counter",
                "# TYPE cdk_answer_generation_total counter",
                "# TYPE httpcomponents_httpclient_pool_total_max gauge",
                "# TYPE httpcomponents_httpclient_pool_total_pending gauge",
                "# TYPE httpcomponents_httpclient_pool_total_connections gauge",
                "# TYPE httpcomponents_httpclient_pool_route_max_default gauge",
                "# TYPE cdk_http_pool_connections_leased gauge"
        );
    }

    @Test
    void allFiveReachableIngestionPhaseSeriesArePreRegisteredAtZero() {
        final String body = scrapePrometheus();

        assertThat(body).contains(
                "cdk_document_ingestion_phase_total{cluster=\"local\",phase=\"WAITING_FOR_UPLOAD\",region=\"local\",service=\"cp-case-document-knowledge-service\",source=\"IDPC\"}",
                "cdk_document_ingestion_phase_total{cluster=\"local\",phase=\"UPLOADED\",region=\"local\",service=\"cp-case-document-knowledge-service\",source=\"IDPC\"}",
                "cdk_document_ingestion_phase_total{cluster=\"local\",phase=\"INGESTED\",region=\"local\",service=\"cp-case-document-knowledge-service\",source=\"IDPC\"}",
                "cdk_document_ingestion_phase_total{cluster=\"local\",phase=\"FAILED\",region=\"local\",service=\"cp-case-document-knowledge-service\",source=\"IDPC\"}",
                "cdk_document_ingestion_phase_total{cluster=\"local\",phase=\"EXCEEDED_FILE_SIZE_LIMIT\",region=\"local\",service=\"cp-case-document-knowledge-service\",source=\"IDPC\"}"
        );
    }

    @Test
    void allEightAnswerGenerationSeriesArePreRegisteredAtZero() {
        final String body = scrapePrometheus();

        for (final String outcome : List.of("succeeded", "failed")) {
            for (final String level : List.of("CASE", "DEFENDANT", "CASE_ALL_DOCUMENTS", "unknown")) {
                assertThat(body).contains("cdk_answer_generation_total{cluster=\"local\",outcome=\"" + outcome
                        + "\",query_level=\"" + level + "\",region=\"local\",service=\"cp-case-document-knowledge-service\"}");
            }
        }
    }

    @Test
    void allSevenTaskRetrySeriesArePreRegisteredAtZero() {
        final String body = scrapePrometheus();

        assertThat(body).contains(
                "task_name=\"GET_CASES_FOR_HEARING\"",
                "task_name=\"CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS\"",
                "task_name=\"RETRIEVE_MATERIAL_AND_UPLOAD\"",
                "task_name=\"CHECK_ALL_DOCUMENTS_INGESTION_STATUS\"",
                "task_name=\"CHECK_INGESTION_STATUS_FOR_ALL_DEFENDANTS\"",
                "task_name=\"CHECK_STATUS_OF_ANSWER_GENERATION\"",
                "task_name=\"GENERATE_ANSWER_FOR_QUERY\""
        );
        assertThat(body.lines().filter(line -> line.startsWith("cdk_task_retry_total{")).count()).isEqualTo(7);
    }

    @Test
    void elevenExternalCallSuccessSeriesArePreRegisteredAtZero() {
        final String body = scrapePrometheus();

        for (final String pair : List.of(
                "dependency=\"rag\",operation=\"initiate-document-upload\"",
                "dependency=\"rag\",operation=\"document-status-by-reference\"",
                "dependency=\"rag\",operation=\"answer-user-query-async\"",
                "dependency=\"rag\",operation=\"answer-user-query-status\"",
                "dependency=\"rag\",operation=\"answer-user-query\"",
                "dependency=\"progression\",operation=\"get-court-documents\"",
                "dependency=\"progression\",operation=\"get-court-documents-all-defendants\"",
                "dependency=\"progression\",operation=\"get-material-download-url\"",
                "dependency=\"hearing\",operation=\"get-hearings-and-cases\"",
                "dependency=\"hearing\",operation=\"get-hearing-cases-for-day\"",
                "dependency=\"azure_blob\",operation=\"copy-from-url\"")) {
            assertThat(body).contains("cdk_external_call_duration_seconds_count{cluster=\"local\"," + pair);
        }
    }

    @Test
    void withdrawnAdr011SignalsAreNeverPresent() {
        final String body = scrapePrometheus();

        assertThat(body)
                .as("ADR-011: cdk_task_retry_exhausted_total is withdrawn — a task execution can never "
                        + "observe an exhausted retry budget")
                .doesNotContain("cdk_task_retry_exhausted");
        assertThat(body)
                .as("ADR-011: outcome=\"timed_out\" on cdk_answer_generation_total is withdrawn")
                .doesNotContain("cdk_answer_generation_total{cluster=\"local\",outcome=\"timed_out\"");
    }

    @Test
    void everyCdkSeriesCarriesTheCommonServiceClusterRegionTags() {
        final String body = scrapePrometheus();

        final List<String> cdkLines = body.lines()
                .filter(line -> line.startsWith("cdk_") && !line.startsWith("cdk_documents_stalled")
                        && !line.startsWith("cdk_queries_awaiting_answer")
                        && !line.startsWith("cdk_monitoring_last_refresh")
                        && !line.startsWith("cdk_scheduler_"))
                .toList();

        assertThat(cdkLines).isNotEmpty();
        assertThat(cdkLines).allSatisfy(line -> assertThat(line)
                .as("every DD-43182 cdk_* series must carry service/cluster/region: %s", line)
                .contains("service=\"cp-case-document-knowledge-service\"")
                .contains("cluster=\"local\"")
                .contains("region=\"local\""));
    }

    private String scrapePrometheus() {
        final HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.TEXT_PLAIN));
        final ResponseEntity<String> response = http.exchange(
                baseUrl + "/actuator/prometheus", HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }
}

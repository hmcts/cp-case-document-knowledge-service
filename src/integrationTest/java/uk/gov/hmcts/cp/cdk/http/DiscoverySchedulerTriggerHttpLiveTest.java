package uk.gov.hmcts.cp.cdk.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static uk.gov.hmcts.cp.cdk.util.UtilConstants.CJSCPPUID;
import static uk.gov.hmcts.cp.cdk.util.UtilConstants.USER_WITH_SYSTEM_USERS_GROUPS;

import uk.gov.hmcts.cp.cdk.testsupport.AbstractHttpLiveTest;
import uk.gov.hmcts.cp.cdk.util.BrokerUtil;
import uk.gov.hmcts.cp.cdk.stub.HearingQueryApiStub;
import uk.gov.hmcts.cp.openapi.model.cdk.DiscoveryOperation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Functional/dispatch coverage for POST /discovery-scheduler/trigger (Story 3, DD-43062).
 * ACL is Story 2's DiscoverySchedulerTriggerAclHttpLiveTest -- not repeated here.
 *
 * Only trigger_dispatchesAndReturns202_withoutBlocking is genuinely red right now: the
 * generated DiscoverySchedulerApi interface already carries @RequestMapping/@Valid on
 * triggerDiscovery, so Spring's method-not-allowed and request-validation handling (and the
 * automatic cp-audit-filter-springboot auditing) already work off that contract alone, before
 * any of this story's code exists. Only the real 202 dispatch requires the new implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiscoverySchedulerTriggerHttpLiveTest extends AbstractHttpLiveTest {

    private static final MediaType VND_TYPE_JSON = MediaType.valueOf(
            "application/vnd.casedocumentknowledge-service.discovery-scheduler-trigger+json");
    private static final String TRIGGER_PATH = "/discovery-scheduler/trigger";

    private HttpHeaders vendorHeaders() {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(VND_TYPE_JSON);
        headers.setAccept(List.of(VND_TYPE_JSON));
        headers.set(CJSCPPUID, USER_WITH_SYSTEM_USERS_GROUPS);
        return headers;
    }

    @ParameterizedTest
    @EnumSource(DiscoveryOperation.class)
    @DisplayName("Authorised trigger dispatches and returns 202 without blocking (AC-001, AC-004-006, AC-017)")
    void trigger_dispatchesAndReturns202_withoutBlocking(final DiscoveryOperation operation) {
        final String correlationId = UUID.randomUUID().toString();
        final HttpHeaders headers = vendorHeaders();
        headers.set("X-Correlation-Id", correlationId);

        final String body = """
                {
                  "discoveryOperation": "%s"
                }
                """.formatted(operation);

        final Instant before = Instant.now();
        final ResponseEntity<String> response = http.exchange(
                baseUrl + TRIGGER_PATH, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        final Duration elapsed = Duration.between(before, Instant.now());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(VND_TYPE_JSON);
        assertThat(response.getBody()).contains("\"discoveryOperation\":\"" + operation + "\"");
        assertThat(response.getBody()).contains("\"correlationId\":\"" + correlationId + "\"");
        assertThat(elapsed)
                .as("202 must return without waiting for the dispatched run to complete")
                .isLessThan(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("Wrong HTTP method is rejected (AC-002)")
    void wrongHttpMethod_isRejected() {
        try {
            http.exchange(baseUrl + TRIGGER_PATH, HttpMethod.GET, new HttpEntity<>(vendorHeaders()), String.class);
            fail("Expected GET to be rejected");
        } catch (final HttpClientErrorException.MethodNotAllowed ex) {
            assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        }
    }

    @Test
    @DisplayName("Missing discoveryOperation field is rejected with 400 (AC-012, AC-015)")
    void missingField_isRejected() {
        assertBadRequest("{}");
    }

    @Test
    @DisplayName("Unrecognised discoveryOperation value is rejected with 400 (AC-013, AC-015)")
    void unrecognisedValue_isRejected() {
        assertBadRequest("""
                {
                  "discoveryOperation": "NOT_A_REAL_OPERATION"
                }
                """);
    }

    @Test
    @DisplayName("Malformed JSON body is rejected with 400 and the fixed message (AC-014, AC-015)")
    void malformedJson_isRejected() {
        try {
            http.exchange(baseUrl + TRIGGER_PATH, HttpMethod.POST,
                    new HttpEntity<>("{ not-json ", vendorHeaders()), String.class);
            fail("Expected malformed JSON to be rejected");
        } catch (final HttpClientErrorException.BadRequest ex) {
            assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(ex.getResponseBodyAsString()).contains("Malformed request body");
        }
    }

    private void assertBadRequest(final String body) {
        try {
            http.exchange(baseUrl + TRIGGER_PATH, HttpMethod.POST,
                    new HttpEntity<>(body, vendorHeaders()), String.class);
            fail("Expected request to be rejected with 400");
        } catch (final HttpClientErrorException.BadRequest ex) {
            assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(ex.getResponseBodyAsString()).doesNotContain(USER_WITH_SYSTEM_USERS_GROUPS);
        }
    }

    @Test
    @DisplayName("A successful trigger emits an audit event identifying the action (AC-023)")
    void trigger_emitsAuditEvent() throws Exception {
        try (BrokerUtil broker = new BrokerUtil()) {
            final ResponseEntity<String> response = http.exchange(
                    baseUrl + TRIGGER_PATH, HttpMethod.POST,
                    new HttpEntity<>("""
                            {
                              "discoveryOperation": "INTRADAY"
                            }
                            """, vendorHeaders()),
                    String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

            final String auditMessage = broker.getMessageMatching(json ->
                    json.toString().contains("discovery-scheduler-trigger"));
            assertThat(auditMessage).as("expected an audit event for the trigger action").isNotNull();
        }
    }

    @Test
    @DisplayName("NIGHTLY trigger returns 202 well before a delayed downstream call completes (design Testing item 5, Story 4)")
    void trigger_nightly_returns202_beforeDelayedHearingCasesForDayCompletes() {
        HearingQueryApiStub.stubGetHearingCasesForDayReturnsEmptyHearingCasesWithDelay(4000);

        final String body = """
                {
                  "discoveryOperation": "NIGHTLY"
                }
                """;

        final Instant before = Instant.now();
        final ResponseEntity<String> response = http.exchange(
                baseUrl + TRIGGER_PATH, HttpMethod.POST, new HttpEntity<>(body, vendorHeaders()), String.class);
        final Duration elapsed = Duration.between(before, Instant.now());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(elapsed)
                .as("202 must return without waiting for the delayed hearing-cases-for-day call(s) to complete")
                .isLessThan(Duration.ofSeconds(2));
    }
}

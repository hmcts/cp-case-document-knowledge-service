package uk.gov.hmcts.cp.cdk.http;

import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static uk.gov.hmcts.cp.cdk.util.UtilConstants.CJSCPPUID;
import static uk.gov.hmcts.cp.cdk.util.UtilConstants.USER_WITH_PERMISSIONS;
import static uk.gov.hmcts.cp.cdk.util.UtilConstants.USER_WITH_SYSTEM_USERS_GROUPS;

import uk.gov.hmcts.cp.cdk.testsupport.AbstractHttpLiveTest;
import uk.gov.hmcts.cp.cdk.util.UtilHttp;
import uk.gov.hmcts.cp.openapi.model.cdk.DiscoveryOperation;

import java.util.List;

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
import org.springframework.web.client.RestTemplate;

/**
 * ACL-only coverage for POST /discovery-scheduler/trigger (Story 2, DD-43061).
 * Functional trigger/dispatch behaviour belongs to Story 3's DiscoverySchedulerTriggerHttpLiveTest.
 * These scenarios stay red until the new Drools rule (Story 2) and the controller method
 * (Story 3) both land -- see 04-test-specs.md constraint 2.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiscoverySchedulerTriggerAclHttpLiveTest extends AbstractHttpLiveTest {

    private static final String USERS_GROUPS_PERMISSIONS_PATH =
            "/usersgroups-query-api/query/api/rest/usersgroups/users/logged-in-user/permissions";
    private static final MediaType VND_TYPE_JSON = MediaType.valueOf(
            "application/vnd.casedocumentknowledge-service.discovery-scheduler-trigger+json");

    private ResponseEntity<String> postTrigger(final RestTemplate client, final String cjscppuid,
                                                final DiscoveryOperation operation) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(VND_TYPE_JSON);
        headers.setAccept(List.of(VND_TYPE_JSON));
        if (cjscppuid != null) {
            headers.set(CJSCPPUID, cjscppuid);
        }

        final String body = """
                {
                  "discoveryOperation": "%s"
                }
                """.formatted(operation);

        return client.exchange(
                baseUrl + "/discovery-scheduler/trigger",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );
    }

    @ParameterizedTest
    @EnumSource(DiscoveryOperation.class)
    @DisplayName("System User is authorised for the trigger action (AC-008)")
    void systemUser_isAuthorisedForTrigger(final DiscoveryOperation operation) {
        final ResponseEntity<String> response = postTrigger(http, USER_WITH_SYSTEM_USERS_GROUPS, operation);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(VND_TYPE_JSON);
    }

    @Test
    @DisplayName("\"AI search\"-permission-only caller is denied (AC-009, runtime half of AC-011)")
    void permissionOnlyCaller_isDeniedForTrigger() {
        try {
            postTrigger(http, USER_WITH_PERMISSIONS, DiscoveryOperation.INTRADAY);
            fail("Expected permission-only caller to be denied");
        } catch (final HttpClientErrorException ex) {
            assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(ex.getResponseBodyAsString()).doesNotContain(USER_WITH_PERMISSIONS);
        }
    }

    @Test
    @DisplayName("No CJSCPPUID header is denied (AC-010)")
    void noCjscppuid_isDeniedForTrigger() {
        configureFor("localhost", 8089);
        final int before = findAll(getRequestedFor(urlPathEqualTo(USERS_GROUPS_PERMISSIONS_PATH))).size();

        try {
            postTrigger(UtilHttp.newClientWithoutDefaultUser(), null, DiscoveryOperation.INTRADAY);
            fail("Expected request with no CJSCPPUID to be denied");
        } catch (final HttpClientErrorException ex) {
            assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        final int after = findAll(getRequestedFor(urlPathEqualTo(USERS_GROUPS_PERMISSIONS_PATH))).size();
        assertThat(after).as("filter must reject before identity resolution").isEqualTo(before);
    }

    @Test
    @DisplayName("Blank CJSCPPUID header is denied (AC-010 variant)")
    void blankCjscppuid_isDeniedForTrigger() {
        try {
            postTrigger(UtilHttp.newClientWithoutDefaultUser(), " ", DiscoveryOperation.INTRADAY);
            fail("Expected request with blank CJSCPPUID to be denied");
        } catch (final HttpClientErrorException ex) {
            assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}

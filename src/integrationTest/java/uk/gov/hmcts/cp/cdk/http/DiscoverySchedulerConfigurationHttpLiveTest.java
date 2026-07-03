package uk.gov.hmcts.cp.cdk.http;

import static org.assertj.core.api.Assertions.assertThat;

import uk.gov.hmcts.cp.cdk.testsupport.AbstractHttpLiveTest;
import uk.gov.hmcts.cp.cdk.util.UtilConstants;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * End-to-end tests for:
 * - POST /discovery-scheduler/configurations
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiscoverySchedulerConfigurationHttpLiveTest extends AbstractHttpLiveTest {

    private static final MediaType VND_TYPE_JSON = MediaType.valueOf(
            "application/vnd.casedocumentknowledge-service.discovery-scheduler-configuration+json");

    private ResponseEntity<String> postConfiguration(final UUID courtCentreId, final UUID courtRoomId,
                                                       final int version, final boolean isActive) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(VND_TYPE_JSON);
        headers.setAccept(List.of(VND_TYPE_JSON));
        headers.set(UtilConstants.CJSCPPUID, UtilConstants.USER_WITH_SYSTEM_USERS_GROUPS);

        final String body = """
                {
                  "courtCentreId": "%s",
                  "courtRoomId": "%s",
                  "uploadedDate": "2026-06-16",
                  "version": %d,
                  "isActive": %b
                }
                """.formatted(courtCentreId, courtRoomId, version, isActive);

        return http.exchange(
                baseUrl + "/discovery-scheduler/configurations",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );
    }

    @Test
    @DisplayName("POST configurations persists multiple versions and latest version reflects the newest upload")
    void postConfigurations_persistsMultipleVersions_andLatestReflectsNewest() throws SQLException {
        final UUID courtCentreId = UUID.randomUUID();
        final UUID courtRoomId = UUID.randomUUID();

        final ResponseEntity<String> v1Response = postConfiguration(courtCentreId, courtRoomId, 1, true);
        assertThat(v1Response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(v1Response.getBody()).contains("message");

        final ResponseEntity<String> v2Response = postConfiguration(courtCentreId, courtRoomId, 2, false);
        assertThat(v2Response.getStatusCode()).isEqualTo(HttpStatus.OK);

        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT version, is_active FROM discovery_scheduler_configuration "
                             + "WHERE court_centre_id = ? AND court_room_id = ? "
                             + "ORDER BY version DESC LIMIT 1")) {
            ps.setObject(1, courtCentreId);
            ps.setObject(2, courtRoomId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("version")).isEqualTo(2);
                assertThat(rs.getBoolean("is_active")).isFalse();
                assertThat(rs.next()).isFalse();
            }
        }
    }

    @Test
    @DisplayName("POST configurations rejects duplicate courtCentre/courtRoom/version with 409")
    void postConfigurations_rejectsDuplicateVersion() {
        final UUID courtCentreId = UUID.randomUUID();
        final UUID courtRoomId = UUID.randomUUID();

        final ResponseEntity<String> firstResponse = postConfiguration(courtCentreId, courtRoomId, 1, true);
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        try {
            postConfiguration(courtCentreId, courtRoomId, 1, true);
            org.junit.jupiter.api.Assertions.fail("Expected duplicate version to be rejected");
        } catch (final org.springframework.web.client.HttpClientErrorException ex) {
            assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }
    }
}

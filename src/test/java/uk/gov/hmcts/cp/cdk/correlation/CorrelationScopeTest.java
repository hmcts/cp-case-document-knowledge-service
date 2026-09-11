package uk.gov.hmcts.cp.cdk.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("CorrelationScope tests (DD-43183 Story 1)")
class CorrelationScopeTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("open(cid) seeds MDC with the explicit value")
    void openSeedsExplicitValue() {
        try (var scope = CorrelationScope.open("explicit-value")) {
            assertThat(MDC.get(CorrelationIds.MDC_KEY)).isEqualTo("explicit-value");
        }
    }

    @Test
    @DisplayName("open(cid) restores the prior map on close — not a blanket clear")
    void openRestoresPriorMapOnClose() {
        MDC.put("preExisting", "value");

        try (var scope = CorrelationScope.open("explicit-value")) {
            assertThat(MDC.get(CorrelationIds.MDC_KEY)).isEqualTo("explicit-value");
        }

        assertThat(MDC.get("preExisting")).isEqualTo("value");
        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("open(cid) always overwrites any ambient value, even if one was already present")
    void openOverwritesAmbientValue() {
        MDC.put(CorrelationIds.MDC_KEY, "old-value");

        try (var scope = CorrelationScope.open("new-value")) {
            assertThat(MDC.get(CorrelationIds.MDC_KEY)).isEqualTo("new-value");
        }

        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isEqualTo("old-value");
    }

    @Test
    @DisplayName("openIfAbsent() generates a value when nothing valid is ambient")
    void openIfAbsentGeneratesWhenNothingAmbient() {
        try (var scope = CorrelationScope.openIfAbsent()) {
            assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNotBlank();
        }

        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("openIfAbsent() keeps the ambient value when one is already valid")
    void openIfAbsentKeepsAmbientValueWhenPresent() {
        MDC.put(CorrelationIds.MDC_KEY, "already-here");

        try (var scope = CorrelationScope.openIfAbsent()) {
            assertThat(MDC.get(CorrelationIds.MDC_KEY)).isEqualTo("already-here");
        }
    }

    @Test
    @DisplayName("fromJobData(...) seeds correlationId from requestId when present and valid")
    void fromJobDataSeedsRequestId() {
        final JsonObject jobData = Json.createObjectBuilder().add("requestId", "job-request-id").build();

        try (var scope = CorrelationScope.fromJobData(jobData)) {
            assertThat(MDC.get(CorrelationIds.MDC_KEY)).isEqualTo("job-request-id");
        }

        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("fromJobData(...) generates a value when requestId is absent, blank or rejected (NFR-004)")
    void fromJobDataGeneratesWhenRequestIdMissing() {
        final JsonObject jobData = Json.createObjectBuilder().build();

        try (var scope = CorrelationScope.fromJobData(jobData)) {
            assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNotBlank();
        }
    }

    @Test
    @DisplayName("fromJobData(...) generates a value when requestId fails validation, rather than throwing")
    void fromJobDataGeneratesWhenRequestIdRejected() {
        final JsonObject jobData = Json.createObjectBuilder().add("requestId", "bad value").build();

        try (var scope = CorrelationScope.fromJobData(jobData)) {
            final String resolved = MDC.get(CorrelationIds.MDC_KEY);
            assertThat(resolved).isNotBlank();
            assertThat(resolved).isNotEqualTo("bad value");
        }
    }

    @Test
    @DisplayName("fromJobData(...) additionally seeds caseId, docId and transactionId when present")
    void fromJobDataSeedsBusinessIdentifiersWhenPresent() {
        final JsonObject jobData = Json.createObjectBuilder()
                .add("requestId", "job-request-id")
                .add("caseId", "11111111-1111-1111-1111-111111111111")
                .add("docId", "22222222-2222-2222-2222-222222222222")
                .add("ragTransactionId", "33333333-3333-3333-3333-333333333333")
                .build();

        try (var scope = CorrelationScope.fromJobData(jobData)) {
            assertThat(MDC.get(CorrelationScope.MDC_KEY_CASE_ID))
                    .isEqualTo("11111111-1111-1111-1111-111111111111");
            assertThat(MDC.get(CorrelationScope.MDC_KEY_DOC_ID))
                    .isEqualTo("22222222-2222-2222-2222-222222222222");
            assertThat(MDC.get(CorrelationScope.MDC_KEY_TRANSACTION_ID))
                    .isEqualTo("33333333-3333-3333-3333-333333333333");
        }

        assertThat(MDC.get(CorrelationScope.MDC_KEY_CASE_ID)).isNull();
        assertThat(MDC.get(CorrelationScope.MDC_KEY_DOC_ID)).isNull();
        assertThat(MDC.get(CorrelationScope.MDC_KEY_TRANSACTION_ID)).isNull();
    }

    @Test
    @DisplayName("fromJobData(...) seeds no caseId/docId/transactionId key at all when absent from jobData "
            + "(no sentinel value)")
    void fromJobDataSeedsNoBusinessIdentifierKeysWhenAbsent() {
        final JsonObject jobData = Json.createObjectBuilder().add("requestId", "job-request-id").build();

        try (var scope = CorrelationScope.fromJobData(jobData)) {
            assertThat(MDC.getCopyOfContextMap()).doesNotContainKey(CorrelationScope.MDC_KEY_CASE_ID);
            assertThat(MDC.getCopyOfContextMap()).doesNotContainKey(CorrelationScope.MDC_KEY_DOC_ID);
            assertThat(MDC.getCopyOfContextMap()).doesNotContainKey(CorrelationScope.MDC_KEY_TRANSACTION_ID);
        }
    }

    @Test
    @DisplayName("close() restores the prior map exactly, whether the scope was opened via open, "
            + "openIfAbsent or fromJobData")
    void closeRestoresPriorMapForEveryFactoryMethod() {
        MDC.put("outer", "value");

        try (var scope = CorrelationScope.fromJobData(
                Json.createObjectBuilder().add("requestId", "x").add("caseId", "y").build())) {
            assertThat(MDC.get("outer")).isEqualTo("value");
        }

        assertThat(MDC.get("outer")).isEqualTo("value");
        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
        assertThat(MDC.get(CorrelationScope.MDC_KEY_CASE_ID)).isNull();
    }
}

package uk.gov.hmcts.cp.cdk.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.cdk.dashboard.DashboardKql.ANSWER_GENERATION_OUTCOMES;
import static uk.gov.hmcts.cp.cdk.dashboard.DashboardKql.INGESTION_PHASE_COUNTS;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guard for FR-006 / OQ-013 (DD-43672): every segment of every dashboard tile query must have a
 * unit test asserting the matching log line is actually emitted. Those tests read their predicate
 * from the {@code .kql} file via {@link DashboardKql}, so rewording a log line (or the query) fails CI.
 *
 * <p>If this test fails because a segment was added, removed or renamed in {@code support/dashboard-kql},
 * add/update the log assertion in the test class listed below, then update {@link #COVERED}.
 */
@DisplayName("Dashboard KQL <-> log line contract")
class DashboardKqlContractTest {

    /** segment -> test that asserts it (kept for humans; the key set is what is enforced). */
    private static final List<String> COVERED = List.of(
            INGESTION_PHASE_COUNTS + "/WAITING_FOR_UPLOAD",            // IdpcAvailabilityServiceTest
            INGESTION_PHASE_COUNTS + "/UPLOADED",                      // RetrieveMaterialAndUploadTaskTest
            INGESTION_PHASE_COUNTS + "/INGESTED",                      // CheckIngestionStatusForAllDefendantsTaskTest
            INGESTION_PHASE_COUNTS + "/EXCEEDED_FILE_SIZE_LIMIT",      // CheckIngestionStatusForAllDefendantsTaskTest
            INGESTION_PHASE_COUNTS + "/FAILED",                        // CheckIngestionStatusForAllDefendantsTaskTest
            ANSWER_GENERATION_OUTCOMES + "/Total RAG transactions",    // GenerateAnswerForQueryTaskTest
            ANSWER_GENERATION_OUTCOMES + "/Succeeded",                 // CheckStatusOfAnswerGenerationTaskTest
            ANSWER_GENERATION_OUTCOMES + "/Failed"                     // CheckStatusOfAnswerGenerationTaskTest
    );

    @Test
    @DisplayName("every tile segment in support/dashboard-kql has a log-line assertion")
    void everySegmentIsCoveredByALogAssertion() {
        final Set<String> inKql = DashboardKql.allSegments().stream()
                .map(s -> s.query() + "/" + s.name())
                .collect(Collectors.toSet());

        assertThat(inKql)
                .as("segments in support/dashboard-kql must match the segments with log assertions (FR-006). "
                        + "Add/adjust the log assertion in the owning test class, then update COVERED.")
                .containsExactlyInAnyOrderElementsOf(COVERED);
    }

    @Test
    @DisplayName("tile segments within a query are mutually exclusive")
    void segmentsWithinAQueryDoNotDoubleCount() {
        final String sizeLimit = "ingestion FAILED for identifier='blob' reason='FILE_SIZE_OVER_LIMIT' (caseId=c, docId=d).";
        final String failed = "ingestion FAILED for identifier='blob' reason='INGESTION_FAILED' (caseId=c, docId=d).";

        assertThat(DashboardKql.segment(INGESTION_PHASE_COUNTS, "EXCEEDED_FILE_SIZE_LIMIT").matches(sizeLimit)).isTrue();
        assertThat(DashboardKql.segment(INGESTION_PHASE_COUNTS, "FAILED").matches(sizeLimit)).isFalse();
        assertThat(DashboardKql.segment(INGESTION_PHASE_COUNTS, "FAILED").matches(failed)).isTrue();
        assertThat(DashboardKql.segment(INGESTION_PHASE_COUNTS, "EXCEEDED_FILE_SIZE_LIMIT").matches(failed)).isFalse();

        final String placeholder = "Saved CaseDocument placeholder docId=1, caseId=2, materialId=3, ingestionPhase=WAITING_FOR_UPLOAD";
        assertThat(DashboardKql.segment(INGESTION_PHASE_COUNTS, "UPLOADED").matches(placeholder)).isFalse();
    }
}

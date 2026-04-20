package uk.gov.hmcts.cp.cdk.clients.hearing.mapper;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummaries;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummariesInfo;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.ProsecutionCaseSummaries;

import java.util.List;

import org.junit.jupiter.api.Test;

class HearingDtoMapperTest {

    private final HearingDtoMapper mapper = new HearingDtoMapper();

    @Test
    void shouldReturnEmptyList_whenSummariesIsNull() {
        final List<String> result = mapper.collectProsecutionCaseIds(null);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyList_whenProsecutionCaseSummariesIsNull() {
        final HearingSummaries summaries = new HearingSummaries(null);

        List<String> result = mapper.collectProsecutionCaseIds(summaries);

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnIds_whenValidSummaries() {
        final ProsecutionCaseSummaries s1 = new ProsecutionCaseSummaries("id1", emptyList());
        final ProsecutionCaseSummaries s2 = new ProsecutionCaseSummaries("id2", emptyList());

        final HearingSummaries summaries = new HearingSummaries(List.of(s1, s2));

        final List<String> result = mapper.collectProsecutionCaseIds(summaries);

        assertThat(result).isEqualTo(List.of("id1", "id2"));
    }

    @Test
    void shouldFilterNullIds() {
        final ProsecutionCaseSummaries s1 = new ProsecutionCaseSummaries("id1", emptyList());
        final ProsecutionCaseSummaries s2 = new ProsecutionCaseSummaries(null, emptyList());

        final HearingSummaries summaries = new HearingSummaries(List.of(s1, s2));

        final List<String> result = mapper.collectProsecutionCaseIds(summaries);

        assertThat(result).isEqualTo(List.of("id1"));
    }

    @Test
    void shouldReturnEmptyList_whenInputIsNull() {
        final List<HearingSummariesInfo> result = mapper.toHearingSummariesInfo(null);

        assertThat(result).isNotNull();
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void shouldReturnEmptyList_whenInputIsEmpty() {
        final List<HearingSummariesInfo> result = mapper.toHearingSummariesInfo(emptyList());

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void shouldMapToHearingSummariesInfo() {
        final List<String> ids = List.of("id1", "id2");

        final List<HearingSummariesInfo> result = mapper.toHearingSummariesInfo(ids);

        assertThat(result.size()).isEqualTo(2);
        assertThat(result.get(0).caseId()).isEqualTo("id1");
        assertThat(result.get(1).caseId()).isEqualTo("id2");
    }
}
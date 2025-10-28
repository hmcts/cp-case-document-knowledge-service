package uk.gov.hmcts.cp.cdk.clients.hearing.mapper;


import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummaries;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummariesInfo;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.ProsecutionCaseSummaries;
import java.util.Collections;
import java.util.List;
import java.util.Objects;


@Component
public class HearingDtoMapper {

    public List<String> collectProsecutionCaseIds(final HearingSummaries summaries) {
        if (summaries == null || summaries.prosecutionCaseSummaries() == null) {
            return List.of();
        }
        return summaries.prosecutionCaseSummaries().stream()
                .map(ProsecutionCaseSummaries::prosecutionCaseId)
                .filter(Objects::nonNull)
                .toList();
    }

    public List<HearingSummariesInfo> toHearingSummariesInfo(final List<String> prosecutionCaseIds) {
        if (prosecutionCaseIds == null || prosecutionCaseIds.isEmpty()) {
            return Collections.emptyList();
        }
        return prosecutionCaseIds.stream().map(HearingSummariesInfo::new).toList();
    }
}
package uk.gov.hmcts.cp.cdk.clients.hearing.mapper;


import static java.util.Collections.emptyList;
import static java.util.Objects.isNull;

import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummaries;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummariesInfo;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.ProsecutionCaseSummaries;

import java.util.List;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class HearingDtoMapper {

    @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.UseExplicitTypes"})
    public List<String> collectProsecutionCaseIds(final HearingSummaries summaries) {
        if (isNull(summaries) || isNull(summaries.prosecutionCaseSummaries())) {
            return emptyList();
        }

        return summaries.prosecutionCaseSummaries().stream()
                .map(ProsecutionCaseSummaries::prosecutionCaseId)
                .filter(Objects::nonNull)
                .toList();
    }

    @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.UseExplicitTypes"})
    public List<HearingSummariesInfo> toHearingSummariesInfo(final List<String> prosecutionCaseIds) {
        if (prosecutionCaseIds == null || prosecutionCaseIds.isEmpty()) {
            return emptyList();
        }
        return prosecutionCaseIds.stream().map(HearingSummariesInfo::new).toList();
    }
}
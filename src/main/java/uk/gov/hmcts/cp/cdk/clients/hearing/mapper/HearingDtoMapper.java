package uk.gov.hmcts.cp.cdk.clients.hearing.mapper;


import uk.gov.hmcts.cp.cdk.batch.IngestionProperties;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummaries;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummariesInfo;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.ProsecutionCaseSummaries;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class HearingDtoMapper {

    private final IngestionProperties ingestionProperties;

    public HearingDtoMapper(final IngestionProperties ingestionProperties) {
        this.ingestionProperties = ingestionProperties;
    }

    @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.UseExplicitTypes"})
    public List<String> collectProsecutionCaseIds(final HearingSummaries summaries) {
        if (summaries == null || summaries.prosecutionCaseSummaries() == null) {
            return List.of();
        }
        var stream = summaries.prosecutionCaseSummaries().stream();

        // If Job Manager is NOT enabled, apply defendant count check
        if (!ingestionProperties.getFeature().isUseJobManager()) {
            stream = stream.filter(pcs -> {
                int count = pcs.defendants() == null ? 0 : pcs.defendants().size();
                if (count != 1) {
                    log.warn(
                            "Skipping prosecution case {} because it has {} defendants (expected exactly 1)",
                            pcs.prosecutionCaseId(), count
                    );
                    return false;
                }
                return true;
            });
        }

        return stream
                .map(ProsecutionCaseSummaries::prosecutionCaseId)
                .filter(Objects::nonNull)
                .toList();
    }

    @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.UseExplicitTypes"})
    public List<HearingSummariesInfo> toHearingSummariesInfo(final List<String> prosecutionCaseIds) {
        if (prosecutionCaseIds == null || prosecutionCaseIds.isEmpty()) {
            return Collections.emptyList();
        }
        return prosecutionCaseIds.stream().map(HearingSummariesInfo::new).toList();
    }
}
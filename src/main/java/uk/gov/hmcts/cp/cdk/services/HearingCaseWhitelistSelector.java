package uk.gov.hmcts.cp.cdk.services;

import static java.util.Collections.emptyList;
import static java.util.Objects.isNull;

import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingCaseForDay;
import uk.gov.hmcts.cp.cdk.domain.DiscoverySchedulerConfiguration;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * Filters hearing cases down to those whose court centre and courtroom are whitelisted
 * by an active {@link DiscoverySchedulerConfiguration}.
 */
@Component
public class HearingCaseWhitelistSelector {

    @SuppressWarnings("PMD.OnlyOneReturn")
    public List<HearingCaseForDay> findMatchingCases(final List<HearingCaseForDay> hearingCases,
                                                     final List<DiscoverySchedulerConfiguration> activeCourtCentreConfigurations) {

        if (isNull(hearingCases) || hearingCases.isEmpty()
                || isNull(activeCourtCentreConfigurations) || activeCourtCentreConfigurations.isEmpty()) {
            return emptyList();
        }

        final Set<CourtCentreRoom> whitelisted = activeCourtCentreConfigurations.stream()
                .map(config -> new CourtCentreRoom(config.getCourtCentreId(), config.getCourtRoomId()))
                .collect(Collectors.toSet());

        return hearingCases.stream()
                .filter(Objects::nonNull)
                .filter(hearingCase -> whitelisted.contains(
                        new CourtCentreRoom(hearingCase.courtCentreId(), hearingCase.courtRoomId())))
                .distinct()
                .toList();
    }

    private record CourtCentreRoom(UUID courtCentreId, UUID courtRoomId) {
    }
}

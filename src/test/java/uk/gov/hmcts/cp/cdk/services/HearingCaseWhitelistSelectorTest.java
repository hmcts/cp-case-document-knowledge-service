package uk.gov.hmcts.cp.cdk.services;

import static org.assertj.core.api.Assertions.assertThat;

import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingCaseForDay;
import uk.gov.hmcts.cp.cdk.domain.DiscoverySchedulerConfiguration;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HearingCaseWhitelistSelector tests")
class HearingCaseWhitelistSelectorTest {

    private static final LocalDate HEARING_DATE = LocalDate.of(2026, 7, 13);

    private HearingCaseWhitelistSelector selector;

    @BeforeEach
    void setUp() {
        selector = new HearingCaseWhitelistSelector();
    }

    @Test
    @DisplayName("Returns the case when both courtCentreId and courtRoomId match an active configuration")
    void findMatchingCases_returnsCase_whenCentreAndRoomMatchConfiguration() {
        final UUID courtCentreId = UUID.randomUUID();
        final UUID courtRoomId = UUID.randomUUID();

        final HearingCaseForDay hearingCase = hearingCase(courtCentreId, courtRoomId);
        final DiscoverySchedulerConfiguration configuration = configuration(courtCentreId, courtRoomId);

        final List<HearingCaseForDay> result = selector.findMatchingCases(List.of(hearingCase), List.of(configuration));

        assertThat(result).containsExactly(hearingCase);
    }

    @Test
    @DisplayName("Filters out the case when courtRoomId does not match, even though courtCentreId matches")
    void findMatchingCases_excludesCase_whenRoomDoesNotMatch() {
        final UUID courtCentreId = UUID.randomUUID();

        final HearingCaseForDay hearingCase = hearingCase(courtCentreId, UUID.randomUUID());
        final DiscoverySchedulerConfiguration configuration = configuration(courtCentreId, UUID.randomUUID());

        final List<HearingCaseForDay> result = selector.findMatchingCases(List.of(hearingCase), List.of(configuration));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Filters out the case when courtCentreId does not match, even though courtRoomId matches")
    void findMatchingCases_excludesCase_whenCentreDoesNotMatch() {
        final UUID courtRoomId = UUID.randomUUID();

        final HearingCaseForDay hearingCase = hearingCase(UUID.randomUUID(), courtRoomId);
        final DiscoverySchedulerConfiguration configuration = configuration(UUID.randomUUID(), courtRoomId);

        final List<HearingCaseForDay> result = selector.findMatchingCases(List.of(hearingCase), List.of(configuration));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Does not match a case whose centre and room each belong to a different configuration")
    void findMatchingCases_excludesCase_whenCentreAndRoomComeFromDifferentConfigurations() {
        final UUID centreA = UUID.randomUUID();
        final UUID roomA = UUID.randomUUID();
        final UUID centreB = UUID.randomUUID();
        final UUID roomB = UUID.randomUUID();

        // hearing case pairs centreA with roomB - neither whitelisted configuration has this exact pair
        final HearingCaseForDay hearingCase = hearingCase(centreA, roomB);
        final DiscoverySchedulerConfiguration configurationA = configuration(centreA, roomA);
        final DiscoverySchedulerConfiguration configurationB = configuration(centreB, roomB);

        final List<HearingCaseForDay> result = selector.findMatchingCases(
                List.of(hearingCase), List.of(configurationA, configurationB));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Returns only the matching subset when multiple cases and configurations are provided")
    void findMatchingCases_returnsOnlyMatchedSubset_whenMultipleCasesAndConfigurationsProvided() {
        final UUID centre1 = UUID.randomUUID();
        final UUID room1 = UUID.randomUUID();
        final UUID centre2 = UUID.randomUUID();
        final UUID room2 = UUID.randomUUID();

        final HearingCaseForDay matchedCase1 = hearingCase(centre1, room1);
        final HearingCaseForDay matchedCase2 = hearingCase(centre2, room2);
        final HearingCaseForDay unmatchedCase = hearingCase(UUID.randomUUID(), UUID.randomUUID());

        final DiscoverySchedulerConfiguration configuration1 = configuration(centre1, room1);
        final DiscoverySchedulerConfiguration configuration2 = configuration(centre2, room2);

        final List<HearingCaseForDay> result = selector.findMatchingCases(
                Arrays.asList(matchedCase1, unmatchedCase, matchedCase2),
                List.of(configuration1, configuration2));

        assertThat(result).containsExactlyInAnyOrder(matchedCase1, matchedCase2);
    }

    @Test
    @DisplayName("Deduplicates identical hearing cases appearing more than once in the input")
    void findMatchingCases_deduplicatesIdenticalCases() {
        final UUID courtCentreId = UUID.randomUUID();
        final UUID courtRoomId = UUID.randomUUID();

        final HearingCaseForDay hearingCase = hearingCase(courtCentreId, courtRoomId);
        final DiscoverySchedulerConfiguration configuration = configuration(courtCentreId, courtRoomId);

        final List<HearingCaseForDay> result = selector.findMatchingCases(
                List.of(hearingCase, hearingCase), List.of(configuration));

        assertThat(result).containsExactly(hearingCase);
    }

    @Test
    @DisplayName("Skips null elements within the hearing cases list instead of throwing")
    void findMatchingCases_skipsNullElements() {
        final UUID courtCentreId = UUID.randomUUID();
        final UUID courtRoomId = UUID.randomUUID();

        final HearingCaseForDay hearingCase = hearingCase(courtCentreId, courtRoomId);
        final DiscoverySchedulerConfiguration configuration = configuration(courtCentreId, courtRoomId);

        final List<HearingCaseForDay> result = selector.findMatchingCases(
                Arrays.asList(null, hearingCase), List.of(configuration));

        assertThat(result).containsExactly(hearingCase);
    }

    @Test
    @DisplayName("Returns an empty list when hearingCases is null")
    void findMatchingCases_returnsEmpty_whenHearingCasesIsNull() {
        final DiscoverySchedulerConfiguration configuration = configuration(UUID.randomUUID(), UUID.randomUUID());

        final List<HearingCaseForDay> result = selector.findMatchingCases(null, List.of(configuration));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Returns an empty list when hearingCases is empty")
    void findMatchingCases_returnsEmpty_whenHearingCasesIsEmpty() {
        final DiscoverySchedulerConfiguration configuration = configuration(UUID.randomUUID(), UUID.randomUUID());

        final List<HearingCaseForDay> result = selector.findMatchingCases(List.of(), List.of(configuration));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Returns an empty list when activeCourtCentreConfigurations is null")
    void findMatchingCases_returnsEmpty_whenConfigurationsIsNull() {
        final HearingCaseForDay hearingCase = hearingCase(UUID.randomUUID(), UUID.randomUUID());

        final List<HearingCaseForDay> result = selector.findMatchingCases(List.of(hearingCase), null);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Returns an empty list when activeCourtCentreConfigurations is empty")
    void findMatchingCases_returnsEmpty_whenConfigurationsIsEmpty() {
        final HearingCaseForDay hearingCase = hearingCase(UUID.randomUUID(), UUID.randomUUID());

        final List<HearingCaseForDay> result = selector.findMatchingCases(List.of(hearingCase), List.of());

        assertThat(result).isEmpty();
    }

    private HearingCaseForDay hearingCase(final UUID courtCentreId, final UUID courtRoomId) {
        return new HearingCaseForDay(
                courtCentreId, courtRoomId, HEARING_DATE, UUID.randomUUID(),
                List.of(UUID.randomUUID()));
    }

    private DiscoverySchedulerConfiguration configuration(final UUID courtCentreId, final UUID courtRoomId) {
        final DiscoverySchedulerConfiguration configuration = new DiscoverySchedulerConfiguration();
        configuration.setCourtCentreId(courtCentreId);
        configuration.setCourtRoomId(courtRoomId);
        return configuration;
    }
}

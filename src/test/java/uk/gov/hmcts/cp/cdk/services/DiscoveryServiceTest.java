package uk.gov.hmcts.cp.cdk.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.cdk.clients.hearing.HearingClient;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingCaseForDay;
import uk.gov.hmcts.cp.cdk.domain.DiscoverySchedulerConfiguration;
import uk.gov.hmcts.cp.cdk.domain.ScheduledIngestionRequest;
import uk.gov.hmcts.cp.cdk.repo.DiscoverySchedulerConfigurationRepository;
import uk.gov.hmcts.cp.cdk.repo.ScheduledIngestionRequestRepository;
import uk.gov.hmcts.cp.cdk.scheduler.SchedulerProperties;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.json.JsonObject;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
class DiscoveryServiceTest {

    private static final int NIGHTLY_DISCOVERY_DAYS = 3;
    private static final String SYSTEM_USER_ID_ENV_KEY = "CASEDOCUMENTKNOWLEDGE_SYSTEM_USER_ID";
    private static final String SYSTEM_USER_ID = "00000000-0000-0000-0000-000000000001";

    @Mock
    private JobManagerService jobManagerService;

    @Mock
    private ScheduledIngestionRequestRepository scheduledIngestionRequestRepository;

    @Mock
    private DiscoverySchedulerConfigurationRepository discoverySchedulerConfigurationRepository;

    @Mock
    private HearingDaysCalculator hearingDaysCalculator;

    @Mock
    private HearingClient hearingClient;

    @Mock
    private HearingCaseWhitelistSelector hearingCaseWhitelistSelector;

    @Mock
    private Environment environment;

    private DiscoveryService discoveryService;

    @BeforeEach
    void setUp() {
        discoveryService = discoveryServiceWithHearingForCasesEnabled(true);
    }

    @Test
    void runIntradayDiscovery_shouldDispatchTasksForAllRequests() {
        // given
        final LocalDate today = LocalDate.now();

        final ScheduledIngestionRequest request1 = mockRequest();
        final ScheduledIngestionRequest request2 = mockRequest();

        when(scheduledIngestionRequestRepository.findAllByHearingDate(today)).thenReturn(List.of(request1, request2));

        // when
        discoveryService.runIntradayDiscovery();

        // then
        final ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);

        verify(jobManagerService, times(2)).dispatchCaseDocumentIngestionTasksGetCasesForHearing(captor.capture());

        final List<JsonObject> captured = captor.getAllValues();
        assertThat(captured).hasSize(2);
        final JsonObject first = captured.get(0);
        assertThat(first).containsKeys("cppuid", "requestId", "courtCentreId", "roomId", "date");

        verify(scheduledIngestionRequestRepository, times(1)).findAllByHearingDate(today);
    }

    @Test
    void runIntradayDiscovery_shouldContinueWhenDispatchFails() {
        // given
        final LocalDate today = LocalDate.now();

        final ScheduledIngestionRequest request1 = mockRequest();
        final ScheduledIngestionRequest request2 = mockRequest();

        when(scheduledIngestionRequestRepository.findAllByHearingDate(today)).thenReturn(List.of(request1, request2));

        doThrow(new RuntimeException("Dispatch failed"))
                .when(jobManagerService)
                .dispatchCaseDocumentIngestionTasksGetCasesForHearing(any(JsonObject.class));

        // when
        Assertions.assertThatCode(() -> discoveryService.runIntradayDiscovery())
                .doesNotThrowAnyException();

        // then
        verify(jobManagerService, times(2)).dispatchCaseDocumentIngestionTasksGetCasesForHearing(any(JsonObject.class));
    }

    @Test
    void runIntradayDiscovery_shouldHandleEmptyRequestList() {
        // given
        final LocalDate today = LocalDate.now();

        when(scheduledIngestionRequestRepository.findAllByHearingDate(today))
                .thenReturn(List.of());

        // when
        discoveryService.runIntradayDiscovery();

        // then
        verify(jobManagerService, never()).dispatchCaseDocumentIngestionTasksGetCasesForHearing(any());

        verify(scheduledIngestionRequestRepository, times(1))
                .findAllByHearingDate(today);
    }

    @Test
    void generatedJobData_shouldContainExpectedValues() {
        // given
        final UUID cppuid = UUID.randomUUID();
        final UUID courtCentreId = UUID.randomUUID();
        final UUID roomId = UUID.randomUUID();
        final LocalDate hearingDate = LocalDate.now();

        final ScheduledIngestionRequest request = mock(ScheduledIngestionRequest.class);

        when(request.getCppuid()).thenReturn(cppuid);
        when(request.getCourtCentreId()).thenReturn(courtCentreId);
        when(request.getCourtRoomId()).thenReturn(roomId);

        when(scheduledIngestionRequestRepository.findAllByHearingDate(hearingDate))
                .thenReturn(List.of(request));

        // when
        discoveryService.runIntradayDiscovery();

        // then
        final ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);

        verify(jobManagerService).dispatchCaseDocumentIngestionTasksGetCasesForHearing(captor.capture());

        final JsonObject jobData = captor.getValue();
        assertThat(jobData.getString("cppuid")).isEqualTo(cppuid.toString());
        assertThat(jobData.getString("courtCentreId")).isEqualTo(courtCentreId.toString());
        assertThat(jobData.getString("roomId")).isEqualTo(roomId.toString());
        assertThat(jobData.getString("date")).isEqualTo(hearingDate.toString());
        assertThat(jobData.getString("requestId")).isNotBlank();
    }

    @Test
    void runNightlyDiscovery_shouldCallCalculatorWithTodayAndNightlyDiscoveryDays() {
        // given
        final LocalDate today = LocalDate.now();
        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of(today));
        when(environment.getProperty(SYSTEM_USER_ID_ENV_KEY)).thenReturn(SYSTEM_USER_ID);

        // when
        discoveryService.runNightlyDiscovery();

        // then
        verify(hearingDaysCalculator).calculate(today, NIGHTLY_DISCOVERY_DAYS);
    }

    @Test
    void runNightlyDiscovery_shouldQueryActiveConfigurationsOnceRegardlessOfCalculatedDateCount() {
        // given
        final LocalDate today = LocalDate.now();
        final LocalDate tomorrow = today.plusDays(1);
        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of(today, tomorrow));
        when(environment.getProperty(SYSTEM_USER_ID_ENV_KEY)).thenReturn(SYSTEM_USER_ID);

        // when
        discoveryService.runNightlyDiscovery();

        // then
        verify(discoverySchedulerConfigurationRepository, times(1)).findLatestActiveConfigurations();
        verifyNoInteractions(scheduledIngestionRequestRepository);
    }

    @Test
    void runNightlyDiscovery_shouldRetrieveHearingCasesForEachCalculatedDateUsingSystemUser() {
        // given
        final LocalDate today = LocalDate.now();
        final LocalDate tomorrow = today.plusDays(1);

        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of(today, tomorrow));
        when(environment.getProperty(SYSTEM_USER_ID_ENV_KEY)).thenReturn(SYSTEM_USER_ID);

        // when
        discoveryService.runNightlyDiscovery();

        // then
        verify(hearingClient).getHearingCasesForDay(today, SYSTEM_USER_ID);
        verify(hearingClient).getHearingCasesForDay(tomorrow, SYSTEM_USER_ID);
    }

    @Test
    void runNightlyDiscovery_shouldFilterRetrievedHearingCasesUsingWhitelistSelector() {
        // given
        final LocalDate today = LocalDate.now();
        final DiscoverySchedulerConfiguration config = mockConfiguration();
        final List<DiscoverySchedulerConfiguration> configs = List.of(config);
        final List<HearingCaseForDay> retrievedCases = List.of(hearingCaseWithProsecutionCases(today, 0));

        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of(today));
        when(discoverySchedulerConfigurationRepository.findLatestActiveConfigurations())
                .thenReturn(configs);
        when(environment.getProperty(SYSTEM_USER_ID_ENV_KEY)).thenReturn(SYSTEM_USER_ID);
        when(hearingClient.getHearingCasesForDay(today, SYSTEM_USER_ID)).thenReturn(retrievedCases);

        // when
        discoveryService.runNightlyDiscovery();

        // then
        verify(hearingCaseWhitelistSelector).findMatchingCases(retrievedCases, configs);
    }

    @Test
    void runNightlyDiscovery_shouldDispatchCheckCaseEligibilityTaskForEachProsecutionCaseInMatchedHearingCases() {
        // given
        final LocalDate today = LocalDate.now();
        final DiscoverySchedulerConfiguration config = mockConfiguration();
        final HearingCaseForDay matchedCase = hearingCaseWithProsecutionCases(today, 2);

        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of(today));
        when(discoverySchedulerConfigurationRepository.findLatestActiveConfigurations())
                .thenReturn(List.of(config));
        when(environment.getProperty(SYSTEM_USER_ID_ENV_KEY)).thenReturn(SYSTEM_USER_ID);
        when(hearingClient.getHearingCasesForDay(any(), any())).thenReturn(List.of(matchedCase));
        when(hearingCaseWhitelistSelector.findMatchingCases(any(), any())).thenReturn(List.of(matchedCase));

        // when
        discoveryService.runNightlyDiscovery();

        // then
        verify(jobManagerService, times(2)).dispatchCaseDocumentIngestionTasksCheckIdpcAvailability(any(JsonObject.class), any(ZonedDateTime.class), anyInt());
    }

    @Test
    void runNightlyDiscovery_shouldDispatchAcrossMultipleDatesAndMatchedHearingCases() {
        // given
        final LocalDate today = LocalDate.now();
        final LocalDate tomorrow = today.plusDays(1);
        final DiscoverySchedulerConfiguration config = mockConfiguration();

        final HearingCaseForDay caseToday = hearingCaseWithProsecutionCases(today, 2);
        final HearingCaseForDay caseTomorrow = hearingCaseWithProsecutionCases(tomorrow, 1);

        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of(today, tomorrow));
        when(discoverySchedulerConfigurationRepository.findLatestActiveConfigurations())
                .thenReturn(List.of(config));
        when(environment.getProperty(SYSTEM_USER_ID_ENV_KEY)).thenReturn(SYSTEM_USER_ID);
        when(hearingClient.getHearingCasesForDay(eq(today), any())).thenReturn(List.of(caseToday));
        when(hearingClient.getHearingCasesForDay(eq(tomorrow), any())).thenReturn(List.of(caseTomorrow));
        when(hearingCaseWhitelistSelector.findMatchingCases(eq(List.of(caseToday)), any()))
                .thenReturn(List.of(caseToday));
        when(hearingCaseWhitelistSelector.findMatchingCases(eq(List.of(caseTomorrow)), any()))
                .thenReturn(List.of(caseTomorrow));

        // when
        discoveryService.runNightlyDiscovery();

        // then
        verify(jobManagerService, times(3)).dispatchCaseDocumentIngestionTasksCheckIdpcAvailability(any(JsonObject.class), any(ZonedDateTime.class), anyInt());
    }

    @Test
    void runNightlyDiscovery_shouldDedupeSameCaseIdAppearingInMultipleMatchedHearingCasesOnSameDate() {
        // given
        final LocalDate today = LocalDate.now();
        final DiscoverySchedulerConfiguration config = mockConfiguration();
        final UUID sharedCaseId = UUID.randomUUID();

        final HearingCaseForDay morningHearing = new HearingCaseForDay(
                UUID.randomUUID(), UUID.randomUUID(), today, UUID.randomUUID(),
                List.of(sharedCaseId));
        final HearingCaseForDay afternoonHearing = new HearingCaseForDay(
                UUID.randomUUID(), UUID.randomUUID(), today, UUID.randomUUID(),
                List.of(sharedCaseId));

        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of(today));
        when(discoverySchedulerConfigurationRepository.findLatestActiveConfigurations())
                .thenReturn(List.of(config));
        when(environment.getProperty(SYSTEM_USER_ID_ENV_KEY)).thenReturn(SYSTEM_USER_ID);
        when(hearingClient.getHearingCasesForDay(any(), any()))
                .thenReturn(List.of(morningHearing, afternoonHearing));
        when(hearingCaseWhitelistSelector.findMatchingCases(any(), any()))
                .thenReturn(List.of(morningHearing, afternoonHearing));

        // when
        discoveryService.runNightlyDiscovery();

        // then
        final ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
        verify(jobManagerService, times(1)).dispatchCaseDocumentIngestionTasksCheckIdpcAvailability(captor.capture(), any(ZonedDateTime.class), anyInt());
        assertThat(captor.getValue().getString("caseId")).isEqualTo(sharedCaseId.toString());
    }

    @Test
    void runNightlyDiscovery_shouldDedupeSameCaseIdAppearingAcrossMultipleDates() {
        // given
        final LocalDate today = LocalDate.now();
        final LocalDate tomorrow = today.plusDays(1);
        final DiscoverySchedulerConfiguration config = mockConfiguration();
        final UUID sharedCaseId = UUID.randomUUID();

        final HearingCaseForDay caseToday = new HearingCaseForDay(
                UUID.randomUUID(), UUID.randomUUID(), today, UUID.randomUUID(),
                List.of(sharedCaseId));
        final HearingCaseForDay caseTomorrow = new HearingCaseForDay(
                UUID.randomUUID(), UUID.randomUUID(), tomorrow, UUID.randomUUID(),
                List.of(sharedCaseId));

        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of(today, tomorrow));
        when(discoverySchedulerConfigurationRepository.findLatestActiveConfigurations())
                .thenReturn(List.of(config));
        when(environment.getProperty(SYSTEM_USER_ID_ENV_KEY)).thenReturn(SYSTEM_USER_ID);
        when(hearingClient.getHearingCasesForDay(eq(today), any())).thenReturn(List.of(caseToday));
        when(hearingClient.getHearingCasesForDay(eq(tomorrow), any())).thenReturn(List.of(caseTomorrow));
        when(hearingCaseWhitelistSelector.findMatchingCases(eq(List.of(caseToday)), any()))
                .thenReturn(List.of(caseToday));
        when(hearingCaseWhitelistSelector.findMatchingCases(eq(List.of(caseTomorrow)), any()))
                .thenReturn(List.of(caseTomorrow));

        // when
        discoveryService.runNightlyDiscovery();

        // then
        final ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
        verify(jobManagerService, times(1)).dispatchCaseDocumentIngestionTasksCheckIdpcAvailability(captor.capture(), any(ZonedDateTime.class), anyInt());
        assertThat(captor.getValue().getString("caseId")).isEqualTo(sharedCaseId.toString());
    }

    @Test
    void runNightlyDiscovery_shouldGenerateJobDataFromMatchedHearingCaseProsecutionCase() {
        // given
        final LocalDate today = LocalDate.now();
        final DiscoverySchedulerConfiguration config = mockConfiguration();
        final UUID caseId = UUID.randomUUID();
        final HearingCaseForDay matchedCase = new HearingCaseForDay(
                UUID.randomUUID(), UUID.randomUUID(), today, UUID.randomUUID(),
                List.of(caseId));

        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of(today));
        when(discoverySchedulerConfigurationRepository.findLatestActiveConfigurations())
                .thenReturn(List.of(config));
        when(environment.getProperty(SYSTEM_USER_ID_ENV_KEY)).thenReturn(SYSTEM_USER_ID);
        when(hearingClient.getHearingCasesForDay(any(), any())).thenReturn(List.of(matchedCase));
        when(hearingCaseWhitelistSelector.findMatchingCases(any(), any())).thenReturn(List.of(matchedCase));

        // when
        discoveryService.runNightlyDiscovery();

        // then
        final ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
        verify(jobManagerService).dispatchCaseDocumentIngestionTasksCheckIdpcAvailability(captor.capture(), any(ZonedDateTime.class), anyInt());

        final JsonObject jobData = captor.getValue();
        assertThat(jobData.getString("caseId")).isEqualTo(caseId.toString());
        assertThat(jobData.getString("cppuid")).isEqualTo(SYSTEM_USER_ID);
        assertThat(jobData.getString("requestId")).isNotBlank();
    }

    @Test
    void runNightlyDiscovery_shouldContinueWhenDispatchFails() {
        // given
        final LocalDate today = LocalDate.now();
        final DiscoverySchedulerConfiguration config = mockConfiguration();
        final HearingCaseForDay matchedCase = hearingCaseWithProsecutionCases(today, 1);

        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of(today));
        when(discoverySchedulerConfigurationRepository.findLatestActiveConfigurations())
                .thenReturn(List.of(config));
        when(environment.getProperty(SYSTEM_USER_ID_ENV_KEY)).thenReturn(SYSTEM_USER_ID);
        when(hearingClient.getHearingCasesForDay(any(), any())).thenReturn(List.of(matchedCase));
        when(hearingCaseWhitelistSelector.findMatchingCases(any(), any())).thenReturn(List.of(matchedCase));
        doThrow(new RuntimeException("Dispatch failed"))
                .when(jobManagerService)
                .dispatchCaseDocumentIngestionTasksCheckIdpcAvailability(any(JsonObject.class), any(ZonedDateTime.class), anyInt());

        // when / then
        Assertions.assertThatCode(() -> discoveryService.runNightlyDiscovery())
                .doesNotThrowAnyException();

        verify(jobManagerService).dispatchCaseDocumentIngestionTasksCheckIdpcAvailability(any(JsonObject.class), any(ZonedDateTime.class), anyInt());
    }

    @Test
    void runNightlyDiscovery_shouldNotDispatchWhenMatchedHearingCaseHasNullProsecutionCases() {
        // given
        final LocalDate today = LocalDate.now();
        final DiscoverySchedulerConfiguration config = mockConfiguration();
        final HearingCaseForDay matchedCaseWithoutProsecutionCases = new HearingCaseForDay(
                UUID.randomUUID(), UUID.randomUUID(), today, UUID.randomUUID(), null);

        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of(today));
        when(discoverySchedulerConfigurationRepository.findLatestActiveConfigurations())
                .thenReturn(List.of(config));
        when(environment.getProperty(SYSTEM_USER_ID_ENV_KEY)).thenReturn(SYSTEM_USER_ID);
        when(hearingClient.getHearingCasesForDay(any(), any())).thenReturn(List.of(matchedCaseWithoutProsecutionCases));
        when(hearingCaseWhitelistSelector.findMatchingCases(any(), any()))
                .thenReturn(List.of(matchedCaseWithoutProsecutionCases));

        // when / then
        Assertions.assertThatCode(() -> discoveryService.runNightlyDiscovery())
                .doesNotThrowAnyException();

        verify(jobManagerService, never()).dispatchCaseDocumentIngestionTasksCheckIdpcAvailability(any(), any(), anyInt());
    }

    @Test
    void runNightlyDiscovery_shouldNotDispatchWhenMatchedHearingCaseHasEmptyProsecutionCases() {
        // given
        final LocalDate today = LocalDate.now();
        final DiscoverySchedulerConfiguration config = mockConfiguration();
        final HearingCaseForDay matchedCaseWithEmptyProsecutionCases = hearingCaseWithProsecutionCases(today, 0);

        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of(today));
        when(discoverySchedulerConfigurationRepository.findLatestActiveConfigurations())
                .thenReturn(List.of(config));
        when(environment.getProperty(SYSTEM_USER_ID_ENV_KEY)).thenReturn(SYSTEM_USER_ID);
        when(hearingClient.getHearingCasesForDay(any(), any())).thenReturn(List.of(matchedCaseWithEmptyProsecutionCases));
        when(hearingCaseWhitelistSelector.findMatchingCases(any(), any()))
                .thenReturn(List.of(matchedCaseWithEmptyProsecutionCases));

        // when
        discoveryService.runNightlyDiscovery();

        // then
        verify(jobManagerService, never()).dispatchCaseDocumentIngestionTasksCheckIdpcAvailability(any(), any(), anyInt());
    }

    @Test
    void runNightlyDiscovery_shouldNotDispatchWhenNoActiveConfigurationsExist() {
        // given
        final LocalDate today = LocalDate.now();
        final LocalDate tomorrow = today.plusDays(1);
        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of(today, tomorrow));
        when(discoverySchedulerConfigurationRepository.findLatestActiveConfigurations())
                .thenReturn(List.of());
        when(environment.getProperty(SYSTEM_USER_ID_ENV_KEY)).thenReturn(SYSTEM_USER_ID);

        // when
        discoveryService.runNightlyDiscovery();

        // then
        verify(jobManagerService, never()).dispatchCaseDocumentIngestionTasksCheckIdpcAvailability(any(), any(), anyInt());
    }

    @Test
    void runNightlyDiscovery_shouldNotDispatchWhenNoHearingDatesCalculated() {
        // given
        final LocalDate today = LocalDate.now();
        final DiscoverySchedulerConfiguration config = mock(DiscoverySchedulerConfiguration.class);
        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of());
        when(discoverySchedulerConfigurationRepository.findLatestActiveConfigurations())
                .thenReturn(List.of(config));
        when(environment.getProperty(SYSTEM_USER_ID_ENV_KEY)).thenReturn(SYSTEM_USER_ID);

        // when / then
        Assertions.assertThatCode(() -> discoveryService.runNightlyDiscovery())
                .doesNotThrowAnyException();

        verify(jobManagerService, never()).dispatchCaseDocumentIngestionTasksGetCasesForHearing(any());
    }

    @Test
    void runNightlyDiscovery_shouldDispatchLegacyTaskForEveryConfigurationAndCalculatedDate_whenHearingForCasesDisabled() {
        // given
        final DiscoveryService legacyDiscoveryService = discoveryServiceWithHearingForCasesEnabled(false);
        final LocalDate today = LocalDate.now();
        final LocalDate tomorrow = today.plusDays(1);

        final DiscoverySchedulerConfiguration config1 = mockConfiguration(UUID.randomUUID(), UUID.randomUUID());
        final DiscoverySchedulerConfiguration config2 = mockConfiguration(UUID.randomUUID(), UUID.randomUUID());

        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of(today, tomorrow));
        when(discoverySchedulerConfigurationRepository.findLatestActiveConfigurations())
                .thenReturn(List.of(config1, config2));
        when(environment.getProperty(SYSTEM_USER_ID_ENV_KEY)).thenReturn(SYSTEM_USER_ID);

        // when
        legacyDiscoveryService.runNightlyDiscovery();

        // then
        verify(jobManagerService, times(4)).dispatchCaseDocumentIngestionTasksGetCasesForHearing(any(JsonObject.class));
        verifyNoInteractions(hearingClient, hearingCaseWhitelistSelector);
    }

    @Test
    void runNightlyDiscovery_shouldGenerateLegacyJobDataFromConfigurationAndHearingDate_whenHearingForCasesDisabled() {
        // given
        final DiscoveryService legacyDiscoveryService = discoveryServiceWithHearingForCasesEnabled(false);
        final LocalDate today = LocalDate.now();
        final UUID courtCentreId = UUID.randomUUID();
        final UUID courtRoomId = UUID.randomUUID();
        final DiscoverySchedulerConfiguration config = mockConfiguration(courtCentreId, courtRoomId);

        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of(today));
        when(discoverySchedulerConfigurationRepository.findLatestActiveConfigurations())
                .thenReturn(List.of(config));
        when(environment.getProperty(SYSTEM_USER_ID_ENV_KEY)).thenReturn(SYSTEM_USER_ID);

        // when
        legacyDiscoveryService.runNightlyDiscovery();

        // then
        final ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
        verify(jobManagerService).dispatchCaseDocumentIngestionTasksGetCasesForHearing(captor.capture());

        final JsonObject jobData = captor.getValue();
        assertThat(jobData.getString("courtCentreId")).isEqualTo(courtCentreId.toString());
        assertThat(jobData.getString("roomId")).isEqualTo(courtRoomId.toString());
        assertThat(jobData.getString("date")).isEqualTo(today.toString());
        assertThat(jobData.getString("requestId")).isNotBlank();
        assertThat(jobData.getString("cppuid")).isEqualTo(SYSTEM_USER_ID);
    }

    @Test
    void runNightlyDiscovery_shouldContinueWhenLegacyDispatchFails_whenHearingForCasesDisabled() {
        // given
        final DiscoveryService legacyDiscoveryService = discoveryServiceWithHearingForCasesEnabled(false);
        final LocalDate today = LocalDate.now();
        final DiscoverySchedulerConfiguration config = mockConfiguration(UUID.randomUUID(), UUID.randomUUID());

        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of(today));
        when(discoverySchedulerConfigurationRepository.findLatestActiveConfigurations())
                .thenReturn(List.of(config));
        when(environment.getProperty(SYSTEM_USER_ID_ENV_KEY)).thenReturn(SYSTEM_USER_ID);
        doThrow(new RuntimeException("Dispatch failed"))
                .when(jobManagerService)
                .dispatchCaseDocumentIngestionTasksGetCasesForHearing(any(JsonObject.class));

        // when / then
        Assertions.assertThatCode(() -> legacyDiscoveryService.runNightlyDiscovery())
                .doesNotThrowAnyException();

        verify(jobManagerService).dispatchCaseDocumentIngestionTasksGetCasesForHearing(any(JsonObject.class));
    }

    @Test
    void runNightlyDiscovery_shouldThrowIllegalStateExceptionWhenSystemUserIdEnvVarIsMissing() {
        // given
        final LocalDate today = LocalDate.now();
        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of(today));
        when(environment.getProperty(SYSTEM_USER_ID_ENV_KEY)).thenReturn(null);

        // when / then
        Assertions.assertThatThrownBy(() -> discoveryService.runNightlyDiscovery())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SYSTEM_USER_ID_ENV_KEY);

        verifyNoInteractions(jobManagerService);
    }

    @Test
    void runNightlyDiscovery_shouldThrowIllegalStateExceptionWhenSystemUserIdIsNotAValidUuid() {
        // given
        final LocalDate today = LocalDate.now();
        final String invalidSystemUserId = "not-a-valid-uuid";
        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of(today));
        when(environment.getProperty(SYSTEM_USER_ID_ENV_KEY)).thenReturn(invalidSystemUserId);

        // when / then
        Assertions.assertThatThrownBy(() -> discoveryService.runNightlyDiscovery())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SYSTEM_USER_ID_ENV_KEY)
                .hasMessageContaining(invalidSystemUserId)
                .hasCauseInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(jobManagerService);
    }

    // ----- Option A1 (DD-43823): deferred-start staggering of the per-case CHECK_IDPC entry tasks -----

    @Test
    void runNightlyDiscovery_whenStaggerDisabled_dispatchesEveryEntryTaskWithImmediateStartTime() {
        // given
        final DiscoveryService service = discoveryServiceWithStagger(false, 2.0, Duration.ofHours(8));
        stubSingleDateWithMatchedCases(3);

        // when
        service.runNightlyDiscovery();

        // then — all three entry tasks share the same (immediate) start time: current behaviour preserved
        final List<ZonedDateTime> startTimes = captureEntryStartTimes(3);
        assertThat(Duration.between(startTimes.get(0), startTimes.get(1))).isZero();
        assertThat(Duration.between(startTimes.get(0), startTimes.get(2))).isZero();
    }

    @Test
    void runNightlyDiscovery_whenStaggerEnabled_releasesEntryTasksAtConfiguredRate() {
        // given — 2 cases/min => 30s between consecutive entry tasks
        final DiscoveryService service = discoveryServiceWithStagger(true, 2.0, Duration.ofHours(8));
        stubSingleDateWithMatchedCases(3);

        // when
        service.runNightlyDiscovery();

        // then
        final List<ZonedDateTime> startTimes = captureEntryStartTimes(3);
        assertThat(Duration.between(startTimes.get(0), startTimes.get(1))).isEqualTo(Duration.ofSeconds(30));
        assertThat(Duration.between(startTimes.get(0), startTimes.get(2))).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void runNightlyDiscovery_staggerStepScalesInverselyWithRate() {
        // given — 4 cases/min => 15s between consecutive entry tasks
        final DiscoveryService service = discoveryServiceWithStagger(true, 4.0, Duration.ofHours(8));
        stubSingleDateWithMatchedCases(2);

        // when
        service.runNightlyDiscovery();

        // then
        final List<ZonedDateTime> startTimes = captureEntryStartTimes(2);
        assertThat(Duration.between(startTimes.get(0), startTimes.get(1))).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void runNightlyDiscovery_firstEntryTaskIsNeverDeferred() {
        // given
        final DiscoveryService service = discoveryServiceWithStagger(true, 2.0, Duration.ofHours(8));
        stubSingleDateWithMatchedCases(1);
        final ZonedDateTime before = ZonedDateTime.now();

        // when
        service.runNightlyDiscovery();

        // then — index 0 starts ~now, not deferred
        final ZonedDateTime after = ZonedDateTime.now();
        final List<ZonedDateTime> startTimes = captureEntryStartTimes(1);
        assertThat(startTimes.get(0)).isBetween(before, after);
    }

    @Test
    void runNightlyDiscovery_whenWindowExceedsMaxWindow_clampsOverflowWarnsAndDropsNothing() {
        // given — 60 cases/min (1s step), cap 2s: indexes 3 and 4 clamp to base + 2s
        final DiscoveryService service = discoveryServiceWithStagger(true, 60.0, Duration.ofSeconds(2));
        stubSingleDateWithMatchedCases(5);

        final Logger logger = (Logger) LoggerFactory.getLogger(DiscoveryService.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        // when
        try {
            service.runNightlyDiscovery();
        } finally {
            logger.detachAppender(appender);
        }

        // then — nothing dropped
        final List<ZonedDateTime> startTimes = captureEntryStartTimes(5);
        assertThat(startTimes).hasSize(5);

        // overflow cases share the capped start time (base + 2s)
        assertThat(Duration.between(startTimes.get(0), startTimes.get(2))).isEqualTo(Duration.ofSeconds(2));
        assertThat(startTimes.get(3)).isEqualTo(startTimes.get(2));
        assertThat(startTimes.get(4)).isEqualTo(startTimes.get(2));

        // and the tripwire warned
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).containsIgnoringCase("stagger window");
        });
    }

    @Test
    void runNightlyDiscovery_whenCasesPerMinuteNotPositive_fallsBackToImmediateStart() {
        // given — guards against divide-by-zero / misconfiguration
        final DiscoveryService service = discoveryServiceWithStagger(true, 0.0, Duration.ofHours(8));
        stubSingleDateWithMatchedCases(2);

        // when / then
        Assertions.assertThatCode(service::runNightlyDiscovery).doesNotThrowAnyException();

        final List<ZonedDateTime> startTimes = captureEntryStartTimes(2);
        assertThat(Duration.between(startTimes.get(0), startTimes.get(1))).isZero();
    }

    private void stubSingleDateWithMatchedCases(final int caseCount) {
        final LocalDate today = LocalDate.now();
        final DiscoverySchedulerConfiguration config = mockConfiguration();
        final HearingCaseForDay matchedCase = hearingCaseWithProsecutionCases(today, caseCount);
        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS)).thenReturn(List.of(today));
        when(discoverySchedulerConfigurationRepository.findLatestActiveConfigurations()).thenReturn(List.of(config));
        when(environment.getProperty(SYSTEM_USER_ID_ENV_KEY)).thenReturn(SYSTEM_USER_ID);
        when(hearingClient.getHearingCasesForDay(any(), any())).thenReturn(List.of(matchedCase));
        when(hearingCaseWhitelistSelector.findMatchingCases(any(), any())).thenReturn(List.of(matchedCase));
    }

    private List<ZonedDateTime> captureEntryStartTimes(final int expectedCount) {
        final ArgumentCaptor<ZonedDateTime> timeCaptor = ArgumentCaptor.forClass(ZonedDateTime.class);
        verify(jobManagerService, times(expectedCount))
                .dispatchCaseDocumentIngestionTasksCheckIdpcAvailability(any(JsonObject.class), timeCaptor.capture(), anyInt());
        return timeCaptor.getAllValues();
    }

    // ----- Option A2 (DD-43823): priority & ordering by hearing proximity -----

    @Test
    void runNightlyDiscovery_whenPriorityDisabled_keepsInsertionOrderAndDefaultPriority() {
        // given — dates seen far-first; A2 off => no reordering, default priority
        final DiscoveryService service = discoveryServiceWithPriority(false, 2);
        final UUID farCase = UUID.randomUUID();
        final UUID midCase = UUID.randomUUID();
        final UUID nearCase = UUID.randomUUID();
        stubThreeDatesFarFirst(farCase, midCase, nearCase);

        // when
        service.runNightlyDiscovery();

        // then
        final DispatchCapture capture = captureDispatch(3);
        assertThat(capture.caseIdsInOrder()).containsExactly(farCase, midCase, nearCase);
        assertThat(capture.priorities()).containsExactly(10, 10, 10);
    }

    @Test
    void runNightlyDiscovery_whenPriorityEnabled_dispatchesNearestHearingFirstWithProximityPriority() {
        // given — dates seen far-first; A2 on => reorder nearest-first, proximity priority
        final DiscoveryService service = discoveryServiceWithPriority(true, 2);
        final UUID farCase = UUID.randomUUID();
        final UUID midCase = UUID.randomUUID();
        final UUID nearCase = UUID.randomUUID();
        stubThreeDatesFarFirst(farCase, midCase, nearCase);

        // when
        service.runNightlyDiscovery();

        // then
        final DispatchCapture capture = captureDispatch(3);
        assertThat(capture.caseIdsInOrder()).containsExactly(nearCase, midCase, farCase);
        assertThat(capture.priorities()).containsExactly(2, 3, 4); // today=2, +1=3, +2=4
    }

    @Test
    void runNightlyDiscovery_caseOnMultipleDates_usesEarliestHearingForPriority() {
        // given — same case on today and +2; earliest (today) must win
        final DiscoveryService service = discoveryServiceWithPriority(true, 2);
        final LocalDate today = LocalDate.now();
        final UUID caseId = UUID.randomUUID();
        final HearingCaseForDay later = new HearingCaseForDay(
                UUID.randomUUID(), UUID.randomUUID(), today.plusDays(2), UUID.randomUUID(), List.of(caseId));
        final HearingCaseForDay earlier = new HearingCaseForDay(
                UUID.randomUUID(), UUID.randomUUID(), today, UUID.randomUUID(), List.of(caseId));

        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of(today.plusDays(2), today));
        when(discoverySchedulerConfigurationRepository.findLatestActiveConfigurations()).thenReturn(List.of(mockConfiguration()));
        when(environment.getProperty(SYSTEM_USER_ID_ENV_KEY)).thenReturn(SYSTEM_USER_ID);
        when(hearingClient.getHearingCasesForDay(eq(today.plusDays(2)), any())).thenReturn(List.of(later));
        when(hearingClient.getHearingCasesForDay(eq(today), any())).thenReturn(List.of(earlier));
        when(hearingCaseWhitelistSelector.findMatchingCases(eq(List.of(later)), any())).thenReturn(List.of(later));
        when(hearingCaseWhitelistSelector.findMatchingCases(eq(List.of(earlier)), any())).thenReturn(List.of(earlier));

        // when
        service.runNightlyDiscovery();

        // then
        final DispatchCapture capture = captureDispatch(1);
        assertThat(capture.caseIdsInOrder()).containsExactly(caseId);
        assertThat(capture.priorities()).containsExactly(2); // earliest hearing is today => 2
    }

    @Test
    void runNightlyDiscovery_priorityHighestBelowFloor_isClampedAboveManualHigh() {
        // given — misconfigured highest=1 (== manual HIGH) must floor at 2
        final DiscoveryService service = discoveryServiceWithPriority(true, 1);
        stubSingleDateWithMatchedCases(1); // 1 case, hearing today

        // when
        service.runNightlyDiscovery();

        // then
        assertThat(captureDispatch(1).priorities()).containsExactly(2);
    }

    @Test
    void runNightlyDiscovery_farFutureHearing_priorityClampedAtDefault() {
        // given — hearing far away: 2 + 20 = 22 must clamp to DEFAULT(10)
        final DiscoveryService service = discoveryServiceWithPriority(true, 2);
        final LocalDate today = LocalDate.now();
        final UUID caseId = UUID.randomUUID();
        final HearingCaseForDay far = new HearingCaseForDay(
                UUID.randomUUID(), UUID.randomUUID(), today.plusDays(20), UUID.randomUUID(), List.of(caseId));

        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS)).thenReturn(List.of(today.plusDays(20)));
        when(discoverySchedulerConfigurationRepository.findLatestActiveConfigurations()).thenReturn(List.of(mockConfiguration()));
        when(environment.getProperty(SYSTEM_USER_ID_ENV_KEY)).thenReturn(SYSTEM_USER_ID);
        when(hearingClient.getHearingCasesForDay(eq(today.plusDays(20)), any())).thenReturn(List.of(far));
        when(hearingCaseWhitelistSelector.findMatchingCases(eq(List.of(far)), any())).thenReturn(List.of(far));

        // when
        service.runNightlyDiscovery();

        // then
        assertThat(captureDispatch(1).priorities()).containsExactly(10);
    }

    private record DispatchCapture(List<UUID> caseIdsInOrder, List<Integer> priorities) {
    }

    private DispatchCapture captureDispatch(final int expectedCount) {
        final ArgumentCaptor<JsonObject> jobDataCaptor = ArgumentCaptor.forClass(JsonObject.class);
        final ArgumentCaptor<Integer> priorityCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(jobManagerService, times(expectedCount))
                .dispatchCaseDocumentIngestionTasksCheckIdpcAvailability(
                        jobDataCaptor.capture(), any(ZonedDateTime.class), priorityCaptor.capture());
        final List<UUID> caseIds = jobDataCaptor.getAllValues().stream()
                .map(jobData -> UUID.fromString(jobData.getString("caseId"))).toList();
        return new DispatchCapture(caseIds, priorityCaptor.getAllValues());
    }

    private void stubThreeDatesFarFirst(final UUID farCase, final UUID midCase, final UUID nearCase) {
        final LocalDate today = LocalDate.now();
        final HearingCaseForDay near = new HearingCaseForDay(
                UUID.randomUUID(), UUID.randomUUID(), today, UUID.randomUUID(), List.of(nearCase));
        final HearingCaseForDay mid = new HearingCaseForDay(
                UUID.randomUUID(), UUID.randomUUID(), today.plusDays(1), UUID.randomUUID(), List.of(midCase));
        final HearingCaseForDay far = new HearingCaseForDay(
                UUID.randomUUID(), UUID.randomUUID(), today.plusDays(2), UUID.randomUUID(), List.of(farCase));

        // calculator returns dates far-first so insertion order is far, mid, near
        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of(today.plusDays(2), today.plusDays(1), today));
        when(discoverySchedulerConfigurationRepository.findLatestActiveConfigurations()).thenReturn(List.of(mockConfiguration()));
        when(environment.getProperty(SYSTEM_USER_ID_ENV_KEY)).thenReturn(SYSTEM_USER_ID);
        when(hearingClient.getHearingCasesForDay(eq(today.plusDays(2)), any())).thenReturn(List.of(far));
        when(hearingClient.getHearingCasesForDay(eq(today.plusDays(1)), any())).thenReturn(List.of(mid));
        when(hearingClient.getHearingCasesForDay(eq(today), any())).thenReturn(List.of(near));
        when(hearingCaseWhitelistSelector.findMatchingCases(eq(List.of(far)), any())).thenReturn(List.of(far));
        when(hearingCaseWhitelistSelector.findMatchingCases(eq(List.of(mid)), any())).thenReturn(List.of(mid));
        when(hearingCaseWhitelistSelector.findMatchingCases(eq(List.of(near)), any())).thenReturn(List.of(near));
    }

    private DiscoveryService discoveryServiceWithPriority(final boolean enabled, final int highest) {
        final SchedulerProperties schedulerProperties = new SchedulerProperties();
        schedulerProperties.getNightlyDiscovery().setDaysAhead(NIGHTLY_DISCOVERY_DAYS);
        final SchedulerProperties.NightlyDiscovery.Priority priority =
                schedulerProperties.getNightlyDiscovery().getPriority();
        priority.setEnabled(enabled);
        priority.setHighest(highest);
        return new DiscoveryService(
                jobManagerService, scheduledIngestionRequestRepository, discoverySchedulerConfigurationRepository,
                hearingDaysCalculator, hearingClient, hearingCaseWhitelistSelector, schedulerProperties, environment,
                true);
    }

    private DiscoveryService discoveryServiceWithStagger(final boolean enabled, final double casesPerMinute,
                                                         final Duration maxWindow) {
        final SchedulerProperties schedulerProperties = new SchedulerProperties();
        schedulerProperties.getNightlyDiscovery().setDaysAhead(NIGHTLY_DISCOVERY_DAYS);
        final SchedulerProperties.NightlyDiscovery.Stagger stagger =
                schedulerProperties.getNightlyDiscovery().getStagger();
        stagger.setEnabled(enabled);
        stagger.setCasesPerMinute(casesPerMinute);
        stagger.setMaxWindow(maxWindow);
        return new DiscoveryService(
                jobManagerService, scheduledIngestionRequestRepository, discoverySchedulerConfigurationRepository,
                hearingDaysCalculator, hearingClient, hearingCaseWhitelistSelector, schedulerProperties, environment,
                true);
    }

    private DiscoveryService discoveryServiceWithHearingForCasesEnabled(final boolean isHearingForCasesEnabled) {
        final SchedulerProperties schedulerProperties = new SchedulerProperties();
        schedulerProperties.getNightlyDiscovery().setDaysAhead(NIGHTLY_DISCOVERY_DAYS);
        return new DiscoveryService(
                jobManagerService, scheduledIngestionRequestRepository, discoverySchedulerConfigurationRepository,
                hearingDaysCalculator, hearingClient, hearingCaseWhitelistSelector, schedulerProperties, environment,
                isHearingForCasesEnabled);
    }

    private DiscoverySchedulerConfiguration mockConfiguration() {
        return mock(DiscoverySchedulerConfiguration.class);
    }

    private DiscoverySchedulerConfiguration mockConfiguration(final UUID courtCentreId, final UUID courtRoomId) {
        final DiscoverySchedulerConfiguration configuration = mock(DiscoverySchedulerConfiguration.class);
        when(configuration.getCourtCentreId()).thenReturn(courtCentreId);
        when(configuration.getCourtRoomId()).thenReturn(courtRoomId);
        return configuration;
    }

    private HearingCaseForDay hearingCaseWithProsecutionCases(final LocalDate hearingDate, final int prosecutionCaseCount) {
        final List<UUID> prosecutionCases = new ArrayList<>();
        for (int i = 0; i < prosecutionCaseCount; i++) {
            prosecutionCases.add(UUID.randomUUID());
        }
        return new HearingCaseForDay(UUID.randomUUID(), UUID.randomUUID(), hearingDate, UUID.randomUUID(), prosecutionCases);
    }

    private ScheduledIngestionRequest mockRequest() {
        final ScheduledIngestionRequest request = mock(ScheduledIngestionRequest.class);

        when(request.getCppuid()).thenReturn(UUID.randomUUID());
        when(request.getCourtCentreId()).thenReturn(UUID.randomUUID());
        when(request.getCourtRoomId()).thenReturn(UUID.randomUUID());

        return request;
    }
}
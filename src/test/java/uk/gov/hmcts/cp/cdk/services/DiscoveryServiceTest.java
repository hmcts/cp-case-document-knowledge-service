package uk.gov.hmcts.cp.cdk.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingCaseProsecutionCase;
import uk.gov.hmcts.cp.cdk.domain.DiscoverySchedulerConfiguration;
import uk.gov.hmcts.cp.cdk.domain.ScheduledIngestionRequest;
import uk.gov.hmcts.cp.cdk.repo.DiscoverySchedulerConfigurationRepository;
import uk.gov.hmcts.cp.cdk.repo.ScheduledIngestionRequestRepository;
import uk.gov.hmcts.cp.cdk.scheduler.SchedulerProperties;

import java.time.LocalDate;
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
        final SchedulerProperties schedulerProperties = new SchedulerProperties();
        schedulerProperties.getNightlyDiscovery().setDaysAhead(NIGHTLY_DISCOVERY_DAYS);
        discoveryService = new DiscoveryService(
                jobManagerService, scheduledIngestionRequestRepository, discoverySchedulerConfigurationRepository,
                hearingDaysCalculator, hearingClient, hearingCaseWhitelistSelector, schedulerProperties, environment);
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
        verify(jobManagerService, times(2)).dispatchCaseDocumentIngestionTasksCheckCaseEligibility(any(JsonObject.class));
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
        verify(jobManagerService, times(3)).dispatchCaseDocumentIngestionTasksCheckCaseEligibility(any(JsonObject.class));
    }

    @Test
    void runNightlyDiscovery_shouldGenerateJobDataFromMatchedHearingCaseProsecutionCase() {
        // given
        final LocalDate today = LocalDate.now();
        final DiscoverySchedulerConfiguration config = mockConfiguration();
        final UUID caseId = UUID.randomUUID();
        final HearingCaseForDay matchedCase = new HearingCaseForDay(
                UUID.randomUUID(), UUID.randomUUID(), today, UUID.randomUUID(),
                List.of(new HearingCaseProsecutionCase(caseId)));

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
        verify(jobManagerService).dispatchCaseDocumentIngestionTasksCheckCaseEligibility(captor.capture());

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
                .dispatchCaseDocumentIngestionTasksCheckCaseEligibility(any(JsonObject.class));

        // when / then
        Assertions.assertThatCode(() -> discoveryService.runNightlyDiscovery())
                .doesNotThrowAnyException();

        verify(jobManagerService).dispatchCaseDocumentIngestionTasksCheckCaseEligibility(any(JsonObject.class));
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

        verify(jobManagerService, never()).dispatchCaseDocumentIngestionTasksCheckCaseEligibility(any());
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
        verify(jobManagerService, never()).dispatchCaseDocumentIngestionTasksCheckCaseEligibility(any());
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
        verify(jobManagerService, never()).dispatchCaseDocumentIngestionTasksCheckCaseEligibility(any());
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

    private DiscoverySchedulerConfiguration mockConfiguration() {
        return mock(DiscoverySchedulerConfiguration.class);
    }

    private HearingCaseForDay hearingCaseWithProsecutionCases(final LocalDate hearingDate, final int prosecutionCaseCount) {
        final List<HearingCaseProsecutionCase> prosecutionCases = new ArrayList<>();
        for (int i = 0; i < prosecutionCaseCount; i++) {
            prosecutionCases.add(new HearingCaseProsecutionCase(UUID.randomUUID()));
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
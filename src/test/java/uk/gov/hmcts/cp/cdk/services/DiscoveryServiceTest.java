package uk.gov.hmcts.cp.cdk.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.cdk.domain.DiscoverySchedulerConfiguration;
import uk.gov.hmcts.cp.cdk.domain.ScheduledIngestionRequest;
import uk.gov.hmcts.cp.cdk.repo.DiscoverySchedulerConfigurationRepository;
import uk.gov.hmcts.cp.cdk.repo.ScheduledIngestionRequestRepository;
import uk.gov.hmcts.cp.cdk.scheduler.SchedulerProperties;

import java.time.LocalDate;
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

@ExtendWith(MockitoExtension.class)
class DiscoveryServiceTest {

    private static final int NIGHTLY_DISCOVERY_DAYS = 3;

    @Mock
    private JobManagerService jobManagerService;

    @Mock
    private ScheduledIngestionRequestRepository scheduledIngestionRequestRepository;

    @Mock
    private DiscoverySchedulerConfigurationRepository discoverySchedulerConfigurationRepository;

    @Mock
    private HearingDaysCalculator hearingDaysCalculator;

    private DiscoveryService discoveryService;

    @BeforeEach
    void setUp() {
        final SchedulerProperties schedulerProperties = new SchedulerProperties();
        schedulerProperties.getNightlyDiscovery().setDaysAhead(NIGHTLY_DISCOVERY_DAYS);
        discoveryService = new DiscoveryService(
                jobManagerService, scheduledIngestionRequestRepository, discoverySchedulerConfigurationRepository,
                hearingDaysCalculator, schedulerProperties);
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

        verify(jobManagerService, times(2)).dispatchCaseDocumentIngestionTasks(captor.capture());

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
                .dispatchCaseDocumentIngestionTasks(any(JsonObject.class));

        // when
        Assertions.assertThatCode(() -> discoveryService.runIntradayDiscovery())
                .doesNotThrowAnyException();

        // then
        verify(jobManagerService, times(2)).dispatchCaseDocumentIngestionTasks(any(JsonObject.class));
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
        verify(jobManagerService, never()).dispatchCaseDocumentIngestionTasks(any());

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

        verify(jobManagerService).dispatchCaseDocumentIngestionTasks(captor.capture());

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

        // when
        discoveryService.runNightlyDiscovery();

        // then
        verify(discoverySchedulerConfigurationRepository, times(1)).findLatestActiveConfigurations();
        verifyNoInteractions(scheduledIngestionRequestRepository);
    }

    @Test
    void runNightlyDiscovery_shouldDispatchTasksForEveryConfigurationAndCalculatedDate() {
        // given
        final LocalDate today = LocalDate.now();
        final LocalDate tomorrow = today.plusDays(1);

        final DiscoverySchedulerConfiguration config1 = mockConfiguration();
        final DiscoverySchedulerConfiguration config2 = mockConfiguration();

        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of(today, tomorrow));
        when(discoverySchedulerConfigurationRepository.findLatestActiveConfigurations())
                .thenReturn(List.of(config1, config2));

        // when
        discoveryService.runNightlyDiscovery();

        // then
        verify(jobManagerService, times(4)).dispatchCaseDocumentIngestionTasks(any(JsonObject.class));
    }

    @Test
    void runNightlyDiscovery_shouldGenerateJobDataFromConfigurationAndHearingDate() {
        // given
        final LocalDate today = LocalDate.now();
        final UUID courtCentreId = UUID.randomUUID();
        final UUID courtRoomId = UUID.randomUUID();
        final DiscoverySchedulerConfiguration config = mockConfiguration(courtCentreId, courtRoomId);

        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of(today));
        when(discoverySchedulerConfigurationRepository.findLatestActiveConfigurations())
                .thenReturn(List.of(config));

        // when
        discoveryService.runNightlyDiscovery();

        // then
        final ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
        verify(jobManagerService).dispatchCaseDocumentIngestionTasks(captor.capture());

        final JsonObject jobData = captor.getValue();
        assertThat(jobData.getString("courtCentreId")).isEqualTo(courtCentreId.toString());
        assertThat(jobData.getString("roomId")).isEqualTo(courtRoomId.toString());
        assertThat(jobData.getString("date")).isEqualTo(today.toString());
        assertThat(jobData.getString("requestId")).isNotBlank();
        assertThat(UUID.fromString(jobData.getString("cppuid"))).isNotNull();
    }

    @Test
    void runNightlyDiscovery_shouldContinueWhenDispatchFails() {
        // given
        final LocalDate today = LocalDate.now();
        final DiscoverySchedulerConfiguration config = mockConfiguration();

        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of(today));
        when(discoverySchedulerConfigurationRepository.findLatestActiveConfigurations())
                .thenReturn(List.of(config));
        doThrow(new RuntimeException("Dispatch failed"))
                .when(jobManagerService)
                .dispatchCaseDocumentIngestionTasks(any(JsonObject.class));

        // when / then
        Assertions.assertThatCode(() -> discoveryService.runNightlyDiscovery())
                .doesNotThrowAnyException();

        verify(jobManagerService).dispatchCaseDocumentIngestionTasks(any(JsonObject.class));
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

        // when
        discoveryService.runNightlyDiscovery();

        // then
        verify(jobManagerService, never()).dispatchCaseDocumentIngestionTasks(any());
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

        // when / then
        Assertions.assertThatCode(() -> discoveryService.runNightlyDiscovery())
                .doesNotThrowAnyException();

        verify(jobManagerService, never()).dispatchCaseDocumentIngestionTasks(any());
    }

    private DiscoverySchedulerConfiguration mockConfiguration() {
        return mockConfiguration(UUID.randomUUID(), UUID.randomUUID());
    }

    private DiscoverySchedulerConfiguration mockConfiguration(final UUID courtCentreId, final UUID courtRoomId) {
        final DiscoverySchedulerConfiguration configuration = mock(DiscoverySchedulerConfiguration.class);
        when(configuration.getCourtCentreId()).thenReturn(courtCentreId);
        when(configuration.getCourtRoomId()).thenReturn(courtRoomId);
        return configuration;
    }

    private ScheduledIngestionRequest mockRequest() {
        final ScheduledIngestionRequest request = mock(ScheduledIngestionRequest.class);

        when(request.getCppuid()).thenReturn(UUID.randomUUID());
        when(request.getCourtCentreId()).thenReturn(UUID.randomUUID());
        when(request.getCourtRoomId()).thenReturn(UUID.randomUUID());

        return request;
    }
}
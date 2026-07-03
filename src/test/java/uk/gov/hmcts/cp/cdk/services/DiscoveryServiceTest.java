package uk.gov.hmcts.cp.cdk.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.cdk.domain.ScheduledIngestionRequest;
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
    private HearingDaysCalculator hearingDaysCalculator;

    private DiscoveryService discoveryService;

    @BeforeEach
    void setUp() {
        final SchedulerProperties schedulerProperties = new SchedulerProperties();
        schedulerProperties.getNightlyDiscovery().setDaysAhead(NIGHTLY_DISCOVERY_DAYS);
        discoveryService = new DiscoveryService(
                jobManagerService, scheduledIngestionRequestRepository, hearingDaysCalculator, schedulerProperties);
    }

    @Test
    void runIntradayDiscovery_shouldDispatchTasksForAllRequests() {
        // given
        final LocalDate today = LocalDate.now();

        final ScheduledIngestionRequest request1 = mockRequest(today);
        final ScheduledIngestionRequest request2 = mockRequest(today);

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

        final ScheduledIngestionRequest request1 = mockRequest(today);
        final ScheduledIngestionRequest request2 = mockRequest(today);

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
        when(request.getHearingDate()).thenReturn(hearingDate);

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
    void runNightlyDiscovery_shouldQueryRepositoryForEachCalculatedDate() {
        // given
        final LocalDate today = LocalDate.now();
        final LocalDate tomorrow = today.plusDays(1);
        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of(today, tomorrow));

        // when
        discoveryService.runNightlyDiscovery();

        // then
        verify(scheduledIngestionRequestRepository).findAllByHearingDate(today);
        verify(scheduledIngestionRequestRepository).findAllByHearingDate(tomorrow);
        verifyNoMoreInteractions(scheduledIngestionRequestRepository);
    }

    @Test
    void runNightlyDiscovery_shouldDispatchTasksForAllRequestsAcrossCalculatedDates() {
        // given
        final LocalDate today = LocalDate.now();
        final LocalDate tomorrow = today.plusDays(1);

        final ScheduledIngestionRequest request1 = mockRequest(today);
        final ScheduledIngestionRequest request2 = mockRequest(tomorrow);

        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of(today, tomorrow));
        when(scheduledIngestionRequestRepository.findAllByHearingDate(today))
                .thenReturn(List.of(request1));
        when(scheduledIngestionRequestRepository.findAllByHearingDate(tomorrow))
                .thenReturn(List.of(request2));

        // when
        discoveryService.runNightlyDiscovery();

        // then
        verify(jobManagerService, times(2)).dispatchCaseDocumentIngestionTasks(any(JsonObject.class));
    }

    @Test
    void runNightlyDiscovery_shouldContinueWhenDispatchFails() {
        // given
        final LocalDate today = LocalDate.now();
        final ScheduledIngestionRequest request = mockRequest(today);

        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of(today));
        when(scheduledIngestionRequestRepository.findAllByHearingDate(today))
                .thenReturn(List.of(request));
        doThrow(new RuntimeException("Dispatch failed"))
                .when(jobManagerService)
                .dispatchCaseDocumentIngestionTasks(any(JsonObject.class));

        // when / then
        Assertions.assertThatCode(() -> discoveryService.runNightlyDiscovery())
                .doesNotThrowAnyException();
    }

    @Test
    void runNightlyDiscovery_shouldNotDispatchWhenNoRequestsExistForCalculatedDates() {
        // given
        final LocalDate today = LocalDate.now();
        final LocalDate tomorrow = today.plusDays(1);
        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of(today, tomorrow));

        // when
        discoveryService.runNightlyDiscovery();

        // then
        verify(scheduledIngestionRequestRepository).findAllByHearingDate(today);
        verify(scheduledIngestionRequestRepository).findAllByHearingDate(tomorrow);
        verify(jobManagerService, never()).dispatchCaseDocumentIngestionTasks(any());
    }

    @Test
    void runNightlyDiscovery_shouldNotThrowWhenCalculatorReturnsEmpty() {
        // given
        final LocalDate today = LocalDate.now();
        when(hearingDaysCalculator.calculate(today, NIGHTLY_DISCOVERY_DAYS))
                .thenReturn(List.of());

        // when / then
        Assertions.assertThatCode(() -> discoveryService.runNightlyDiscovery())
                .doesNotThrowAnyException();
    }

    private ScheduledIngestionRequest mockRequest(LocalDate hearingDate) {
        final ScheduledIngestionRequest request = mock(ScheduledIngestionRequest.class);

        when(request.getCppuid()).thenReturn(UUID.randomUUID());
        when(request.getCourtCentreId()).thenReturn(UUID.randomUUID());
        when(request.getCourtRoomId()).thenReturn(UUID.randomUUID());
        when(request.getHearingDate()).thenReturn(hearingDate);

        return request;
    }
}
package uk.gov.hmcts.cp.cdk.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.cdk.domain.ScheduledIngestionRequest;
import uk.gov.hmcts.cp.cdk.repo.ScheduledIngestionRequestRepository;

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

    @Mock
    private JobManagerService jobManagerService;

    @Mock
    private ScheduledIngestionRequestRepository scheduledIngestionRequestRepository;

    private DiscoveryService discoveryService;

    @BeforeEach
    void setUp() {
        discoveryService = new DiscoveryService(jobManagerService, scheduledIngestionRequestRepository);
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

    private ScheduledIngestionRequest mockRequest(LocalDate hearingDate) {
        final ScheduledIngestionRequest request = mock(ScheduledIngestionRequest.class);

        when(request.getCppuid()).thenReturn(UUID.randomUUID());
        when(request.getCourtCentreId()).thenReturn(UUID.randomUUID());
        when(request.getCourtRoomId()).thenReturn(UUID.randomUUID());
        when(request.getHearingDate()).thenReturn(hearingDate);

        return request;
    }
}
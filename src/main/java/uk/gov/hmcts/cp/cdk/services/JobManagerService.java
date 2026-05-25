package uk.gov.hmcts.cp.cdk.services;

import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.GET_CASES_FOR_HEARING;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;

import uk.gov.hmcts.cp.cdk.domain.ScheduledIngestionRequest;
import uk.gov.hmcts.cp.cdk.repo.ScheduledIngestionRequestRepository;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessPhase;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessResponse;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.UUID;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobManagerService implements IngestionProcessor {

    private final ExecutionService executor;
    private final ScheduledIngestionRequestRepository scheduledIngestionRequestRepository;

    private static String sanitizeForLog(final String value) {
        if (value == null) {
            return null;
        }
        final StringBuilder sanitized = new StringBuilder(value.length());
        value.codePoints().forEach(cp -> {
            if (Character.isISOControl(cp)) {
                sanitized.append('?');
            } else {
                sanitized.appendCodePoint(cp);
            }
        });
        return sanitized.toString();
    }

    @Override
    public IngestionProcessResponse startIngestionProcess(final String cppuid,
                                                          final IngestionProcessRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        final String requestId = UUID.randomUUID().toString();

        final JsonObject jobData = Json.createObjectBuilder()
                .add("cppuid", cppuid)
                .add("requestId", requestId)
                .add("courtCentreId", request.getCourtCentreId().toString())
                .add("roomId", request.getRoomId().toString())
                .add("date", request.getDate().toString())
                .build();

        final IngestionProcessResponse response = new IngestionProcessResponse();
        response.setLastUpdated(OffsetDateTime.now());
        final String safeCppuid = sanitizeForLog(cppuid);

        // Only persist if not already exists
        boolean alreadyExists =
                scheduledIngestionRequestRepository
                        .existsByCourtCentreIdAndCourtRoomIdAndHearingDate(
                                request.getCourtCentreId(),
                                request.getRoomId(),
                                request.getDate()
                        );

        if (!alreadyExists) {
            ScheduledIngestionRequest entity = new ScheduledIngestionRequest();
            entity.setId(UUID.randomUUID());
            entity.setCourtCentreId(request.getCourtCentreId());
            entity.setCourtRoomId(request.getRoomId());
            entity.setHearingDate(request.getDate());
            entity.setCppuid(UUID.fromString(cppuid));
            entity.setCreatedAt(OffsetDateTime.now());

            scheduledIngestionRequestRepository.save(entity);

            log.info("Persisted ScheduledIngestionRequest for courtCentreId={}, roomId={}, hearingDate ={}", request.getCourtCentreId(), request.getRoomId(), request.getDate());
        } else {
            log.info("ScheduledIngestionRequest already exists for courtCentreId={}, roomId={}, hearingDate={}",
                    request.getCourtCentreId(), request.getRoomId(), request.getDate());
        }

        try {
            final ExecutionInfo executionInfo = executionInfo()
                    .withAssignedTaskName(GET_CASES_FOR_HEARING)
                    .withAssignedTaskStartTime(ZonedDateTime.now())
                    .withJobData(jobData)
                    .withExecutionStatus(ExecutionStatus.STARTED)
                    .build();

            executor.executeWith(executionInfo);
            log.info("Case ingestion process started via JobManager. requestId={}, cppuid={}", requestId, safeCppuid);

            response.setPhase(IngestionProcessPhase.STARTED);
            response.setMessage(
                    "Ingestion workflow request accepted; task submitted via JobManager (requestId=%s)".formatted(requestId)
            );

        } catch (Exception e) {
            log.error("Failed to start case ingestion workflow via JobManager. requestId={}, cppuid={}", requestId, safeCppuid, e);
            response.setPhase(IngestionProcessPhase.FAILED);
            response.setMessage(
                    "Failed to submit ingestion workflow via JobManager (requestId=%s): %s"
                            .formatted(requestId, e.getMessage())
            );
        }


        return response;
    }
}

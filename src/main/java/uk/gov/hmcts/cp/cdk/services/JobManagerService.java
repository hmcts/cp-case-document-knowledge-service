package uk.gov.hmcts.cp.cdk.services;

import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessPhase;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessResponse;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;

import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.UUID;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.GET_CASES_FOR_HEARING;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobManagerService {


    private final ExecutionService workflowExecutor;

    public IngestionProcessResponse startIngestionProcessThroughJobManager(final String cppuid,
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

        try {
            ExecutionInfo executionInfo = ExecutionInfo.executionInfo()
                    .withAssignedTaskName(GET_CASES_FOR_HEARING)
                    .withAssignedTaskStartTime(ZonedDateTime.now())
                    .withJobData(jobData)
                    .withExecutionStatus(ExecutionStatus.STARTED)
                    .build();

            workflowExecutor.executeWith(executionInfo);
            log.info("Case ingestion workflow started via JobManager. requestId={}, cppuid={}", requestId, cppuid);
        } catch (Exception e) {
            log.error("Failed to start case ingestion workflow via JobManager. requestId={}, cppuid={}", requestId, cppuid, e);
        }


        final IngestionProcessResponse response = new IngestionProcessResponse();
        response.setPhase(IngestionProcessPhase.STARTED);
        response.setMessage("Ingestion workflow request accepted; task submitted via JobManager (requestId=%s)".formatted(requestId));
        return response;
    }
}

package uk.gov.hmcts.cp.cdk.services;

import uk.gov.hmcts.cp.cdk.batch.support.IngestionJobParams;
import uk.gov.hmcts.cp.cdk.repo.IngestionStatusViewRepository;
import uk.gov.hmcts.cp.openapi.model.cdk.DocumentIngestionPhase;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessPhase;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessResponse;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionStatusResponse;
import uk.gov.hmcts.cp.openapi.model.cdk.Scope;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.UUID;

import com.taskmanager.domain.ExecutionInfo;
import com.taskmanager.domain.ExecutionStatus;
import com.taskmanager.service.ExecutionService;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@RequiredArgsConstructor
@Slf4j
public class IngestionService {

    private final IngestionStatusViewRepository repo;
    private final JobOperator jobOperator;
    private final Job caseIngestionJob;
    private final TaskExecutor ingestionStarterExecutor;

    private final ExecutionService workflowExecutor;// injected executor

    @Transactional(readOnly = true)
    public IngestionStatusResponse getStatus(final UUID caseId) {
        final IngestionStatusResponse resp = new IngestionStatusResponse();
        final Scope scope = new Scope();
        scope.setCaseId(caseId);
        resp.setScope(scope);

        return repo.findByCaseId(caseId)
                .map(r -> {
                    resp.setPhase(DocumentIngestionPhase.fromValue(r.phase()));
                    resp.setLastUpdated(r.lastUpdated());
                    resp.setMessage(null);
                    return resp;
                })
                .orElseGet(() -> {
                    resp.setPhase(DocumentIngestionPhase.NOT_FOUND);
                    resp.setLastUpdated(null);
                    resp.setMessage("No uploads seen for this case");
                    return resp;
                });
    }

    public IngestionProcessResponse startIngestionProcess(final String cppuid,
                                                          final IngestionProcessRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        final JobParameters baseParams = IngestionJobParams.build(cppuid, request, Clock.systemUTC());
        final String requestId = UUID.randomUUID().toString();

        final JobParameters params = new JobParametersBuilder(baseParams)
                .addString("requestId", requestId, true)
                .toJobParameters();

        ingestionStarterExecutor.execute(() -> {
            try {
                jobOperator.start(caseIngestionJob, params);
                log.info("Ingestion job submitted asynchronously. requestId={}, cppuid={}", requestId, cppuid);
            } catch (JobExecutionAlreadyRunningException
                     | JobRestartException
                     | JobInstanceAlreadyCompleteException e) {
                log.error("Failed to start ingestion job asynchronously. requestId={}, cppuid={}, error={}",
                        requestId, cppuid, e.getMessage(), e);
            } catch (Exception e) {
                log.error("Unexpected error while starting ingestion job asynchronously. requestId={}, cppuid={}",
                        requestId, cppuid, e);
            }
        });

        final IngestionProcessResponse response = new IngestionProcessResponse();
        response.setPhase(IngestionProcessPhase.STARTED);
        response.setMessage("Ingestion request accepted; job will start asynchronously (requestId=%s)".formatted(requestId));
        return response;
    }

    // -------------------------
    // New TaskManager method
    // -------------------------
    public IngestionProcessResponse startIngestionProcessThroughJobManager(final String cppuid,
                                                                           final IngestionProcessRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        final String requestId = UUID.randomUUID().toString();
        log.info("starting startIngestionProcessThroughJobManager ");
        // Build JSON job data
        final JsonObject jobData = Json.createObjectBuilder()
                .add("cppuid", cppuid)
                .add("requestId", requestId)
                .add("courtCentreId", request.getCourtCentreId().toString())
                .add("roomId", request.getRoomId().toString())
                .add("date", request.getDate().toString())
                .build();

            try {
                ExecutionInfo executionInfo = ExecutionInfo.executionInfo()
                        .withAssignedTaskName("FETCH_HEARINGS_CASES_TASK")
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

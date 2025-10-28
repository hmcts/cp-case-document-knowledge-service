package uk.gov.hmcts.cp.cdk.services;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.job.parameters.JobParametersInvalidException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.cp.cdk.repo.IngestionStatusViewRepository;
import uk.gov.hmcts.cp.openapi.model.cdk.*;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;


@Service
public class IngestionService {

    private final IngestionStatusViewRepository repo;
    private final JobOperator jobOperator;
    private final Job caseIngestionJob;

    public IngestionService(final IngestionStatusViewRepository repo,final JobOperator jobOperator, final Job caseIngestionJob) {
        this.repo = repo;
        this.jobOperator = Objects.requireNonNull(jobOperator);
        this.caseIngestionJob = Objects.requireNonNull(caseIngestionJob);
    }

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


    @Transactional
    public IngestionProcessResponse startIngestionProcess(final IngestionProcessRequest request)
            throws JobInstanceAlreadyCompleteException,
            JobExecutionAlreadyRunningException,
            JobParametersInvalidException,
            JobRestartException,
            NoSuchJobException {

        Objects.requireNonNull(request, "request must not be null");
        final UUID courtCentreId = Objects.requireNonNull(request.getCourtCentreId(), "courtCentreId must not be null");
        final UUID roomId = Objects.requireNonNull(request.getRoomId(), "roomId must not be null");
        final LocalDate requestedDate = Objects.requireNonNull(request.getDate(), "date must not be null");

        final JobParameters params = new JobParametersBuilder()
                .addString("courtCentreId", courtCentreId.toString())
                .addString("roomId", roomId.toString())
                .addString("date", requestedDate.toString())
                .addLong("run", System.currentTimeMillis())
                .toJobParameters();

        final JobExecution execution = jobOperator.start(caseIngestionJob, params);

        final IngestionProcessResponse response = new IngestionProcessResponse();
        response.setPhase(IngestionProcessPhase.STARTED);
        response.setMessage("Ingestion process started successfully (executionId=%d)".formatted(execution.getId()));
        return response;
    }
}

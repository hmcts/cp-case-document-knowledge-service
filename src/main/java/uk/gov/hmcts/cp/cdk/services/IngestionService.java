package uk.gov.hmcts.cp.cdk.services;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersInvalidException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.cp.cdk.batch.IngestionJobParams;
import uk.gov.hmcts.cp.cdk.repo.IngestionStatusViewRepository;
import uk.gov.hmcts.cp.openapi.model.cdk.*;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;


@Service
public class IngestionService {

    private final IngestionStatusViewRepository repo;
    private final JobOperator jobOperator;
    private final Job caseIngestionJob;

    public IngestionService(final IngestionStatusViewRepository repo, final JobOperator jobOperator, final Job caseIngestionJob) {
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

    public IngestionProcessResponse startIngestionProcess(final String cppuid, final IngestionProcessRequest request)
            throws JobInstanceAlreadyCompleteException,
            JobExecutionAlreadyRunningException,
            JobParametersInvalidException,
            JobRestartException,
            NoSuchJobException {

        Objects.requireNonNull(request, "request must not be null");

        final JobParameters params = IngestionJobParams.build(cppuid, request, Clock.systemUTC());

        final JobExecution execution = jobOperator.start(caseIngestionJob, params);

        final IngestionProcessResponse response = new IngestionProcessResponse();
        response.setPhase(IngestionProcessPhase.STARTED);
        response.setMessage("Ingestion process started successfully (executionId=%d)".formatted(execution.getId()));
        return response;
    }
}

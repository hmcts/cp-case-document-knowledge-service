package uk.gov.hmcts.cp.cdk.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.cp.cdk.repo.IngestionStatusViewRepository;
import uk.gov.hmcts.cp.openapi.model.cdk.DocumentIngestionPhase;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionStatusResponse;
import uk.gov.hmcts.cp.openapi.model.cdk.Scope;

import java.util.UUID;


@Service
@Transactional(readOnly = true)
public class IngestionService {

    private final IngestionStatusViewRepository repo;

    public IngestionService(final IngestionStatusViewRepository repo) {
        this.repo = repo;
    }

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
}

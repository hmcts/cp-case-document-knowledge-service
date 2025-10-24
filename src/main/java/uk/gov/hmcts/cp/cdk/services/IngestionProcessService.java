package uk.gov.hmcts.cp.cdk.services;


import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessPhase;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessResponse;

@Service
@Transactional(readOnly = true)
public class IngestionProcessService {


    public IngestionProcessResponse getIngestionStatus(final IngestionProcessRequest request) {
        IngestionProcessResponse response = new IngestionProcessResponse();
        response.setPhase(IngestionProcessPhase.STARTED);
        response.setMessage("Ingestion process started succesfully");

        return response;
    }
}

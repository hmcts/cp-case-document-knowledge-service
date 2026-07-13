package uk.gov.hmcts.cp.cdk.services;

import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessByCaseRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessResponse;

public interface IngestionProcessorByCase {
    IngestionProcessResponse startIngestionProcess(String cppuid, IngestionProcessByCaseRequest req);
}

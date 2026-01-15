package uk.gov.hmcts.cp.cdk.services;

import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessResponse;

public interface IngestionProcessor  {
    IngestionProcessResponse startIngestionProcess(String cppuid, IngestionProcessRequest req);
}

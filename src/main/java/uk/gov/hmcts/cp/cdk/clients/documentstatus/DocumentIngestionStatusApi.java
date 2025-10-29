package uk.gov.hmcts.cp.cdk.clients.documentstatus;

import java.util.Optional;

/**
 * API interface for checking document ingestion status via APIM/Function App.
 * This interface represents the contract for calling the DocumentStatusCheck function.
 */
public interface DocumentIngestionStatusApi {

    Optional<DocumentStatusResponse> checkDocumentStatus(String documentName);
}


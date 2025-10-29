package uk.gov.hmcts.cp.cdk.clients.documentstatus;

import java.util.Optional;

public interface DocumentStatusClient {

    /**
     * Checks the status of a document in Azure Table Storage via the Function App.
     *
     * // TODO this needs to be implemented since on the function app side we have implemented with doucment name being uuid
     * // we will have to change on the function app side
     * @param documentName The name of the document to check (e.g., "{materialId}_{date}.pdf") th
     * @return Optional containing DocumentStatusResponse if found (200 OK), empty if not found (404)
     */
    Optional<DocumentStatusResponse> checkDocumentStatus(String documentName);
}


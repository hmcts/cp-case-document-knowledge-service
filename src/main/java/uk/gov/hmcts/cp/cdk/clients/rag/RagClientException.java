package uk.gov.hmcts.cp.cdk.clients.rag;

public class RagClientException extends RuntimeException {
    public RagClientException(String message, Throwable cause) {
        super(message, cause);
    }
}

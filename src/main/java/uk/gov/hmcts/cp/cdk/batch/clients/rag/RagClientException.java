package uk.gov.hmcts.cp.cdk.batch.clients.rag;

public class RagClientException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RagClientException(final String message, final Throwable cause) {
        super(message, cause);
    }
}

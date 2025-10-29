package uk.gov.hmcts.cp.cdk.clients.documentstatus;

public class DocumentStatusCheckException extends RuntimeException {

    public DocumentStatusCheckException(String message) {
        super(message);
    }

    public DocumentStatusCheckException(String message, Throwable cause) {
        super(message, cause);
    }
}


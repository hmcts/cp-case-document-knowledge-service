package uk.gov.hmcts.cp.filters.jwt;

public class InvalidJWTException extends Exception {
    public InvalidJWTException(String message) {
        super(message);
    }
}

package uk.gov.hmcts.cp.filters.jwt;

public class InvalidJWTException extends Exception {
    @java.io.Serial
    private static final long serialVersionUID = -3387516993124229948L;

    public InvalidJWTException(final String message) {
        super(message);
    }
}

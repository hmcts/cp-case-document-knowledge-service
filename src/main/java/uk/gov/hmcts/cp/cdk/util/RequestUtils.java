package uk.gov.hmcts.cp.cdk.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

/**
 * Lightweight utilities for reading request-scoped data in controllers
 * without repeating boilerplate. Kept package-visible and dependency-free.
 */
public final class RequestUtils {

    private RequestUtils() {
    }

    /**
     * Obtain the current HttpServletRequest or throw 500 if not in a servlet request context.
     */
    public static HttpServletRequest currentRequest() {
        final RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes sra)) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No servlet request in context");
        }
        return sra.getRequest();
    }

    /**
     * Read a required header and translate absence into a 400 Bad Request.
     */
    public static String requireHeader(final String headerName) {
        final String value = currentRequest().getHeader(headerName);
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required header: " + headerName);
        }
        return value;
    }

}

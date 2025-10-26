package uk.gov.hmcts.cp.cdk.controllers.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.cp.cdk.controllers.IngestionProcessController;
import uk.gov.hmcts.cp.openapi.model.cdk.ErrorResponse;

import static uk.gov.hmcts.cp.cdk.controllers.IngestionProcessController.VND_INGESTION;

@RestControllerAdvice(assignableTypes = IngestionProcessController.class)
public class IngestionExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(final ResponseStatusException exception) {
        final ErrorResponse error = new ErrorResponse();
        error.setMessage(exception.getReason());
        return ResponseEntity.status(exception.getStatusCode())
                .contentType(VND_INGESTION)
                .body(error);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(final HttpMessageNotReadableException exception) {
        final ErrorResponse error = new ErrorResponse();
        error.setMessage("invalid parameters");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(VND_INGESTION)
                .body(error);
    }
}

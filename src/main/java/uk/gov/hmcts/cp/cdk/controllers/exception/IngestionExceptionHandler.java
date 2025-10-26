package uk.gov.hmcts.cp.cdk.controllers.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.cp.cdk.controllers.IngestionProcessController;
import uk.gov.hmcts.cp.openapi.model.cdk.ErrorResponse;

import static uk.gov.hmcts.cp.cdk.controllers.IngestionProcessController.VND_INGESTION;

@RestControllerAdvice(assignableTypes = IngestionProcessController.class)
public class IngestionExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(final ResponseStatusException ex) {
        ErrorResponse body = new ErrorResponse();
        body.setMessage(ex.getReason());
        return ResponseEntity.status(ex.getStatusCode())
                .contentType(VND_INGESTION)
                .body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(final HttpMessageNotReadableException ex) {
        ErrorResponse body = new ErrorResponse();
        body.setMessage("invalid parameters");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(VND_INGESTION)
                .body(body);
    }
}
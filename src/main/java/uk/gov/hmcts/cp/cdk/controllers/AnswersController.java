package uk.gov.hmcts.cp.cdk.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.cdk.services.AnswerService;
import uk.gov.hmcts.cp.openapi.api.cdk.AnswersApi;
import uk.gov.hmcts.cp.openapi.model.cdk.AnswerResponse;
import uk.gov.hmcts.cp.openapi.model.cdk.AnswerWithLlmResponse;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Implementation of AnswersApi generated contract.
 * Mappings, parameter validation, and media types come from the interface.
 */
@RestController
public class AnswersController implements AnswersApi {

    private final AnswerService service;

    public AnswersController(final AnswerService service) {
        this.service = service;
    }

    @Override
    @SuppressWarnings("PMD.ShortVariable") // 'at' name comes from the OpenAPI contract
    public ResponseEntity<AnswerResponse> getAnswerByCaseAndQuery(
            final UUID caseId,
            final UUID queryId,
            final Integer version,
            final OffsetDateTime at
    ) {
        final AnswerResponse body = service.getAnswer(queryId, caseId, version, at);
        return ResponseEntity.ok(body);
    }

    @Override
    @SuppressWarnings("PMD.ShortVariable") // 'at' name comes from the OpenAPI contract
    public ResponseEntity<AnswerWithLlmResponse> getAnswerWithLlmByCaseAndQuery(
            final UUID caseId,
            final UUID queryId,
            final Integer version,
            final OffsetDateTime at
    ) {
        final AnswerWithLlmResponse body = service.getAnswerWithLlm(queryId, caseId, version, at);
        return ResponseEntity.ok(body);
    }
}

package uk.gov.hmcts.cp.cdk.controllers;

import uk.gov.hmcts.cp.cdk.services.AnswerService;
import uk.gov.hmcts.cp.openapi.api.cdk.AnswersApi;
import uk.gov.hmcts.cp.openapi.model.cdk.AnswerResponse;
import uk.gov.hmcts.cp.openapi.model.cdk.AnswerWithLlmResponse;

import java.time.OffsetDateTime;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers API controller.
 * Implements the OpenAPI-generated {@link AnswersApi} and delegates to {@link AnswerService}.
 * Logging is lightweight and structured; exceptions are handled centrally by GlobalExceptionHandler.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AnswersController implements AnswersApi {

    private final AnswerService service;

    @Override
    @SuppressWarnings("PMD.ShortVariable") // 'at' is defined by the OpenAPI contract
    public ResponseEntity<AnswerResponse> getAnswerByCaseAndQuery(
            final UUID caseId,
            final UUID queryId,
            final Integer version,
            final OffsetDateTime at
    ) {
        log.debug("getAnswerByCaseAndQuery caseId={}, queryId={}, version={}, at={}", caseId, queryId, version, at);
        final AnswerResponse body = service.getAnswer(queryId, caseId, version, at);
        return ResponseEntity.ok(body);
    }

    @Override
    @SuppressWarnings("PMD.ShortVariable") // 'at' is defined by the OpenAPI contract
    public ResponseEntity<AnswerWithLlmResponse> getAnswerWithLlmByCaseAndQuery(
            final UUID caseId,
            final UUID queryId,
            final Integer version,
            final OffsetDateTime at
    ) {
        log.debug("getAnswerWithLlmByCaseAndQuery caseId={}, queryId={}, version={}, at={}", caseId, queryId, version, at);
        final AnswerWithLlmResponse body = service.getAnswerWithLlm(queryId, caseId, version, at);
        return ResponseEntity.ok(body);
    }
}

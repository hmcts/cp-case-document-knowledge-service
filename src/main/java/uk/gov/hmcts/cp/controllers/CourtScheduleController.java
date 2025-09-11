package uk.gov.hmcts.cp.controllers;

import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.cp.openapi.api.CourtScheduleApi;
import uk.gov.hmcts.cp.openapi.model.CourtScheduleResponse;
import uk.gov.hmcts.cp.services.CourtScheduleService;

@RestController
public class CourtScheduleController implements CourtScheduleApi {
    private static final Logger LOG = LoggerFactory.getLogger(CourtScheduleController.class);
    private final CourtScheduleService courtScheduleService;

    public CourtScheduleController(final CourtScheduleService courtScheduleService) {
        this.courtScheduleService = courtScheduleService;
    }

    @Override
    public ResponseEntity<CourtScheduleResponse> getCourtScheduleByCaseUrn(final String caseUrn) {
        final String sanitizedCaseUrn;
        final CourtScheduleResponse courtScheduleResponse;
        try {
            sanitizedCaseUrn = sanitizeCaseUrn(caseUrn);
            courtScheduleResponse = courtScheduleService.getCourtScheduleByCaseUrn(sanitizedCaseUrn);
        } catch (ResponseStatusException e) {
            LOG.atError().log(e.getMessage());
            throw e;
        }
        LOG.atDebug().log("Found court schedule for caseUrn: {}", sanitizedCaseUrn);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(courtScheduleResponse);
    }

    private String sanitizeCaseUrn(final String urn) {
        if (urn == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "caseUrn is required");
        }
        return StringEscapeUtils.escapeHtml4(urn);
    }
}

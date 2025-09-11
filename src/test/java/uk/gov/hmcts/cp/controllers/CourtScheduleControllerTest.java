package uk.gov.hmcts.cp.controllers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.cp.openapi.model.CourtSchedule;
import uk.gov.hmcts.cp.openapi.model.CourtScheduleResponse;

import uk.gov.hmcts.cp.openapi.model.CourtSitting;
import uk.gov.hmcts.cp.openapi.model.Hearing;
import uk.gov.hmcts.cp.repositories.CourtScheduleRepository;
import uk.gov.hmcts.cp.repositories.InMemoryCourtScheduleRepositoryImpl;
import uk.gov.hmcts.cp.services.CourtScheduleService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CourtScheduleControllerTest {

    private static final Logger log = LoggerFactory.getLogger(CourtScheduleControllerTest.class);

    private CourtScheduleController courtScheduleController;

    @BeforeEach
    void setUp() {
        CourtScheduleService courtScheduleService = new CourtScheduleService(new InMemoryCourtScheduleRepositoryImpl());
        courtScheduleController = new CourtScheduleController(courtScheduleService);
    }

    @Test
    void getJudgeById_ShouldReturnJudgesWithOkStatus() {
        UUID caseUrn = UUID.randomUUID();
        log.info("Calling courtScheduleController.getCourtScheduleByCaseUrn with caseUrn: {}", caseUrn);
        ResponseEntity<?> response = courtScheduleController.getCourtScheduleByCaseUrn(caseUrn.toString());

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        CourtScheduleResponse responseBody = (CourtScheduleResponse) response.getBody();
        assertNotNull(responseBody);
        assertNotNull(responseBody.getCourtSchedule());
        assertEquals(1, responseBody.getCourtSchedule().size());

        CourtSchedule courtSchedule = responseBody.getCourtSchedule().get(0);
        assertNotNull(courtSchedule.getHearings());
        assertEquals(1, courtSchedule.getHearings().size());

        Hearing hearing = courtSchedule.getHearings().get(0);
        assertNotNull(hearing.getHearingId());
        assertEquals("Requires interpreter", hearing.getListNote());
        assertEquals("Sentencing for theft case", hearing.getHearingDescription());
        assertEquals("Trial", hearing.getHearingType());
        assertNotNull(hearing.getCourtSittings());
        assertEquals(1, hearing.getCourtSittings().size());

        CourtSitting courtSitting =
                hearing.getCourtSittings().get(0);
        assertEquals("Central Criminal Court", courtSitting.getCourtHouse());
        assertNotNull(courtSitting.getSittingStart());
        assertTrue(courtSitting.getSittingEnd().isAfter(courtSitting.getSittingStart()));
        assertNotNull(courtSitting.getJudiciaryId());

    }

    @Test
    void getCourtScheduleByCaseUrn_ShouldSanitizeCaseUrn() {
        String unsanitizedCaseUrn = "<script>alert('xss')</script>";
        log.info("Calling courtScheduleController.getCourtScheduleByCaseUrn with unsanitized caseUrn: {}", unsanitizedCaseUrn);

        ResponseEntity<?> response = courtScheduleController.getCourtScheduleByCaseUrn(unsanitizedCaseUrn);
        assertNotNull(response);
        log.debug("Received response: {}", response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void getJudgeById_ShouldReturnBadRequestStatus() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            courtScheduleController.getCourtScheduleByCaseUrn(null);
        });
        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exception.getReason()).isEqualTo("caseUrn is required");
        assertThat(exception.getMessage()).isEqualTo("400 BAD_REQUEST \"caseUrn is required\"");
    }

} 
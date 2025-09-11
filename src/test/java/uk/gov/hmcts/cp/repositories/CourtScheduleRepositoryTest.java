package uk.gov.hmcts.cp.repositories;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.openapi.model.CourtSchedule;
import uk.gov.hmcts.cp.openapi.model.CourtScheduleResponse;
import uk.gov.hmcts.cp.openapi.model.CourtSitting;
import uk.gov.hmcts.cp.openapi.model.Hearing;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CourtScheduleRepositoryTest {

    private CourtScheduleRepository courtScheduleRepository;

    @BeforeEach
    void setUp() {
        courtScheduleRepository = new InMemoryCourtScheduleRepositoryImpl();
    }

    @Test
    void getCourtScheduleByCaseUrn_shouldReturnCourtScheduleResponse() {
        UUID caseUrn = UUID.randomUUID();
        CourtScheduleResponse response = courtScheduleRepository.getCourtScheduleByCaseUrn(caseUrn.toString());

        assertNotNull(response.getCourtSchedule());
        assertEquals(1, response.getCourtSchedule().size());

        CourtSchedule schedule = response.getCourtSchedule().get(0);
        assertNotNull(schedule.getHearings());
        assertEquals(1, schedule.getHearings().size());

        Hearing hearing = schedule.getHearings().get(0);
        assertNotNull(hearing.getHearingId());
        assertEquals("Requires interpreter", hearing.getListNote());
        assertEquals("Sentencing for theft case", hearing.getHearingDescription());
        assertEquals("Trial", hearing.getHearingType());
        assertNotNull(hearing.getCourtSittings());
        assertEquals(1, hearing.getCourtSittings().size());

       CourtSitting sitting =
                hearing.getCourtSittings().get(0);
        assertEquals("Central Criminal Court", sitting.getCourtHouse());
        assertNotNull(sitting.getSittingStart());
        assertTrue(sitting.getSittingEnd().isAfter(sitting.getSittingStart()));
        assertNotNull(sitting.getJudiciaryId());
    }

}
package uk.gov.hmcts.cp.repositories;

import org.springframework.stereotype.Repository;
import uk.gov.hmcts.cp.openapi.model.CourtScheduleResponse;

@Repository
public interface CourtScheduleRepository  {

    CourtScheduleResponse getCourtScheduleByCaseUrn(String caseUrn);
    void saveCourtSchedule(String caseUrn, CourtScheduleResponse courtScheduleResponse);
    void clearAll();

}

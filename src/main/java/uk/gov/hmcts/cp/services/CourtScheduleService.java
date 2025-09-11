package uk.gov.hmcts.cp.services;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.cp.openapi.model.CourtScheduleResponse;
import uk.gov.hmcts.cp.repositories.CourtScheduleRepository;

@Service
@RequiredArgsConstructor
public class CourtScheduleService {

    private static final Logger LOG = LoggerFactory.getLogger(CourtScheduleService.class);

    private final CourtScheduleRepository courtScheduleRepository;

    public CourtScheduleResponse getCourtScheduleByCaseUrn(final String caseUrn) throws ResponseStatusException {
        if (StringUtils.isEmpty(caseUrn)) {
            LOG.atWarn().log("No case urn provided");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "caseUrn is required");
        }
        LOG.atWarn().log("NOTE: System configured to return stubbed Court Schedule details. Ignoring provided caseUrn : {}", caseUrn);
        final CourtScheduleResponse stubbedCourtScheduleResponse = courtScheduleRepository.getCourtScheduleByCaseUrn(caseUrn);
        LOG.atDebug().log("Court Schedule response: {}", stubbedCourtScheduleResponse);
        return stubbedCourtScheduleResponse;
    }

}

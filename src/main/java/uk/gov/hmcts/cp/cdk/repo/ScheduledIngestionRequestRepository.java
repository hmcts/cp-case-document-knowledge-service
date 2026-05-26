package uk.gov.hmcts.cp.cdk.repo;

import uk.gov.hmcts.cp.cdk.domain.ScheduledIngestionRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ScheduledIngestionRequestRepository
        extends JpaRepository<ScheduledIngestionRequest, UUID> {

    boolean existsByCourtCentreIdAndCourtRoomIdAndHearingDate(
            UUID courtCentreId,
            UUID courtRoomId,
            LocalDate hearingDate
    );

    List<ScheduledIngestionRequest>
    findAllByHearingDate(LocalDate hearingDate);
}
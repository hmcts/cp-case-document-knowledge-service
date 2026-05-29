package uk.gov.hmcts.cp.cdk.services;

import uk.gov.hmcts.cp.cdk.domain.ScheduledIngestionRequest;
import uk.gov.hmcts.cp.cdk.repo.ScheduledIngestionRequestRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class DiscoveryService {

    private final JobManagerService jobManagerService;
    private final ScheduledIngestionRequestRepository scheduledIngestionRequestRepository;

    public DiscoveryService(final JobManagerService jobManagerService,
                            final ScheduledIngestionRequestRepository scheduledIngestionRequestRepository) {
        this.jobManagerService = jobManagerService;
        this.scheduledIngestionRequestRepository = scheduledIngestionRequestRepository;
    }

    /**
     * Intraday discovery: targets late-arriving IDPCs, schedule changes, and late list additions.
     * find all ingestion requests for the current date and initiate the Discovery
     */
    @Transactional
    public void runIntradayDiscovery() {
        final LocalDate hearingDate = LocalDate.now();
        final List<ScheduledIngestionRequest> ingestionRequestList = scheduledIngestionRequestRepository.findAllByHearingDate(hearingDate);
        ingestionRequestList
                .stream()
                .map(this::toJobData)
                .forEach(jobData -> {
                    try {
                        jobManagerService.dispatchCaseDocumentIngestionTasks(jobData);
                    } catch (Exception e) {
                        log.error("Failed to dispatch case ingestion tasks for the jobData={}", jobData, e);
                    }
                });
    }

    private JsonObject toJobData(final ScheduledIngestionRequest ir) {
        return Json.createObjectBuilder()
                .add("cppuid", ir.getCppuid().toString())
                .add("requestId", UUID.randomUUID().toString())
                .add("courtCentreId", ir.getCourtCentreId().toString())
                .add("roomId", ir.getCourtRoomId().toString())
                .add("date", ir.getHearingDate().toString())
                .build();
    }
}

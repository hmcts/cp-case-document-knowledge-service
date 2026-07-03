package uk.gov.hmcts.cp.cdk.services;

import uk.gov.hmcts.cp.cdk.domain.ScheduledIngestionRequest;
import uk.gov.hmcts.cp.cdk.repo.ScheduledIngestionRequestRepository;
import uk.gov.hmcts.cp.cdk.scheduler.SchedulerProperties;

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
    private final HearingDaysCalculator hearingDaysCalculator;
    private final SchedulerProperties schedulerProperties;

    public DiscoveryService(final JobManagerService jobManagerService,
                            final ScheduledIngestionRequestRepository scheduledIngestionRequestRepository,
                            final HearingDaysCalculator hearingDaysCalculator,
                            final SchedulerProperties schedulerProperties) {
        this.jobManagerService = jobManagerService;
        this.scheduledIngestionRequestRepository = scheduledIngestionRequestRepository;
        this.hearingDaysCalculator = hearingDaysCalculator;
        this.schedulerProperties = schedulerProperties;
    }

    /**
     * Intraday discovery: targets late-arriving IDPCs, schedule changes, and late list additions.
     * find all ingestion requests for the current date and initiate the Discovery
     */
    @Transactional
    public void runIntradayDiscovery() {
        final LocalDate hearingDate = LocalDate.now();
        processScheduledIngestionRequests(hearingDate);
    }

    /**
     * Nightly discovery: pre-loads hearing dates calculated from today before court opens.
     * Uses HearingDatesCalculator to determine the relevant date window based on the start day.
     */
    @Transactional
    public void runNightlyDiscovery() {
        final LocalDate today = LocalDate.now();
        final List<LocalDate> hearingDates = hearingDaysCalculator.calculate(today, schedulerProperties.getNightlyDiscovery().getDaysAhead());
        log.info("Nightly discovery hearing dates={}", hearingDates);
        hearingDates.forEach(this::processScheduledIngestionRequests);
    }

    private void processScheduledIngestionRequests(final LocalDate hearingDate) {
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

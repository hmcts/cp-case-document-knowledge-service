package uk.gov.hmcts.cp.cdk.services;

import static java.util.UUID.fromString;
import static java.util.UUID.randomUUID;
import static org.springframework.util.StringUtils.hasText;

import uk.gov.hmcts.cp.cdk.domain.DiscoverySchedulerConfiguration;
import uk.gov.hmcts.cp.cdk.domain.ScheduledIngestionRequest;
import uk.gov.hmcts.cp.cdk.repo.DiscoverySchedulerConfigurationRepository;
import uk.gov.hmcts.cp.cdk.repo.ScheduledIngestionRequestRepository;
import uk.gov.hmcts.cp.cdk.scheduler.SchedulerProperties;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DiscoveryService {

    private static final String CASEDOCUMENTKNOWLEDGE_SYSTEM_USER_ID = "CASEDOCUMENTKNOWLEDGE_SYSTEM_USER_ID";
    private final JobManagerService jobManagerService;
    private final ScheduledIngestionRequestRepository scheduledIngestionRequestRepository;
    private final DiscoverySchedulerConfigurationRepository discoverySchedulerConfigurationRepository;
    private final HearingDaysCalculator hearingDaysCalculator;
    private final SchedulerProperties schedulerProperties;
    private final Environment environment;

    public DiscoveryService(final JobManagerService jobManagerService,
                            final ScheduledIngestionRequestRepository scheduledIngestionRequestRepository,
                            final DiscoverySchedulerConfigurationRepository discoverySchedulerConfigurationRepository,
                            final HearingDaysCalculator hearingDaysCalculator,
                            final SchedulerProperties schedulerProperties,
                            final Environment environment) {
        this.jobManagerService = jobManagerService;
        this.scheduledIngestionRequestRepository = scheduledIngestionRequestRepository;
        this.discoverySchedulerConfigurationRepository = discoverySchedulerConfigurationRepository;
        this.hearingDaysCalculator = hearingDaysCalculator;
        this.schedulerProperties = schedulerProperties;
        this.environment = environment;
    }

    /**
     * Intraday discovery: targets late-arriving IDPCs, schedule changes, and late list additions.
     * find all ingestion requests for the current date and initiate the Discovery
     */
    public void runIntradayDiscovery() {
        final LocalDate hearingDate = LocalDate.now();
        final List<ScheduledIngestionRequest> ingestionRequestList = scheduledIngestionRequestRepository.findAllByHearingDate(hearingDate);
        ingestionRequestList
                .stream()
                .map(ir -> toJobData(ir.getCppuid().toString(), ir.getCourtCentreId().toString(),
                        ir.getCourtRoomId().toString(), hearingDate.toString()))
                .forEach(jobData -> {
                    try {
                        jobManagerService.dispatchCaseDocumentIngestionTasks(jobData);
                    } catch (Exception e) {
                        log.error("Intraday Discovery - Failed to dispatch case ingestion tasks for the jobData={}", jobData, e);
                    }
                });
    }

    /**
     * Nightly discovery: pre-loads hearing dates calculated from today before court opens.
     * Uses HearingDatesCalculator to determine the relevant date window based on the start day.
     */
    public void runNightlyDiscovery() {
        final LocalDate today = LocalDate.now();
        final List<LocalDate> hearingDates = hearingDaysCalculator.calculate(today, schedulerProperties.getNightlyDiscovery().getDaysAhead());
        log.info("Nightly discovery hearing dates={}", hearingDates);

        final List<DiscoverySchedulerConfiguration> activeCourtCentreConfigurations = discoverySchedulerConfigurationRepository.findLatestActiveConfigurations();
        final UUID cpSystemUserId = getSystemUserId(environment);
        log.info("Nightly discovery for active courtCentre configurations={} is made using the CPP SystemUser={}", activeCourtCentreConfigurations.size(), cpSystemUserId);

        activeCourtCentreConfigurations.forEach(acc -> {
            hearingDates.stream()
                    .map(hd -> toJobData(cpSystemUserId.toString(), acc.getCourtCentreId().toString(),
                            acc.getCourtRoomId().toString(), hd.toString()))
                    .forEach(jobData -> {
                        try {
                            jobManagerService.dispatchCaseDocumentIngestionTasks(jobData);
                        } catch (Exception e) {
                            log.error("Nightly Discovery - Failed to dispatch case ingestion tasks for the jobData={}", jobData, e);
                        }
                    });
        });
    }

    private JsonObject toJobData(final String cppUid, final String courtCentreId,
                                 final String roomId, final String date) {
        return Json.createObjectBuilder()
                .add("cppuid", cppUid)
                .add("requestId", randomUUID().toString())
                .add("courtCentreId", courtCentreId)
                .add("roomId", roomId)
                .add("date", date)
                .build();
    }

    private static @NotNull UUID getSystemUserId(final Environment environment) {
        final String configuredSystemUserId = environment.getProperty(CASEDOCUMENTKNOWLEDGE_SYSTEM_USER_ID);
        if (!hasText(configuredSystemUserId)) {
            throw new IllegalStateException("Required environment variable '" + CASEDOCUMENTKNOWLEDGE_SYSTEM_USER_ID + "' is not set.");
        }

        try {
            return fromString(configuredSystemUserId);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Environment variable '" + CASEDOCUMENTKNOWLEDGE_SYSTEM_USER_ID + "' must contain a valid UUID, but was: '" + configuredSystemUserId + "'.", e);
        }
    }
}

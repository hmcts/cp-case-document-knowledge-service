package uk.gov.hmcts.cp.cdk.services;

import static java.util.Objects.nonNull;
import static java.util.UUID.fromString;
import static java.util.UUID.randomUUID;
import static org.springframework.util.StringUtils.hasText;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.COURT_CENTRE_ID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.CPPUID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.REQUEST_ID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.ROOM_ID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.DATE;

import uk.gov.hmcts.cp.cdk.clients.hearing.HearingClient;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingCaseForDay;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingCaseProsecutionCase;
import uk.gov.hmcts.cp.cdk.domain.DiscoverySchedulerConfiguration;
import uk.gov.hmcts.cp.cdk.domain.ScheduledIngestionRequest;
import uk.gov.hmcts.cp.cdk.repo.DiscoverySchedulerConfigurationRepository;
import uk.gov.hmcts.cp.cdk.repo.ScheduledIngestionRequestRepository;
import uk.gov.hmcts.cp.cdk.scheduler.SchedulerProperties;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final HearingClient hearingClient;
    private final HearingCaseWhitelistSelector hearingCaseWhitelistSelector;
    private final SchedulerProperties schedulerProperties;
    private final Environment environment;

    public DiscoveryService(final JobManagerService jobManagerService,
                            final ScheduledIngestionRequestRepository scheduledIngestionRequestRepository,
                            final DiscoverySchedulerConfigurationRepository discoverySchedulerConfigurationRepository,
                            final HearingDaysCalculator hearingDaysCalculator,
                            final HearingClient hearingClient,
                            final HearingCaseWhitelistSelector hearingCaseWhitelistSelector,
                            final SchedulerProperties schedulerProperties,
                            final Environment environment) {
        this.jobManagerService = jobManagerService;
        this.scheduledIngestionRequestRepository = scheduledIngestionRequestRepository;
        this.discoverySchedulerConfigurationRepository = discoverySchedulerConfigurationRepository;
        this.hearingDaysCalculator = hearingDaysCalculator;
        this.hearingClient = hearingClient;
        this.hearingCaseWhitelistSelector = hearingCaseWhitelistSelector;
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
                .map(ir -> toJobDataForGetCaseHearings(ir.getCppuid().toString(), ir.getCourtCentreId().toString(),
                        ir.getCourtRoomId().toString(), hearingDate.toString()))
                .forEach(jobData -> {
                    try {
                        jobManagerService.dispatchCaseDocumentIngestionTasksGetCasesForHearing(jobData);
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
        log.info("Nightly discovery for active courtCentre configurations={} is made using the CPP SystemUser={}",
                activeCourtCentreConfigurations.size(), cpSystemUserId);

        final List<HearingCaseForDay> matchedHearingCases = hearingDates.stream()
                .flatMap(hearingDate -> matchHearingCasesForDate(hearingDate, cpSystemUserId, activeCourtCentreConfigurations).stream())
                .toList();

        final Set<UUID> uniqueCaseIds = matchedHearingCases.stream()
                .filter(hearingCase -> nonNull(hearingCase.prosecutionCases()))
                .flatMap(hearingCase -> hearingCase.prosecutionCases().stream())
                .map(HearingCaseProsecutionCase::caseId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        log.info("Nightly discovery dispatching case eligibility checks for uniqueCaseIds={} out of matchedHearingCases={}",
                uniqueCaseIds.size(), matchedHearingCases.size());

        uniqueCaseIds.forEach(caseId -> dispatchCaseEligibilityCheck(caseId, cpSystemUserId));
    }

    private List<HearingCaseForDay> matchHearingCasesForDate(final LocalDate hearingDate, final UUID cpSystemUserId,
                                                              final List<DiscoverySchedulerConfiguration> activeCourtCentreConfigurations) {
        final List<HearingCaseForDay> hearingCases = hearingClient.getHearingCasesForDay(hearingDate, cpSystemUserId.toString());
        final List<HearingCaseForDay> matchedHearingCases = hearingCaseWhitelistSelector.findMatchingCases(hearingCases, activeCourtCentreConfigurations);
        log.info("Nightly discovery matched hearingCases={} out of retrieved={} for hearingDate={}",
                matchedHearingCases.size(), hearingCases.size(), hearingDate);
        return matchedHearingCases;
    }

    private void dispatchCaseEligibilityCheck(final UUID caseId, final UUID cpSystemUserId) {
        final JsonObject jobData = toJobDataForCaseEligibility(caseId, cpSystemUserId);
        try {
            jobManagerService.dispatchCaseDocumentIngestionTasksCheckCaseEligibility(jobData);
        } catch (Exception e) {
            log.error("Nightly Discovery - Failed to dispatch case ingestion tasks for the jobData={}", jobData, e);
        }
    }

    private JsonObject toJobDataForCaseEligibility(final UUID caseId, final UUID cpSystemUserId) {
        return Json.createObjectBuilder()
                .add(REQUEST_ID, randomUUID().toString())
                .add(CPPUID, cpSystemUserId.toString())
                .add(CTX_CASE_ID_KEY, caseId.toString())
                .build();
    }

    private JsonObject toJobDataForGetCaseHearings(final String cppUid, final String courtCentreId,
                                                   final String roomId, final String date) {
        return Json.createObjectBuilder()
                .add(CPPUID, cppUid)
                .add(REQUEST_ID, randomUUID().toString())
                .add(COURT_CENTRE_ID, courtCentreId)
                .add(ROOM_ID, roomId)
                .add(DATE, date)
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

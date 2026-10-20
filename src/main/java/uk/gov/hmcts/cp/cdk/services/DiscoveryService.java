package uk.gov.hmcts.cp.cdk.services;

import static java.util.Objects.nonNull;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.COURT_CENTRE_ID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.CPPUID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.DATE;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.REQUEST_ID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.ROOM_ID;
import static uk.gov.hmcts.cp.cdk.util.EnvironmentUtil.getSystemUserId;

import uk.gov.hmcts.cp.cdk.clients.hearing.HearingClient;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingCaseForDay;
import uk.gov.hmcts.cp.cdk.correlation.CorrelationIds;
import uk.gov.hmcts.cp.cdk.domain.DiscoverySchedulerConfiguration;
import uk.gov.hmcts.cp.cdk.domain.ScheduledIngestionRequest;
import uk.gov.hmcts.cp.cdk.repo.DiscoverySchedulerConfigurationRepository;
import uk.gov.hmcts.cp.cdk.repo.ScheduledIngestionRequestRepository;
import uk.gov.hmcts.cp.cdk.scheduler.SchedulerProperties;
import uk.gov.hmcts.cp.cdk.scheduler.SchedulerProperties.NightlyDiscovery.Stagger;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DiscoveryService {

    private final JobManagerService jobManagerService;
    private final ScheduledIngestionRequestRepository scheduledIngestionRequestRepository;
    private final DiscoverySchedulerConfigurationRepository discoverySchedulerConfigurationRepository;
    private final HearingDaysCalculator hearingDaysCalculator;
    private final HearingClient hearingClient;
    private final HearingCaseWhitelistSelector hearingCaseWhitelistSelector;
    private final SchedulerProperties schedulerProperties;
    private final Environment environment;
    private final boolean isHearingForCasesEnabled;

    public DiscoveryService(final JobManagerService jobManagerService,
                            final ScheduledIngestionRequestRepository scheduledIngestionRequestRepository,
                            final DiscoverySchedulerConfigurationRepository discoverySchedulerConfigurationRepository,
                            final HearingDaysCalculator hearingDaysCalculator,
                            final HearingClient hearingClient,
                            final HearingCaseWhitelistSelector hearingCaseWhitelistSelector,
                            final SchedulerProperties schedulerProperties,
                            final Environment environment,
                            @Value("${cqrs.client.hearing.is-hearing-for-cases-enabled:false}") final boolean isHearingForCasesEnabled) {
        this.jobManagerService = jobManagerService;
        this.scheduledIngestionRequestRepository = scheduledIngestionRequestRepository;
        this.discoverySchedulerConfigurationRepository = discoverySchedulerConfigurationRepository;
        this.hearingDaysCalculator = hearingDaysCalculator;
        this.hearingClient = hearingClient;
        this.hearingCaseWhitelistSelector = hearingCaseWhitelistSelector;
        this.schedulerProperties = schedulerProperties;
        this.environment = environment;
        this.isHearingForCasesEnabled = isHearingForCasesEnabled;
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

        if (isHearingForCasesEnabled) {
            final List<HearingCaseForDay> matchedHearingCases = hearingDates.stream()
                    .flatMap(hearingDate -> matchHearingCasesForDate(hearingDate, cpSystemUserId, activeCourtCentreConfigurations).stream())
                    .toList();

            final Set<UUID> uniqueCaseIds = matchedHearingCases.stream()
                    .filter(hearingCase -> nonNull(hearingCase.prosecutionCases()))
                    .flatMap(hearingCase -> hearingCase.prosecutionCases().stream())
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            log.info("Nightly discovery dispatching case eligibility checks for uniqueCaseIds={} out of matchedHearingCases={}",
                    uniqueCaseIds.size(), matchedHearingCases.size());

            dispatchCaseEligibilityChecksStaggered(new ArrayList<>(uniqueCaseIds), cpSystemUserId);
        } else {
            activeCourtCentreConfigurations.forEach(acc -> {
                hearingDates.stream()
                        .map(hd -> toJobDataForGetCaseHearings(cpSystemUserId.toString(), acc.getCourtCentreId().toString(),
                                acc.getCourtRoomId().toString(), hd.toString()))
                        .forEach(jobData -> {
                            try {
                                jobManagerService.dispatchCaseDocumentIngestionTasksGetCasesForHearing(jobData);
                            } catch (Exception e) {
                                log.error("Nightly Discovery - Failed to dispatch case ingestion tasks for the jobData={}", jobData, e);
                            }
                        });
            });
        }
    }

    private List<HearingCaseForDay> matchHearingCasesForDate(final LocalDate hearingDate, final UUID cpSystemUserId,
                                                              final List<DiscoverySchedulerConfiguration> activeCourtCentreConfigurations) {
        final List<HearingCaseForDay> hearingCases = hearingClient.getHearingCasesForDay(hearingDate, cpSystemUserId.toString());
        final List<HearingCaseForDay> matchedHearingCases = hearingCaseWhitelistSelector.findMatchingCases(hearingCases, activeCourtCentreConfigurations);
        log.info("Nightly discovery matched hearingCases={} out of retrieved={} for hearingDate={}",
                matchedHearingCases.size(), hearingCases.size(), hearingDate);
        return matchedHearingCases;
    }

    /**
     * Option A1 (DD-43823): release the per-case CHECK_IDPC entry tasks at a controlled rate by
     * stamping each with a staggered assigned-start-time, instead of all at 02:00. When staggering
     * is disabled, every task is stamped with the same base instant — i.e. current behaviour.
     */
    private void dispatchCaseEligibilityChecksStaggered(final List<UUID> orderedCaseIds, final UUID cpSystemUserId) {
        final Stagger stagger = schedulerProperties.getNightlyDiscovery().getStagger();
        final ZonedDateTime base = ZonedDateTime.now();

        if (stagger.isEnabled() && stagger.getCasesPerMinute() > 0 && orderedCaseIds.size() > 1) {
            final long fullWindowMs = Math.round((orderedCaseIds.size() - 1) / stagger.getCasesPerMinute() * 60_000d);
            final long capMs = stagger.getMaxWindow().toMillis();
            if (fullWindowMs > capMs) {
                log.warn("Nightly discovery stagger window {} min exceeds tripwire {} min for cases={} at {}/min; "
                                + "volume is outgrowing stagger-based smoothing",
                        fullWindowMs / 60_000, capMs / 60_000, orderedCaseIds.size(), stagger.getCasesPerMinute());
            }
            log.info("Nightly discovery staggering cases={} at {}/min over ~{} min",
                    orderedCaseIds.size(), stagger.getCasesPerMinute(), Math.min(fullWindowMs, capMs) / 60_000);
        }

        for (int i = 0; i < orderedCaseIds.size(); i++) {
            dispatchCaseEligibilityCheck(orderedCaseIds.get(i), cpSystemUserId, staggeredStartTime(base, i, stagger));
        }
    }

    private ZonedDateTime staggeredStartTime(final ZonedDateTime base, final int index, final Stagger stagger) {
        if (!stagger.isEnabled() || stagger.getCasesPerMinute() <= 0 || index == 0) {
            return base;
        }
        final long offsetMs = Math.round(index / stagger.getCasesPerMinute() * 60_000d);
        final long cappedMs = Math.min(offsetMs, stagger.getMaxWindow().toMillis());
        return base.plus(Duration.ofMillis(cappedMs));
    }

    private void dispatchCaseEligibilityCheck(final UUID caseId, final UUID cpSystemUserId, final ZonedDateTime startTime) {
        final JsonObject jobData = toJobDataForCaseEligibility(caseId, cpSystemUserId);
        try {
            jobManagerService.dispatchCaseDocumentIngestionTasksCheckIdpcAvailability(jobData, startTime);
        } catch (Exception e) {
            log.error("Nightly Discovery - Failed to dispatch case ingestion tasks for the jobData={}", jobData, e);
        }
    }

    private JsonObject toJobDataForCaseEligibility(final UUID caseId, final UUID cpSystemUserId) {
        return Json.createObjectBuilder()
                .add(REQUEST_ID, CorrelationIds.currentOrGenerate())
                .add(CPPUID, cpSystemUserId.toString())
                .add(CTX_CASE_ID_KEY, caseId.toString())
                .build();
    }

    private JsonObject toJobDataForGetCaseHearings(final String cppUid, final String courtCentreId,
                                                   final String roomId, final String date) {
        return Json.createObjectBuilder()
                .add(CPPUID, cppUid)
                .add(REQUEST_ID, CorrelationIds.currentOrGenerate())
                .add(COURT_CENTRE_ID, courtCentreId)
                .add(ROOM_ID, roomId)
                .add(DATE, date)
                .build();
    }
}

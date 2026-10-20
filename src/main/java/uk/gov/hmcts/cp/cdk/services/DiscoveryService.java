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
import uk.gov.hmcts.cp.cdk.jobmanager.support.JobPriority;
import uk.gov.hmcts.cp.cdk.repo.DiscoverySchedulerConfigurationRepository;
import uk.gov.hmcts.cp.cdk.repo.ScheduledIngestionRequestRepository;
import uk.gov.hmcts.cp.cdk.scheduler.SchedulerProperties;
import uk.gov.hmcts.cp.cdk.scheduler.SchedulerProperties.NightlyDiscovery.Priority;
import uk.gov.hmcts.cp.cdk.scheduler.SchedulerProperties.NightlyDiscovery.Stagger;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

            // One pass: dedup case ids (map keys) and keep each case's nearest hearing date (map value).
            // A null hearing date is tolerated so a dateless case is still dispatched (sorted/priced last).
            final Map<UUID, LocalDate> nearestHearingByCase = new LinkedHashMap<>();
            for (final HearingCaseForDay hearingCase : matchedHearingCases) {
                if (nonNull(hearingCase.prosecutionCases())) {
                    for (final UUID caseId : hearingCase.prosecutionCases()) {
                        nearestHearingByCase.put(caseId,
                                earliest(nearestHearingByCase.get(caseId), hearingCase.hearingDate()));
                    }
                }
            }

            log.info("Nightly discovery dispatching case eligibility checks for uniqueCaseIds={} out of matchedHearingCases={}",
                    nearestHearingByCase.size(), matchedHearingCases.size());

            dispatchCaseEligibilityChecks(today, nearestHearingByCase, cpSystemUserId);
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
     * Dispatches the per-case CHECK_IDPC entry tasks, applying:
     * <ul>
     *   <li>Option A1 (DD-43823): a staggered assigned-start-time so the tasks are released at a
     *       controlled rate instead of all at 02:00; and</li>
     *   <li>Option A2 (DD-43823): ordering + priority by hearing proximity, so nearer hearing dates
     *       start earlier and are claimed ahead of farther ones (manual HIGH always pre-empts).</li>
     * </ul>
     * When both are disabled this stamps every task with the same base instant and the default
     * priority in insertion order — i.e. current behaviour.
     */
    private void dispatchCaseEligibilityChecks(final LocalDate today,
                                               final Map<UUID, LocalDate> nearestHearingByCase, final UUID cpSystemUserId) {
        final Priority priorityCfg = schedulerProperties.getNightlyDiscovery().getPriority();

        final List<UUID> orderedCaseIds = priorityCfg.isEnabled()
                ? nearestHearingByCase.entrySet().stream()
                        .sorted(Map.Entry.<UUID, LocalDate>comparingByValue(Comparator.nullsLast(Comparator.naturalOrder())))
                        .map(Map.Entry::getKey)
                        .toList()
                : new ArrayList<>(nearestHearingByCase.keySet());

        final Stagger stagger = schedulerProperties.getNightlyDiscovery().getStagger();
        final ZonedDateTime base = ZonedDateTime.now();
        maybeLogStaggerPlan(orderedCaseIds.size(), stagger);

        for (int i = 0; i < orderedCaseIds.size(); i++) {
            final UUID caseId = orderedCaseIds.get(i);
            dispatchCaseEligibilityCheck(caseId, cpSystemUserId, staggeredStartTime(base, i, stagger),
                    hearingProximityPriority(today, nearestHearingByCase.get(caseId), priorityCfg));
        }
    }

    private void maybeLogStaggerPlan(final int caseCount, final Stagger stagger) {
        if (stagger.isEnabled() && stagger.getCasesPerMinute() > 0 && caseCount > 1) {
            final long fullWindowMs = Math.round((caseCount - 1) / stagger.getCasesPerMinute() * 60_000d);
            final long capMs = stagger.getMaxWindow().toMillis();
            if (fullWindowMs > capMs) {
                log.warn("Nightly discovery stagger window {} min exceeds tripwire {} min for cases={} at {}/min; "
                                + "volume is outgrowing stagger-based smoothing",
                        fullWindowMs / 60_000, capMs / 60_000, caseCount, stagger.getCasesPerMinute());
            }
            log.info("Nightly discovery staggering cases={} at {}/min over ~{} min",
                    caseCount, stagger.getCasesPerMinute(), Math.min(fullWindowMs, capMs) / 60_000);
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

    /**
     * Option A2: nearer hearing date =&gt; higher priority (lower number), clamped to
     * [JobPriority.HIGH + 1 .. JobPriority.DEFAULT] so manual HIGH(1) ingestion always pre-empts.
     * Returns the default priority when A2 is disabled or the hearing date is unknown.
     */
    /** Earliest of two nullable dates; null only when both are null. Never drops a case from dispatch. */
    private static LocalDate earliest(final LocalDate current, final LocalDate candidate) {
        final LocalDate result;
        if (current == null) {
            result = candidate;
        } else if (candidate == null || current.isBefore(candidate)) {
            result = current;
        } else {
            result = candidate;
        }
        return result;
    }

    private int hearingProximityPriority(final LocalDate today, final LocalDate hearingDate, final Priority cfg) {
        if (!cfg.isEnabled() || hearingDate == null) {
            return JobPriority.DEFAULT;
        }
        final long daysUntil = Math.max(0, ChronoUnit.DAYS.between(today, hearingDate));
        final int floor = JobPriority.HIGH + 1;
        final int raw = Math.max(cfg.getHighest(), floor) + (int) daysUntil;
        return Math.min(JobPriority.DEFAULT, raw);
    }

    private void dispatchCaseEligibilityCheck(final UUID caseId, final UUID cpSystemUserId,
                                              final ZonedDateTime startTime, final int priority) {
        final JsonObject jobData = toJobDataForCaseEligibility(caseId, cpSystemUserId);
        try {
            jobManagerService.dispatchCaseDocumentIngestionTasksCheckIdpcAvailability(jobData, startTime, priority);
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

package uk.gov.hmcts.cp.cdk.services;

import static java.util.Objects.nonNull;

import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingCaseForDay;
import uk.gov.hmcts.cp.cdk.jobmanager.support.JobPriority;
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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Burst-shaping policy for the nightly discovery entry tasks (DD-43823). Given the matched hearing
 * cases, it produces an ordered dispatch plan — one {@link CaseDispatch} per unique case — applying:
 * <ul>
 *   <li>Option A1: a staggered assigned-start-time so the per-case CHECK_IDPC entry tasks are
 *       released at a controlled rate instead of all at 02:00; and</li>
 *   <li>Option A2: ordering + priority by hearing proximity, so nearer hearing dates start earlier
 *       and are claimed ahead of farther ones (manual HIGH always pre-empts).</li>
 * </ul>
 * Pure policy: no I/O, no task dispatch. When both options are disabled every case is planned with
 * the same base instant and the default priority in insertion order — i.e. current behaviour.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class NightlyEntryTaskPlanner {

    private final SchedulerProperties schedulerProperties;

    /** A single planned entry-task dispatch. */
    public record CaseDispatch(UUID caseId, ZonedDateTime startTime, int priority) {
    }

    /* package */
    List<CaseDispatch> plan(final LocalDate today, final List<HearingCaseForDay> matchedHearingCases) {
        // One pass: dedup case ids (map keys) and keep each case's nearest hearing date (map value).
        // A null hearing date is tolerated so a dateless case is still planned (sorted/priced last).
        final Map<UUID, LocalDate> nearestHearingByCase = new LinkedHashMap<>();
        for (final HearingCaseForDay hearingCase : matchedHearingCases) {
            if (nonNull(hearingCase.prosecutionCases())) {
                for (final UUID caseId : hearingCase.prosecutionCases()) {
                    nearestHearingByCase.put(caseId,
                            earliest(nearestHearingByCase.get(caseId), hearingCase.hearingDate()));
                }
            }
        }

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

        final List<CaseDispatch> plan = new ArrayList<>(orderedCaseIds.size());
        for (int i = 0; i < orderedCaseIds.size(); i++) {
            final UUID caseId = orderedCaseIds.get(i);
            plan.add(new CaseDispatch(caseId, staggeredStartTime(base, i, stagger),
                    hearingProximityPriority(today, nearestHearingByCase.get(caseId), priorityCfg)));
        }
        return plan;
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
    private int hearingProximityPriority(final LocalDate today, final LocalDate hearingDate, final Priority cfg) {
        if (!cfg.isEnabled() || hearingDate == null) {
            return JobPriority.DEFAULT;
        }
        final long daysUntil = Math.max(0, ChronoUnit.DAYS.between(today, hearingDate));
        final int floor = JobPriority.HIGH + 1;
        final int raw = Math.max(cfg.getHighest(), floor) + (int) daysUntil;
        return Math.min(JobPriority.DEFAULT, raw);
    }

    /** Earliest of two nullable dates; null only when both are null. Never drops a case from the plan. */
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
}

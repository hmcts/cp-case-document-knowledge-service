package uk.gov.hmcts.cp.cdk.services;

import static org.assertj.core.api.Assertions.assertThat;

import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingCaseForDay;
import uk.gov.hmcts.cp.cdk.scheduler.SchedulerProperties;
import uk.gov.hmcts.cp.cdk.services.NightlyEntryTaskPlanner.CaseDispatch;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Pure policy tests for the DD-43823 burst-shaping logic (A1 staggering + A2 proximity priority).
 * No Spring, no mocks — build a list of matched hearing cases and assert the produced plan.
 */
class NightlyEntryTaskPlannerTest {

    private static final int NIGHTLY_DISCOVERY_DAYS = 3;

    private final LocalDate today = LocalDate.now();

    // ----- Option A1: deferred-start staggering -----

    @Test
    void plan_whenStaggerDisabled_allImmediateWithDefaultPriorityInInsertionOrder() {
        final UUID c0 = UUID.randomUUID();
        final UUID c1 = UUID.randomUUID();
        final UUID c2 = UUID.randomUUID();

        final List<CaseDispatch> plan = plannerWithStagger(false, 2.0, Duration.ofHours(8))
                .plan(today, List.of(hearingCaseOn(today, c0, c1, c2)));

        assertThat(plan).extracting(CaseDispatch::caseId).containsExactly(c0, c1, c2);
        assertThat(plan).extracting(CaseDispatch::priority).containsExactly(10, 10, 10);
        assertThat(delta(plan, 0, 1)).isZero();
        assertThat(delta(plan, 0, 2)).isZero();
    }

    @Test
    void plan_whenStaggerEnabled_releasesAtConfiguredRate() {
        final UUID c0 = UUID.randomUUID();
        final UUID c1 = UUID.randomUUID();
        final UUID c2 = UUID.randomUUID();

        final List<CaseDispatch> plan = plannerWithStagger(true, 2.0, Duration.ofHours(8))
                .plan(today, List.of(hearingCaseOn(today, c0, c1, c2)));

        assertThat(delta(plan, 0, 1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(delta(plan, 0, 2)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void plan_staggerStepScalesInverselyWithRate() {
        final UUID c0 = UUID.randomUUID();
        final UUID c1 = UUID.randomUUID();

        final List<CaseDispatch> plan = plannerWithStagger(true, 4.0, Duration.ofHours(8))
                .plan(today, List.of(hearingCaseOn(today, c0, c1)));

        assertThat(delta(plan, 0, 1)).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void plan_firstEntryTaskIsNeverDeferred() {
        final ZonedDateTime before = ZonedDateTime.now();
        final List<CaseDispatch> plan = plannerWithStagger(true, 2.0, Duration.ofHours(8))
                .plan(today, List.of(hearingCaseOn(today, UUID.randomUUID())));
        final ZonedDateTime after = ZonedDateTime.now();

        assertThat(plan.get(0).startTime()).isBetween(before, after);
    }

    @Test
    void plan_whenWindowExceedsMaxWindow_clampsOverflowWarnsAndDropsNothing() {
        // 60 cases/min (1s step), cap 2s: indexes 3 and 4 clamp to base + 2s
        final NightlyEntryTaskPlanner planner = plannerWithStagger(true, 60.0, Duration.ofSeconds(2));
        final List<HearingCaseForDay> cases = List.of(hearingCaseOn(today,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));

        final Logger logger = (Logger) LoggerFactory.getLogger(NightlyEntryTaskPlanner.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        final List<CaseDispatch> plan;
        try {
            plan = planner.plan(today, cases);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(plan).hasSize(5);
        assertThat(delta(plan, 0, 2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(plan.get(3).startTime()).isEqualTo(plan.get(2).startTime());
        assertThat(plan.get(4).startTime()).isEqualTo(plan.get(2).startTime());
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).containsIgnoringCase("stagger window");
        });
    }

    @Test
    void plan_whenCasesPerMinuteNotPositive_fallsBackToImmediateStart() {
        final List<CaseDispatch> plan = plannerWithStagger(true, 0.0, Duration.ofHours(8))
                .plan(today, List.of(hearingCaseOn(today, UUID.randomUUID(), UUID.randomUUID())));

        assertThat(delta(plan, 0, 1)).isZero();
    }

    // ----- Option A2: priority & ordering by hearing proximity -----

    @Test
    void plan_whenPriorityDisabled_keepsInsertionOrderAndDefaultPriority() {
        final UUID farCase = UUID.randomUUID();
        final UUID midCase = UUID.randomUUID();
        final UUID nearCase = UUID.randomUUID();

        final List<CaseDispatch> plan = plannerWithPriority(false, 2).plan(today, List.of(
                hearingCaseOn(today.plusDays(2), farCase),
                hearingCaseOn(today.plusDays(1), midCase),
                hearingCaseOn(today, nearCase)));

        assertThat(plan).extracting(CaseDispatch::caseId).containsExactly(farCase, midCase, nearCase);
        assertThat(plan).extracting(CaseDispatch::priority).containsExactly(10, 10, 10);
    }

    @Test
    void plan_whenPriorityEnabled_ordersNearestFirstWithProximityPriority() {
        final UUID farCase = UUID.randomUUID();
        final UUID midCase = UUID.randomUUID();
        final UUID nearCase = UUID.randomUUID();

        final List<CaseDispatch> plan = plannerWithPriority(true, 2).plan(today, List.of(
                hearingCaseOn(today.plusDays(2), farCase),
                hearingCaseOn(today.plusDays(1), midCase),
                hearingCaseOn(today, nearCase)));

        assertThat(plan).extracting(CaseDispatch::caseId).containsExactly(nearCase, midCase, farCase);
        assertThat(plan).extracting(CaseDispatch::priority).containsExactly(2, 3, 4); // today=2, +1=3, +2=4
    }

    @Test
    void plan_caseOnMultipleDates_usesEarliestHearingForPriority() {
        final UUID caseId = UUID.randomUUID();

        final List<CaseDispatch> plan = plannerWithPriority(true, 2).plan(today, List.of(
                hearingCaseOn(today.plusDays(2), caseId),
                hearingCaseOn(today, caseId)));

        assertThat(plan).hasSize(1);
        assertThat(plan.get(0).priority()).isEqualTo(2); // earliest hearing is today
    }

    @Test
    void plan_priorityHighestBelowFloor_isClampedAboveManualHigh() {
        final List<CaseDispatch> plan = plannerWithPriority(true, 1) // == manual HIGH, must floor at 2
                .plan(today, List.of(hearingCaseOn(today, UUID.randomUUID())));

        assertThat(plan.get(0).priority()).isEqualTo(2);
    }

    @Test
    void plan_farFutureHearing_priorityClampedAtDefault() {
        final List<CaseDispatch> plan = plannerWithPriority(true, 2) // 2 + 20 = 22 -> clamp 10
                .plan(today, List.of(hearingCaseOn(today.plusDays(20), UUID.randomUUID())));

        assertThat(plan.get(0).priority()).isEqualTo(10);
    }

    @Test
    void plan_caseWithNullHearingDate_isStillPlannedLastWithDefaultPriority() {
        final UUID dateless = UUID.randomUUID();
        final UUID dated = UUID.randomUUID();

        final List<CaseDispatch> plan = plannerWithPriority(true, 2).plan(today, List.of(
                new HearingCaseForDay(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), List.of(dateless)),
                hearingCaseOn(today, dated)));

        assertThat(plan).extracting(CaseDispatch::caseId).containsExactly(dated, dateless); // null sorts last
        assertThat(plan).extracting(CaseDispatch::priority).containsExactly(2, 10); // dateless -> DEFAULT
    }

    private static Duration delta(final List<CaseDispatch> plan, final int from, final int to) {
        return Duration.between(plan.get(from).startTime(), plan.get(to).startTime());
    }

    private HearingCaseForDay hearingCaseOn(final LocalDate date, final UUID... caseIds) {
        return new HearingCaseForDay(UUID.randomUUID(), UUID.randomUUID(), date, UUID.randomUUID(), List.of(caseIds));
    }

    private NightlyEntryTaskPlanner plannerWithStagger(final boolean enabled, final double casesPerMinute,
                                                       final Duration maxWindow) {
        final SchedulerProperties props = baseProps();
        final SchedulerProperties.NightlyDiscovery.Stagger stagger = props.getNightlyDiscovery().getStagger();
        stagger.setEnabled(enabled);
        stagger.setCasesPerMinute(casesPerMinute);
        stagger.setMaxWindow(maxWindow);
        return new NightlyEntryTaskPlanner(props);
    }

    private NightlyEntryTaskPlanner plannerWithPriority(final boolean enabled, final int highest) {
        final SchedulerProperties props = baseProps();
        final SchedulerProperties.NightlyDiscovery.Priority priority = props.getNightlyDiscovery().getPriority();
        priority.setEnabled(enabled);
        priority.setHighest(highest);
        return new NightlyEntryTaskPlanner(props);
    }

    private SchedulerProperties baseProps() {
        final SchedulerProperties props = new SchedulerProperties();
        props.getNightlyDiscovery().setDaysAhead(NIGHTLY_DISCOVERY_DAYS);
        return props;
    }
}

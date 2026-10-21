package uk.gov.hmcts.cp.cdk.scheduler;

import java.time.Duration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "scheduler")
public class SchedulerProperties {

    private final IntradayDiscovery intradayDiscovery = new IntradayDiscovery();
    private final NightlyDiscovery nightlyDiscovery = new NightlyDiscovery();

    public IntradayDiscovery getIntradayDiscovery() {
        return intradayDiscovery;
    }

    public NightlyDiscovery getNightlyDiscovery() {
        return nightlyDiscovery;
    }

    @Data
    public static class IntradayDiscovery {
        private String name;
        private String cron;
        private String lockAtLeastFor;
        private String lockAtMostFor;

        /**
         * Mirrors @ConditionalOnProperty(..., havingValue = "true", matchIfMissing = true) on
         * IntradayDiscoveryScheduler. The Java default is deliberately true so that "property
         * absent" resolves the same way here as it does in the conditional. application-cdk.yml
         * always supplies a value (defaulting to false via CP_CDK_SCHEDULER_INTRADAY_DISCOVERY_ENABLED),
         * so the effective shipped default is unchanged. If the conditional's matchIfMissing is
         * ever changed, change this default with it.
         */
        private boolean enabled = true;
    }

    @Data
    public static class NightlyDiscovery {
        private String name;
        private String cron;
        private String lockAtLeastFor;
        private String lockAtMostFor;
        private int daysAhead = 3;

        /**
         * Mirrors @ConditionalOnProperty(..., havingValue = "true", matchIfMissing = true) on
         * NightlyDiscoveryScheduler. See IntradayDiscovery#enabled for the same rationale.
         */
        private boolean enabled = true;

        private final Stagger stagger = new Stagger();

        private final Priority priority = new Priority();

        public Stagger getStagger() {
            return stagger;
        }

        public Priority getPriority() {
            return priority;
        }

        /**
         * A1 (DD-43823): release per-case CHECK_IDPC entry tasks at a controlled rate instead of
         * all at 02:00, by stamping each job with a staggered assigned-start-time. Off by default.
         */
        @Data
        public static class Stagger {
            /** When false, entry tasks start immediately (current behaviour). */
            private boolean enabled;

            /** Release rate for per-case entry tasks; keep at or below the sustainable RAG rate. */
            private double casesPerMinute = 2.0;

            /**
             * Tripwire threshold, not a deadline: if the computed stagger window exceeds this, log a
             * warning (volume is outgrowing A1 smoothing — consider DD-43823 option B1). Set generous
             * so it never clamps a legitimate night; the clamp itself is only a backstop against
             * misconfiguration.
             */
            private Duration maxWindow = Duration.ofHours(8);
        }

        /**
         * A2 (DD-43823): prioritise nightly CHECK_IDPC entry tasks by hearing proximity, within the
         * nightly band and always below JobPriority.HIGH(1). Off by default.
         */
        @Data
        public static class Priority {
            /** When false, entry tasks keep the default priority (JobPriority.DEFAULT = 10). */
            private boolean enabled;

            /**
             * Priority for the nearest hearing date; each further day adds 1 (toward lower priority).
             * Clamped at dispatch time to [JobPriority.HIGH + 1 .. JobPriority.DEFAULT] so manual
             * HIGH(1) ingestion always pre-empts.
             */
            private int highest = 2;
        }
    }
}

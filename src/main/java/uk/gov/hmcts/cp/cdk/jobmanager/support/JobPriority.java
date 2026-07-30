package uk.gov.hmcts.cp.cdk.jobmanager.support;

/**
 * JobManager priority levels.
 *
 * <p>Priority is an integer in the range 1-10 where {@code 1} is the highest priority and
 * {@code 10} the lowest (the task-manager default). Manually-triggered ingestions (e.g. the
 * "Process IDPC" button on the AI Search page) run at {@link #HIGH} so they are picked up ahead
 * of the nightly scheduled ingestions, which continue to run at {@link #DEFAULT}.
 */
public final class JobPriority {

    /** Highest priority — used for manual, user-initiated ingestion. */
    public static final int HIGH = 1;

    /** Default priority — used for scheduled/background ingestion. */
    public static final int DEFAULT = 10;

    private JobPriority() {
    }
}

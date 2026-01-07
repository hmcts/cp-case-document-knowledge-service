package uk.gov.hmcts.cp.cdk.tasks;

import java.util.Arrays;

/**
 * Per-case ingestion workflow.
 * Executed once per caseId.
 */
public enum CaseIngestionWorkflow {

    RESOLVE_MATERIAL_FOR_CASE_TASK,
    UPLOAD_AND_PERSIST_TASK,
    VERIFY_DOCUMENT_INGESTION_TASK,
    RESERVE_ANSWER_VERSION_TASK,
    GENERATE_ANSWERS_TASK;

    public static CaseIngestionWorkflow firstTask() {
        return RESOLVE_MATERIAL_FOR_CASE_TASK;
    }

    public static CaseIngestionWorkflow nextTask(CaseIngestionWorkflow current) {
        return Arrays.stream(values())
                .filter(t -> t.ordinal() > current.ordinal())
                .findFirst()
                .orElse(null);
    }

    public boolean isLast() {
        return this == GENERATE_ANSWERS_TASK;
    }
}

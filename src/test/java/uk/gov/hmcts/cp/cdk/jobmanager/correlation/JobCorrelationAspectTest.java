package uk.gov.hmcts.cp.cdk.jobmanager.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;

import uk.gov.hmcts.cp.cdk.correlation.CorrelationIds;
import uk.gov.hmcts.cp.cdk.correlation.CorrelationScope;
import uk.gov.hmcts.cp.cdk.correlation.JobCorrelationAspect;

import java.time.ZonedDateTime;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;

@DisplayName("JobCorrelationAspect tests (DD-43183 Story 3)")
class JobCorrelationAspectTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("AC-001: restores requestId from jobData into MDC for the duration of execute(...)")
    void restoresCorrelationIdFromJobDataDuringExecution() throws Throwable {
        final StubTask stub = new StubTask();
        final ExecutableTask proxy = proxiedTask(stub);
        final JsonObject jobData = Json.createObjectBuilder().add("requestId", "abc-123").build();

        proxy.execute(baseExecutionInfo(jobData));

        assertThat(stub.observedCorrelationId).isEqualTo("abc-123");
    }

    @Test
    @DisplayName("AC-002: after the task returns, the correlation value is no longer present on this thread")
    void restoresPriorMapAfterNormalReturn() throws Throwable {
        final StubTask stub = new StubTask();
        final ExecutableTask proxy = proxiedTask(stub);
        final JsonObject jobData = Json.createObjectBuilder().add("requestId", "abc-123").build();

        proxy.execute(baseExecutionInfo(jobData));

        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("AC-002: the prior MDC map is restored exactly — safe on a thread that legitimately "
            + "carries other context")
    void restoresExactPriorMapNotJustCorrelationId() throws Throwable {
        MDC.put("outerKey", "outerValue");
        final StubTask stub = new StubTask();
        final ExecutableTask proxy = proxiedTask(stub);
        final JsonObject jobData = Json.createObjectBuilder().add("requestId", "abc-123").build();

        proxy.execute(baseExecutionInfo(jobData));

        assertThat(MDC.get("outerKey")).isEqualTo("outerValue");
        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("close() restores the prior map even when the task throws")
    void restoresPriorMapWhenTaskThrows() {
        final StubTask stub = new StubTask();
        stub.toThrow = new IllegalStateException("boom");
        final ExecutableTask proxy = proxiedTask(stub);
        final JsonObject jobData = Json.createObjectBuilder().add("requestId", "abc-123").build();

        assertThatThrownBy(() -> proxy.execute(baseExecutionInfo(jobData)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("NFR-004: the returned ExecutionInfo passes through unaltered")
    void returnedExecutionInfoPassesThroughUnaltered() throws Throwable {
        final StubTask stub = new StubTask();
        final ExecutableTask proxy = proxiedTask(stub);
        final JsonObject jobData = Json.createObjectBuilder().add("requestId", "abc-123").build();
        stub.toReturn = executionInfo().from(baseExecutionInfo(jobData))
                .withExecutionStatus(ExecutionStatus.COMPLETED).build();

        final ExecutionInfo result = proxy.execute(baseExecutionInfo(jobData));

        assertThat(result).isSameAs(stub.toReturn);
    }

    @Test
    @DisplayName("NFR-004: the exact same Throwable instance propagates unchanged")
    void sameThrowableInstancePropagates() {
        final StubTask stub = new StubTask();
        stub.toThrow = new IllegalStateException("original instance");
        final ExecutableTask proxy = proxiedTask(stub);
        final JsonObject jobData = Json.createObjectBuilder().add("requestId", "abc-123").build();

        assertThatThrownBy(() -> proxy.execute(baseExecutionInfo(jobData))).isSameAs(stub.toThrow);
    }

    @Test
    @DisplayName("a missing/blank/rejected requestId generates a fresh non-blank value rather than throwing")
    void generatesAValueWhenRequestIdMissing() throws Throwable {
        final StubTask stub = new StubTask();
        final ExecutableTask proxy = proxiedTask(stub);
        final JsonObject jobData = Json.createObjectBuilder().build();

        proxy.execute(baseExecutionInfo(jobData));

        assertThat(stub.observedCorrelationId).isNotBlank();
    }

    @Test
    @DisplayName("AC-007: caseId, docId and transactionId are also seeded into MDC where present in jobData")
    void seedsBusinessIdentifiersWhenPresent() throws Throwable {
        final StubTask stub = new StubTask();
        final ExecutableTask proxy = proxiedTask(stub);
        final JsonObject jobData = Json.createObjectBuilder()
                .add("requestId", "abc-123")
                .add("caseId", "case-1")
                .add("docId", "doc-1")
                .add("ragTransactionId", "txn-1")
                .build();

        proxy.execute(baseExecutionInfo(jobData));

        assertThat(stub.observedCaseId).isEqualTo("case-1");
        assertThat(stub.observedDocId).isEqualTo("doc-1");
        assertThat(stub.observedTransactionId).isEqualTo("txn-1");
    }

    private static ExecutableTask proxiedTask(final StubTask stub) {
        final AspectJProxyFactory factory = new AspectJProxyFactory(stub);
        factory.addAspect(new JobCorrelationAspect());
        return factory.getProxy();
    }

    private static ExecutionInfo baseExecutionInfo(final JsonObject jobData) {
        return executionInfo()
                .withAssignedTaskName("STUB_TASK")
                .withAssignedTaskStartTime(ZonedDateTime.now())
                .withJobData(jobData)
                .withExecutionStatus(ExecutionStatus.STARTED)
                .build();
    }

    static class StubTask implements ExecutableTask {
        private volatile String observedCorrelationId;
        private volatile String observedCaseId;
        private volatile String observedDocId;
        private volatile String observedTransactionId;
        private ExecutionInfo toReturn;
        private RuntimeException toThrow;

        @Override
        public ExecutionInfo execute(final ExecutionInfo executionInfo) {
            observedCorrelationId = MDC.get(CorrelationIds.MDC_KEY);
            observedCaseId = MDC.get(CorrelationScope.MDC_KEY_CASE_ID);
            observedDocId = MDC.get(CorrelationScope.MDC_KEY_DOC_ID);
            observedTransactionId = MDC.get(CorrelationScope.MDC_KEY_TRANSACTION_ID);
            if (toThrow != null) {
                throw toThrow;
            }
            return toReturn != null ? toReturn : executionInfo;
        }
    }
}

package uk.gov.hmcts.cp.cdk.batch.partition;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.cdk.batch.support.PartitionKeys.PARTITION_CASE_ID;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.ExecutionContext;

class CaseIdPartitionerTest {

    private CaseIdPartitioner partitioner;

    @BeforeEach
    void setUp() {
        partitioner = new CaseIdPartitioner();
    }

    @Test
    void returnsEmptyPartitionsWhenCaseIdsIsNull() {
        // caseIds not set → null

        Map<String, ExecutionContext> result = partitioner.partition(4);

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyPartitionsWhenCaseIdsIsEmpty() throws Exception {
        setCaseIds(List.of());

        Map<String, ExecutionContext> result = partitioner.partition(4);

        assertThat(result).isEmpty();
    }

    @Test
    void createsOnePartitionPerCaseId() throws Exception {
        setCaseIds(List.of("CASE-1", "CASE-2", "CASE-3"));

        Map<String, ExecutionContext> result = partitioner.partition(10);

        assertThat(result).hasSize(3);
        assertThat(result.keySet()).containsExactly("case-CASE-1", "case-CASE-2", "case-CASE-3");
    }

    @Test
    void eachPartitionContainsCorrectExecutionContext() throws Exception {
        setCaseIds(List.of("A", "B"));

        Map<String, ExecutionContext> result = partitioner.partition(2);

        ExecutionContext ctxA = result.get("case-A");
        ExecutionContext ctxB = result.get("case-B");

        assertThat(ctxA).isNotNull();
        assertThat(ctxB).isNotNull();

        assertThat(ctxA.getString(PARTITION_CASE_ID)).isEqualTo("A");
        assertThat(ctxB.getString(PARTITION_CASE_ID)).isEqualTo("B");
    }

    @Test
    void gridSizeDoesNotAffectPartitioning() throws Exception {
        setCaseIds(List.of("X", "Y"));

        Map<String, ExecutionContext> result1 = partitioner.partition(1);
        Map<String, ExecutionContext> result2 = partitioner.partition(100);

        assertThat(result1).isEqualTo(result2);
    }

    private void setCaseIds(List<String> caseIds) throws Exception {
        Field field = CaseIdPartitioner.class.getDeclaredField("caseIds");
        field.setAccessible(true);
        field.set(partitioner, caseIds);
    }
}
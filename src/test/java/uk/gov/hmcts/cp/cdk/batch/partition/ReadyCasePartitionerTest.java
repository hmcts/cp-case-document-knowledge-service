package uk.gov.hmcts.cp.cdk.batch.partition;

import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_MATERIAL_ID_KEY;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@ExtendWith(MockitoExtension.class)
class ReadyCasePartitionerTest {
    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    private ReadyCasePartitioner partitioner;

    @Captor
    private ArgumentCaptor<RowMapper<ReadyCasePartitioner.CaseStatusRow>> mapperCaptor;

    @Captor
    private ArgumentCaptor<Map<String, Object>> paramsCaptor;

    @BeforeEach
    void setUp() {
        partitioner = new ReadyCasePartitioner(jdbcTemplate);
    }

    @Test
    void returnsEmptyWhenNoRows() {
        when(jdbcTemplate.query(any(), anyMap(), mapperCaptor.capture())).thenReturn(List.of());

        final Map<String, ExecutionContext> result = partitioner.partition(3);

        assertThat(result).isEmpty();
    }

    @Test
    void usesAllQueryWhenCaseIdsNull() {
        when(jdbcTemplate.query(any(), anyMap(), mapperCaptor.capture())).thenReturn(sampleRows());

        final Map<String, ExecutionContext> result = partitioner.partition(4);

        assertThat(result).hasSize(2);

        verify(jdbcTemplate).query(
                argThat(sql -> sql.contains("WHERE cd.ingestion_phase = 'INGESTED'")),
                eq(Map.of()),
                mapperCaptor.capture()
        );
    }

    @Test
    void usesFilteredQueryWhenCaseIdsProvided() throws Exception {
        final UUID caseId = randomUUID();
        setCaseIdsParameter(caseId.toString());
        when(jdbcTemplate.query(any(), anyMap(), mapperCaptor.capture())).thenReturn(sampleRows());

        final Map<String, ExecutionContext> result = partitioner.partition(2);

        assertThat(result).hasSize(2);
        verify(jdbcTemplate).query(
                argThat(sql -> sql.contains("IN (:case_ids)")),
                paramsCaptor.capture(),
                mapperCaptor.capture()
        );
        assertThat(paramsCaptor.getValue()).containsKey("case_ids");
    }

    @Test
    void createsPartitionsCorrectly() {
        final List<ReadyCasePartitioner.CaseStatusRow> rows = sampleRows();
        when(jdbcTemplate.query(any(), anyMap(), mapperCaptor.capture())).thenReturn(rows);

        final Map<String, ExecutionContext> result = partitioner.partition(5);

        assertThat(result).hasSize(2);
        assertThat(result.keySet()).containsExactlyInAnyOrder("case-partition-0", "case-partition-1");

        ExecutionContext ctx0 = result.get("case-partition-0");

        assertThat(ctx0.getString(CTX_CASE_ID_KEY)).isEqualTo(rows.get(0).caseId().toString());
        assertThat(ctx0.getString(CTX_DOC_ID_KEY)).isEqualTo(rows.get(0).docId().toString());
        assertThat(ctx0.getString(CTX_MATERIAL_ID_KEY)).isEqualTo(rows.get(0).materialId().toString());
        assertThat(ctx0.getString("ingestionPhaseAt")).isEqualTo(rows.get(0).lastUpdated().toString());
    }

    @Test
    void ignoresInvalidUuids() throws Exception {
        final UUID valid = randomUUID();
        setCaseIdsParameter("not-a-uuid, " + valid);

        when(jdbcTemplate.query(any(), anyMap(), mapperCaptor.capture())).thenReturn(sampleRows());

        partitioner.partition(1);

        verify(jdbcTemplate).query(
                any(),
                paramsCaptor.capture(),
                mapperCaptor.capture()
        );

        assertThat(paramsCaptor.getValue()).containsKey("case_ids");
    }

    private List<ReadyCasePartitioner.CaseStatusRow> sampleRows() {
        return List.of(
                new ReadyCasePartitioner.CaseStatusRow(randomUUID(), randomUUID(), randomUUID(), OffsetDateTime.now()),
                new ReadyCasePartitioner.CaseStatusRow(randomUUID(), randomUUID(), randomUUID(), OffsetDateTime.now().minusDays(1))
        );
    }

    private void setCaseIdsParameter(String value) throws Exception {
        final Field field = ReadyCasePartitioner.class.getDeclaredField("caseIdsParameter");
        field.setAccessible(true);
        field.set(partitioner, value);
    }
}
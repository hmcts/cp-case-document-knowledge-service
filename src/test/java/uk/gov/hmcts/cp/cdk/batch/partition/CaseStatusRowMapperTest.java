package uk.gov.hmcts.cp.cdk.batch.partition;


import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
public class CaseStatusRowMapperTest {

    @Mock
    private ResultSet rs;

    private final RowMapper<ReadyCasePartitioner.CaseStatusRow> mapper = new ReadyCasePartitioner.CaseStatusRowMapper();

    @Test
    void testMapRow_withOffsetDateTime() throws SQLException {
        final UUID caseId = randomUUID();
        final UUID docId = randomUUID();
        final UUID materialId = randomUUID();
        final OffsetDateTime now = OffsetDateTime.now();

        when(rs.getObject("case_id")).thenReturn(caseId);
        when(rs.getObject("doc_id")).thenReturn(docId);
        when(rs.getObject("material_id")).thenReturn(materialId);
        when(rs.getObject("last_updated")).thenReturn(now);

        final ReadyCasePartitioner.CaseStatusRow row = mapper.mapRow(rs, 0);

        assertEquals(caseId, row.caseId());
        assertEquals(docId, row.docId());
        assertEquals(materialId, row.materialId());
        assertEquals(now, row.lastUpdated());
    }

    @Test
    void testMapRow_withTimestamp() throws SQLException {
        final UUID caseId = randomUUID();
        final UUID docId = randomUUID();
        final UUID materialId = randomUUID();
        final Timestamp ts = Timestamp.from(OffsetDateTime.now().toInstant());

        when(rs.getObject("case_id")).thenReturn(caseId);
        when(rs.getObject("doc_id")).thenReturn(docId);
        when(rs.getObject("material_id")).thenReturn(materialId);
        when(rs.getObject("last_updated")).thenReturn(ts);

        final ReadyCasePartitioner.CaseStatusRow row = mapper.mapRow(rs, 0);

        assertThat(row.caseId()).isEqualTo(caseId);
        assertThat(row.docId()).isEqualTo(docId);
        assertThat(row.materialId()).isEqualTo(materialId);
        assertThat(row.lastUpdated()).isEqualTo(ts.toInstant().atOffset(ZoneOffset.UTC));
    }

    @Test
    void testMapRow_withNullLastUpdated() throws SQLException {
        final UUID caseId = randomUUID();
        final UUID docId = randomUUID();
        final UUID materialId = randomUUID();

        when(rs.getObject("case_id")).thenReturn(caseId);
        when(rs.getObject("doc_id")).thenReturn(docId);
        when(rs.getObject("material_id")).thenReturn(materialId);
        when(rs.getObject("last_updated")).thenReturn(null);

        final ReadyCasePartitioner.CaseStatusRow row = mapper.mapRow(rs, 0);

        assertThat(row.caseId()).isEqualTo(caseId);
        assertThat(row.docId()).isEqualTo(docId);
        assertThat(row.materialId()).isEqualTo(materialId);
        assertThat(row.lastUpdated()).isNull();
    }
}

package uk.gov.hmcts.cp.cdk.repo;

import static java.util.Collections.singletonList;
import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@ExtendWith(MockitoExtension.class)
class DocumentIdResolverTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    @InjectMocks
    private DocumentIdResolver resolver;

    @Captor
    private ArgumentCaptor<MapSqlParameterSource> captor;

    @Captor
    private ArgumentCaptor<RowMapper<?>> rowMapper;

    @Test
    void testResolveExistingDocId_found() {
        UUID caseId = randomUUID();
        UUID materialId = randomUUID();
        UUID docId = randomUUID();

        when(jdbc.query(anyString(), ArgumentMatchers.<MapSqlParameterSource>any(), ArgumentMatchers.<RowMapper<UUID>>any())).thenReturn(List.of(docId));

        final Optional<UUID> result = resolver.resolveExistingDocId(caseId, materialId);

        assertTrue(result.isPresent());
        assertEquals(docId, result.get());

        // verify correct SQL and params were passed
        verify(jdbc).query(eq(DocumentIdResolver.SQL_FIND_EXISTING_DOC), captor.capture(), rowMapper.capture());
        MapSqlParameterSource params = captor.getValue();
        assertEquals(caseId, params.getValue("case_id"));
        assertEquals(materialId, params.getValue("material_id"));
    }

    @Test
    void testResolveExistingDocId_notFound() {
        final UUID caseId = randomUUID();
        final UUID materialId = randomUUID();

        when(jdbc.query(anyString(), ArgumentMatchers.<MapSqlParameterSource>any(), ArgumentMatchers.<RowMapper<UUID>>any())).thenReturn(List.of());

        final Optional<UUID> result = resolver.resolveExistingDocId(caseId, materialId);

        assertTrue(result.isEmpty());
    }

    @Test
    void testResolveExistingDocId_nullRow() {
        final UUID caseId = randomUUID();
        final UUID materialId = randomUUID();
        final UUID docId = null;

        when(jdbc.query(anyString(), ArgumentMatchers.<MapSqlParameterSource>any(), ArgumentMatchers.<RowMapper<UUID>>any())).thenReturn(singletonList(docId));

        final Optional<UUID> result = resolver.resolveExistingDocId(caseId, materialId);

        assertTrue(result.isEmpty());
    }
}
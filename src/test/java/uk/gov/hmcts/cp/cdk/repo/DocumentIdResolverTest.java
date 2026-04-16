package uk.gov.hmcts.cp.cdk.repo;

import static java.util.Collections.singletonList;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
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

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void shouldReturnDocId_whenRowExists() {
        final UUID expectedId = randomUUID();

        when(jdbc.query(anyString(), ArgumentMatchers.<MapSqlParameterSource>any(), ArgumentMatchers.<RowMapper<UUID>>any())).thenReturn(List.of(expectedId));

        final Optional<UUID> result = resolver.resolveExistingDocIdForDefendant(randomUUID(), randomUUID(), randomUUID());

        assertThat(result.isPresent()).isTrue();
        assertThat(result.get()).isEqualTo(expectedId);
    }

    @Test
    void shouldReturnEmpty_whenNoRows() {
        when(jdbc.query(anyString(), ArgumentMatchers.<MapSqlParameterSource>any(), ArgumentMatchers.<RowMapper<UUID>>any())).thenReturn(List.of());

        final Optional<UUID> result = resolver.resolveExistingDocIdForDefendant(randomUUID(), randomUUID(), randomUUID());

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void shouldReturnStatus_whenFound() {
        when(jdbc.query(anyString(), ArgumentMatchers.<MapSqlParameterSource>any(), ArgumentMatchers.<RowMapper<String>>any()))
                .thenReturn(List.of("INGESTED"));

        final Optional<String> result = resolver.findIngestionStatus(UUID.randomUUID());

        assertThat(result.isPresent()).isTrue();
        assertThat(result.get()).isEqualTo("INGESTED");
    }

    @Test
    void shouldReturnEmpty_whenNoStatus() {
        when(jdbc.query(anyString(), ArgumentMatchers.<MapSqlParameterSource>any(), ArgumentMatchers.<RowMapper<String>>any()))
                .thenReturn(List.of());

        final Optional<String> result = resolver.findIngestionStatus(UUID.randomUUID());

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void shouldReturnEmpty_whenExceptionThrown() {
        when(jdbc.query(anyString(), ArgumentMatchers.<MapSqlParameterSource>any(), ArgumentMatchers.<RowMapper<String>>any()))
                .thenThrow(new RuntimeException("DB error"));

        final Optional<String> result = resolver.findIngestionStatus(UUID.randomUUID());

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void shouldReturnTrue_whenAllDocsIngested() {
        final List<UUID> input = List.of(UUID.randomUUID(), UUID.randomUUID());

        when(jdbc.query(anyString(), ArgumentMatchers.<MapSqlParameterSource>any(), ArgumentMatchers.<RowMapper<UUID>>any()))
                .thenReturn(input);

        boolean result = resolver.findIngestionStatusForAllDocs(input);

        assertThat(result).isTrue();
    }

    @Test
    void shouldReturnFalse_whenNotAllDocsIngested() {
        final List<UUID> input = List.of(UUID.randomUUID(), UUID.randomUUID());

        when(jdbc.query(anyString(), ArgumentMatchers.<MapSqlParameterSource>any(), ArgumentMatchers.<RowMapper<UUID>>any()))
                .thenReturn(List.of(input.get(0))); // only one ingested

        final boolean result = resolver.findIngestionStatusForAllDocs(input);

        assertThat(result).isFalse();
    }

    @Test
    void shouldReturnFalse_whenEmptyInput() {
        assertThat(resolver.findIngestionStatusForAllDocs(List.of())).isFalse();
    }

    @Test
    void shouldReturnFalse_whenNullInput() {
        assertThat(resolver.findIngestionStatusForAllDocs(null)).isFalse();
    }

    @Test
    void shouldReturnFalse_whenExceptionThrown() {
        final List<UUID> input = List.of(UUID.randomUUID());

        when(jdbc.query(anyString(), ArgumentMatchers.<MapSqlParameterSource>any(), ArgumentMatchers.<RowMapper<UUID>>any()))
                .thenThrow(new RuntimeException("DB error"));

        final boolean result = resolver.findIngestionStatusForAllDocs(input);

        assertThat(result).isFalse();
    }
}
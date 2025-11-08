package uk.gov.hmcts.cp.cdk.batch.clients.progression.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import uk.gov.hmcts.cp.cdk.batch.clients.progression.ProgressionClientConfig;
import uk.gov.hmcts.cp.cdk.batch.clients.progression.dto.CourtDocumentSearchResponse;

import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Progression Dto Mapper tests")
class ProgressionDtoMapperTest {

    @Test
    @DisplayName("Returns Latest Material When Doc Type Matches")
    void returnsLatestMaterialWhenDocTypeMatches() {
        final var cfg = new ProgressionClientConfig(
                "application/vnd.progression.query.courtdocuments+json",
                "DOC-41",
                "/x",
                "/y/{materialId}",
                "a",
                "b"
        );
        final var mapper = new ProgressionDtoMapper(cfg);

        final var m1 = new CourtDocumentSearchResponse.Material("m1", ZonedDateTime.parse("2024-01-01T10:15:30Z"));
        final var m2 = new CourtDocumentSearchResponse.Material("m2", ZonedDateTime.parse("2024-03-01T10:15:30Z"));
        final var doc = new CourtDocumentSearchResponse.Document("DOC-41", "Some Doc", "IDPC", List.of(m1, m2));
        final var idx = new CourtDocumentSearchResponse.DocumentIndex(List.of("CASE-1"), doc);

        final var latest = mapper.mapToLatestMaterialInfo(idx);
        assertThat(latest).isPresent();
        assertThat(latest.get().materialId()).isEqualTo("m2");
    }

    @Test
    @DisplayName("Empty When Doc Type Does Not Match")
    void emptyWhenDocTypeDoesNotMatch() {
        final var cfg = new ProgressionClientConfig(
                "application/vnd.progression.query.courtdocuments+json",
                "DOC-XX",
                "/x",
                "/y/{materialId}",
                "a",
                "b"
        );
        final var mapper = new ProgressionDtoMapper(cfg);

        final var m1 = new CourtDocumentSearchResponse.Material("m1", ZonedDateTime.parse("2024-01-01T10:15:30Z"));
        final var doc = new CourtDocumentSearchResponse.Document("DOC-41", "Some Doc", "IDPC", List.of(m1));
        final var idx = new CourtDocumentSearchResponse.DocumentIndex(List.of("CASE-1"), doc);

        assertThat(mapper.mapToLatestMaterialInfo(idx)).isEmpty();
    }


    @Test
    @DisplayName("Empty When Upload Time Does Not Exists")
    void emptyWhenUploadTimeDoesNotExists() {
        final var cfg = new ProgressionClientConfig(
                "application/vnd.progression.query.courtdocuments+json",
                "41be14e8-9df5-4b08-80b0-1e670bc80a5b",
                "/x",
                "/y/{materialId}",
                "a",
                "b"
        );
        final var mapper = new ProgressionDtoMapper(cfg);

        final var m1 = new CourtDocumentSearchResponse.Material(
                "m1",
                null
        );
        final var doc = new CourtDocumentSearchResponse.Document("41be14e8-9df5-4b08-80b0-1e670bc80a5b", "Some Doc", null, List.of(m1));
        final var idx = new CourtDocumentSearchResponse.DocumentIndex(List.of("CASE-1"), doc);

        assertThat(mapper.mapToLatestMaterialInfo(idx)).isEmpty();
    }

    @Test
    @DisplayName("Empty When Document Not Exists")
    void emptyWhenDocumentNotExists() {
        final var cfg = new ProgressionClientConfig(
                "application/vnd.progression.query.courtdocuments+json",
                "DOC-XX",
                "/x",
                "/y/{materialId}",
                "a",
                "b"
        );
        final var mapper = new ProgressionDtoMapper(cfg);

        final var idx = new CourtDocumentSearchResponse.DocumentIndex(List.of("CASE-1"), null);

        assertThat(mapper.mapToLatestMaterialInfo(idx)).isEmpty();
    }

}
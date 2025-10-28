package uk.gov.hmcts.cp.cdk.clients.progression;

import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.CourtDocumentSearchResponse;
import uk.gov.hmcts.cp.cdk.clients.progression.mapper.ProgressionDtoMapper;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProgressionDtoMapperTest {

    @Test
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
        final var doc = new CourtDocumentSearchResponse.Document("DOC-41", "Some Doc", List.of(m1, m2));
        final var idx = new CourtDocumentSearchResponse.DocumentIndex(List.of("CASE-1"), doc);

        final var latest = mapper.mapToLatestMaterialInfo(idx);
        assertThat(latest).isPresent();
        assertThat(latest.get().materialId()).isEqualTo("m2");
    }

    @Test
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
        final var doc = new CourtDocumentSearchResponse.Document("DOC-41", "Some Doc", List.of(m1));
        final var idx = new CourtDocumentSearchResponse.DocumentIndex(List.of("CASE-1"), doc);

        assertThat(mapper.mapToLatestMaterialInfo(idx)).isEmpty();
    }
}
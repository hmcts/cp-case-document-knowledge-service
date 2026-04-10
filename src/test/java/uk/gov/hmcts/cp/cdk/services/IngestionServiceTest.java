package uk.gov.hmcts.cp.cdk.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.cdk.repo.IngestionStatusViewRepository;
import uk.gov.hmcts.cp.openapi.model.cdk.DocumentIngestionPhase;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionStatusResponse;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("Ingestion Service tests (asynchronous start)")
@ExtendWith(MockitoExtension.class)
class IngestionServiceTest {

    @Mock
    private IngestionStatusViewRepository repo;

    @Test
    @DisplayName("getStatus valid row found for the caseId")
    void getStatus() {
        final IngestionService service = new IngestionService(repo);
        final UUID caseId = UUID.randomUUID();
        final OffsetDateTime lastUpdated = OffsetDateTime.now();

        when(repo.findByCaseId(caseId)).thenReturn(Optional.of(new IngestionStatusViewRepository.Row(caseId, "UPLOADING", lastUpdated)));

        final IngestionStatusResponse response = service.getStatus(caseId);

        assertThat(response.getScope()).isNotNull();
        assertThat(response.getScope().getCaseId()).isEqualTo(caseId);
        assertThat(response.getScope().getIsIdpcAvailable()).isNull();
        assertThat(response.getLastUpdated()).isEqualTo(lastUpdated);
        assertThat(response.getPhase()).isEqualTo(DocumentIngestionPhase.UPLOADING);
        assertThat(response.getMessage()).isNull();
    }

    @Test
    @DisplayName("getStatus no row found for the caseId")
    void getStatus_rowNotFound() {
        final IngestionStatusViewRepository repo = mock(IngestionStatusViewRepository.class);

        final IngestionService service = new IngestionService(repo);
        final UUID caseId = UUID.randomUUID();

        when(repo.findByCaseId(caseId)).thenReturn(Optional.empty());

        final IngestionStatusResponse response = service.getStatus(caseId);

        assertThat(response.getScope()).isNotNull();
        assertThat(response.getScope().getCaseId()).isEqualTo(caseId);
        assertThat(response.getScope().getIsIdpcAvailable()).isNull();
        assertThat(response.getLastUpdated()).isNull();
        assertThat(response.getPhase()).isEqualTo(DocumentIngestionPhase.NOT_FOUND);
        assertThat(response.getMessage()).isEqualTo("No uploads seen for this case");
    }
}

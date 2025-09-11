package uk.gov.hmcts.cp.services;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.cp.openapi.model.CourtScheduleResponse;
import uk.gov.hmcts.cp.repositories.CourtScheduleRepository;
import uk.gov.hmcts.cp.repositories.InMemoryCourtScheduleRepositoryImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CourtScheduleServiceTest {

    private final CourtScheduleRepository courtScheduleRepository = new InMemoryCourtScheduleRepositoryImpl();
    private final CourtScheduleService courtScheduleService = new CourtScheduleService(courtScheduleRepository);

    @Test
    void shouldReturnStubbedCourtScheduleResponse_whenValidCaseUrnProvided() {
        // Arrange
        String validCaseUrn = "123-ABC-456";

        // Act
        CourtScheduleResponse response = courtScheduleService.getCourtScheduleByCaseUrn(validCaseUrn);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.getCourtSchedule()).isNotEmpty();
        assertThat(response.getCourtSchedule().get(0).getHearings()).isNotEmpty();
        assertThat(response.getCourtSchedule().get(0).getHearings().get(0).getCourtSittings()).isNotEmpty();
        assertThat(response.getCourtSchedule().get(0).getHearings().get(0).getHearingDescription())
                .isEqualTo("Sentencing for theft case");
    }

    @Test
    void shouldThrowBadRequestException_whenCaseUrnIsNull() {
        // Arrange
        String nullCaseUrn = null;

        // Act & Assert
        assertThatThrownBy(() -> courtScheduleService.getCourtScheduleByCaseUrn(nullCaseUrn))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST")
                .hasMessageContaining("caseUrn is required");
    }

    @Test
    void shouldThrowBadRequestException_whenCaseUrnIsEmpty() {
        // Arrange
        String emptyCaseUrn = "";

        // Act & Assert
        assertThatThrownBy(() -> courtScheduleService.getCourtScheduleByCaseUrn(emptyCaseUrn))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST")
                .hasMessageContaining("caseUrn is required");
    }
}
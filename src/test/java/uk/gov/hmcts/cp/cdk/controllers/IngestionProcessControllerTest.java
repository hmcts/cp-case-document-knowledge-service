package uk.gov.hmcts.cp.cdk.controllers;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.gov.hmcts.cp.cdk.services.IngestionProcessService;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessPhase;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessResponse;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit test for {@link IngestionProcessController}.
 */
class IngestionProcessControllerTest {

    @Test
    void startIngestionProcess_returns_response_payload() throws Exception {
        // Mock the service
        final IngestionProcessService service = Mockito.mock(IngestionProcessService.class);
        final IngestionProcessController controller = new IngestionProcessController(service);

        // Build standalone MockMvc
        final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        // Create mock request and response models
        IngestionProcessRequest request = new IngestionProcessRequest();
        request.setCourtCentreId(UUID.fromString("f8254db1-1683-483e-afb3-b87fde5a0a26"));
        request.setRoomId(UUID.fromString("9e4932f7-97b2-3010-b942-ddd2624e4dd8"));
        request.setDate(LocalDate.parse("2025-10-23"));
        request.setEffectiveAt(OffsetDateTime.parse("2025-05-01T12:00:00Z"));

        IngestionProcessResponse response = new IngestionProcessResponse();
        response.setPhase(IngestionProcessPhase.STARTED);
        response.setLastUpdated(OffsetDateTime.parse("2025-01-02T10:00:00Z"));
        response.setMessage("Ingestion process started");

        // Mock service behavior
        when(service.getIngestionStatus(Mockito.eq(request))).thenReturn(response);

        // Perform POST request
        mvc.perform(
                        post("/ingestion-process")
                                .contentType("application/vnd.casedocumentknowledge-service.ingestion-process+json")
                                .accept("application/vnd.casedocumentknowledge-service.ingestion-process+json")
                                .content("""
                            {
                              "courtCentreId": "f8254db1-1683-483e-afb3-b87fde5a0a26",
                              "roomId": "9e4932f7-97b2-3010-b942-ddd2624e4dd8",
                              "date": "2025-10-23",
                              "effectiveAt": "2025-05-01T12:00:00Z"
                            }
                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("STARTED"))
                .andExpect(jsonPath("$.message").value("Ingestion process started"))
                .andExpect(jsonPath("$.lastUpdated").value("2025-01-02T10:00:00Z"));
    }
}

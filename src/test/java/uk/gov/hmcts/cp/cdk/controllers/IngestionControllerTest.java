package uk.gov.hmcts.cp.cdk.controllers;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.gov.hmcts.cp.cdk.services.IngestionService;
import uk.gov.hmcts.cp.openapi.model.cdk.DocumentIngestionPhase;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionStatusResponse;
import uk.gov.hmcts.cp.openapi.model.cdk.Scope;


class IngestionControllerTest {

    @Test
    void getIngestionStatus_returns_payload() throws Exception {
        final IngestionService service = Mockito.mock(IngestionService.class);
        final IngestionController controller = new IngestionController(service);
        final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        final UUID caseId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        final IngestionStatusResponse resp = new IngestionStatusResponse();
        final Scope scope = new Scope();
        scope.setCaseId(caseId);
        resp.setScope(scope);
        resp.setPhase(DocumentIngestionPhase.INGESTED);
        resp.setLastUpdated(OffsetDateTime.parse("2025-05-01T12:05:00Z"));
        resp.setMessage(null);

        when(service.getStatus(Mockito.eq(caseId))).thenReturn(resp);

        mvc.perform(get("/ingestions/status").param("caseId", caseId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.caseId").value(caseId.toString()))
                .andExpect(jsonPath("$.phase").value("INGESTED"));
    }
}


package uk.gov.hmcts.cp.cdk.controllers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.batch.core.job.parameters.JobParametersInvalidException;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.gov.hmcts.cp.cdk.controllers.exception.IngestionExceptionHandler;
import uk.gov.hmcts.cp.cdk.services.IngestionService;
import uk.gov.hmcts.cp.openapi.model.cdk.*;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;


class IngestionControllerTest {

    private static final MediaType VND =
            MediaType.valueOf("application/vnd.casedocumentknowledge-service.ingestion-process+json");

    private MockMvc mvc(final IngestionService service) {
        return MockMvcBuilders
                .standaloneSetup(new IngestionController(service))
                .setControllerAdvice(new IngestionExceptionHandler())
                .build();
    }
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



    @Test
    @DisplayName("POST /ingestions/start returns 200 with STARTED payload")
    void start_success() throws Exception {
        IngestionService service = mock(IngestionService.class);
        MockMvc mvc = mvc(service);

        IngestionProcessResponse response = new IngestionProcessResponse();
        response.setPhase(IngestionProcessPhase.STARTED);
        response.setMessage("Ingestion process started successfully (executionId=42)");

        when(service.startIngestionProcess(any(IngestionProcessRequest.class))).thenReturn(response);

        String body = """
                {
                  "courtCentreId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                  "roomId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                  "date": "2025-10-23",
                  "effectiveAt": "2025-05-01T12:00:00Z"
                }
                """;

        mvc.perform(post("/ingestions/start").contentType(VND).accept(VND).content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentType(VND))
                .andExpect(jsonPath("$.phase").value("STARTED"))
                .andExpect(jsonPath("$.message", containsString("executionId=42")));
    }

    @Test
    @DisplayName("NoSuchJobException -> 404 vendor error")
    void start_noSuchJob_404() throws Exception {
        IngestionService service = mock(IngestionService.class);
        MockMvc mvc = mvc(service);

        when(service.startIngestionProcess(any(IngestionProcessRequest.class)))
                .thenThrow(new NoSuchJobException("caseIngestionJob"));

        String body = """
                {
                  "courtCentreId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                  "roomId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                  "date": "2025-10-23"
                }
                """;

        mvc.perform(post("/ingestions/start").contentType(VND).accept(VND).content(body))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(VND))
                .andExpect(jsonPath("$.message", containsString("caseIngestionJob")));
    }

    @Test
    @DisplayName("JobParametersInvalidException -> 400 vendor error")
    void start_invalidParams_400() throws Exception {
        IngestionService service = mock(IngestionService.class);
        MockMvc mvc = mvc(service);

        when(service.startIngestionProcess(any(IngestionProcessRequest.class)))
                .thenThrow(new JobParametersInvalidException("invalid parameters"));

        String body = """
                {
                  "courtCentreId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                  "roomId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                  "date": "not-a-date"
                }
                """;

        mvc.perform(post("/ingestions/start").contentType(VND).accept(VND).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(VND))
                .andExpect(jsonPath("$.message", containsString("invalid parameters")));
    }

    @Test
    @DisplayName("JobExecutionAlreadyRunningException -> 409 vendor error")
    void start_conflict_409() throws Exception {
        IngestionService service = mock(IngestionService.class);
        MockMvc mvc = mvc(service);

        when(service.startIngestionProcess(any(IngestionProcessRequest.class)))
                .thenThrow(new JobExecutionAlreadyRunningException("already running"));

        String body = """
                {
                  "courtCentreId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                  "roomId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                  "date": "2025-10-23"
                }
                """;

        mvc.perform(post("/ingestions/start").contentType(VND).accept(VND).content(body))
                .andExpect(status().isConflict())
               .andExpect(content().contentType(VND))
                .andExpect(jsonPath("$.message", containsString("already running")));
    }
}


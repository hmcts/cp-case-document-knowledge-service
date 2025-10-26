package uk.gov.hmcts.cp.cdk.controllers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.parameters.JobParametersInvalidException;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.gov.hmcts.cp.cdk.controllers.exception.IngestionExceptionHandler;
import uk.gov.hmcts.cp.cdk.services.IngestionProcessService;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessPhase;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessResponse;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IngestionProcessControllerTest {

    private static final MediaType VND =
            MediaType.valueOf("application/vnd.casedocumentknowledge-service.ingestion-process+json");

    private MockMvc mvc(final IngestionProcessService service) {
        return MockMvcBuilders
                .standaloneSetup(new IngestionProcessController(service))
                .setControllerAdvice(new IngestionExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /ingestion-process returns 200 with STARTED payload")
    void start_success() throws Exception {
        IngestionProcessService service = mock(IngestionProcessService.class);
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

        mvc.perform(post("/ingestion-process").contentType(VND).accept(VND).content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentType(VND))
                .andExpect(jsonPath("$.phase").value("STARTED"))
                .andExpect(jsonPath("$.message", containsString("executionId=42")));
    }

    @Test
    @DisplayName("NoSuchJobException -> 404 vendor error")
    void start_noSuchJob_404() throws Exception {
        IngestionProcessService service = mock(IngestionProcessService.class);
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

        mvc.perform(post("/ingestion-process").contentType(VND).accept(VND).content(body))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(VND))
                .andExpect(jsonPath("$.message", containsString("caseIngestionJob")));
    }

    @Test
    @DisplayName("JobParametersInvalidException -> 400 vendor error")
    void start_invalidParams_400() throws Exception {
        IngestionProcessService service = mock(IngestionProcessService.class);
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

        mvc.perform(post("/ingestion-process").contentType(VND).accept(VND).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(VND))
                .andExpect(jsonPath("$.message", containsString("invalid parameters")));
    }

    @Test
    @DisplayName("JobExecutionAlreadyRunningException -> 409 vendor error")
    void start_conflict_409() throws Exception {
        IngestionProcessService service = mock(IngestionProcessService.class);
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

        mvc.perform(post("/ingestion-process").contentType(VND).accept(VND).content(body))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(VND))
                .andExpect(jsonPath("$.message", containsString("already running")));
    }
}

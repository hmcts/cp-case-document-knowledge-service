package uk.gov.hmcts.cp.cdk.controllers;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import uk.gov.hmcts.cp.cdk.batch.IngestionProperties;
import uk.gov.hmcts.cp.cdk.batch.clients.common.CQRSClientProperties;
import uk.gov.hmcts.cp.cdk.controllers.exception.IngestionExceptionHandler;
import uk.gov.hmcts.cp.cdk.services.IngestionService;
import uk.gov.hmcts.cp.cdk.services.JobManagerService;
import uk.gov.hmcts.cp.openapi.model.cdk.DocumentIngestionPhase;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessPhase;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessResponse;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionStatusResponse;
import uk.gov.hmcts.cp.openapi.model.cdk.Scope;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

@DisplayName("Ingestion Controller tests")
class IngestionControllerTest {

    private static final MediaType VND =
            MediaType.valueOf("application/vnd.casedocumentknowledge-service.ingestion-process+json");

    private static final String HEADER_NAME = "CJSCPPUID";
    private static final String HEADER_VALUE = "u-123";

    private MockMvc mvc(final IngestionService service, final JobManagerService jobService) {
        final CQRSClientProperties props = mock(CQRSClientProperties.class, Mockito.RETURNS_DEEP_STUBS);
        final IngestionProperties ingestionProps = mock(IngestionProperties.class, Mockito.RETURNS_DEEP_STUBS);
        when(props.headers().cjsCppuid()).thenReturn(HEADER_NAME);

        return MockMvcBuilders
                .standaloneSetup(new IngestionController(service, jobService,props,ingestionProps))
                .setControllerAdvice(new IngestionExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("Get Ingestion Status returns payload")
    void getIngestionStatus_returns_payload() throws Exception {
        final IngestionService service = mock(IngestionService.class);
        final JobManagerService jobService = mock(JobManagerService.class);
        final IngestionProperties ingestionProps = mock(IngestionProperties.class, Mockito.RETURNS_DEEP_STUBS);
        final CQRSClientProperties props = mock(CQRSClientProperties.class, Mockito.RETURNS_DEEP_STUBS);
        when(props.headers().cjsCppuid()).thenReturn(HEADER_NAME);
        final IngestionController controller = new IngestionController(service, jobService,props,ingestionProps);

        final MockMvc mvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new IngestionExceptionHandler())
                .build();

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
    @DisplayName("POST /ingestions/start returns 202 with STARTED payload and requestId")
    void start_success() throws Exception {
        IngestionService service = mock(IngestionService.class);
        final JobManagerService jobService = mock(JobManagerService.class);
        MockMvc mvc = mvc(service,jobService);

        IngestionProcessResponse response = new IngestionProcessResponse();
        response.setPhase(IngestionProcessPhase.STARTED);
        response.setMessage("Ingestion request accepted; job will start asynchronously (requestId=abc123)");

        when(service.startIngestionProcess(anyString(), any(IngestionProcessRequest.class))).thenReturn(response);

        String body = """
                {
                  "courtCentreId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                  "roomId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                  "date": "2025-10-23",
                  "effectiveAt": "2025-05-01T12:00:00Z"
                }
                """;

        mvc.perform(post("/ingestions/start")
                        .contentType(VND).accept(VND)
                        .header(HEADER_NAME, HEADER_VALUE)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(content().contentType(VND))
                .andExpect(jsonPath("$.phase").value("STARTED"))
                .andExpect(jsonPath("$.message", containsString("requestId=")));
    }

    @Test
    @DisplayName("ResponseStatusException NOT_FOUND -> 404 vendor error")
    void start_notFound_404() throws Exception {
        IngestionService service = mock(IngestionService.class);
        JobManagerService jobService = mock(JobManagerService.class);
        MockMvc mvc = mvc(service,jobService);

        when(service.startIngestionProcess(anyString(), any(IngestionProcessRequest.class)))
                .thenThrow(new ResponseStatusException(NOT_FOUND, "caseIngestionJob"));

        String body = """
                {
                  "courtCentreId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                  "roomId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                  "date": "2025-10-24"
                }
                """;

        mvc.perform(post("/ingestions/start")
                        .contentType(VND).accept(VND)
                        .header(HEADER_NAME, HEADER_VALUE)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(VND))
                .andExpect(jsonPath("$.message", containsString("caseIngestionJob")));
    }

    @Test
    @DisplayName("ResponseStatusException BAD_REQUEST -> 400 vendor error")
    void start_invalidParams_400() throws Exception {
        IngestionService service = mock(IngestionService.class);
        JobManagerService jobService = mock(JobManagerService.class);
        MockMvc mvc = mvc(service,jobService);

        when(service.startIngestionProcess(anyString(), any(IngestionProcessRequest.class)))
                .thenThrow(new ResponseStatusException(BAD_REQUEST, "invalid parameters"));

        String body = """
                {
                  "courtCentreId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                  "roomId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                  "date": "not-a-date"
                }
                """;

        mvc.perform(post("/ingestions/start")
                        .contentType(VND).accept(VND)
                        .header(HEADER_NAME, HEADER_VALUE)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(VND))
                .andExpect(jsonPath("$.message", containsString("invalid parameters")));
    }

    @Test
    @DisplayName("ResponseStatusException CONFLICT -> 409 vendor error")
    void start_conflict_409() throws Exception {
        IngestionService service = mock(IngestionService.class);
        JobManagerService jobService = mock(JobManagerService.class);
        MockMvc mvc = mvc(service,jobService);

        when(service.startIngestionProcess(anyString(), any(IngestionProcessRequest.class)))
                .thenThrow(new ResponseStatusException(CONFLICT, "already running"));

        String body = """
                {
                  "courtCentreId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                  "roomId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                  "date": "2025-10-23"
                }
                """;

        mvc.perform(post("/ingestions/start")
                        .contentType(VND).accept(VND)
                        .header(HEADER_NAME, HEADER_VALUE)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(VND))
                .andExpect(jsonPath("$.message", containsString("already running")));
    }
}

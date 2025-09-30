package uk.gov.hmcts.cp.controllers;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.gov.hmcts.cp.openapi.model.IngestionStatus;
import uk.gov.hmcts.cp.openapi.model.QueryStatusResponse;
import uk.gov.hmcts.cp.openapi.model.QuerySummary;
import uk.gov.hmcts.cp.services.QueryReadService;
import uk.gov.hmcts.cp.services.QueryWriteService;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class QueriesApiControllerTest {

    @Test
    void listQueries_asOf_param() throws Exception {
        // mock services
        QueryReadService readService = Mockito.mock(QueryReadService.class);
        QueryWriteService writeService = Mockito.mock(QueryWriteService.class);

        // real controller wired with the mocks
        QueriesApiController controller = new QueriesApiController(readService, writeService);

        // standalone MockMvc (no Spring context)
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        var resp = new QueryStatusResponse()
                .asOf(OffsetDateTime.parse("2025-05-01T12:00:00Z"))
                .addQueriesItem(new QuerySummary()
                        .queryId(UUID.randomUUID())
                        .userQuery("Q1")
                        .queryPrompt("Prompt for Q1")
                        .status(IngestionStatus.INGESTED));

        when(readService.listQueries(any())).thenReturn(resp);

        mvc.perform(get("/queries")
                        .queryParam("at", "2025-05-01T12:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value("2025-05-01T12:00:00Z"))
                .andExpect(jsonPath("$.queries[0].userQuery").value("Q1"))
                .andExpect(jsonPath("$.queries[0].queryPrompt").value("Prompt for Q1"))
                .andExpect(jsonPath("$.queries[0].status").value("INGESTED"));
    }

    @Test
    void upsertQueries_post_returns_accepted_and_echoes() throws Exception {
        // mock services
        QueryReadService readService = Mockito.mock(QueryReadService.class);
        QueryWriteService writeService = Mockito.mock(QueryWriteService.class);

        // controller with both mocks
        QueriesApiController controller = new QueriesApiController(readService, writeService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        UUID qid = UUID.randomUUID();

        // response that the write service will return
        var echoed = new QueryStatusResponse()
                .asOf(OffsetDateTime.parse("2025-05-01T12:00:00Z"))
                .addQueriesItem(new QuerySummary()
                        .queryId(qid)
                        .userQuery("Q1")
                        .queryPrompt("Prompt for Q1")
                        .status(IngestionStatus.INGESTED));

        when(writeService.upsertQueries(any())).thenReturn(echoed);

        // JSON body for POST /queries
        String body = """
            {
              "effectiveAt": "2025-05-01T12:00:00Z",
              "queries": [
                {
                  "queryId": "%s",
                  "userQuery": "Q1",
                  "queryPrompt": "Prompt for Q1",
                  "status": "INGESTED"
                }
              ]
            }
            """.formatted(qid);

        mvc.perform(post("/queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.asOf").value("2025-05-01T12:00:00Z"))
                .andExpect(jsonPath("$.queries[0].queryId").isNotEmpty())
                .andExpect(jsonPath("$.queries[0].userQuery").value("Q1"))
                .andExpect(jsonPath("$.queries[0].queryPrompt").value("Prompt for Q1"))
                .andExpect(jsonPath("$.queries[0].status").value("INGESTED"));
    }
}

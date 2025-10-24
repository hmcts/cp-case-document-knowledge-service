package uk.gov.hmcts.cp.cdk.controllers;


import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.gov.hmcts.cp.cdk.services.QueryService;
import uk.gov.hmcts.cp.openapi.model.cdk.*;


class QueriesControllerTest {

    public final String VND_TYPE_JSON = "application/vnd.casedocumentknowledge-service.queries+json";
    @Test
    void listQueries_returns_case_as_of() throws Exception {
        final QueryService service = Mockito.mock(QueryService.class);
        final QueriesController controller = new QueriesController(service);
        final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        final UUID caseId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        final QuerySummary qs = new QuerySummary();
        qs.setQueryId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
        qs.setCaseId(caseId);
        qs.setLabel("Case Summary");
        qs.setUserQuery("UQ");
        qs.setQueryPrompt("QP");
        qs.setStatus(QueryLifecycleStatus.ANSWER_NOT_AVAILABLE);
        qs.setEffectiveAt(OffsetDateTime.parse("2025-05-01T11:59:00Z"));

        final QueryStatusResponse resp = new QueryStatusResponse();
        resp.setAsOf(OffsetDateTime.parse("2025-05-01T12:00:00Z"));
        final Scope scope = new Scope();
        scope.setCaseId(caseId);
        resp.setScope(scope);
        resp.setQueries(List.of(qs));

        when(service.listForCaseAsOf(Mockito.eq(caseId), Mockito.any())).thenReturn(resp);

        mvc.perform(get("/queries").param("caseId", caseId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.caseId").value(caseId.toString()))
                .andExpect(jsonPath("$.queries[0].label").value("Case Summary"))
                .andExpect(jsonPath("$.queries[0].status").value("ANSWER_NOT_AVAILABLE"));
    }

    @Test
    void upsertQueries_returns_accepted_definition_snapshot() throws Exception {
        final QueryService service = Mockito.mock(QueryService.class);
        final QueriesController controller = new QueriesController(service);
        final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        final QueryVersionSummary v = new QueryVersionSummary();
        v.setQueryId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
        v.setLabel("Case Summary");
        v.setUserQuery("UQ");
        v.setQueryPrompt("QP");
        v.setEffectiveAt(OffsetDateTime.parse("2025-05-01T12:00:00Z"));

        final QueryDefinitionsResponse resp = new QueryDefinitionsResponse();
        resp.setAsOf(OffsetDateTime.parse("2025-05-01T12:00:00Z"));
        resp.setQueries(List.of(v));

        when(service.upsertDefinitions(Mockito.any(QueryUpsertRequest.class))).thenReturn(resp);

        mvc.perform(
                        post("/queries")
                                .contentType(VND_TYPE_JSON)
                                .content("""
                        {
                          "effectiveAt":"2025-05-01T12:00:00Z",
                          "queries":[
                            {
                              "queryId":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                              "userQuery":"UQ",
                              "queryPrompt":"QP"
                            }
                          ]
                        }
                        """)
                )
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.queries[0].label").value("Case Summary"))
                .andExpect(jsonPath("$.asOf").value("2025-05-01T12:00:00Z"));
    }

    @Test
    void getQuery_returns_single_summary() throws Exception {
        final QueryService service = Mockito.mock(QueryService.class);
        final QueriesController controller = new QueriesController(service);
        final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        final UUID caseId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        final UUID queryId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        final QuerySummary qs = new QuerySummary();
        qs.setQueryId(queryId);
        qs.setCaseId(caseId);
        qs.setLabel("Case Summary");
        qs.setUserQuery("UQ");
        qs.setQueryPrompt("QP");
        qs.setStatus(QueryLifecycleStatus.ANSWER_AVAILABLE);
        qs.setEffectiveAt(OffsetDateTime.parse("2025-05-01T12:00:00Z"));

        when(service.getOneForCaseAsOf(Mockito.eq(caseId), Mockito.eq(queryId), Mockito.any()))
                .thenReturn(qs);

        mvc.perform(get("/queries/{queryId}", queryId).param("caseId", caseId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queryId").value(queryId.toString()))
                .andExpect(jsonPath("$.status").value("ANSWER_AVAILABLE"));
    }

    @Test
    void listQueryVersions_returns_wrapped_payload() throws Exception {
        final QueryService service = Mockito.mock(QueryService.class);
        final QueriesController controller = new QueriesController(service);
        final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        final UUID queryId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        final QueryVersionSummary v1 = new QueryVersionSummary();
        v1.setQueryId(queryId);
        v1.setLabel("L");
        v1.setUserQuery("UQ1");
        v1.setQueryPrompt("QP1");
        v1.setEffectiveAt(OffsetDateTime.parse("2025-05-01T11:58:00Z"));

        when(service.listVersions(queryId)).thenReturn(List.of(v1));

        mvc.perform(get("/queries/{queryId}/versions", queryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queryId").value(queryId.toString()))
                .andExpect(jsonPath("$.versions[0].userQuery").value("UQ1"));
    }
}


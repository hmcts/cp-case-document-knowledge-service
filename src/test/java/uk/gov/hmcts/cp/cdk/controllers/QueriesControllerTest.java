package uk.gov.hmcts.cp.cdk.controllers;


import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import uk.gov.hmcts.cp.cdk.batch.clients.common.CQRSClientProperties;
import uk.gov.hmcts.cp.cdk.services.QueryService;
import uk.gov.hmcts.cp.openapi.model.cdk.QueryDefinitionsResponse;
import uk.gov.hmcts.cp.openapi.model.cdk.QueryLifecycleStatus;
import uk.gov.hmcts.cp.openapi.model.cdk.QueryStatusResponse;
import uk.gov.hmcts.cp.openapi.model.cdk.QuerySummary;
import uk.gov.hmcts.cp.openapi.model.cdk.QueryUpsertRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.QueryVersionSummary;
import uk.gov.hmcts.cp.openapi.model.cdk.Scope;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("Queries Controller tests")
class QueriesControllerTest {

    public final String VND_TYPE_JSON = "application/vnd.casedocumentknowledge-service.queries+json";

    private static final String HEADER_NAME = "CJSCPPUID";
    private static final String HEADER_VALUE = "u-123";

    @Test
    @DisplayName("List Queries returns case as of")
    void listQueries_returns_case_as_of() throws Exception {
        final QueryService service = Mockito.mock(QueryService.class);
        final CQRSClientProperties props = mock(CQRSClientProperties.class, Mockito.RETURNS_DEEP_STUBS);
        when(props.headers().cjsCppuid()).thenReturn(HEADER_NAME);
        final QueriesController controller = new QueriesController(service, props);
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
        scope.setIsIdpcAvailable(true);
        resp.setScope(scope);
        resp.setQueries(List.of(qs));

        when(service.listForCaseAsOf(Mockito.eq(caseId), Mockito.any(), Mockito.any())).thenReturn(resp);

        mvc.perform(get("/queries")
                        .header(HEADER_NAME, HEADER_VALUE)
                        .param("caseId", caseId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.caseId").value(caseId.toString()))
                .andExpect(jsonPath("$.scope.isIdpcAvailable").value(true))
                .andExpect(jsonPath("$.queries[0].label").value("Case Summary"))
                .andExpect(jsonPath("$.queries[0].status").value("ANSWER_NOT_AVAILABLE"));
    }

    @Test
    @DisplayName("Upsert Queries returns accepted definition snapshot")
    void upsertQueries_returns_accepted_definition_snapshot() throws Exception {
        final QueryService service = Mockito.mock(QueryService.class);
        final CQRSClientProperties props = mock(CQRSClientProperties.class, Mockito.RETURNS_DEEP_STUBS);
        when(props.headers().cjsCppuid()).thenReturn(HEADER_NAME);
        final QueriesController controller = new QueriesController(service, props);
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
    @DisplayName("Get Query returns single summary")
    void getQuery_returns_single_summary() throws Exception {
        final QueryService service = Mockito.mock(QueryService.class);
        final CQRSClientProperties props = mock(CQRSClientProperties.class, Mockito.RETURNS_DEEP_STUBS);
        when(props.headers().cjsCppuid()).thenReturn(HEADER_NAME);
        final QueriesController controller = new QueriesController(service, props);
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

        mvc.perform(get("/queries/{queryId}", queryId)
                        .header(HEADER_NAME, HEADER_VALUE)
                        .param("caseId", caseId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queryId").value(queryId.toString()))
                .andExpect(jsonPath("$.status").value("ANSWER_AVAILABLE"));
    }

    @Test
    @DisplayName("List Query Versions returns wrapped payload")
    void listQueryVersions_returns_wrapped_payload() throws Exception {
        final QueryService service = Mockito.mock(QueryService.class);
        final CQRSClientProperties props = mock(CQRSClientProperties.class, Mockito.RETURNS_DEEP_STUBS);
        when(props.headers().cjsCppuid()).thenReturn(HEADER_NAME);
        final QueriesController controller = new QueriesController(service, props);
        final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        final UUID queryId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        final QueryVersionSummary v1 = new QueryVersionSummary();
        v1.setQueryId(queryId);
        v1.setLabel("L");
        v1.setUserQuery("UQ1");
        v1.setQueryPrompt("QP1");
        v1.setEffectiveAt(OffsetDateTime.parse("2025-05-01T11:58:00Z"));

        when(service.listVersions(queryId)).thenReturn(List.of(v1));

        mvc.perform(get("/queries/{queryId}/versions", queryId).header(HEADER_NAME, HEADER_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queryId").value(queryId.toString()))
                .andExpect(jsonPath("$.versions[0].userQuery").value("UQ1"));
    }

    @Test
    @DisplayName("Get Query -> 404 when not found")
    void getQuery_notFound_404() throws Exception {
        final QueryService service = Mockito.mock(QueryService.class);
        final CQRSClientProperties props = mock(CQRSClientProperties.class, Mockito.RETURNS_DEEP_STUBS);
        when(props.headers().cjsCppuid()).thenReturn(HEADER_NAME);
        final QueriesController controller = new QueriesController(service, props);
        final MockMvc mvc = MockMvcBuilders
                .standaloneSetup(controller)
                .build();

        final UUID caseId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        final UUID queryId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        when(service.getOneForCaseAsOf(Mockito.eq(caseId), Mockito.eq(queryId), Mockito.any()))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "Query not found for case=" + caseId + ", queryId=" + queryId
                ));

        mvc.perform(get("/queries/{queryId}", queryId)
                        .header(HEADER_NAME, HEADER_VALUE)
                        .param("caseId", caseId.toString()))
                .andExpect(status().isNotFound());
    }


}


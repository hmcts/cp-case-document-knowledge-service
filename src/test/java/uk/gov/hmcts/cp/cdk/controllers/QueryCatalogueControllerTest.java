package uk.gov.hmcts.cp.cdk.controllers;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.gov.hmcts.cp.cdk.services.QueryCatalogueService;
import uk.gov.hmcts.cp.openapi.model.cdk.LabelUpdateRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.QueryCatalogueItem;


class QueryCatalogueControllerTest {

    public final String VND_TYPE_JSON = "application/vnd.casedocumentknowledge-service.query-catalogue+json";
    @Test
    void listQueryCatalogue_returns_items() throws Exception {
        final QueryCatalogueService service = Mockito.mock(QueryCatalogueService.class);
        final QueryCatalogueController controller = new QueryCatalogueController(service);
        final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        final QueryCatalogueItem i1 = new QueryCatalogueItem();
        i1.setQueryId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        i1.setLabel("Case Summary");
        final QueryCatalogueItem i2 = new QueryCatalogueItem();
        i2.setQueryId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        i2.setLabel("Defendant Position");

        when(service.list()).thenReturn(List.of(i1, i2));

        mvc.perform(get("/query-catalogue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].label").value("Case Summary"))
                .andExpect(jsonPath("$.items[1].label").value("Defendant Position"));
    }

    @Test
    void getQueryCatalogueItem_returns_single_item() throws Exception {
        final QueryCatalogueService service = Mockito.mock(QueryCatalogueService.class);
        final QueryCatalogueController controller = new QueryCatalogueController(service);
        final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        final UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        final QueryCatalogueItem item = new QueryCatalogueItem();
        item.setQueryId(id);
        item.setLabel("Case Summary");

        when(service.get(id)).thenReturn(item);

        mvc.perform(get("/query-catalogue/{queryId}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queryId").value(id.toString()))
                .andExpect(jsonPath("$.label").value("Case Summary"));
    }

    @Test
    void setQueryCatalogueLabel_updates_label() throws Exception {
        final QueryCatalogueService service = Mockito.mock(QueryCatalogueService.class);
        final QueryCatalogueController controller = new QueryCatalogueController(service);
        final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        final UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        final LabelUpdateRequest req = new LabelUpdateRequest();
        req.setLabel("New Label");

        final QueryCatalogueItem updated = new QueryCatalogueItem();
        updated.setQueryId(id);
        updated.setLabel("New Label");

        when(service.updateLabel(Mockito.eq(id), Mockito.any(LabelUpdateRequest.class))).thenReturn(updated);

        mvc.perform(
                        put("/query-catalogue/{queryId}/label", id)
                                .contentType(VND_TYPE_JSON)
                                .content("{\"label\":\"New Label\"}")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queryId").value(id.toString()))
                .andExpect(jsonPath("$.label").value("New Label"));
    }
}

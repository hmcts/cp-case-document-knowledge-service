package uk.gov.hmcts.cp.cdk.controllers;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.gov.hmcts.cp.cdk.services.AnswerService;
import uk.gov.hmcts.cp.openapi.model.cdk.AnswerResponse;
import uk.gov.hmcts.cp.openapi.model.cdk.AnswerWithLlmResponse;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AnswersControllerTest {

    public final String VND_TYPE_JSON = "application/vnd.casedocumentknowledge-service.answers+json";

    @Test
    void getAnswerByCaseAndQuery_latest_returns_answer() throws Exception {
        final AnswerService service = Mockito.mock(AnswerService.class);
        final AnswersController controller = new AnswersController(service);
        final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        final UUID caseId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        final UUID queryId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        final AnswerResponse resp = new AnswerResponse();
        resp.setQueryId(queryId);
        resp.setUserQuery("UQ");
        resp.setAnswer("A");
        resp.setVersion(2);
        resp.setCreatedAt(OffsetDateTime.parse("2025-05-01T12:00:00Z"));

        when(service.getAnswer(eq(queryId), eq(caseId), isNull(), isNull()))
                .thenReturn(resp);

        mvc.perform(get("/answers/{caseId}/{queryId}", caseId, queryId)
                        .accept(VND_TYPE_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(VND_TYPE_JSON))
                .andExpect(jsonPath("$.queryId").value(queryId.toString()))
                .andExpect(jsonPath("$.version").value(2));

        verify(service).getAnswer(eq(queryId), eq(caseId), isNull(), isNull());
    }

    @Test
    void getAnswerByCaseAndQuery_withVersion_and_asOf_returns_answer() throws Exception {
        final AnswerService service = Mockito.mock(AnswerService.class);
        final AnswersController controller = new AnswersController(service);
        final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        final UUID caseId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        final UUID queryId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        final String asOfStr = "2025-01-02T03:04:05Z";
        final OffsetDateTime asOf = OffsetDateTime.parse(asOfStr);

        final AnswerResponse resp = new AnswerResponse();
        resp.setQueryId(queryId);
        resp.setUserQuery("UQ");
        resp.setAnswer("A2");
        resp.setVersion(3);
        resp.setCreatedAt(asOf);

        when(service.getAnswer(eq(queryId), eq(caseId), eq(3), eq(asOf)))
                .thenReturn(resp);

        mvc.perform(get("/answers/{caseId}/{queryId}", caseId, queryId)
                        .param("version", "3")
                        .param("at", asOfStr)
                        .accept(VND_TYPE_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(VND_TYPE_JSON))
                .andExpect(jsonPath("$.queryId").value(queryId.toString()))
                .andExpect(jsonPath("$.version").value(3));

        verify(service).getAnswer(eq(queryId), eq(caseId), eq(3), eq(asOf));
    }

    @Test
    void getAnswerWithLlmByCaseAndQuery_latest_returns_answer_with_llm() throws Exception {
        final AnswerService service = Mockito.mock(AnswerService.class);
        final AnswersController controller = new AnswersController(service);
        final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        final UUID caseId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        final UUID queryId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        final AnswerWithLlmResponse resp = new AnswerWithLlmResponse();
        resp.setQueryId(queryId);
        resp.setUserQuery("UQ");
        resp.setAnswer("A");
        resp.setVersion(1);
        resp.setCreatedAt(OffsetDateTime.parse("2025-05-01T11:00:00Z"));
        resp.setLlmInput("LLM");

        when(service.getAnswerWithLlm(eq(queryId), eq(caseId), isNull(), isNull()))
                .thenReturn(resp);

        mvc.perform(get("/answers/{caseId}/{queryId}/with-llm", caseId, queryId)
                        .accept(VND_TYPE_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(VND_TYPE_JSON))
                .andExpect(jsonPath("$.queryId").value(queryId.toString()))
                .andExpect(jsonPath("$.llmInput").value("LLM"));

        verify(service).getAnswerWithLlm(eq(queryId), eq(caseId), isNull(), isNull());
    }

    @Test
    void getAnswerWithLlmByCaseAndQuery_withVersion_and_asOf_returns_answer_with_llm() throws Exception {
        final AnswerService service = Mockito.mock(AnswerService.class);
        final AnswersController controller = new AnswersController(service);
        final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        final UUID caseId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        final UUID queryId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        final String asOfStr = "2025-03-04T05:06:07Z";
        final OffsetDateTime asOf = OffsetDateTime.parse(asOfStr);

        final AnswerWithLlmResponse resp = new AnswerWithLlmResponse();
        resp.setQueryId(queryId);
        resp.setUserQuery("UQ");
        resp.setAnswer("A3");
        resp.setVersion(5);
        resp.setCreatedAt(asOf);
        resp.setLlmInput("LLM-INPUT");

        when(service.getAnswerWithLlm(eq(queryId), eq(caseId), eq(5), eq(asOf)))
                .thenReturn(resp);

        mvc.perform(get("/answers/{caseId}/{queryId}/with-llm", caseId, queryId)
                        .param("version", "5")
                        .param("at", asOfStr)
                        .accept(VND_TYPE_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(VND_TYPE_JSON))
                .andExpect(jsonPath("$.queryId").value(queryId.toString()))
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.llmInput").value("LLM-INPUT"));

        verify(service).getAnswerWithLlm(eq(queryId), eq(caseId), eq(5), eq(asOf));
    }
}

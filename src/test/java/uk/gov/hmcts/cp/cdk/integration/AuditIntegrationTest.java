package uk.gov.hmcts.cp.cdk.integration;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.hmcts.cp.cdk.domain.Answer;
import uk.gov.hmcts.cp.cdk.domain.AnswerId;
import uk.gov.hmcts.cp.cdk.repo.AnswerRepository;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Slf4j
class AuditIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    AnswerRepository answerRepository;

    @Test
    void root_incoming_request_should_send_audit() throws Exception {
        mockMvc.perform(get("/hello")
                        .header("test-header", "some-value")
                        .content("json body"))
                .andExpect(status().isOk());
    }

    @Test
    void answers_request_should_send_audit() throws Exception {
        Answer answer = insertAnswer();
        String answersUrl = String.format("/answers/%s/%s", answer.getAnswerId().getCaseId(), answer.getAnswerId().getQueryId());
        // curl http://localhost:8082/answers/181da2ad-44bc-43eb-b5de-5fd970b37a1b/559a79fa-56e9-4243-8c38-fcf96c07a6a4
        mockMvc.perform(get(answersUrl)
                        .header("test-header", "some-value")
                        .content("json body"))
                .andExpect(status().isOk());
    }

    private Answer insertAnswer() {
        AnswerId answerId = AnswerId.builder().caseId(UUID.randomUUID()).queryId(UUID.randomUUID()).build();
        return answerRepository.save(Answer.builder()
                .answerId(answerId)
                .createdAt(OffsetDateTime.now())
                .build());
    }

    @SneakyThrows
    private void sleep_to_let_artemis_queue_flush() {
        Thread.sleep(500);
    }
}

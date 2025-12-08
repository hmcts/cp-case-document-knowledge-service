package uk.gov.hmcts.cp.cdk.services.mapper;

import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;

import uk.gov.hmcts.cp.cdk.domain.Answer;
import uk.gov.hmcts.cp.cdk.domain.AnswerId;
import uk.gov.hmcts.cp.openapi.model.cdk.AnswerResponse;
import uk.gov.hmcts.cp.openapi.model.cdk.AnswerWithLlmResponse;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class AnswerMapperTest {

    private final AnswerMapper mapper = new AnswerMapper() {
    };

    @Test
    void testToAnswerResponse() {
        // Given
        final String userQuery = "User query1";
        final String testAnswer = "Test answer1";
        final String llmText = "llmText1";
        final UUID caseId = randomUUID();
        final UUID queryId = randomUUID();
        final AnswerId answerId = new AnswerId(caseId, queryId, 2);
        final Answer answer = new Answer(answerId, utcNow(), testAnswer, llmText, randomUUID());

        // When
        final AnswerResponse response = mapper.toAnswerResponse(answer, userQuery);

        // Then
        assertThat(response.getQueryId()).isEqualTo(queryId);
        assertThat(response.getUserQuery()).isEqualTo(userQuery);
        assertThat(response.getAnswer()).isEqualTo(testAnswer);
        assertThat(response.getVersion()).isEqualTo(answerId.getVersion());
        assertThat(response.getCreatedAt()).isEqualTo(answer.getCreatedAt());
    }

    @Test
    void testToAnswerWithLlm() {
        // Given
        final String userQuery = "Another User query";
        final String testAnswer = "Test answer2";
        final String llmText = "llmText2";
        final UUID caseId = randomUUID();
        final UUID queryId = randomUUID();
        final AnswerId answerId = new AnswerId(caseId, queryId, 2);
        final Answer answer = new Answer(answerId, utcNow(), testAnswer, llmText, randomUUID());

        // When
        final AnswerWithLlmResponse response = mapper.toAnswerWithLlm(answer, userQuery);

        // Then
        assertThat(response.getQueryId()).isEqualTo(queryId);
        assertThat(response.getUserQuery()).isEqualTo(userQuery);
        assertThat(response.getAnswer()).isEqualTo(testAnswer);
        assertThat(response.getVersion()).isEqualTo(answerId.getVersion());
        assertThat(response.getCreatedAt()).isEqualTo(answer.getCreatedAt());
        assertThat(response.getLlmInput()).isEqualTo(llmText);
    }
}
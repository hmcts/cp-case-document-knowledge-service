package uk.gov.hmcts.cp.cdk.services.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import uk.gov.hmcts.cp.cdk.domain.Answer;
import uk.gov.hmcts.cp.cdk.domain.AnswerId;
import uk.gov.hmcts.cp.openapi.model.cdk.AnswerResponse;
import uk.gov.hmcts.cp.openapi.model.cdk.AnswerWithLlmResponse;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface AnswerMapper {

    default AnswerResponse toAnswerResponse(final Answer answerEntity, final String userQueryText) {
        final AnswerId answerId = answerEntity.getAnswerId();
        final AnswerResponse response = new AnswerResponse();
        response.setQueryId(answerId.getQueryId());
        response.setUserQuery(userQueryText);
        response.setAnswer(answerEntity.getAnswerText());
        response.setVersion(answerId.getVersion());
        response.setCreatedAt(answerEntity.getCreatedAt());
        return response;
    }

    default AnswerWithLlmResponse toAnswerWithLlm(final Answer answerEntity, final String userQueryText) {
        final AnswerId answerId = answerEntity.getAnswerId();
        final AnswerWithLlmResponse response = new AnswerWithLlmResponse();
        response.setQueryId(answerId.getQueryId());
        response.setUserQuery(userQueryText);
        response.setAnswer(answerEntity.getAnswerText());
        response.setVersion(answerId.getVersion());
        response.setCreatedAt(answerEntity.getCreatedAt());
        response.setLlmInput(answerEntity.getLlmInput());
        return response;
    }
}

package uk.gov.hmcts.cp.cdk.clients.rag;

import uk.gov.hmcts.cp.openapi.model.MetadataFilter;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerReturnedSuccessfully;

import java.util.List;

public interface RagAnswerService {
    UserQueryAnswerReturnedSuccessfully answerUserQuery(String userQuery, String queryPrompt, List<MetadataFilter> metadataFilters);
}

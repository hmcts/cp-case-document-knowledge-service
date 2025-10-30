package uk.gov.hmcts.cp.cdk.batch;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.cdk.domain.Query;
import uk.gov.hmcts.cp.cdk.repo.QueryRepository;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class QueryResolver {

    private final QueryRepository queryRepository;

    public List<Query> resolve() {

        return queryRepository.findAll();
    }
}

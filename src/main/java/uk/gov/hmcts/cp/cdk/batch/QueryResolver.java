package uk.gov.hmcts.cp.cdk.batch;

import uk.gov.hmcts.cp.cdk.domain.Query;
import uk.gov.hmcts.cp.cdk.repo.QueryRepository;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class QueryResolver {

    private final QueryRepository queryRepository;

    public List<Query> resolve() {

        return queryRepository.findAll();
    }
}

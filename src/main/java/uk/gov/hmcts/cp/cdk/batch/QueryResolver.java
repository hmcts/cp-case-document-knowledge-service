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
    private final QuestionsProperties questionsProperties;
    private final QueryRepository queryRepository;

    public List<Query> resolve() {
        final List<String> labels = questionsProperties.labels();
        if (labels != null && !labels.isEmpty()) {
            final List<Query> out = new ArrayList<>();
            for (final String l : labels) {
                queryRepository.findByLabelIgnoreCase(l).ifPresent(out::add);
            }
            return out;
        }
        return queryRepository.findAll();
    }
}

package uk.gov.hmcts.cp.cdk.batch;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "cdk.questions")
public record QuestionsProperties(List<String> labels) {
}

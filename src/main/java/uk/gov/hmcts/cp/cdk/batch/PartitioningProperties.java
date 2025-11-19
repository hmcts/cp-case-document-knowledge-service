package uk.gov.hmcts.cp.cdk.batch;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cdk.ingestion.batch.partition")
public record PartitioningProperties(
        int caseGridSize,
        int queryGridSize,
        int filterGridSize
) {}

package uk.gov.hmcts.cp.cdk.clients.config;


import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import uk.gov.hmcts.cp.cdk.clients.common.CdkClientProperties;
import uk.gov.hmcts.cp.cdk.clients.hearing.HearingClient;
import uk.gov.hmcts.cp.cdk.clients.hearing.HearingClientConfig;
import uk.gov.hmcts.cp.cdk.clients.hearing.HearingClientImpl;
import uk.gov.hmcts.cp.cdk.clients.hearing.mapper.HearingDtoMapper;
import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClientConfig;
import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClientImpl;
import uk.gov.hmcts.cp.cdk.clients.progression.mapper.ProgressionDtoMapper;

import java.util.Objects;


@Configuration
@EnableConfigurationProperties({
        CdkClientProperties.class,
        HearingClientConfig.class,
        ProgressionClientConfig.class
})
public class CdkClientsConfig {


    @Bean
    public RestClient cdkRestClient(final CdkClientProperties props) {
        return RestClient.builder()
                .baseUrl(Objects.requireNonNull(props.baseUrl(), "cdk.client.base-url must be set"))
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
    }


    @Bean
    public HearingClient hearingClient(final RestClient restClient,
                                       final CdkClientProperties rootProps,
                                       final HearingClientConfig hearingProps,
                                       final HearingDtoMapper mapper) {
        return new HearingClientImpl(restClient, rootProps, hearingProps, mapper);
    }


    @Bean
    public ProgressionClient progressionClient(final RestClient restClient,
                                               final CdkClientProperties rootProps,
                                               final ProgressionClientConfig props,
                                               final ProgressionDtoMapper mapper) {
        return new ProgressionClientImpl(restClient, rootProps, props, mapper);
    }
}
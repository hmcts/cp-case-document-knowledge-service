package uk.gov.hmcts.cp.cdk.clients.config;


import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import uk.gov.hmcts.cp.cdk.clients.common.CQRSClientProperties;
import uk.gov.hmcts.cp.cdk.clients.hearing.HearingClient;
import uk.gov.hmcts.cp.cdk.clients.hearing.HearingClientConfig;
import uk.gov.hmcts.cp.cdk.clients.hearing.HearingClientImpl;
import uk.gov.hmcts.cp.cdk.clients.hearing.mapper.HearingDtoMapper;
import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClientConfig;
import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClientImpl;
import uk.gov.hmcts.cp.cdk.clients.progression.mapper.ProgressionDtoMapper;


@Configuration
@EnableConfigurationProperties({
        CQRSClientProperties.class,
        HearingClientConfig.class,
        ProgressionClientConfig.class
})
public class CdkClientsConfig {

    @Bean
    public HearingClient hearingClient(@Qualifier("cqrsRestClient") final RestClient restClient,
                                       final CQRSClientProperties rootProps,
                                       final HearingClientConfig hearingProps,
                                       final HearingDtoMapper mapper) {
        return new HearingClientImpl(restClient, rootProps, hearingProps, mapper);
    }


    @Bean
    public ProgressionClient progressionClient(@Qualifier("cqrsRestClient") final RestClient restClient,
                                               final CQRSClientProperties rootProps,
                                               final ProgressionClientConfig props,
                                               final ProgressionDtoMapper mapper) {
        return new ProgressionClientImpl(restClient, rootProps, props, mapper);
    }
}
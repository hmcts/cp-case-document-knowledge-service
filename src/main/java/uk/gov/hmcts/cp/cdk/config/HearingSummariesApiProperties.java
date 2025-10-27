package uk.gov.hmcts.cp.cdk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hearingsummaries.api")
public class HearingSummariesApiProperties {

    private String hearingSummariesUrlTemplate;
    private String actionHeader;
    private String userIdHeader;


    public String getHearingSummariesUrlTemplate() {
        return hearingSummariesUrlTemplate;
    }

    public void setHearingSummariesUrlTemplate(String hearingSummariesUrlTemplate) {
        this.hearingSummariesUrlTemplate = hearingSummariesUrlTemplate;
    }

    public String getActionHeader() {
        return actionHeader;
    }

    public void setActionHeader(String actionHeader) {
        this.actionHeader = actionHeader;
    }

    public String getUserIdHeader() {
        return userIdHeader;
    }

    public void setUserIdHeader(String userIdHeader) {
        this.userIdHeader = userIdHeader;
    }
}

package uk.gov.hmcts.cp.cdk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {
        "uk.gov.hmcts.cp.cdk",
        "uk.gov.hmcts.cp.taskmanager"
})
@EntityScan(basePackages = {
        "uk.gov.hmcts.cp.cdk.domain",
        "uk.gov.hmcts.cp.taskmanager.persistence.entity"
})
@SuppressWarnings("HideUtilityClassConstructor")
public class Application {

    public static void main(final String[] args) {
        SpringApplication.run(Application.class, args);
    }
}

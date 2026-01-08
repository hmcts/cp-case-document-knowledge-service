package uk.gov.hmcts.cp.cdk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {
        "uk.gov.hmcts.cp.cdk",            // your main service package
        "uk.gov.hmcts.cp.taskmanager"              // task manager core packages

})
@EntityScan(basePackages = {
        "uk.gov.hmcts.cp.cdk.domain",
        "uk.gov.hmcts.cp.taskmanager.persistence.entity" // Job entity for TaskManager
})
@SuppressWarnings("HideUtilityClassConstructor")
public class Application {

    public static void main(final String[] args) {
        SpringApplication.run(Application.class, args);
    }
}

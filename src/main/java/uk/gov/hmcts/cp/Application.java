package uk.gov.hmcts.cp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@SuppressWarnings("HideUtilityClassConstructor")
public class Application {

    public static void main(final String[] args) {
        SpringApplication.run(Application.class, args);
    }
}

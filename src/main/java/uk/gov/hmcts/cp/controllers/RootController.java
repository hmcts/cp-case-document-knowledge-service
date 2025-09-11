package uk.gov.hmcts.cp.controllers;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.http.ResponseEntity.ok;

import uk.gov.hmcts.cp.filters.jwt.AuthDetails;

/**
 * Default endpoints per application.
 */
@RestController
@AllArgsConstructor
@Slf4j
public class RootController {

    // request scope bean
    AuthDetails jwtToken;

    /**
     * Root GET endpoint.
     *
     * <p>Azure application service has a hidden feature of making requests to root endpoint when
     * "Always On" is turned on. This is the endpoint to deal with that and therefore silence the
     * unnecessary 404s as a response code.
     *
     * @return Welcome message from the service.
     */
    @GetMapping("/")
    public ResponseEntity<String> welcome() {
        log.info("START");
        return ok("Welcome to service-hmcts-springboot-template, " + jwtToken.getUserName());
    }
}

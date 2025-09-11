package uk.gov.hmcts.cp.controllers;

import static org.springframework.http.ResponseEntity.ok;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController()
@RequestMapping(path = "api", produces = MediaType.TEXT_PLAIN_VALUE)
public class ApiController {

    @GetMapping("/public")
    public ResponseEntity<String> publicEndpoint() {
        return ok("Welcome to service-hmcts-marketplace-springboot-template");
    }

}

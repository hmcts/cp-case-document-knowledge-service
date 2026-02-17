package uk.gov.hmcts.cp.cdk.controllers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class InsecureController {

    @GetMapping("/bad")
    public ResponseEntity<String> getAnswerByCaseAndQuery(@RequestParam(name = "badParam") final String badParam) {
        log.info("Passed badParam:{} that should trigger a build failure", badParam);
        return ResponseEntity.ok("Hello");
    }
}

package uk.gov.hmcts.cp.cdk.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stable GET endpoint just for this test. It logs with the SAME logger name
 * as your RootController so your existing assertions keep working.
 */
@RestController
public class TracingProbeController {
    private static final Logger ROOT_LOGGER =
            LoggerFactory.getLogger("uk.gov.hmcts.cp.controllers.RootController");

    @GetMapping("/_trace-probe")
    public String probe() {
        ROOT_LOGGER.info("START");
        return "ok";
    }
}

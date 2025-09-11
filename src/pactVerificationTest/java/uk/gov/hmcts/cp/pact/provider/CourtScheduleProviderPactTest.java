package uk.gov.hmcts.cp.pact.provider;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactBroker;
import au.com.dius.pact.provider.junitsupport.loader.PactBrokerAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import uk.gov.hmcts.cp.openapi.model.CourtScheduleResponse;
import uk.gov.hmcts.cp.pact.helper.JsonFileToObject;
import uk.gov.hmcts.cp.repositories.CourtScheduleRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith({SpringExtension.class, PactVerificationInvocationContextProvider.class})
@Provider("CPCourtScheduleProvider")
@PactBroker(
        url = "${pact.broker.url}",
        authentication = @PactBrokerAuth(token = "${pact.broker.token}")
)
@Tag("pact")
public class CourtScheduleProviderPactTest {

    private static final Logger LOG = LoggerFactory.getLogger(CourtScheduleProviderPactTest.class);

    @Autowired
    private CourtScheduleRepository courtScheduleRepository;

    @LocalServerPort
    private int port;

    @BeforeEach
    void setupTarget(PactVerificationContext context) {
        LOG.atDebug().log("Running test on port: " + port);
        context.setTarget(new HttpTestTarget("localhost", port));
        LOG.atDebug().log("pact.verifier.publishResults: " + System.getProperty("pact.verifier.publishResults"));
    }

    @State("court schedule for case 456789 exists")
    public void setupCourtSchedule() throws Exception{
        courtScheduleRepository.clearAll();
        CourtScheduleResponse courtScheduleResponse = JsonFileToObject.readJsonFromResources("courtSchedule.json", CourtScheduleResponse.class);
        courtScheduleRepository.saveCourtSchedule("456789", courtScheduleResponse);
    }

    @TestTemplate
    void pactVerificationTestTemplate(PactVerificationContext context) {
        context.verifyInteraction();
    }
}
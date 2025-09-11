package uk.gov.hmcts.cp.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@SpringBootTest
@AutoConfigureMockMvc
class CourtScheduleControllerIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(CourtScheduleControllerIntegrationTest.class);

    @Resource
    private MockMvc mockMvc;

    @Test
    void shouldReturnOkWhenValidUrnIsProvided() throws Exception {
        String caseUrn = "test-case-urn";
        mockMvc.perform(get("/case/{case_urn}/courtschedule", caseUrn)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    // You may need to adjust this depending on the actual fields in CourtScheduleResponse
                    String responseBody = result.getResponse().getContentAsString();
                    log.info("Response: {}", responseBody);
                    JsonNode jsonBody = new ObjectMapper().readTree(result.getResponse().getContentAsString());


                    assertEquals("courtSchedule", jsonBody.fieldNames().next());
                    JsonNode courtSchedule = jsonBody.get("courtSchedule");
                    assertTrue(courtSchedule.isArray());
                    assertEquals(1, courtSchedule.size());

                    JsonNode hearings = courtSchedule.get(0).get("hearings");
                    assertTrue(hearings.isArray());
                    assertEquals(1, hearings.size());

                    JsonNode hearing = hearings.get(0);
                    UUID hearingId = UUID.fromString(hearing.get("hearingId").asText());
                    assertNotNull(hearingId);
                    assertEquals("Requires interpreter", hearing.get("listNote").asText());
                    assertEquals("Sentencing for theft case", hearing.get("hearingDescription").asText());
                    assertEquals("Trial", hearing.get("hearingType").asText());

                    JsonNode courtSittings = hearing.get("courtSittings");
                    assertTrue(courtSittings.isArray());
                    assertEquals(1, courtSittings.size());

                    JsonNode sitting = courtSittings.get(0);
                    assertEquals("Central Criminal Court", sitting.get("courtHouse").asText());
                    assertNotNull(sitting.get("sittingStart").asText());
                    assertNotNull(sitting.get("sittingEnd").asText());
                    UUID judiciaryId = UUID.fromString(sitting.get("judiciaryId").asText());
                    assertNotNull(judiciaryId);
                    log.info("Response Object: {}", jsonBody);
                });
    }
}
package uk.gov.hmcts.cp.cdk.controllers;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import uk.gov.hmcts.cp.cdk.services.DiscoverySchedulerConfigurationService;
import uk.gov.hmcts.cp.cdk.services.DiscoveryTriggerService;
import uk.gov.hmcts.cp.openapi.model.cdk.DiscoveryOperation;
import uk.gov.hmcts.cp.openapi.model.cdk.DiscoverySchedulerConfigurationRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.UpsertDiscoverySchedulerConfiguration200Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

@DisplayName("Discovery Scheduler Controller tests")
class DiscoverySchedulerControllerTest {

    private static final MediaType VND =
            MediaType.valueOf("application/vnd.casedocumentknowledge-service.discovery-scheduler-configuration+json");
    private static final MediaType VND_TRIGGER =
            MediaType.valueOf("application/vnd.casedocumentknowledge-service.discovery-scheduler-trigger+json");

    private MockMvc mvc(final DiscoverySchedulerConfigurationService service) {
        return mvc(service, mock(DiscoveryTriggerService.class));
    }

    private MockMvc mvc(final DiscoverySchedulerConfigurationService service, final DiscoveryTriggerService triggerService) {
        return MockMvcBuilders.standaloneSetup(new DiscoverySchedulerController(service, triggerService)).build();
    }

    @Test
    @DisplayName("POST /discovery-scheduler/configurations returns 200 with body")
    void upsert_returns_200_with_body() throws Exception {
        final DiscoverySchedulerConfigurationService service = mock(DiscoverySchedulerConfigurationService.class);
        final MockMvc mvc = mvc(service);

        final UpsertDiscoverySchedulerConfiguration200Response response =
                new UpsertDiscoverySchedulerConfiguration200Response().message("Discovery scheduler configuration saved");
        when(service.upsert(ArgumentMatchers.any(DiscoverySchedulerConfigurationRequest.class))).thenReturn(response);

        final String body = """
                {
                  "courtCentreId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                  "courtRoomId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                  "uploadedDate": "2026-06-16",
                  "version": 1,
                  "isActive": true
                }
                """;

        mvc.perform(post("/discovery-scheduler/configurations")
                        .contentType(VND).accept(VND)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Discovery scheduler configuration saved"));

        verify(service).upsert(ArgumentMatchers.any(DiscoverySchedulerConfigurationRequest.class));
    }

    @Test
    @DisplayName("POST /discovery-scheduler/configurations returns 409 on duplicate version")
    void upsert_returns_409_on_conflict() throws Exception {
        final DiscoverySchedulerConfigurationService service = mock(DiscoverySchedulerConfigurationService.class);
        final MockMvc mvc = mvc(service);

        when(service.upsert(ArgumentMatchers.any(DiscoverySchedulerConfigurationRequest.class)))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                        "Discovery scheduler configuration already exists for this court centre, court room and version"));

        final String body = """
                {
                  "courtCentreId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                  "courtRoomId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                  "uploadedDate": "2026-06-16",
                  "version": 1,
                  "isActive": true
                }
                """;

        mvc.perform(post("/discovery-scheduler/configurations")
                        .contentType(VND).accept(VND)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST /discovery-scheduler/configurations returns 400 for missing required field")
    void upsert_returns_400_for_missing_field() throws Exception {
        final DiscoverySchedulerConfigurationService service = mock(DiscoverySchedulerConfigurationService.class);
        final MockMvc mvc = mvc(service);

        final String body = """
                {
                  "courtRoomId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                  "uploadedDate": "2026-06-16",
                  "version": 1,
                  "isActive": true
                }
                """;

        mvc.perform(post("/discovery-scheduler/configurations")
                        .contentType(VND).accept(VND)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /discovery-scheduler/configurations returns 400 for malformed UUID")
    void upsert_returns_400_for_malformed_uuid() throws Exception {
        final DiscoverySchedulerConfigurationService service = mock(DiscoverySchedulerConfigurationService.class);
        final MockMvc mvc = mvc(service);

        final String body = """
                {
                  "courtCentreId": "not-a-uuid",
                  "courtRoomId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                  "uploadedDate": "2026-06-16",
                  "version": 1,
                  "isActive": true
                }
                """;

        mvc.perform(post("/discovery-scheduler/configurations")
                        .contentType(VND).accept(VND)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /discovery-scheduler/trigger returns 202 with vendor content type and delegates once")
    void triggerDiscovery_returns_202_and_delegates_once() throws Exception {
        final DiscoveryTriggerService triggerService = mock(DiscoveryTriggerService.class);
        final MockMvc mvc = mvc(mock(DiscoverySchedulerConfigurationService.class), triggerService);

        final String body = """
                {
                  "discoveryOperation": "INTRADAY"
                }
                """;

        mvc.perform(post("/discovery-scheduler/trigger")
                        .contentType(VND_TRIGGER).accept(VND_TRIGGER)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.discoveryOperation").value("INTRADAY"))
                .andExpect(jsonPath("$.message").value("Discovery run dispatched."));

        verify(triggerService).trigger(eq(DiscoveryOperation.INTRADAY));
    }
}

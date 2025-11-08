package uk.gov.hmcts.cp.cdk.controllers;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import uk.gov.hmcts.cp.cdk.batch.clients.common.CQRSClientProperties;
import uk.gov.hmcts.cp.cdk.services.DocumentService;

import java.net.URI;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

public class DocumentControllerTest {

    private static final String HEADER_NAME = "CJSCPPUID";
    private static final String HEADER_VALUE = "u-123";

    @Test
    @DisplayName("Get Material Content URL returns 200 with valid response")
    void getMaterialContentUrl_returns_200_with_url() throws Exception {

        final DocumentService service = Mockito.mock(DocumentService.class);
        final CQRSClientProperties props = mock(CQRSClientProperties.class, Mockito.RETURNS_DEEP_STUBS);
        when(props.headers().cjsCppuid()).thenReturn(HEADER_NAME);

        final DocumentController controller = new DocumentController(service, props);
        final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        final UUID docId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        final URI expectedUri = URI.create("https://example.com/materials/" + docId);


        when(service.getMaterialContentUrl(docId, HEADER_VALUE)).thenReturn(expectedUri);


        mvc.perform(get("/document/{documentId}/content", docId)
                        .header(HEADER_NAME, HEADER_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(expectedUri.toString()));
    }


}

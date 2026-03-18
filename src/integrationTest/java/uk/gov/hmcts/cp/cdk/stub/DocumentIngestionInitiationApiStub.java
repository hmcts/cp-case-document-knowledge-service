package uk.gov.hmcts.cp.cdk.stub;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static jakarta.json.Json.createObjectBuilder;
import static java.util.UUID.randomUUID;
import static org.apache.http.HttpStatus.SC_ACCEPTED;
import static uk.gov.hmcts.cp.cdk.http.AzureSasUtil.generateSasUrl;

import jakarta.json.JsonObject;

public class DocumentIngestionInitiationApiStub {

    private static final String INITIATE_DOCUMENT_UPLOAD = "/document-upload";
    public static final String APPLICATION_JSON = "application/json";

    public static void stubInitiateDocumentUpload(final String containerName, final String blobName) {

        final String sasStorageUrl = generateSasUrl(containerName, blobName);
        final JsonObject responseJson = createObjectBuilder()
                .add("storageUrl", sasStorageUrl)
                .add("documentReference", randomUUID().toString())
                .build();

        stubFor(post(urlPathEqualTo(INITIATE_DOCUMENT_UPLOAD))
                .willReturn(aResponse().withStatus(SC_ACCEPTED)
                        .withHeader("CPPID", randomUUID().toString())
                        .withHeader("Content-Type", APPLICATION_JSON)
                        .withBody(responseJson.toString())
                ));
    }

}

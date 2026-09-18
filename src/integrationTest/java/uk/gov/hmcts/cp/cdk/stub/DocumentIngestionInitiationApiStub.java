package uk.gov.hmcts.cp.cdk.stub;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.apache.http.HttpStatus.SC_ACCEPTED;
import static uk.gov.hmcts.cp.cdk.http.AzureSasUtil.generateContainerSasUrl;

public class DocumentIngestionInitiationApiStub {

    private static final String INITIATE_DOCUMENT_UPLOAD = "/document-upload";
    public static final String APPLICATION_JSON = "application/json";

    public static void stubInitiateDocumentUpload(final String containerName, final String blobNamePrefix) {

        final String containerSasUrl = generateContainerSasUrl(containerName);
        final int queryIndex = containerSasUrl.indexOf('?');
        final String containerBaseUrl = containerSasUrl.substring(0, queryIndex);
        final String sasQuery = containerSasUrl.substring(queryIndex + 1);

        // {{request.id}} is a fresh UUID WireMock assigns per served request, so every call gets a
        // distinct blob name against one shared container-scoped SAS -- no scenario/counter state to
        // race on across concurrent JobManager tasks or overlapping test methods.
        final String body = """
                {
                  "storageUrl": "%s/%s-{{request.id}}?%s",
                  "documentReference": "{{request.id}}"
                }
                """.formatted(containerBaseUrl, blobNamePrefix, sasQuery);

        stubFor(post(urlPathEqualTo(INITIATE_DOCUMENT_UPLOAD))
                .willReturn(aResponse()
                        .withStatus(SC_ACCEPTED)
                        .withHeader("CPPID", "{{request.id}}")
                        .withHeader("Content-Type", APPLICATION_JSON)
                        .withTransformers("response-template")
                        .withBody(body)
                ));
    }

}

package uk.gov.hmcts.cp.cdk.http;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.slf4j.LoggerFactory.getLogger;

import java.time.OffsetDateTime;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobClientBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import org.slf4j.Logger;

public final class AzureSasUtil {

    private static final Logger LOGGER = getLogger(AzureSasUtil.class);

    private AzureSasUtil() {
    }

    public static void main(String[] args) {

        /**LOGGER.info(sasUrlFromEnv("idpc-ai",
                "hello.pdf",   // blob
                120));**/

        LOGGER.info(" url : "+generateSasUrl("documents-new", "destination.pdf"));

    }

    public static String sasUrlFromEnv(final String container, final String blobName, final int minutes) {
        final String conn = "";

        if (isBlank(conn)) {
            throw new IllegalStateException("Missing env: CP_CDK_AZURE_STORAGE_CONNECTION_STRING");
        }
        return sasUrl(conn, container, blobName, minutes);
    }

    private static String sasUrl(final String connectionString, final String container, final String blobName, final int minutes) {
        final BlobServiceClient svc = new BlobServiceClientBuilder()
                .connectionString(connectionString)
                .buildClient();
        final BlobContainerClient cont = svc.getBlobContainerClient(container);
        final BlobClient blob = cont.getBlobClient(blobName);

        final BlobSasPermission perms = new BlobSasPermission().setReadPermission(true);
        final BlobServiceSasSignatureValues values =
                new BlobServiceSasSignatureValues(OffsetDateTime.now().plusMinutes(minutes), perms);
        return blob.getBlobUrl() + "?" + blob.generateSas(values);
    }

    public static String generateSasUrl(final String containerName, final String blobName) {
         final String connectionString = System.getenv("AZURE_STORAGE_CONNECTION_STRING");


        final BlobClient blobClient = new BlobClientBuilder()
                .connectionString(connectionString)
                .containerName(containerName)
                .blobName(blobName)
                .buildClient();

        final BlobSasPermission permissions = new BlobSasPermission().setReadPermission(true).setWritePermission(true);

        final BlobServiceSasSignatureValues sasValues = new BlobServiceSasSignatureValues(OffsetDateTime.now().plusMinutes(10), permissions);

        final String sasToken = blobClient.generateSas(sasValues);
        return blobClient.getBlobUrl() + "?" + sasToken;
    }

}

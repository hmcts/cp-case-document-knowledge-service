package uk.gov.hmcts.cp.cdk.http;

import java.time.OffsetDateTime;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;

public final class AzureSasUtil {
    public static void main(String[] args) {
        System.out.println(sasUrlFromEnv("idpc-ai",     // container
                "hello.pdf",   // blob
                120));
    }

    private AzureSasUtil() {
    }

    public static String sasUrlFromEnv(final String container, final String blobName, final int minutes) {
        String conn = "";

        if (conn == null || conn.isBlank()) {
            throw new IllegalStateException("Missing env: CP_CDK_AZURE_STORAGE_CONNECTION_STRING");
        }
        return sasUrl(conn, container, blobName, minutes);
    }

    public static String sasUrl(final String connectionString, final String container, final String blobName, final int minutes) {
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

}

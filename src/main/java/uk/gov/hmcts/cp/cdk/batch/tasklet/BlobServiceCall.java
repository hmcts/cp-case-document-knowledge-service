package uk.gov.hmcts.cp.cdk.batch.tasklet;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.fasterxml.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.cdk.batch.storage.AzureBlobStorageService;
import uk.gov.hmcts.cp.cdk.batch.storage.StorageProperties;
import uk.gov.hmcts.cp.cdk.batch.storage.StorageService;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static java.time.format.DateTimeFormatter.ofPattern;
import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;

@Deprecated
public class BlobServiceCall {
    ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) {


        final long pollIntervalMs = 1_000L;
        final long timeoutSeconds = 120L;
        final String connectionString = "";

        final StorageProperties storageProperties = new StorageProperties(connectionString, "documents", pollIntervalMs, timeoutSeconds);
        final StorageService storageService = new AzureBlobStorageService(storageProperties);

        final String sasurl = generateSas();

        // call azure service and copy the file from one destination to another
        final UUID materialID = UUID.randomUUID();
        final UUID documentId = UUID.randomUUID();
        final String uploadDate = utcNow().format(ofPattern("yyyyMMdd"));
        final String blobName = String.format("%s_%s.pdf", materialID, uploadDate);
        final String destBlobPath = String.format("cases/%s/%s", uploadDate, blobName);
        final String contentType = "application/pdf";

        final BlobServiceCall blobServiceCall = new BlobServiceCall();
        final Map<String, String> metadata = blobServiceCall.createBlobMetadata(documentId, materialID, destBlobPath, contentType);

        final String blobUrl = storageService.copyFromUrl(sasurl, destBlobPath, contentType, metadata);
        final long sizeBytes = storageService.getBlobSize(destBlobPath);
    }

    Map<String, String> createBlobMetadata(
            final UUID documentId,
            final UUID materialId,
            final String caseId,
            final String uploadedDate
    ) {
        try {
            final Map<String, Object> metadataJson = Map.of(
                    "case_id", caseId,
                    "material_id", materialId.toString(),
                    "uploaded_at", uploadedDate
            );


            return Map.of(
                    "document_id", documentId.toString(),
                    "metadata", objectMapper.writeValueAsString(metadataJson)
            );
        } catch (Exception e) {

        }
        return Map.of();
    }

    public static String generateSas() {


        // SAS // source
        String connectionString = "";
        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .connectionString(connectionString)
                .buildClient();

        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("idpc-ai");
        BlobClient blobClient = containerClient.getBlobClient("hello.pdf");

        // Generate SAS URL valid for 15 minutes
        BlobSasPermission permissions = new BlobSasPermission().setReadPermission(true);
        BlobServiceSasSignatureValues values = new BlobServiceSasSignatureValues(
                OffsetDateTime.now().plusMinutes(15),
                permissions
        );

        return blobClient.getBlobUrl() + "?" + blobClient.generateSas(values);
    }
}

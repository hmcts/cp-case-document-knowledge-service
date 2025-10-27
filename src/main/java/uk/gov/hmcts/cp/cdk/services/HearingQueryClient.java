package uk.gov.hmcts.cp.cdk.services;

import org.springframework.http.HttpHeaders;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import uk.gov.hmcts.cp.cdk.config.HearingSummariesApiProperties;
import uk.gov.hmcts.cp.cdk.domain.HearingSummaries;
import uk.gov.hmcts.cp.cdk.domain.ProsecutionCaseSummaries;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class HearingQueryClient {

    private final RestTemplate restTemplate;
    private final HearingSummariesApiProperties properties;

    public HearingQueryClient(RestTemplate restTemplate,
                              HearingSummariesApiProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /**
     * Fetch all hearingSummaries along with the defandants for a given date, courtCentreId and roomId
     */
    public List<ProsecutionCaseSummaries> findHearingCases(LocalDate date, UUID courtCentreId, UUID roomId) {

        // Build the URL from template in properties
        final String url = properties.getHearingSummariesUrlTemplate();

        // Prepare headers from properties
        HttpHeaders headers = new HttpHeaders();
        headers.add("Accept", properties.getActionHeader());
        headers.add(properties.getUserIdHeader(), "CJSCPPUID");
// How will I get userId

        // Build request
        RequestEntity<Void> request = RequestEntity.get(URI.create(url))
                .headers(headers)
                .build();

        // Call Micro service
        ResponseEntity<HearingSummaries> response =
                restTemplate.exchange(request, HearingSummaries.class);

        HearingSummaries body = response.getBody();

        if (body == null || body.getProsecutionCaseSummaries() == null || body.getProsecutionCaseSummaries().isEmpty()) {
            return List.of(); // empty list if no data
        }

        // Write the logic of
        // response.hearingSummaries[0].prosecutionCaseSummaries.defendants.size() == 1; // more than 1 ignore as we are only considering 1 to 1

        // Process each DocumentIndex and extract latest Material
        return body.getProsecutionCaseSummaries();
    }

/**
 * @Job(name = "IDPC publish run: #{runId}", retries = 3)
 * public void execute(UUID runId, java.time.LocalDate date, UUID courtCentreId, UUID roomId) {
 *     repo.updateRunStatus(runId, "RUNNING_STEP1");
 *     var list = hearings.findHearingCases(date, courtCentreId, roomId);
 *     list.forEach(hc -> repo.upsertCase(runId, hc.caseId(), hc.hearingId()));
 *
 *     repo.updateRunStatus(runId, "RUNNING_STEP2");
 *     for (UUID caseId : repo.casesNeedingEnrichment(runId)) {
 *         ProgressionDoc doc = progression.getCourtDocuments(caseId);
 *         UUID latestIdpc = doc.documents().stream()
 *                 .filter(d -> "IDPC".equalsIgnoreCase(d.documentType()))
 *                 .map(ProgressionDoc.Document::documentId)
 *                 .findFirst().orElse(null);
 *         repo.setEnrichment(runId, caseId, doc.singleDefendant(), latestIdpc);
 *     }
 *
 *     repo.updateRunStatus(runId, "RUNNING_STEP3");
 *     for (UUID caseId : repo.casesNeedingUpload(runId)) {
 *         UUID docId = repo.latestIdpc(runId, caseId);
 *         var target = material.getUploadTarget(caseId, docId);
 *         repo.setIngestionPhase(runId, caseId, DocumentIngestionPhase.UPLOADING);
 *         storage.copyToBlob(caseId, docId, target.get("uploadUrl"));
 *         repo.setIngestionPhase(runId, caseId, DocumentIngestionPhase.UPLOADED);
 *     }
 *
 *     BackgroundJob.enqueue(() -> checkUploadStatus(runId)); // kick off step 4 as separate job
 * }
 */
}

package uk.gov.hmcts.cp.cdk.services;

import static jakarta.json.Json.createObjectBuilder;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_COURTDOCUMENT_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DEFENDANT_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOCIDS_ARRAY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_LATEST_DEFENDANT;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_MATERIAL_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_MATERIAL_NAME;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.CPPUID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.REQUEST_ID;

import java.util.List;
import java.util.UUID;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import org.springframework.stereotype.Service;

/**
 * Builds the per-document {@code RETRIEVE_MATERIAL_AND_UPLOAD} job data for newly-identified IDPC
 * documents. Shared by {@code CheckIdpcAvailabilityAllDefendantsTask} (scheduled ingestion, which
 * already has job data from the JobManager execution context to merge onto) and
 * {@link IngestionProcessorByCaseService} (manual "Process IDPC" ingestion, which has none and
 * needs a base built from scratch). Each caller stays responsible for turning the returned job data
 * into whatever {@code ExecutionInfo}/dispatch shape its own flow needs.
 */
@Service
public class RetrieveMaterialAndUploadJobDataService {

    /**
     * Merges per-document fields onto the supplied base job data.
     *
     * @return one enriched {@link JsonObject} per document, in the same order as {@code newDocuments}.
     */
    public List<JsonObject> enrich(final JsonObject baseJobData, final List<NewIdpcDocument> newDocuments) {
        final JsonArray docIdsArray = docIdsArray(newDocuments);
        return newDocuments.stream()
                .map(doc -> createObjectBuilder(baseJobData)
                        .add(CTX_DOC_ID_KEY, doc.docId())
                        .add(CTX_MATERIAL_ID_KEY, doc.materialId())
                        .add(CTX_MATERIAL_NAME, doc.materialName())
                        .add(CTX_DEFENDANT_ID_KEY, doc.defendantId())
                        .add(CTX_COURTDOCUMENT_ID_KEY, doc.courtDocumentId())
                        .add(CTX_DOCIDS_ARRAY, docIdsArray)
                        .add(CTX_LATEST_DEFENDANT, doc.latestDefendant())
                        .build())
                .toList();
    }

    /**
     * Builds a base job data from scratch (no pre-existing {@code ExecutionInfo} job data available)
     * then merges per-document fields onto it, as per {@link #enrich(JsonObject, List)}.
     */
    public List<JsonObject> enrich(final String cppuid, final String requestId, final UUID caseId,
                                    final List<NewIdpcDocument> newDocuments) {
        final JsonObject baseJobData = createObjectBuilder()
                .add(CPPUID, cppuid)
                .add(REQUEST_ID, requestId)
                .add(CTX_CASE_ID_KEY, caseId.toString())
                .build();
        return enrich(baseJobData, newDocuments);
    }

    private JsonArray docIdsArray(final List<NewIdpcDocument> newDocuments) {
        final JsonArrayBuilder builder = Json.createArrayBuilder();
        newDocuments.forEach(doc -> builder.add(doc.docId()));
        return builder.build();
    }
}

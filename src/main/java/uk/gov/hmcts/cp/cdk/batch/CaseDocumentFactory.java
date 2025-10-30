package uk.gov.hmcts.cp.cdk.batch;

import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;

import java.time.OffsetDateTime;
import java.util.UUID;

import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;

final class CaseDocumentFactory {
    private CaseDocumentFactory() {
    }

    public static CaseDocument build(final UUID caseId,
                              final UUID docId,
                              final String blobUrl,
                              final String contentType,
                              final long size) {
        final CaseDocument caseDocument = new CaseDocument();
        caseDocument.setDocId(docId);
        caseDocument.setCaseId(caseId);
        caseDocument.setBlobUri(blobUrl);
        caseDocument.setContentType(contentType);
        caseDocument.setSizeBytes(size);
        final OffsetDateTime now = utcNow();
        caseDocument.setUploadedAt(now);
        caseDocument.setIngestionPhase(DocumentIngestionPhase.UPLOADED);
        caseDocument.setIngestionPhaseAt(now);
        return caseDocument;
    }
}

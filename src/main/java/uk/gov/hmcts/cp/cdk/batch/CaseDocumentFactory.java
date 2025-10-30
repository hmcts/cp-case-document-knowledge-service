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
        final CaseDocument document = new CaseDocument();
        document.setDocId(docId);
        document.setCaseId(caseId);
        document.setBlobUri(blobUrl);
        document.setContentType(contentType);
        document.setSizeBytes(size);
        final OffsetDateTime now = utcNow();
        document.setUploadedAt(now);
        document.setIngestionPhase(DocumentIngestionPhase.UPLOADED);
        document.setIngestionPhaseAt(now);
        return document;
    }
}

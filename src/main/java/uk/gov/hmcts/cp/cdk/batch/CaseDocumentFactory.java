package uk.gov.hmcts.cp.cdk.batch;

import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;

import java.time.OffsetDateTime;
import java.util.UUID;

import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;

final class CaseDocumentFactory {
    private CaseDocumentFactory() {
    }

    static CaseDocument build(final UUID caseId,
                              final UUID docId,
                              final String blobUrl,
                              final String contentType,
                              final long size) {
        final CaseDocument d = new CaseDocument();
        d.setDocId(docId);
        d.setCaseId(caseId);
        d.setBlobUri(blobUrl);
        d.setContentType(contentType);
        d.setSizeBytes(size);
        final OffsetDateTime now = utcNow();
        d.setUploadedAt(now);
        d.setIngestionPhase(DocumentIngestionPhase.UPLOADED);
        d.setIngestionPhaseAt(now);
        return d;
    }
}

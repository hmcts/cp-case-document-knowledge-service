package uk.gov.hmcts.cp.cdk.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;

@Entity
@Table(
        name = "case_documents",
        indexes = {
                @Index(name = "idx_cd_case_uploaded_desc", columnList = "case_id,uploaded_at DESC"),
                @Index(name = "idx_cd_case_phase", columnList = "case_id,ingestion_phase"),
                @Index(name = "idx_cd_phase", columnList = "ingestion_phase")
        }
)
public class CaseDocument {

    @Id
    @Column(name = "doc_id", nullable = false)
    private UUID docId;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "source", nullable = false)
    private String source = "IDPC";

    @Column(name = "doc_name", nullable = false)
    private String docName;

    @Column(name = "blob_uri", nullable = false)
    private String blobUri;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "sha256_hex")
    private String sha256Hex;

    @Column(name = "uploaded_at", nullable = false)
    private OffsetDateTime uploadedAt = utcNow();

    @Enumerated(EnumType.STRING)
    @Column(name = "ingestion_phase", nullable = false)
    private DocumentIngestionPhase ingestionPhase = DocumentIngestionPhase.UPLOADING;

    @Column(name = "ingestion_phase_at", nullable = false)
    private OffsetDateTime ingestionPhaseAt = utcNow();

    public UUID getDocId() {
        return docId;
    }

    public void setDocId(final UUID docId) {
        this.docId = docId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public void setCaseId(final UUID caseId) {
        this.caseId = caseId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(final String source) {
        this.source = source;
    }

    public String getBlobUri() {
        return blobUri;
    }

    public void setBlobUri(final String blobUri) {
        this.blobUri = blobUri;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(final String contentType) {
        this.contentType = contentType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(final Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getSha256Hex() {
        return sha256Hex;
    }

    public void setSha256Hex(final String sha256Hex) {
        this.sha256Hex = sha256Hex;
    }

    public OffsetDateTime getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(final OffsetDateTime uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    public DocumentIngestionPhase getIngestionPhase() {
        return ingestionPhase;
    }

    public void setIngestionPhase(final DocumentIngestionPhase ingestionPhase) {
        this.ingestionPhase = ingestionPhase;
    }

    public OffsetDateTime getIngestionPhaseAt() {
        return ingestionPhaseAt;
    }

    public void setIngestionPhaseAt(final OffsetDateTime ingestionPhaseAt) {
        this.ingestionPhaseAt = ingestionPhaseAt;
    }

    public String getDocName() {
        return docName;
    }

    public void setDocName(final String docName) {
        this.docName = docName;
    }

    @Override
    public boolean equals(final Object other) {
        boolean equal = false;
        if (this == other) {
            equal = true;
        } else if (other instanceof CaseDocument that) {
            equal = Objects.equals(docId, that.docId);
        }
        return equal;
    }

    @Override
    public int hashCode() {
        return Objects.hash(docId);
    }
}

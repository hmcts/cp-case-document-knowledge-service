package uk.gov.hmcts.cp.cdk.domain;

import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@EqualsAndHashCode
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

    @Column(name = "material_id", nullable = false)
    private UUID materialId;

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
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(
            name = "ingestion_phase",
            nullable = false,
            columnDefinition = "document_ingestion_phase_enum"
    )
    private DocumentIngestionPhase ingestionPhase = DocumentIngestionPhase.UPLOADING;

    @Column(name = "ingestion_phase_at", nullable = false)
    private OffsetDateTime ingestionPhaseAt = utcNow();
}

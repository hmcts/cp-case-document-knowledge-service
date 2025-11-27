package uk.gov.hmcts.cp.cdk.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "document_verification_task")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class DocumentVerificationTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "doc_id", nullable = false)
    private UUID docId;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "blob_name", nullable = false)
    private String blobName;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(
            name = "status",
            nullable = false,
            columnDefinition = "document_verification_status_enum"
    )
    private DocumentVerificationStatus status = DocumentVerificationStatus.PENDING;

    @Column(name = "last_status")
    private String lastStatus;

    @Column(name = "last_reason")
    private String lastReason;

    @Column(name = "last_status_ts")
    private OffsetDateTime lastStatusTimestamp;

    @Column(name = "next_attempt_at", nullable = false)
    private OffsetDateTime nextAttemptAt;

    @Column(name = "lock_owner")
    private String lockOwner;

    @Column(name = "lock_acquired_at")
    private OffsetDateTime lockAcquiredAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}

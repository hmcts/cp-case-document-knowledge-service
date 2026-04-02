package uk.gov.hmcts.cp.cdk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@MappedSuperclass
@Getter
@Setter
public abstract class DocumentAnswer extends BaseAnswer {

    @Column(name = "doc_id")
    protected UUID docId;
}
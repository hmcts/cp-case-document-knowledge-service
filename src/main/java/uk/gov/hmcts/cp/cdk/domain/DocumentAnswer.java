package uk.gov.hmcts.cp.cdk.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

@MappedSuperclass
@Getter
@Setter
public abstract class DocumentAnswer extends BaseAnswer {

    @Column(name = "doc_id")
    protected UUID docId;
}
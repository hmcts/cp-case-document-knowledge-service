package uk.gov.hmcts.cp.cdk.clients.progression.dto;

import java.util.List;

public record ProsecutionCase(
        String id,
        List<Defendant> defendants
) {
}

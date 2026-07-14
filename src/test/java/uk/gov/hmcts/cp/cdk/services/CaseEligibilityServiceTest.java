package uk.gov.hmcts.cp.cdk.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DEFENDANT_COUNT;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DEFENDANT_ID_KEY;

import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.ProsecutionCaseEligibilityInfo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("CaseEligibilityService tests")
class CaseEligibilityServiceTest {

    private static final String CPPUID_VALUE = "cppuid-123";

    @Mock
    private ProgressionClient progressionClient;

    private CaseEligibilityService service;
    private UUID caseId;

    @BeforeEach
    void setUp() {
        service = new CaseEligibilityService(progressionClient);
        caseId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Empty when caseId or cppuid is null")
    void empty_whenArgumentsNull() {
        assertThat(service.resolveEligibleCase(null, CPPUID_VALUE)).isEmpty();
        assertThat(service.resolveEligibleCase(caseId, null)).isEmpty();
        verifyNoInteractions(progressionClient);
    }

    @Test
    @DisplayName("Empty when no prosecution case data found")
    void empty_whenNoProsecutionCase() {
        when(progressionClient.getProsecutionCaseEligibilityInfo(caseId, CPPUID_VALUE))
                .thenReturn(Optional.empty());

        assertThat(service.resolveEligibleCase(caseId, CPPUID_VALUE)).isEmpty();
    }

    @Test
    @DisplayName("Empty when the case has zero defendants")
    void empty_whenNoDefendants() {
        when(progressionClient.getProsecutionCaseEligibilityInfo(caseId, CPPUID_VALUE))
                .thenReturn(Optional.of(new ProsecutionCaseEligibilityInfo(caseId.toString(), List.of())));

        assertThat(service.resolveEligibleCase(caseId, CPPUID_VALUE)).isEmpty();
    }

    @Test
    @DisplayName("Present when the case has at least one defendant")
    void present_whenEligible() {
        final ProsecutionCaseEligibilityInfo info =
                new ProsecutionCaseEligibilityInfo(caseId.toString(), List.of("def-1", "def-2"));
        when(progressionClient.getProsecutionCaseEligibilityInfo(caseId, CPPUID_VALUE))
                .thenReturn(Optional.of(info));

        assertThat(service.resolveEligibleCase(caseId, CPPUID_VALUE)).contains(info);
    }

    @Test
    @DisplayName("withDefendantContext adds first defendant id and defendant count")
    void withDefendantContext_addsContext() {
        final JsonObject base = Json.createObjectBuilder().add("existing", "value").build();
        final ProsecutionCaseEligibilityInfo info =
                new ProsecutionCaseEligibilityInfo(caseId.toString(), List.of("def-1", "def-2"));

        final JsonObject enriched = service.withDefendantContext(base, info);

        assertThat(enriched.getString("existing")).isEqualTo("value");
        assertThat(enriched.getString(CTX_DEFENDANT_ID_KEY)).isEqualTo("def-1");
        assertThat(enriched.getInt(CTX_DEFENDANT_COUNT)).isEqualTo(2);
    }
}

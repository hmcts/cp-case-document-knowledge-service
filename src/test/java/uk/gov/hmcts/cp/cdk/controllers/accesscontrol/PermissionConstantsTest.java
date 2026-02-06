package uk.gov.hmcts.cp.cdk.controllers.accesscontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.cdk.controllers.accesscontrol.PermissionConstants.accessToIntelligencePermissions;

import org.junit.jupiter.api.Test;

class PermissionConstantsTest {

    @Test
    void testAccessToIntelligencePermissionsJson() {
        assertThat(accessToIntelligencePermissions()).isEqualTo(new String[]{"{\"object\":\"AI search\",\"action\":\"View\"}"});
    }
}
package uk.gov.hmcts.cp.cdk.controllers.accesscontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class PermissionConstants {
    static final String INTELLIGENCE_ACCESS = "Intelligence";
    static final String OBJECT_STR = "object";
    static final String ACTION_STR = "action";
    static final String ACTION = "View";

    private PermissionConstants() {
    }

    public static String[] accessToIntelligencePermissions() {
        ObjectNode intelligencePermissionObj = new ObjectMapper().createObjectNode();
        intelligencePermissionObj.put(OBJECT_STR, INTELLIGENCE_ACCESS);
        intelligencePermissionObj.put(ACTION_STR, ACTION);
        return new String[]{intelligencePermissionObj.toString()};
    }

}

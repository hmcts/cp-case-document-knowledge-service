package uk.gov.hmcts.cp.cdk.controllers.accesscontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class PermissionConstants {
    private static final String INTELLIGENCE_ACCESS = "AI search";
    private static final String OBJECT_STR = "object";
    private static final String ACTION_STR = "action";
    private static final String ACTION = "View";

    private PermissionConstants() {
    }

    public static String[] accessToIntelligencePermissions() {
        ObjectNode intelligencePermissionObj = new ObjectMapper().createObjectNode();
        intelligencePermissionObj.put(OBJECT_STR, INTELLIGENCE_ACCESS);
        intelligencePermissionObj.put(ACTION_STR, ACTION);
        return new String[]{intelligencePermissionObj.toString()};
    }

}

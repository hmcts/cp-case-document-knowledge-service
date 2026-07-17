package uk.gov.hmcts.cp.cdk.util;

import static java.util.UUID.fromString;
import static org.springframework.util.StringUtils.hasText;

import java.util.UUID;

import org.springframework.core.env.Environment;

public class EnvironmentUtil {

    private static final String CASEDOCUMENTKNOWLEDGE_SYSTEM_USER_ID = "CASEDOCUMENTKNOWLEDGE_SYSTEM_USER_ID";

    public static UUID getSystemUserId(final Environment environment) {
        final String configuredSystemUserId = environment.getProperty(CASEDOCUMENTKNOWLEDGE_SYSTEM_USER_ID);
        if (!hasText(configuredSystemUserId)) {
            throw new IllegalStateException("Required environment variable '" + CASEDOCUMENTKNOWLEDGE_SYSTEM_USER_ID + "' is not set.");
        }

        try {
            return fromString(configuredSystemUserId);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Environment variable '" + CASEDOCUMENTKNOWLEDGE_SYSTEM_USER_ID + "' must contain a valid UUID, but was: '" + configuredSystemUserId + "'.", e);
        }
    }
}

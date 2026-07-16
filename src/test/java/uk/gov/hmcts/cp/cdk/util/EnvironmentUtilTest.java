package uk.gov.hmcts.cp.cdk.util;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.util.EnvironmentUtil.getSystemUserId;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

@ExtendWith(MockitoExtension.class)
class EnvironmentUtilTest {

    private static final String PROPERTY_NAME = "CASEDOCUMENTKNOWLEDGE_SYSTEM_USER_ID";

    @Mock
    private Environment environment;

    @Test
    void shouldReturnSystemUserIdWhenEnvironmentVariableContainsValidUuid() {

        final UUID expected = randomUUID();
        when(environment.getProperty(PROPERTY_NAME)).thenReturn(expected.toString());

        final UUID actual = getSystemUserId(environment);

        assertEquals(expected, actual);
    }

    @Test
    void shouldThrowExceptionWhenEnvironmentVariableIsMissing() {

        when(environment.getProperty(PROPERTY_NAME)).thenReturn(null);
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> getSystemUserId(environment));
        assertEquals(
                "Required environment variable 'CASEDOCUMENTKNOWLEDGE_SYSTEM_USER_ID' is not set.",
                exception.getMessage());
    }

    @Test
    void shouldThrowExceptionWhenEnvironmentVariableIsBlank() {
        when(environment.getProperty(PROPERTY_NAME)).thenReturn("   ");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> EnvironmentUtil.getSystemUserId(environment));
        assertEquals(
                "Required environment variable 'CASEDOCUMENTKNOWLEDGE_SYSTEM_USER_ID' is not set.",
                exception.getMessage());
    }

    @Test
    void shouldThrowExceptionWhenEnvironmentVariableIsNotValidUuid() {
        when(environment.getProperty(PROPERTY_NAME)).thenReturn("not-a-uuid");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> EnvironmentUtil.getSystemUserId(environment));
        assertEquals(
                "Environment variable 'CASEDOCUMENTKNOWLEDGE_SYSTEM_USER_ID' must contain a valid UUID, but was: 'not-a-uuid'.",
                exception.getMessage());
    }
}
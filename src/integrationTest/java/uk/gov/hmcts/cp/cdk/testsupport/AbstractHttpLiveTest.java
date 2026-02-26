package uk.gov.hmcts.cp.cdk.testsupport;

import uk.gov.hmcts.cp.cdk.util.UtilConstants;
import uk.gov.hmcts.cp.cdk.util.UtilHttp;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.springframework.web.client.RestTemplate;

/**
 * Base class for HTTP live tests.
 * Declared abstract to prevent direct instantiation.
 */

@SuppressWarnings("PMD.AbstractClassWithoutAbstractMethod")
public abstract class AbstractHttpLiveTest {
    protected final String baseUrl = UtilConstants.baseUrl();
    protected final String jdbcUrl = UtilConstants.jdbcUrl();
    protected final String jdbcUser = UtilConstants.jdbcUser();
    protected final String jdbcPass = UtilConstants.jdbcPass();

    protected final RestTemplate http = UtilHttp.newClient();

    protected Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPass);
    }
}

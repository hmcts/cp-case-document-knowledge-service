package uk.gov.hmcts.cp.cdk.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.springframework.web.client.RestTemplate;

public abstract class AbstractHttpLiveTest {
    protected final String baseUrl = TestConstants.baseUrl();
    protected final String jdbcUrl = TestConstants.jdbcUrl();
    protected final String jdbcUser = TestConstants.jdbcUser();
    protected final String jdbcPass = TestConstants.jdbcPass();

    protected final RestTemplate http = TestHttp.newClient();

    protected Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPass);
    }
}

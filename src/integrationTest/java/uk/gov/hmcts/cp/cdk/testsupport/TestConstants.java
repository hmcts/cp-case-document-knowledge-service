package uk.gov.hmcts.cp.cdk.testsupport;

public final class TestConstants {
    private TestConstants() {
    }

    public static final String CJSCPPUID = "Cjscppuid";
    public static final String USER_WITH_SYSTEM_USERS_GROUPS = "a085e359-6069-4694-8820-7810e7dfe762";
    public static final String USER_WITH_PERMISSIONS = "49e00ac7-47b3-44a2-bc8a-a0e584a0a1c4";

    public static String baseUrl() {
        return System.getProperty("app.baseUrl", "http://localhost:8082/casedocumentknowledge-service");
    }

    public static String jdbcUrl() {
        return System.getProperty("it.db.url", "jdbc:postgresql://localhost:55432/casedocumentknowledgeDatabase");
    }

    public static String jdbcUser() {
        return System.getProperty("it.db.user", "casedocumentknowledge");
    }

    public static String jdbcPass() {
        return System.getProperty("it.db.pass", "casedocumentknowledge");
    }
}

package uk.gov.hmcts.cp.cdk.testsupport;

public final class TestConstants {
    private TestConstants() {
    }

    public static final String HEADER_NAME = "Cjscppuid";
    public static final String HEADER_VALUE = "a085e359-6069-4694-8820-7810e7dfe762";

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

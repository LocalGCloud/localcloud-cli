package com.localcloud.emulators.cloudsql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localcloud.integration.TestDataSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudSqlRestServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void createsInstancesDatabasesAndUsersInAdminMetadata() throws Exception {
        TestDataSource testDataSource = TestDataSource.create("cloudsql_metadata");
        try {
            CloudSqlRestService service = new CloudSqlEmulator(testDataSource.getDataSource(), 8080).getRestService();
            body(service.insertInstance("local-project",
                    "{\"name\":\"pg1\",\"region\":\"us-central1\",\"databaseVersion\":\"POSTGRES_15\",\"settings\":{\"tier\":\"db-custom-1-3840\"}}"));
            body(service.insertDatabase("local-project", "pg1", "{\"name\":\"appdb\"}"));
            body(service.insertUser("local-project", "pg1", "{\"name\":\"app\",\"password\":\"secret\"}"));

            var instance = mapper.readTree(body(service.getInstance("local-project", "pg1")));
            assertEquals("POSTGRES", instance.get("backendType").asText());
            assertEquals("local-project:us-central1:pg1", instance.get("connectionName").asText());

            var databases = mapper.readTree(body(service.listDatabases("local-project", "pg1")));
            assertEquals("appdb", databases.get("items").get(0).get("name").asText());

            var users = mapper.readTree(body(service.listUsers("local-project", "pg1")));
            assertEquals("app", users.get("items").get(0).get("name").asText());
        } finally {
            testDataSource.close();
        }
    }

    @Test
    void mysqlFlavorIsExplicitlyMarkedAsOpenHaloDependent() throws Exception {
        TestDataSource testDataSource = TestDataSource.create("cloudsql_mysql_metadata");
        try {
            CloudSqlRestService service = new CloudSqlEmulator(testDataSource.getDataSource(), 8080).getRestService();
            body(service.insertInstance("local-project",
                    "{\"name\":\"mysql1\",\"region\":\"us-central1\",\"databaseVersion\":\"MYSQL_8_0\"}"));
            var instance = mapper.readTree(body(service.getInstance("local-project", "mysql1")));
            assertEquals("OPENHALO_MYSQL_COMPAT", instance.get("backendType").asText());
            assertTrue(instance.get("localcloud").get("dataPlaneStatus").asText().contains("OPENHALO"));
        } finally {
            testDataSource.close();
        }
    }

    private String body(com.linecorp.armeria.common.HttpResponse response) {
        return response.aggregate().join().contentUtf8();
    }
}

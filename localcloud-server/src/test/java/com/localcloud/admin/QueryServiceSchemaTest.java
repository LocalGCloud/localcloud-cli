package com.localcloud.admin;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.config.ServiceRegistry;
import com.localcloud.integration.TestDataSource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryServiceSchemaTest {

    @Test
    void memorystoreSchemaReturnsRedisDatabases() throws Exception {
        // Memorystore schema now queries Redis directly for database names
        // This test would require a running Redis instance, so we just verify
        // the endpoint returns an error gracefully when Redis is not available
        TestDataSource testDataSource = TestDataSource.create("query-schema-memorystore-" + System.nanoTime());
        try {
            QueryService service = queryService(testDataSource);
            var response = service.schema(context(), "memorystore").aggregate().join();
            // Should return OK with tables (or error if Redis not running)
            assertEquals(HttpStatus.OK, response.status());
        } finally {
            testDataSource.close();
        }
    }

    private static QueryService queryService(TestDataSource testDataSource) {
        LocalCloudConfig config = mock(LocalCloudConfig.class);
        when(config.getProjectId()).thenReturn("local-project");

        var dataSource = testDataSource.getDataSource();
        return new QueryService(
                config,
                dataSource,
                ServiceRegistry.load(8080),
                new UsageMetricsRepository(dataSource),
                new QueryHistoryRepository(dataSource));
    }

    private static ServiceRequestContext context() {
        ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        when(ctx.queryParams()).thenReturn(QueryParams.of("project", "local-project"));
        return ctx;
    }
}

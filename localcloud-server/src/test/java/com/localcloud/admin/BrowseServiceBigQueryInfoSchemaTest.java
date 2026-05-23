package com.localcloud.admin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.config.ServiceRegistry;
import com.localcloud.integration.TestDataSource;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowseServiceBigQueryInfoSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void informationSchemaTablesComesFromQueryResults() throws Exception {
        try (MockBigQueryServer bigQuery = MockBigQueryServer.withDatasets(List.of("ds1", "ds2"))) {
            BrowseService service = browseService(bigQuery.baseUrl());

            @SuppressWarnings("unchecked")
            Map<String, Object> response = MAPPER.readValue(
                    service.browseBigQueryInformationSchema("tables", "proj"), Map.class);

            assertEquals(List.of("table_catalog", "table_schema", "table_name", "table_type"),
                    response.get("columns"));
            assertEquals(2, response.get("rowCount"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("rows");
            assertEquals("query_ds1", rows.get(0).get("table_name"));
            assertEquals("query_ds2", rows.get(1).get("table_name"));
            assertEquals(2, bigQuery.queryBodies().size());
            assertTrue(bigQuery.queryBodies().get(0).contains("INFORMATION_SCHEMA.TABLES"));
            assertTrue(bigQuery.queryBodies().get(0).contains("\"defaultDataset\""));
        }
    }

    @Test
    void emptyProjectReturnsStableColumnsAndNoRows() throws Exception {
        try (MockBigQueryServer bigQuery = MockBigQueryServer.withDatasets(List.of())) {
            BrowseService service = browseService(bigQuery.baseUrl());

            @SuppressWarnings("unchecked")
            Map<String, Object> response = MAPPER.readValue(
                    service.browseBigQueryInformationSchema("columns", "proj"), Map.class);

            assertEquals(0, response.get("rowCount"));
            assertEquals(List.of(), response.get("rows"));
            @SuppressWarnings("unchecked")
            List<String> columns = (List<String>) response.get("columns");
            assertTrue(columns.contains("table_catalog"));
            assertTrue(columns.contains("column_name"));
            assertEquals(0, bigQuery.queryBodies().size());
        }
    }

    @Test
    void schemataQueriesAllDatasets() throws Exception {
        try (MockBigQueryServer bigQuery = MockBigQueryServer.withDatasets(List.of("ds1", "ds2"))) {
            BrowseService service = browseService(bigQuery.baseUrl());

            @SuppressWarnings("unchecked")
            Map<String, Object> response = MAPPER.readValue(
                    service.browseBigQueryInformationSchema("schemata", "proj"), Map.class);

            assertEquals(2, response.get("rowCount"));
            assertEquals(2, bigQuery.queryBodies().size());
            assertTrue(bigQuery.queryBodies().get(0).contains("INFORMATION_SCHEMA.SCHEMATA"));
            assertTrue(bigQuery.queryBodies().get(1).contains("INFORMATION_SCHEMA.SCHEMATA"));
        }
    }

    @Test
    void unknownInformationSchemaViewReturnsClearError() throws Exception {
        BrowseService service = browseService("http://localhost:0");

        @SuppressWarnings("unchecked")
        Map<String, Object> response = MAPPER.readValue(
                service.browseBigQueryInformationSchema("unknown_view", "proj"), Map.class);

        assertEquals(true, response.get("error"));
        assertEquals("Unknown INFORMATION_SCHEMA view: unknown_view", response.get("message"));
    }

    private BrowseService browseService(String bigQueryBase) {
        TestDataSource testDataSource = TestDataSource.create("browse-bq-info-" + System.nanoTime());
        return new BrowseService(
                LocalCloudConfig.fromEnvironment(),
                testDataSource.getDataSource(),
                ServiceRegistry.load(8080),
                new UsageMetricsRepository(testDataSource.getDataSource()),
                bigQueryBase);
    }

    private static final class MockBigQueryServer implements AutoCloseable {
        private final HttpServer server;
        private final List<String> datasets;
        private final List<String> queryBodies = new ArrayList<>();

        private MockBigQueryServer(List<String> datasets) throws IOException {
            this.datasets = datasets;
            this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            this.server.createContext("/", this::handle);
            this.server.start();
        }

        static MockBigQueryServer withDatasets(List<String> datasets) throws IOException {
            return new MockBigQueryServer(datasets);
        }

        String baseUrl() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        List<String> queryBodies() {
            return queryBodies;
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod()) && path.endsWith("/datasets")) {
                respond(exchange, datasetListResponse());
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/queries")) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                queryBodies.add(body);
                respond(exchange, queryResponse(body));
                return;
            }
            exchange.sendResponseHeaders(404, 0);
            exchange.close();
        }

        private void respond(HttpExchange exchange, String json) throws IOException {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        private String datasetListResponse() throws IOException {
            List<Map<String, Object>> datasetItems = new ArrayList<>();
            for (String dataset : datasets) {
                datasetItems.add(Map.of("datasetReference", Map.of("datasetId", dataset)));
            }
            return MAPPER.writeValueAsString(Map.of("datasets", datasetItems));
        }

        private String queryResponse(String requestBody) throws IOException {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = MAPPER.readValue(requestBody, Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> defaultDataset = (Map<String, Object>) request.get("defaultDataset");
            String datasetId = String.valueOf(defaultDataset.get("datasetId"));
            String query = String.valueOf(request.get("query"));
            if (query.contains("INFORMATION_SCHEMA.SCHEMATA")) {
                return MAPPER.writeValueAsString(Map.of(
                        "schema", Map.of("fields", List.of(
                                Map.of("name", "catalog_name"),
                                Map.of("name", "schema_name"))),
                        "rows", List.of(row("proj", datasetId))));
            }
            return MAPPER.writeValueAsString(Map.of(
                    "schema", Map.of("fields", List.of(
                            Map.of("name", "table_catalog"),
                            Map.of("name", "table_schema"),
                            Map.of("name", "table_name"),
                            Map.of("name", "table_type"))),
                    "rows", List.of(row("proj", datasetId, "query_" + datasetId, "BASE TABLE"))));
        }

        private Map<String, Object> row(Object... values) {
            List<Map<String, Object>> cells = new ArrayList<>();
            for (Object value : values) {
                cells.add(Map.of("v", value));
            }
            return Map.of("f", cells);
        }
    }
}

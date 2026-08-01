package com.localcloud.admin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.config.ServiceRegistry;
import com.localcloud.integration.TestDataSource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the browse methods added for services that previously had
 * no entry in BrowseService.browseService(). The six services — cloudrun,
 * compute, gke, serviceusage, cloudbilling, cloudresourcemanager — each
 * query the corresponding PostgreSQL table or registry and return JSON.
 *
 * <p>These tests use the H2-backed TestDataSource so no live Postgres is
 * needed. Each test seeds a few rows directly with JDBC, then calls the
 * browse method and asserts on the returned JSON payload.
 */
class BrowseServiceNewFacadesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BrowseService newService(TestDataSource ds) {
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        UsageMetricsRepository usageMetrics = new UsageMetricsRepository(ds.getDataSource());
        return new BrowseService(config, ds.getDataSource(),
                ServiceRegistry.load(24080), usageMetrics);
    }

    private static void insertCloudRunService(Connection c, String project, String location,
                                              String serviceId, String image, int hostPort) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO cloudrun_services(project_id, location, service_id, container_image, " +
                "container_port, host_port, uri, env_vars) VALUES (?, ?, ?, ?, 8080, ?, ?, '{}')")) {
            ps.setString(1, project);
            ps.setString(2, location);
            ps.setString(3, serviceId);
            ps.setString(4, image);
            ps.setInt(5, hostPort);
            ps.setString(6, "http://localhost:" + hostPort);
            ps.executeUpdate();
        }
    }

    private static void insertCloudRunRevision(Connection c, String project, String location,
                                               String serviceId, String revisionId, String image) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO cloudrun_revisions(project_id, location, service_id, revision_id, " +
                "container_image) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, project);
            ps.setString(2, location);
            ps.setString(3, serviceId);
            ps.setString(4, revisionId);
            ps.setString(5, image);
            ps.executeUpdate();
        }
    }

    @Test
    void cloudRunListsServicesAcrossLocations() throws Exception {
        try (TestDataSource ds = TestDataSource.create("browse-cloudrun-" + System.nanoTime())) {
            try (Connection c = ds.getConnection()) {
                insertCloudRunService(c, "local-project", "us-central1", "api", "gcr.io/x/api", 31001);
                insertCloudRunService(c, "local-project", "europe-west1", "worker", "gcr.io/x/worker", 31002);
            }

            BrowseService service = newService(ds);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = MAPPER.readValue(
                    service.browseCloudRunAsString( null, null, "local-project"), Map.class);

            assertNotNull(result.get("services"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> services = (List<Map<String, Object>>) result.get("services");
            assertEquals(2, services.size());
            // Sorted by location (europe-west1 < us-central1), then service_id
            assertEquals("worker", services.get(0).get("serviceId"));
            assertEquals("europe-west1", services.get(0).get("location"));
            assertEquals("api", services.get(1).get("serviceId"));
            assertEquals("us-central1", services.get(1).get("location"));
        }
    }

    @Test
    void cloudRunListsRevisions() throws Exception {
        try (TestDataSource ds = TestDataSource.create("browse-cloudrun-rev-" + System.nanoTime())) {
            try (Connection c = ds.getConnection()) {
                insertCloudRunRevision(c, "local-project", "us-central1", "api", "api-00001-abc", "gcr.io/x/api:v1");
                insertCloudRunRevision(c, "local-project", "us-central1", "api", "api-00002-xyz", "gcr.io/x/api:v2");
            }

            BrowseService service = newService(ds);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = MAPPER.readValue(
                    service.browseCloudRunAsString( "revisions", null, "local-project"), Map.class);

            assertNotNull(result.get("revisions"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> revisions = (List<Map<String, Object>>) result.get("revisions");
            assertEquals(2, revisions.size());
        }
    }

    private static void insertComputeInstance(Connection c, String project, String zone,
                                              String name, String machineType, String status) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO compute_instances(project_id, zone, instance_name, machine_type, status, " +
                "container_image, network_ip) VALUES (?, ?, ?, ?, ?, 'gcr.io/x/app', '10.0.0.1')")) {
            ps.setString(1, project);
            ps.setString(2, zone);
            ps.setString(3, name);
            ps.setString(4, machineType);
            ps.setString(5, status);
            ps.executeUpdate();
        }
    }

    @Test
    void computeListsInstancesAcrossZones() throws Exception {
        try (TestDataSource ds = TestDataSource.create("browse-compute-" + System.nanoTime())) {
            try (Connection c = ds.getConnection()) {
                insertComputeInstance(c, "local-project", "us-central1-a", "vm-1", "e2-medium", "RUNNING");
                insertComputeInstance(c, "local-project", "europe-west1-b", "vm-2", "n1-standard-1", "STOPPED");
            }

            BrowseService service = newService(ds);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = MAPPER.readValue(
                    service.browseComputeAsString( null, null, "local-project"), Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> instances = (List<Map<String, Object>>) result.get("instances");
            assertEquals(2, instances.size());
            // Sorted by zone (europe-west1-b < us-central1-a)
            assertEquals("vm-2", instances.get(0).get("instanceName"));
            assertEquals("n1-standard-1", instances.get(0).get("machineType"));
            assertEquals("STOPPED", instances.get(0).get("status"));
            assertEquals("vm-1", instances.get(1).get("instanceName"));
        }
    }

    private static void insertGkeCluster(Connection c, String project, String location,
                                        String name, String version, int nodeCount) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO gke_clusters(project_id, location, cluster_id, status, " +
                "k3d_cluster_name, endpoint, cluster_version, node_count) " +
                "VALUES (?, ?, ?, 'RUNNING', ?, ?, ?, ?)")) {
            ps.setString(1, project);
            ps.setString(2, location);
            ps.setString(3, name);
            ps.setString(4, name + "-k3d");
            ps.setString(5, "https://127.0.0.1:" + (24092 + nodeCount));
            ps.setString(6, version);
            ps.setInt(7, nodeCount);
            ps.executeUpdate();
        }
    }

    @Test
    void gkeListsClusters() throws Exception {
        try (TestDataSource ds = TestDataSource.create("browse-gke-" + System.nanoTime())) {
            try (Connection c = ds.getConnection()) {
                insertGkeCluster(c, "local-project", "us-central1", "dev", "1.28.0", 1);
                insertGkeCluster(c, "local-project", "us-west1", "prod", "1.28.0", 3);
            }

            BrowseService service = newService(ds);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = MAPPER.readValue(
                    service.browseGkeAsString( null, null, "local-project"), Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> clusters = (List<Map<String, Object>>) result.get("clusters");
            assertEquals(2, clusters.size());
            assertEquals("dev", clusters.get(0).get("clusterId"));
            assertEquals("us-central1", clusters.get(0).get("location"));
            assertEquals("RUNNING", clusters.get(0).get("status"));
        }
    }

    @Test
    void serviceUsageReflectsRegistry() throws Exception {
        try (TestDataSource ds = TestDataSource.create("browse-su-" + System.nanoTime())) {
            BrowseService service = newService(ds);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = MAPPER.readValue(
                    service.browseServiceUsageAsString( null, null, "local-project"), Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> services = (List<Map<String, Object>>) result.get("services");
            assertFalse(services.isEmpty());
            // Bigtable has no gcloudApiName (gRPC data-plane only) so it should be filtered out
            boolean sawBigtable = services.stream()
                    .anyMatch(s -> "bigtable".equals(s.get("serviceId")));
            assertFalse(sawBigtable, "bigtable should be filtered out (no gcloudApiName)");
            // Other community services should be present and enabled by default
            boolean sawGcs = services.stream()
                    .anyMatch(s -> "storage".equals(s.get("serviceId")) && "ENABLED".equals(s.get("state")));
            assertTrue(sawGcs);
        }
    }

    @Test
    void cloudBillingListsAccountsAndLinkedProjects() throws Exception {
        try (TestDataSource ds = TestDataSource.create("browse-cb-" + System.nanoTime())) {
            try (Connection c = ds.getConnection()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO projects(project_id, display_name) VALUES (?, ?)")) {
                    ps.setString(1, "alpha");
                    ps.setString(2, "Alpha");
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO projects(project_id, display_name) VALUES (?, ?)")) {
                    ps.setString(1, "beta");
                    ps.setString(2, "Beta");
                    ps.executeUpdate();
                }
            }

            BrowseService service = newService(ds);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = MAPPER.readValue(
                    service.browseCloudBillingAsString( null, null, "local-project"), Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> accounts = (List<Map<String, Object>>) result.get("billingAccounts");
            assertEquals(1, accounts.size());
            assertEquals("billingAccounts/000000-AAAAAA-BBBBBB", accounts.get(0).get("name"));
            assertEquals(true, accounts.get(0).get("open"));

            @SuppressWarnings("unchecked")
            List<String> linked = (List<String>) result.get("linkedProjects");
            assertTrue(linked.contains("alpha"));
            assertTrue(linked.contains("beta"));
        }
    }

    @Test
    void cloudBillingListsBudgets() throws Exception {
        try (TestDataSource ds = TestDataSource.create("browse-cb-budgets-" + System.nanoTime())) {
            try (Connection c = ds.getConnection()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO billing_budgets(billing_account, budget_id, display_name, " +
                        "amount_json, threshold_rules_json) VALUES (?, ?, ?, ?, ?)")) {
                    ps.setString(1, "billingAccounts/000000-AAAAAA-BBBBBB");
                    ps.setString(2, "budget-1");
                    ps.setString(3, "Quarterly cap");
                    ps.setString(4, "{\"units\":\"USD\",\"nanos\":0}");
                    ps.setString(5, "[{\"percent\":0.5}]");
                    ps.executeUpdate();
                }
            }

            BrowseService service = newService(ds);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = MAPPER.readValue(
                    service.browseCloudBillingAsString( "budgets", null, "local-project"), Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> budgets = (List<Map<String, Object>>) result.get("budgets");
            assertEquals(1, budgets.size());
            assertEquals("budget-1", budgets.get(0).get("budgetId"));
            assertEquals("Quarterly cap", budgets.get(0).get("displayName"));
        }
    }

    @Test
    void cloudResourceManagerListsProjects() throws Exception {
        try (TestDataSource ds = TestDataSource.create("browse-crm-" + System.nanoTime())) {
            try (Connection c = ds.getConnection()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO projects(project_id, display_name) VALUES (?, ?)")) {
                    ps.setString(1, "alpha");
                    ps.setString(2, "Alpha");
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO projects(project_id, display_name) VALUES (?, ?)")) {
                    ps.setString(1, "beta");
                    ps.setString(2, "Beta");
                    ps.executeUpdate();
                }
            }

            BrowseService service = newService(ds);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = MAPPER.readValue(
                    service.browseCloudResourceManagerAsString( null, null, "local-project"), Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> projects = (List<Map<String, Object>>) result.get("projects");
            assertEquals(2, projects.size());
        }
    }

    @Test
    void cloudResourceManagerLooksUpSingleProject() throws Exception {
        try (TestDataSource ds = TestDataSource.create("browse-crm-1-" + System.nanoTime())) {
            try (Connection c = ds.getConnection()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO projects(project_id, display_name) VALUES (?, ?)")) {
                    ps.setString(1, "alpha");
                    ps.setString(2, "Alpha");
                    ps.executeUpdate();
                }
            }

            BrowseService service = newService(ds);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = MAPPER.readValue(
                    service.browseCloudResourceManagerAsString( "projects", "alpha", "local-project"), Map.class);

            assertNotNull(result.get("project"));
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) result.get("project");
            assertEquals("alpha", p.get("projectId"));
            assertEquals("projects/alpha", p.get("name"));
        }
    }

    @Test
    void cloudResourceManagerReturnsErrorForMissingProject() throws Exception {
        try (TestDataSource ds = TestDataSource.create("browse-crm-missing-" + System.nanoTime())) {
            BrowseService service = newService(ds);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = MAPPER.readValue(
                    service.browseCloudResourceManagerAsString( "projects", "ghost", "local-project"), Map.class);

            assertEquals(true, result.get("error"));
            assertTrue(result.get("message").toString().contains("ghost"));
        }
    }
}

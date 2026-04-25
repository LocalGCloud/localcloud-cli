# Data Mirror Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Enable researchers to sync filtered subsets of production GCP data into LocalCloud emulators for $0 local querying.

**Architecture:** New `SyncService` orchestrates per-service `SyncAdapter` implementations that pull from real GCP APIs (via OAuth/SA credentials) and load into local emulators. Console gets a "Remote Sync" mode tab in ServiceExplorer with shared `SchemaExplorer` component, filter builder, and progress tracking via SSE.

**Tech Stack:** Java 21 + Armeria (backend), Solid.js (console), Google OAuth2 (auth), SSE (progress), PostgreSQL (manifests/credentials), AES-256 (credential encryption)

**Design Doc:** `docs/plans/2026-04-24-data-mirror-design.md`

---

## Phase 1: Database & Repository Foundation

### Task 1: Add sync tables to SchemaManager

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/persistence/SchemaManager.java`
- Test: `localcloud-server/src/test/java/com/localcloud/persistence/SchemaManagerTest.java`

**Step 1: Write failing test**

```java
package com.localcloud.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

class SchemaManagerSyncTablesTest {

    private TestDataSource testDs;
    private SchemaManager schemaManager;

    @BeforeEach
    void setUp() throws Exception {
        testDs = new TestDataSource();
        schemaManager = new SchemaManager(testDs.getDataSource());
        schemaManager.initialize("test-project");
    }

    @Test
    void syncManifests_tableExists() throws Exception {
        try (var conn = testDs.getDataSource().getConnection();
             var rs = conn.getMetaData().getTables(null, null, "sync_manifests", null)) {
            assertTrue(rs.next(), "sync_manifests table should exist");
        }
    }

    @Test
    void syncCredentials_tableExists() throws Exception {
        try (var conn = testDs.getDataSource().getConnection();
             var rs = conn.getMetaData().getTables(null, null, "sync_credentials", null)) {
            assertTrue(rs.next(), "sync_credentials table should exist");
        }
    }
}
```

Note: uses the existing `TestDataSource` from `localcloud-server/src/test/java/com/localcloud/integration/TestDataSource.java`.

**Step 2: Run test to verify it fails**

Run: `cd localcloud-server && ./gradlew test --tests '*SchemaManagerSyncTablesTest*' -i`
Expected: FAIL — tables don't exist yet

**Step 3: Add table creation to SchemaManager.initialize()**

Add after the `workflow_config` table creation block in `SchemaManager.java`:

```java
// Sync tables for Data Mirror
stmt.execute(
    "CREATE TABLE IF NOT EXISTS sync_manifests (" +
    "    id SERIAL PRIMARY KEY," +
    "    project_id VARCHAR(255) NOT NULL," +
    "    service_id VARCHAR(50) NOT NULL," +
    "    resource_path VARCHAR(500) NOT NULL," +
    "    source_project VARCHAR(255) NOT NULL," +
    "    filters_json TEXT DEFAULT '[]'," +
    "    row_count BIGINT DEFAULT 0," +
    "    bytes_synced BIGINT DEFAULT 0," +
    "    estimated_cost DECIMAL(10,6) DEFAULT 0," +
    "    status VARCHAR(20) DEFAULT 'pending'," +
    "    error_message TEXT," +
    "    synced_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
    "    UNIQUE(project_id, service_id, resource_path)" +
    ")"
);

stmt.execute(
    "CREATE TABLE IF NOT EXISTS sync_credentials (" +
    "    id SERIAL PRIMARY KEY," +
    "    project_id VARCHAR(255) NOT NULL," +
    "    source_project VARCHAR(255) NOT NULL," +
    "    auth_method VARCHAR(20) NOT NULL," +
    "    credential_data TEXT," +
    "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
    "    UNIQUE(project_id, source_project)" +
    ")"
);
```

**Step 4: Run test to verify it passes**

Run: `cd localcloud-server && ./gradlew test --tests '*SchemaManagerSyncTablesTest*' -i`
Expected: PASS

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/persistence/SchemaManager.java \
        localcloud-server/src/test/java/com/localcloud/persistence/SchemaManagerSyncTablesTest.java
git commit -m "feat(sync): add sync_manifests and sync_credentials tables"
```

---

### Task 2: Create SyncManifestRepository

**Files:**
- Create: `localcloud-server/src/main/java/com/localcloud/sync/SyncManifestRepository.java`
- Test: `localcloud-server/src/test/java/com/localcloud/sync/SyncManifestRepositoryTest.java`

**Step 1: Write failing tests**

```java
package com.localcloud.sync;

import com.localcloud.integration.TestDataSource;
import com.localcloud.persistence.SchemaManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

class SyncManifestRepositoryTest {

    private SyncManifestRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        var testDs = new TestDataSource();
        var dataSource = testDs.getDataSource();
        new SchemaManager(dataSource).initialize("test-project");
        repo = new SyncManifestRepository(dataSource);
    }

    @Test
    void save_insertsManifest() throws Exception {
        var manifest = new SyncManifest("test-project", "bigquery",
                "analytics.events", "prod-project-123",
                "[{\"column\":\"created_at\",\"operator\":\">=\",\"value\":\"2026-01-01\"}]",
                1000000, 892000000L, 0.006, "completed", null);
        int id = repo.save(manifest);
        assertTrue(id > 0);
    }

    @Test
    void getByService_filtersCorrectly() throws Exception {
        repo.save(new SyncManifest("test-project", "bigquery", "analytics.events",
                "prod-123", "[]", 100, 1000L, 0.001, "completed", null));
        repo.save(new SyncManifest("test-project", "firestore", "users",
                "prod-123", "[]", 50, 500L, 0.0005, "completed", null));

        List<Map<String, Object>> results = repo.getByService("test-project", "bigquery");
        assertEquals(1, results.size());
        assertEquals("analytics.events", results.get(0).get("resource_path"));
    }

    @Test
    void getAll_returnsAllForProject() throws Exception {
        repo.save(new SyncManifest("test-project", "bigquery", "t1",
                "prod-123", "[]", 10, 100L, 0.001, "completed", null));
        repo.save(new SyncManifest("test-project", "firestore", "t2",
                "prod-123", "[]", 20, 200L, 0.002, "completed", null));

        List<Map<String, Object>> results = repo.getAll("test-project");
        assertEquals(2, results.size());
    }

    @Test
    void upsert_replacesSameResource() throws Exception {
        repo.save(new SyncManifest("test-project", "bigquery", "analytics.events",
                "prod-123", "[]", 100, 1000L, 0.001, "completed", null));
        repo.save(new SyncManifest("test-project", "bigquery", "analytics.events",
                "prod-123", "[{\"col\":\"x\"}]", 200, 2000L, 0.002, "completed", null));

        List<Map<String, Object>> results = repo.getByService("test-project", "bigquery");
        assertEquals(1, results.size());
        assertEquals(200L, ((Number) results.get(0).get("row_count")).longValue());
    }

    @Test
    void delete_removesManifest() throws Exception {
        int id = repo.save(new SyncManifest("test-project", "bigquery", "analytics.events",
                "prod-123", "[]", 100, 1000L, 0.001, "completed", null));
        repo.delete(id);

        List<Map<String, Object>> results = repo.getAll("test-project");
        assertTrue(results.isEmpty());
    }

    @Test
    void updateStatus_changesStatusAndRowCount() throws Exception {
        int id = repo.save(new SyncManifest("test-project", "bigquery", "analytics.events",
                "prod-123", "[]", 0, 0L, 0.001, "in_progress", null));
        repo.updateProgress(id, "completed", 50000, 45000000L, null);

        var results = repo.getAll("test-project");
        assertEquals("completed", results.get(0).get("status"));
        assertEquals(50000L, ((Number) results.get(0).get("row_count")).longValue());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd localcloud-server && ./gradlew test --tests '*SyncManifestRepositoryTest*' -i`
Expected: FAIL — class not found

**Step 3: Create SyncManifest record and SyncManifestRepository**

```java
// SyncManifest.java
package com.localcloud.sync;

public record SyncManifest(
    String projectId,
    String serviceId,
    String resourcePath,
    String sourceProject,
    String filtersJson,
    long rowCount,
    long bytesSynced,
    double estimatedCost,
    String status,
    String errorMessage
) {}
```

```java
// SyncManifestRepository.java
package com.localcloud.sync;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

public class SyncManifestRepository {

    private final DataSource dataSource;

    public SyncManifestRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public int save(SyncManifest m) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            // Delete existing for same project+service+resource (upsert)
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM sync_manifests WHERE project_id = ? AND service_id = ? AND resource_path = ?")) {
                del.setString(1, m.projectId());
                del.setString(2, m.serviceId());
                del.setString(3, m.resourcePath());
                del.executeUpdate();
            }
            try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO sync_manifests (project_id, service_id, resource_path, source_project, " +
                    "filters_json, row_count, bytes_synced, estimated_cost, status, error_message, synced_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ins.setString(1, m.projectId());
                ins.setString(2, m.serviceId());
                ins.setString(3, m.resourcePath());
                ins.setString(4, m.sourceProject());
                ins.setString(5, m.filtersJson());
                ins.setLong(6, m.rowCount());
                ins.setLong(7, m.bytesSynced());
                ins.setDouble(8, m.estimatedCost());
                ins.setString(9, m.status());
                ins.setString(10, m.errorMessage());
                ins.executeUpdate();
                ResultSet keys = ins.getGeneratedKeys();
                return keys.next() ? keys.getInt(1) : -1;
            }
        }
    }

    public List<Map<String, Object>> getAll(String projectId) throws SQLException {
        return query("SELECT * FROM sync_manifests WHERE project_id = ? ORDER BY synced_at DESC",
                projectId);
    }

    public List<Map<String, Object>> getByService(String projectId, String serviceId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM sync_manifests WHERE project_id = ? AND service_id = ? ORDER BY synced_at DESC")) {
            ps.setString(1, projectId);
            ps.setString(2, serviceId);
            return resultSetToList(ps.executeQuery());
        }
    }

    public Map<String, Object> getById(int id) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM sync_manifests WHERE id = ?")) {
            ps.setInt(1, id);
            var list = resultSetToList(ps.executeQuery());
            return list.isEmpty() ? null : list.get(0);
        }
    }

    public void updateProgress(int id, String status, long rowCount, long bytesSynced,
                                String errorMessage) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE sync_manifests SET status = ?, row_count = ?, bytes_synced = ?, " +
                 "error_message = ?, synced_at = CURRENT_TIMESTAMP WHERE id = ?")) {
            ps.setString(1, status);
            ps.setLong(2, rowCount);
            ps.setLong(3, bytesSynced);
            ps.setString(4, errorMessage);
            ps.setInt(5, id);
            ps.executeUpdate();
        }
    }

    public void delete(int id) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM sync_manifests WHERE id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    private List<Map<String, Object>> query(String sql, String projectId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            return resultSetToList(ps.executeQuery());
        }
    }

    private List<Map<String, Object>> resultSetToList(ResultSet rs) throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= cols; i++) {
                row.put(meta.getColumnName(i), rs.getObject(i));
            }
            list.add(row);
        }
        return list;
    }
}
```

**Step 4: Run test to verify it passes**

Run: `cd localcloud-server && ./gradlew test --tests '*SyncManifestRepositoryTest*' -i`
Expected: PASS

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/sync/SyncManifest.java \
        localcloud-server/src/main/java/com/localcloud/sync/SyncManifestRepository.java \
        localcloud-server/src/test/java/com/localcloud/sync/SyncManifestRepositoryTest.java
git commit -m "feat(sync): add SyncManifest record and SyncManifestRepository"
```

---

### Task 3: Create SyncCredentialRepository

**Files:**
- Create: `localcloud-server/src/main/java/com/localcloud/sync/SyncCredentialRepository.java`
- Test: `localcloud-server/src/test/java/com/localcloud/sync/SyncCredentialRepositoryTest.java`

**Step 1: Write failing tests**

```java
package com.localcloud.sync;

import com.localcloud.integration.TestDataSource;
import com.localcloud.persistence.SchemaManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

class SyncCredentialRepositoryTest {

    private SyncCredentialRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        var testDs = new TestDataSource();
        var dataSource = testDs.getDataSource();
        new SchemaManager(dataSource).initialize("test-project");
        repo = new SyncCredentialRepository(dataSource);
    }

    @Test
    void saveOAuth_storesCredential() throws Exception {
        repo.save("test-project", "prod-123", "oauth",
                "{\"refresh_token\":\"rt_xxx\",\"email\":\"user@co.com\"}");
        Map<String, String> status = repo.getStatus("test-project");
        assertNotNull(status);
        assertEquals("oauth", status.get("auth_method"));
        assertEquals("prod-123", status.get("source_project"));
    }

    @Test
    void saveServiceAccount_storesCredential() throws Exception {
        repo.save("test-project", "prod-123", "service_account",
                "{\"client_email\":\"sa@prod.iam.gserviceaccount.com\"}");
        Map<String, String> status = repo.getStatus("test-project");
        assertEquals("service_account", status.get("auth_method"));
    }

    @Test
    void getStatus_neverReturnsCredentialData() throws Exception {
        repo.save("test-project", "prod-123", "oauth",
                "{\"refresh_token\":\"secret\"}");
        Map<String, String> status = repo.getStatus("test-project");
        assertFalse(status.containsKey("credential_data"));
    }

    @Test
    void getCredentialData_returnsRawData() throws Exception {
        repo.save("test-project", "prod-123", "oauth",
                "{\"refresh_token\":\"rt_xxx\"}");
        String data = repo.getCredentialData("test-project");
        assertTrue(data.contains("rt_xxx"));
    }

    @Test
    void delete_clearsCredential() throws Exception {
        repo.save("test-project", "prod-123", "oauth", "{}");
        repo.delete("test-project");
        assertNull(repo.getStatus("test-project"));
    }

    @Test
    void save_upserts_replacesExisting() throws Exception {
        repo.save("test-project", "prod-123", "oauth", "{\"old\":true}");
        repo.save("test-project", "prod-456", "service_account", "{\"new\":true}");
        Map<String, String> status = repo.getStatus("test-project");
        assertEquals("prod-456", status.get("source_project"));
        assertEquals("service_account", status.get("auth_method"));
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd localcloud-server && ./gradlew test --tests '*SyncCredentialRepositoryTest*' -i`
Expected: FAIL — class not found

**Step 3: Create SyncCredentialRepository**

```java
package com.localcloud.sync;

import javax.sql.DataSource;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Map;

public class SyncCredentialRepository {

    private final DataSource dataSource;

    public SyncCredentialRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void save(String projectId, String sourceProject, String authMethod,
                     String credentialData) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM sync_credentials WHERE project_id = ?")) {
                del.setString(1, projectId);
                del.executeUpdate();
            }
            try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO sync_credentials (project_id, source_project, auth_method, " +
                    "credential_data, created_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)")) {
                ins.setString(1, projectId);
                ins.setString(2, sourceProject);
                ins.setString(3, authMethod);
                ins.setString(4, credentialData);
                ins.executeUpdate();
            }
        }
    }

    /** Returns status info (no secrets). Null if no credential configured. */
    public Map<String, String> getStatus(String projectId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT source_project, auth_method, created_at FROM sync_credentials WHERE project_id = ?")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Map<String, String> status = new LinkedHashMap<>();
                status.put("source_project", rs.getString("source_project"));
                status.put("auth_method", rs.getString("auth_method"));
                status.put("created_at", rs.getString("created_at"));
                status.put("connected", "true");
                return status;
            }
            return null;
        }
    }

    /** Returns raw credential data for internal use (token refresh, API calls). */
    public String getCredentialData(String projectId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT credential_data FROM sync_credentials WHERE project_id = ?")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString("credential_data") : null;
        }
    }

    public void delete(String projectId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM sync_credentials WHERE project_id = ?")) {
            ps.setString(1, projectId);
            ps.executeUpdate();
        }
    }
}
```

**Step 4: Run test to verify it passes**

Run: `cd localcloud-server && ./gradlew test --tests '*SyncCredentialRepositoryTest*' -i`
Expected: PASS

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/sync/SyncCredentialRepository.java \
        localcloud-server/src/test/java/com/localcloud/sync/SyncCredentialRepositoryTest.java
git commit -m "feat(sync): add SyncCredentialRepository for OAuth/SA credentials"
```

---

## Phase 2: SyncAdapter Interface & BigQuery Adapter

### Task 4: Create SyncAdapter interface, SyncFilter, supporting records

**Files:**
- Create: `localcloud-server/src/main/java/com/localcloud/sync/SyncAdapter.java`
- Create: `localcloud-server/src/main/java/com/localcloud/sync/SyncFilter.java`
- Create: `localcloud-server/src/main/java/com/localcloud/sync/SyncResult.java`
- Create: `localcloud-server/src/main/java/com/localcloud/sync/CostEstimate.java`
- Create: `localcloud-server/src/main/java/com/localcloud/sync/BrowseResult.java`
- Create: `localcloud-server/src/main/java/com/localcloud/sync/PreviewResult.java`
- Create: `localcloud-server/src/main/java/com/localcloud/sync/SyncProgressCallback.java`

**Step 1: Create all types (no test needed — pure interfaces/records)**

```java
// SyncFilter.java
package com.localcloud.sync;

public record SyncFilter(String column, String operator, String value, String columnType) {}
```

```java
// CostEstimate.java
package com.localcloud.sync;

public record CostEstimate(long estimatedRows, long estimatedBytes, double estimatedCostUsd,
                            String details) {}
```

```java
// BrowseResult.java
package com.localcloud.sync;

import java.util.List;
import java.util.Map;

public record BrowseResult(List<Map<String, Object>> nodes) {}
```

```java
// PreviewResult.java
package com.localcloud.sync;

import java.util.List;
import java.util.Map;

public record PreviewResult(List<String> columns, List<Map<String, Object>> rows,
                             long totalRows, long totalBytes) {}
```

```java
// SyncResult.java
package com.localcloud.sync;

public record SyncResult(int manifestId, long rowsSynced, long bytesSynced,
                          double costIncurred, String status, String errorMessage) {}
```

```java
// SyncProgressCallback.java
package com.localcloud.sync;

@FunctionalInterface
public interface SyncProgressCallback {
    void onProgress(long rowsTransferred, long bytesTransferred, long estimatedTotalRows);
}
```

```java
// SyncAdapter.java
package com.localcloud.sync;

import java.util.List;

public interface SyncAdapter {
    BrowseResult browseRemote(String project, String accessToken);
    PreviewResult previewRemote(String project, String resource, String accessToken, int limit);
    CostEstimate estimate(String project, String resource, List<SyncFilter> filters,
                          int rowLimit, String accessToken);
    SyncResult sync(String project, String resource, List<SyncFilter> filters,
                    int rowLimit, String accessToken, String localProject,
                    SyncProgressCallback progress);
}
```

**Step 2: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/sync/
git commit -m "feat(sync): add SyncAdapter interface and supporting records"
```

---

### Task 5: Create BigQuerySyncAdapter

**Files:**
- Create: `localcloud-server/src/main/java/com/localcloud/sync/adapters/BigQuerySyncAdapter.java`
- Test: `localcloud-server/src/test/java/com/localcloud/sync/adapters/BigQuerySyncAdapterTest.java`

**Step 1: Write failing tests**

```java
package com.localcloud.sync.adapters;

import com.localcloud.sync.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

class BigQuerySyncAdapterTest {

    private BigQuerySyncAdapter adapter;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // Uses localhost emulator URLs for testing — no real GCP calls
        adapter = new BigQuerySyncAdapter("http://localhost:9050", mapper);
    }

    @Test
    void buildQuery_noFilters_selectAll() {
        String sql = adapter.buildSyncQuery("analytics", "events", List.of(), 1000);
        assertEquals("SELECT * FROM `analytics.events` LIMIT 1000", sql);
    }

    @Test
    void buildQuery_withFilters_addsWhereClause() {
        List<SyncFilter> filters = List.of(
            new SyncFilter("created_at", ">=", "2026-01-01", "TIMESTAMP"),
            new SyncFilter("event_type", "=", "purchase", "STRING")
        );
        String sql = adapter.buildSyncQuery("analytics", "events", filters, 500000);
        assertEquals("SELECT * FROM `analytics.events` " +
                "WHERE created_at >= '2026-01-01' AND event_type = 'purchase' LIMIT 500000", sql);
    }

    @Test
    void buildQuery_inOperator_handledCorrectly() {
        List<SyncFilter> filters = List.of(
            new SyncFilter("status", "IN", "active,pending", "STRING")
        );
        String sql = adapter.buildSyncQuery("analytics", "orders", filters, 100);
        assertEquals("SELECT * FROM `analytics.orders` " +
                "WHERE status IN ('active','pending') LIMIT 100", sql);
    }

    @Test
    void buildQuery_numericFilter_noQuotes() {
        List<SyncFilter> filters = List.of(
            new SyncFilter("amount", ">", "100", "FLOAT64")
        );
        String sql = adapter.buildSyncQuery("billing", "charges", filters, 1000);
        assertEquals("SELECT * FROM `billing.charges` WHERE amount > 100 LIMIT 1000", sql);
    }

    @Test
    void parseDatasetAndTable_dotSeparated() {
        String[] parts = adapter.parseResource("analytics.events");
        assertEquals("analytics", parts[0]);
        assertEquals("events", parts[1]);
    }

    @Test
    void parseDatasetAndTable_invalid_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.parseResource("no_dot_here"));
    }

    @Test
    void estimateCostFromBytes_correctCalculation() {
        // BQ pricing: $5 per TB scanned (first 1TB free, but we charge all for estimates)
        double cost = adapter.estimateCost(5_000_000_000L); // 5 GB
        assertEquals(0.025, cost, 0.001); // 5 GB * $5/TB = $0.025
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd localcloud-server && ./gradlew test --tests '*BigQuerySyncAdapterTest*' -i`
Expected: FAIL — class not found

**Step 3: Implement BigQuerySyncAdapter**

```java
package com.localcloud.sync.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localcloud.sync.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class BigQuerySyncAdapter implements SyncAdapter {

    private static final Logger logger = LoggerFactory.getLogger(BigQuerySyncAdapter.class);
    private static final String GCP_BQ_BASE = "https://bigquery.googleapis.com";
    private static final double COST_PER_TB = 5.0;
    private static final int PAGE_SIZE = 10000;

    private final String localEmulatorBase;
    private final ObjectMapper mapper;

    public BigQuerySyncAdapter(String localEmulatorBase, ObjectMapper mapper) {
        this.localEmulatorBase = localEmulatorBase;
        this.mapper = mapper;
    }

    @Override
    public BrowseResult browseRemote(String project, String accessToken) {
        try {
            // List datasets
            String dsUrl = GCP_BQ_BASE + "/bigquery/v2/projects/" + project + "/datasets";
            String dsBody = gcpGet(dsUrl, accessToken);
            JsonNode dsNode = mapper.readTree(dsBody);

            List<Map<String, Object>> nodes = new ArrayList<>();
            JsonNode datasets = dsNode.path("datasets");
            if (datasets.isArray()) {
                for (JsonNode ds : datasets) {
                    String datasetId = ds.path("datasetReference").path("datasetId").asText();
                    // List tables in dataset
                    String tblUrl = GCP_BQ_BASE + "/bigquery/v2/projects/" + project +
                            "/datasets/" + datasetId + "/tables";
                    String tblBody = gcpGet(tblUrl, accessToken);
                    JsonNode tblNode = mapper.readTree(tblBody);

                    List<Map<String, Object>> tables = new ArrayList<>();
                    JsonNode tableList = tblNode.path("tables");
                    if (tableList.isArray()) {
                        for (JsonNode tbl : tableList) {
                            Map<String, Object> table = new LinkedHashMap<>();
                            table.put("id", datasetId + "." + tbl.path("tableReference").path("tableId").asText());
                            table.put("name", tbl.path("tableReference").path("tableId").asText());
                            table.put("type", "table");
                            table.put("metadata", Map.of(
                                "rowCount", tbl.path("numRows").asLong(0),
                                "sizeBytes", tbl.path("numBytes").asLong(0)
                            ));
                            // Get schema
                            String schemaUrl = GCP_BQ_BASE + "/bigquery/v2/projects/" + project +
                                    "/datasets/" + datasetId + "/tables/" +
                                    tbl.path("tableReference").path("tableId").asText();
                            try {
                                String schemaBody = gcpGet(schemaUrl, accessToken);
                                JsonNode schemaNode = mapper.readTree(schemaBody);
                                List<Map<String, String>> schema = new ArrayList<>();
                                JsonNode fields = schemaNode.path("schema").path("fields");
                                if (fields.isArray()) {
                                    for (JsonNode f : fields) {
                                        schema.add(Map.of(
                                            "name", f.path("name").asText(),
                                            "type", f.path("type").asText(),
                                            "mode", f.path("mode").asText("NULLABLE")
                                        ));
                                    }
                                }
                                table.put("schema", schema);
                            } catch (Exception e) {
                                logger.warn("Failed to get schema for {}.{}", datasetId,
                                        tbl.path("tableReference").path("tableId").asText());
                            }
                            tables.add(table);
                        }
                    }

                    nodes.add(Map.of(
                        "id", datasetId,
                        "name", datasetId,
                        "type", "dataset",
                        "children", tables
                    ));
                }
            }
            return new BrowseResult(nodes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to browse remote BigQuery: " + e.getMessage(), e);
        }
    }

    @Override
    public PreviewResult previewRemote(String project, String resource,
                                        String accessToken, int limit) {
        String[] parts = parseResource(resource);
        String sql = "SELECT * FROM `" + parts[0] + "." + parts[1] + "` LIMIT " + limit;
        try {
            String url = GCP_BQ_BASE + "/bigquery/v2/projects/" + project + "/queries";
            String payload = mapper.writeValueAsString(Map.of(
                    "query", sql, "useLegacySql", false, "maxResults", limit));
            String body = gcpPost(url, payload, accessToken);
            return parseQueryResult(body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to preview: " + e.getMessage(), e);
        }
    }

    @Override
    public CostEstimate estimate(String project, String resource, List<SyncFilter> filters,
                                  int rowLimit, String accessToken) {
        String[] parts = parseResource(resource);
        String sql = buildSyncQuery(parts[0], parts[1], filters, rowLimit);
        try {
            String url = GCP_BQ_BASE + "/bigquery/v2/projects/" + project + "/queries";
            String payload = mapper.writeValueAsString(Map.of(
                    "query", sql, "useLegacySql", false, "dryRun", true));
            String body = gcpPost(url, payload, accessToken);
            JsonNode node = mapper.readTree(body);
            long totalBytes = node.path("totalBytesProcessed").asLong(0);
            double cost = estimateCost(totalBytes);
            // Row estimate from statistics
            long estRows = node.path("statistics").path("query").path("estimatedRows").asLong(rowLimit);
            return new CostEstimate(estRows, totalBytes, cost,
                    String.format("BQ scan: %s, ~$%.4f", humanBytes(totalBytes), cost));
        } catch (Exception e) {
            throw new RuntimeException("Failed to estimate: " + e.getMessage(), e);
        }
    }

    @Override
    public SyncResult sync(String project, String resource, List<SyncFilter> filters,
                           int rowLimit, String accessToken, String localProject,
                           SyncProgressCallback progress) {
        String[] parts = parseResource(resource);
        String dataset = parts[0];
        String table = parts[1];
        String sql = buildSyncQuery(dataset, table, filters, rowLimit);

        try {
            // 1. Get schema from remote
            String schemaUrl = GCP_BQ_BASE + "/bigquery/v2/projects/" + project +
                    "/datasets/" + dataset + "/tables/" + table;
            String schemaBody = gcpGet(schemaUrl, accessToken);
            JsonNode schemaNode = mapper.readTree(schemaBody).path("schema");

            // 2. Ensure local dataset + table exist
            ensureLocalDatasetAndTable(localProject, dataset, table, schemaNode);

            // 3. Execute query on remote
            String queryUrl = GCP_BQ_BASE + "/bigquery/v2/projects/" + project + "/queries";
            String queryPayload = mapper.writeValueAsString(Map.of(
                    "query", sql, "useLegacySql", false, "maxResults", PAGE_SIZE));
            String queryBody = gcpPost(queryUrl, queryPayload, accessToken);
            JsonNode queryResult = mapper.readTree(queryBody);

            long totalRows = 0;
            long totalBytes = 0;
            String pageToken = null;
            String jobId = queryResult.path("jobReference").path("jobId").asText();

            // 4. Paginate and insert into local emulator
            do {
                JsonNode rows = queryResult.path("rows");
                JsonNode schemaFields = queryResult.path("schema").path("fields");
                if (rows.isArray() && rows.size() > 0) {
                    insertIntoLocalEmulator(localProject, dataset, table, schemaFields, rows);
                    totalRows += rows.size();
                    totalBytes += queryBody.length(); // approximate
                    if (progress != null) {
                        progress.onProgress(totalRows, totalBytes, rowLimit);
                    }
                }

                pageToken = queryResult.has("pageToken") ? queryResult.get("pageToken").asText() : null;
                if (pageToken != null) {
                    String nextUrl = GCP_BQ_BASE + "/bigquery/v2/projects/" + project +
                            "/queries/" + jobId + "?pageToken=" + pageToken + "&maxResults=" + PAGE_SIZE;
                    queryBody = gcpGet(nextUrl, accessToken);
                    queryResult = mapper.readTree(queryBody);
                }
            } while (pageToken != null);

            double cost = estimateCost(queryResult.path("totalBytesProcessed").asLong(0));
            return new SyncResult(-1, totalRows, totalBytes, cost, "completed", null);

        } catch (Exception e) {
            logger.error("BigQuery sync failed: {}", e.getMessage());
            return new SyncResult(-1, 0, 0, 0, "failed", e.getMessage());
        }
    }

    // --- Internal helpers (package-visible for testing) ---

    String buildSyncQuery(String dataset, String table, List<SyncFilter> filters, int limit) {
        StringBuilder sb = new StringBuilder("SELECT * FROM `" + dataset + "." + table + "`");
        if (!filters.isEmpty()) {
            sb.append(" WHERE ");
            sb.append(filters.stream().map(this::filterToSql).collect(Collectors.joining(" AND ")));
        }
        sb.append(" LIMIT ").append(limit);
        return sb.toString();
    }

    String[] parseResource(String resource) {
        String[] parts = resource.split("\\.", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Resource must be dataset.table format: " + resource);
        }
        return parts;
    }

    double estimateCost(long bytes) {
        return (bytes / 1_000_000_000_000.0) * COST_PER_TB;
    }

    private String filterToSql(SyncFilter f) {
        boolean isNumeric = Set.of("INT64", "FLOAT64", "NUMERIC", "BIGNUMERIC", "INTEGER", "FLOAT")
                .contains(f.columnType().toUpperCase());
        if ("IN".equalsIgnoreCase(f.operator())) {
            String vals = Arrays.stream(f.value().split(","))
                    .map(v -> isNumeric ? v.trim() : "'" + v.trim() + "'")
                    .collect(Collectors.joining(","));
            return f.column() + " IN (" + vals + ")";
        }
        if (isNumeric) {
            return f.column() + " " + f.operator() + " " + f.value();
        }
        return f.column() + " " + f.operator() + " '" + f.value() + "'";
    }

    private void ensureLocalDatasetAndTable(String localProject, String dataset, String table,
                                             JsonNode schemaNode) throws Exception {
        // Create dataset
        String dsUrl = localEmulatorBase + "/bigquery/v2/projects/" + localProject + "/datasets";
        try {
            localPost(dsUrl, mapper.writeValueAsString(Map.of(
                    "datasetReference", Map.of("datasetId", dataset, "projectId", localProject))));
        } catch (Exception e) {
            // Dataset may already exist — ignore 409
            logger.debug("Dataset create (may exist): {}", e.getMessage());
        }

        // Create table with schema
        String tblUrl = dsUrl + "/" + dataset + "/tables";
        try {
            localPost(tblUrl, mapper.writeValueAsString(Map.of(
                    "tableReference", Map.of("tableId", table, "datasetId", dataset, "projectId", localProject),
                    "schema", mapper.readTree(mapper.writeValueAsString(schemaNode)))));
        } catch (Exception e) {
            logger.debug("Table create (may exist): {}", e.getMessage());
        }
    }

    private void insertIntoLocalEmulator(String localProject, String dataset, String table,
                                          JsonNode schemaFields, JsonNode rows) throws Exception {
        // Build insertAll request
        List<Map<String, Object>> insertRows = new ArrayList<>();
        for (JsonNode row : rows) {
            Map<String, Object> json = new LinkedHashMap<>();
            JsonNode cells = row.path("f");
            for (int i = 0; i < schemaFields.size() && i < cells.size(); i++) {
                String colName = schemaFields.get(i).path("name").asText();
                String val = cells.get(i).path("v").asText(null);
                json.put(colName, val);
            }
            insertRows.add(Map.of("json", json));
        }

        String url = localEmulatorBase + "/bigquery/v2/projects/" + localProject +
                "/datasets/" + dataset + "/tables/" + table + "/insertAll";
        localPost(url, mapper.writeValueAsString(Map.of("rows", insertRows)));
    }

    private PreviewResult parseQueryResult(String body) throws Exception {
        JsonNode node = mapper.readTree(body);
        List<String> columns = new ArrayList<>();
        JsonNode fields = node.path("schema").path("fields");
        if (fields.isArray()) {
            for (JsonNode f : fields) columns.add(f.path("name").asText());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        JsonNode rowNodes = node.path("rows");
        if (rowNodes.isArray()) {
            for (JsonNode row : rowNodes) {
                Map<String, Object> r = new LinkedHashMap<>();
                JsonNode cells = row.path("f");
                for (int i = 0; i < columns.size() && i < cells.size(); i++) {
                    r.put(columns.get(i), cells.get(i).path("v").asText(null));
                }
                rows.add(r);
            }
        }
        return new PreviewResult(columns, rows,
                node.path("totalRows").asLong(rows.size()),
                node.path("totalBytesProcessed").asLong(0));
    }

    private String gcpGet(String url, String accessToken) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);
        int status = conn.getResponseCode();
        String body = new String(
                (status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream())
                        .readAllBytes(), StandardCharsets.UTF_8);
        conn.disconnect();
        if (status >= 400) throw new IOException("HTTP " + status + ": " + body);
        return body;
    }

    private String gcpPost(String url, String payload, String accessToken) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
        int status = conn.getResponseCode();
        String body = new String(
                (status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream())
                        .readAllBytes(), StandardCharsets.UTF_8);
        conn.disconnect();
        if (status >= 400) throw new IOException("HTTP " + status + ": " + body);
        return body;
    }

    private void localPost(String url, String payload) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
        int status = conn.getResponseCode();
        conn.disconnect();
        if (status >= 400 && status != 409) {
            throw new IOException("Local emulator HTTP " + status);
        }
    }

    private String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
```

**Step 4: Run test to verify it passes**

Run: `cd localcloud-server && ./gradlew test --tests '*BigQuerySyncAdapterTest*' -i`
Expected: PASS (unit tests only test query building and cost calculation — no HTTP calls)

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/sync/adapters/BigQuerySyncAdapter.java \
        localcloud-server/src/test/java/com/localcloud/sync/adapters/BigQuerySyncAdapterTest.java
git commit -m "feat(sync): add BigQuerySyncAdapter with query building, cost estimation, and sync"
```

---

### Task 6: Create remaining SyncAdapters (Firestore, GCS, Spanner, Bigtable)

**Files:**
- Create: `localcloud-server/src/main/java/com/localcloud/sync/adapters/FirestoreSyncAdapter.java`
- Create: `localcloud-server/src/main/java/com/localcloud/sync/adapters/GcsSyncAdapter.java`
- Create: `localcloud-server/src/main/java/com/localcloud/sync/adapters/SpannerSyncAdapter.java`
- Create: `localcloud-server/src/main/java/com/localcloud/sync/adapters/BigtableSyncAdapter.java`
- Tests: corresponding test files per adapter

Each adapter follows the same structure as BigQuerySyncAdapter:
- Unit tests for query/filter building and cost estimation (no HTTP calls)
- `browseRemote()` calls GCP REST API, returns `BrowseResult` with same node structure
- `sync()` pulls from GCP, writes to local emulator

**Key differences per adapter:**

| Adapter | GCP API | Local target | Filter mechanism |
|---------|---------|-------------|-----------------|
| Firestore | `documents:runQuery` REST | Firestore emulator gRPC port 8086 | structuredQuery with fieldFilter |
| GCS | `storage/v1/b/{bucket}/o` REST | Local filesystem + GCS emulator port 4443 | prefix + limit |
| Spanner | `projects/{p}/instances/{i}/databases/{d}/sessions` REST | Spanner emulator port 9010 | SQL WHERE clause |
| Bigtable | `bigtable.googleapis.com/v2` REST | Bigtable emulator port 8087 | RowFilter with row key prefix |

**Step 1:** Write unit tests for each adapter (filter building, cost calculation).
**Step 2:** Implement each adapter following BigQuerySyncAdapter pattern.
**Step 3:** Run all adapter tests.
**Step 4:** Commit per adapter or batch commit.

```bash
git commit -m "feat(sync): add Firestore, GCS, Spanner, Bigtable sync adapters"
```

---

## Phase 3: SyncService Orchestrator & API Endpoints

### Task 7: Create SyncService orchestrator

**Files:**
- Create: `localcloud-server/src/main/java/com/localcloud/sync/SyncService.java`
- Test: `localcloud-server/src/test/java/com/localcloud/sync/SyncServiceTest.java`

**Step 1: Write failing tests**

```java
package com.localcloud.sync;

import com.localcloud.sync.adapters.BigQuerySyncAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

@ExtendWith(MockitoExtension.class)
class SyncServiceTest {

    @Mock private SyncManifestRepository manifestRepo;
    @Mock private SyncCredentialRepository credentialRepo;
    @Mock private SyncAdapter bigqueryAdapter;

    private SyncService service;

    @BeforeEach
    void setUp() {
        service = new SyncService(manifestRepo, credentialRepo, 1.0);
        service.registerAdapter("bigquery", bigqueryAdapter);
    }

    @Test
    void estimate_delegatesToCorrectAdapter() throws Exception {
        when(credentialRepo.getCredentialData("proj")).thenReturn("{\"access_token\":\"tok\"}");
        when(bigqueryAdapter.estimate("remote", "ds.tbl", List.of(), 1000, "tok"))
                .thenReturn(new CostEstimate(1000, 5000, 0.005, "ok"));

        CostEstimate est = service.estimate("proj", "bigquery", "remote", "ds.tbl", List.of(), 1000);
        assertEquals(0.005, est.estimatedCostUsd());
    }

    @Test
    void startSync_rejectsCostAboveCeiling() throws Exception {
        when(credentialRepo.getCredentialData("proj")).thenReturn("{\"access_token\":\"tok\"}");
        when(bigqueryAdapter.estimate("remote", "ds.tbl", List.of(), 1000, "tok"))
                .thenReturn(new CostEstimate(1000, 5000, 2.50, "over limit"));

        assertThrows(IllegalStateException.class,
                () -> service.startSync("proj", "bigquery", "remote", "ds.tbl",
                        List.of(), 1000, null));
    }

    @Test
    void startSync_savesManifest() throws Exception {
        when(credentialRepo.getCredentialData("proj")).thenReturn("{\"access_token\":\"tok\"}");
        when(credentialRepo.getStatus("proj")).thenReturn(
                java.util.Map.of("source_project", "remote"));
        when(bigqueryAdapter.estimate("remote", "ds.tbl", List.of(), 1000, "tok"))
                .thenReturn(new CostEstimate(1000, 5000, 0.005, "ok"));
        when(bigqueryAdapter.sync("remote", "ds.tbl", List.of(), 1000, "tok", "proj", null))
                .thenReturn(new SyncResult(1, 1000, 5000, 0.005, "completed", null));
        when(manifestRepo.save(any())).thenReturn(1);

        SyncResult result = service.startSync("proj", "bigquery", "remote", "ds.tbl",
                List.of(), 1000, null);
        assertEquals("completed", result.status());
        verify(manifestRepo).save(any());
    }

    @Test
    void getAdapter_unknownService_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.estimate("proj", "unknown", "remote", "x", List.of(), 100));
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd localcloud-server && ./gradlew test --tests '*SyncServiceTest*' -i`
Expected: FAIL — class not found

**Step 3: Implement SyncService**

```java
package com.localcloud.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SyncService {

    private static final Logger logger = LoggerFactory.getLogger(SyncService.class);

    private final SyncManifestRepository manifestRepo;
    private final SyncCredentialRepository credentialRepo;
    private final double costCeilingUsd;
    private final Map<String, SyncAdapter> adapters = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    // Track running syncs for progress/cancel
    private final Map<String, SyncProgress> activeSyncs = new ConcurrentHashMap<>();

    public record SyncProgress(long rowsTransferred, long bytesTransferred,
                                long estimatedTotal, int percent, long elapsedMs) {}

    public SyncService(SyncManifestRepository manifestRepo,
                       SyncCredentialRepository credentialRepo,
                       double costCeilingUsd) {
        this.manifestRepo = manifestRepo;
        this.credentialRepo = credentialRepo;
        this.costCeilingUsd = costCeilingUsd;
    }

    public void registerAdapter(String serviceId, SyncAdapter adapter) {
        adapters.put(serviceId, adapter);
    }

    public BrowseResult browseRemote(String projectId, String serviceId) throws Exception {
        String token = getAccessToken(projectId);
        String sourceProject = getSourceProject(projectId);
        return getAdapter(serviceId).browseRemote(sourceProject, token);
    }

    public PreviewResult previewRemote(String projectId, String serviceId,
                                        String resource, int limit) throws Exception {
        String token = getAccessToken(projectId);
        String sourceProject = getSourceProject(projectId);
        return getAdapter(serviceId).previewRemote(sourceProject, resource, token, limit);
    }

    public CostEstimate estimate(String projectId, String serviceId, String sourceProject,
                                  String resource, List<SyncFilter> filters, int rowLimit) throws Exception {
        String token = getAccessToken(projectId);
        return getAdapter(serviceId).estimate(sourceProject, resource, filters, rowLimit, token);
    }

    public SyncResult startSync(String projectId, String serviceId, String sourceProject,
                                 String resource, List<SyncFilter> filters, int rowLimit,
                                 SyncProgressCallback externalCallback) throws Exception {
        String token = getAccessToken(projectId);

        // Cost check
        CostEstimate est = getAdapter(serviceId).estimate(sourceProject, resource, filters, rowLimit, token);
        if (est.estimatedCostUsd() > costCeilingUsd) {
            throw new IllegalStateException(String.format(
                    "Estimated cost $%.4f exceeds ceiling $%.2f. Raise limit in Settings.",
                    est.estimatedCostUsd(), costCeilingUsd));
        }

        // Save initial manifest
        String filtersJson = mapper.writeValueAsString(filters);
        int manifestId = manifestRepo.save(new SyncManifest(
                projectId, serviceId, resource, sourceProject,
                filtersJson, 0, 0, est.estimatedCostUsd(), "in_progress", null));

        String syncKey = projectId + ":" + serviceId + ":" + resource;
        long startTime = System.currentTimeMillis();

        try {
            SyncProgressCallback progressTracker = (rows, bytes, total) -> {
                long elapsed = System.currentTimeMillis() - startTime;
                int pct = total > 0 ? (int) (rows * 100 / total) : 0;
                activeSyncs.put(syncKey, new SyncProgress(rows, bytes, total, pct, elapsed));
                try {
                    manifestRepo.updateProgress(manifestId, "in_progress", rows, bytes, null);
                } catch (Exception e) {
                    logger.warn("Failed to update progress: {}", e.getMessage());
                }
                if (externalCallback != null) externalCallback.onProgress(rows, bytes, total);
            };

            SyncResult result = getAdapter(serviceId).sync(
                    sourceProject, resource, filters, rowLimit, token, projectId, progressTracker);

            manifestRepo.updateProgress(manifestId, result.status(),
                    result.rowsSynced(), result.bytesSynced(), result.errorMessage());

            return new SyncResult(manifestId, result.rowsSynced(), result.bytesSynced(),
                    result.costIncurred(), result.status(), result.errorMessage());
        } catch (Exception e) {
            manifestRepo.updateProgress(manifestId, "failed", 0, 0, e.getMessage());
            throw e;
        } finally {
            activeSyncs.remove(syncKey);
        }
    }

    public SyncProgress getProgress(String projectId, String serviceId, String resource) {
        return activeSyncs.get(projectId + ":" + serviceId + ":" + resource);
    }

    public List<Map<String, Object>> getManifests(String projectId) throws Exception {
        return manifestRepo.getAll(projectId);
    }

    public List<Map<String, Object>> getManifests(String projectId, String serviceId) throws Exception {
        return manifestRepo.getByService(projectId, serviceId);
    }

    public void deleteManifest(int id) throws Exception {
        manifestRepo.delete(id);
    }

    private SyncAdapter getAdapter(String serviceId) {
        SyncAdapter adapter = adapters.get(serviceId);
        if (adapter == null) {
            throw new IllegalArgumentException("No sync adapter for service: " + serviceId);
        }
        return adapter;
    }

    private String getAccessToken(String projectId) throws Exception {
        String data = credentialRepo.getCredentialData(projectId);
        if (data == null) {
            throw new IllegalStateException("No credentials configured. Connect to a GCP project first.");
        }
        JsonNode node = mapper.readTree(data);
        String token = node.has("access_token") ? node.get("access_token").asText() : null;
        if (token == null) {
            throw new IllegalStateException("No access token available. Re-authenticate.");
        }
        return token;
    }

    private String getSourceProject(String projectId) throws Exception {
        var status = credentialRepo.getStatus(projectId);
        if (status == null) {
            throw new IllegalStateException("No credentials configured.");
        }
        return status.get("source_project");
    }
}
```

**Step 4: Run test to verify it passes**

Run: `cd localcloud-server && ./gradlew test --tests '*SyncServiceTest*' -i`
Expected: PASS

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/sync/SyncService.java \
        localcloud-server/src/test/java/com/localcloud/sync/SyncServiceTest.java
git commit -m "feat(sync): add SyncService orchestrator with cost ceiling and progress tracking"
```

---

### Task 8: Create SyncApiService (REST endpoints) and wire in LocalCloudApplication

**Files:**
- Create: `localcloud-server/src/main/java/com/localcloud/sync/SyncApiService.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java`
- Test: `localcloud-server/src/test/java/com/localcloud/sync/SyncApiServiceTest.java`

**Step 1: Write failing test**

```java
package com.localcloud.sync;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SyncApiServiceTest {

    @Test
    void classExists() throws Exception {
        Class.forName("com.localcloud.sync.SyncApiService");
    }
}
```

**Step 2: Implement SyncApiService**

Armeria annotated service at `/_localcloud/sync` prefix. Follows exact same pattern as `AdminApiService`:

```java
package com.localcloud.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.*;
import com.localcloud.config.LocalCloudConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class SyncApiService {

    private static final Logger logger = LoggerFactory.getLogger(SyncApiService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final SyncService syncService;
    private final SyncCredentialRepository credentialRepo;
    private final LocalCloudConfig config;

    public SyncApiService(SyncService syncService, SyncCredentialRepository credentialRepo,
                          LocalCloudConfig config) {
        this.syncService = syncService;
        this.credentialRepo = credentialRepo;
        this.config = config;
    }

    // --- Auth endpoints ---

    @Get("/auth/status")
    public HttpResponse authStatus(ServiceRequestContext ctx) {
        try {
            String project = resolveProject(ctx);
            Map<String, String> status = credentialRepo.getStatus(project);
            if (status == null) {
                return jsonResponse(Map.of("connected", false));
            }
            return jsonResponse(status);
        } catch (Exception e) {
            return errorResponse(e);
        }
    }

    @Post("/auth/connect")
    public HttpResponse authConnect(ServiceRequestContext ctx, AggregatedHttpRequest req) {
        try {
            String project = resolveProject(ctx);
            Map<String, Object> body = mapper.readValue(req.contentUtf8(), Map.class);
            String sourceProject = (String) body.get("source_project");
            String authMethod = (String) body.get("auth_method");
            String credentialData = mapper.writeValueAsString(body.get("credential_data"));

            credentialRepo.save(project, sourceProject, authMethod, credentialData);
            return jsonResponse(Map.of("connected", true, "source_project", sourceProject));
        } catch (Exception e) {
            return errorResponse(e);
        }
    }

    @Post("/auth/disconnect")
    public HttpResponse authDisconnect(ServiceRequestContext ctx) {
        try {
            credentialRepo.delete(resolveProject(ctx));
            return jsonResponse(Map.of("connected", false));
        } catch (Exception e) {
            return errorResponse(e);
        }
    }

    // --- Browse remote ---

    @Get("/{service}/browse")
    public HttpResponse browseRemote(ServiceRequestContext ctx, @Param("service") String service) {
        try {
            BrowseResult result = syncService.browseRemote(resolveProject(ctx), service);
            return jsonResponse(result);
        } catch (Exception e) {
            return errorResponse(e);
        }
    }

    @Get("/{service}/preview")
    public HttpResponse previewRemote(ServiceRequestContext ctx, @Param("service") String service) {
        try {
            String resource = ctx.queryParams().get("resource");
            int limit = Integer.parseInt(ctx.queryParams().get("limit", "5"));
            PreviewResult result = syncService.previewRemote(resolveProject(ctx), service, resource, limit);
            return jsonResponse(result);
        } catch (Exception e) {
            return errorResponse(e);
        }
    }

    // --- Sync operations ---

    @Post("/{service}/estimate")
    public HttpResponse estimate(ServiceRequestContext ctx, @Param("service") String service,
                                  AggregatedHttpRequest req) {
        try {
            Map<String, Object> body = mapper.readValue(req.contentUtf8(), Map.class);
            String resource = (String) body.get("resource");
            String sourceProject = (String) body.get("source_project");
            int rowLimit = ((Number) body.getOrDefault("row_limit", 1000000)).intValue();
            List<SyncFilter> filters = parseFilters(body.get("filters"));

            CostEstimate est = syncService.estimate(resolveProject(ctx), service,
                    sourceProject, resource, filters, rowLimit);
            return jsonResponse(est);
        } catch (Exception e) {
            return errorResponse(e);
        }
    }

    @Post("/{service}/start")
    public HttpResponse startSync(ServiceRequestContext ctx, @Param("service") String service,
                                   AggregatedHttpRequest req) {
        try {
            Map<String, Object> body = mapper.readValue(req.contentUtf8(), Map.class);
            String resource = (String) body.get("resource");
            String sourceProject = (String) body.get("source_project");
            int rowLimit = ((Number) body.getOrDefault("row_limit", 1000000)).intValue();
            List<SyncFilter> filters = parseFilters(body.get("filters"));

            SyncResult result = syncService.startSync(resolveProject(ctx), service,
                    sourceProject, resource, filters, rowLimit, null);
            return jsonResponse(result);
        } catch (Exception e) {
            return errorResponse(e);
        }
    }

    // --- Manifests ---

    @Get("/manifests")
    public HttpResponse manifests(ServiceRequestContext ctx) {
        try {
            return jsonResponse(syncService.getManifests(resolveProject(ctx)));
        } catch (Exception e) {
            return errorResponse(e);
        }
    }

    @Get("/{service}/manifests")
    public HttpResponse serviceManifests(ServiceRequestContext ctx, @Param("service") String service) {
        try {
            return jsonResponse(syncService.getManifests(resolveProject(ctx), service));
        } catch (Exception e) {
            return errorResponse(e);
        }
    }

    @Delete("/manifests/{id}")
    public HttpResponse deleteManifest(@Param("id") int id) {
        try {
            syncService.deleteManifest(id);
            return jsonResponse(Map.of("deleted", true));
        } catch (Exception e) {
            return errorResponse(e);
        }
    }

    // --- Helpers ---

    private String resolveProject(ServiceRequestContext ctx) {
        String project = ctx.queryParams().get("project");
        return (project != null && !project.isBlank()) ? project : config.getProjectId();
    }

    @SuppressWarnings("unchecked")
    private List<SyncFilter> parseFilters(Object filtersObj) {
        if (filtersObj == null) return List.of();
        List<Map<String, String>> raw = (List<Map<String, String>>) filtersObj;
        return raw.stream().map(f -> new SyncFilter(
                f.get("column"), f.get("operator"), f.get("value"),
                f.getOrDefault("columnType", "STRING")
        )).toList();
    }

    private HttpResponse jsonResponse(Object data) {
        try {
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writeValueAsString(data));
        } catch (Exception e) {
            return errorResponse(e);
        }
    }

    private HttpResponse errorResponse(Exception e) {
        logger.error("Sync API error: {}", e.getMessage());
        return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON,
                "{\"error\":true,\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}");
    }
}
```

**Step 3: Wire in LocalCloudApplication.java**

Add after existing admin service registrations (~line 156):

```java
// Data Mirror sync service
SyncManifestRepository syncManifestRepo = new SyncManifestRepository(dataSource.getDataSource());
SyncCredentialRepository syncCredentialRepo = new SyncCredentialRepository(dataSource.getDataSource());
SyncService syncService = new SyncService(syncManifestRepo, syncCredentialRepo, 1.0);

// Register adapters
ObjectMapper syncMapper = new ObjectMapper();
syncService.registerAdapter("bigquery", new BigQuerySyncAdapter(
        "http://localhost:" + registry.getService("bigquery").port(), syncMapper));
// TODO: register other adapters as they are implemented

SyncApiService syncApiService = new SyncApiService(syncService, syncCredentialRepo, config);
sb.annotatedService("/_localcloud/sync", syncApiService);
```

**Step 4: Run tests**

Run: `cd localcloud-server && ./gradlew test -i`
Expected: All tests pass

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/sync/SyncApiService.java \
        localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java \
        localcloud-server/src/test/java/com/localcloud/sync/SyncApiServiceTest.java
git commit -m "feat(sync): add SyncApiService REST endpoints, wire into LocalCloudApplication"
```

---

## Phase 4: Console Frontend

### Task 9: Add sync API methods to api.js

**Files:**
- Modify: `localcloud-console/src/api.js`

**Step 1: Add sync methods to api object**

Add to the existing `api` export:

```javascript
// Data Mirror sync
syncAuthStatus:     ()           => get(appendProject('/_localcloud/sync/auth/status')),
syncConnect:        (body)       => postJson(appendProject('/_localcloud/sync/auth/connect'), body),
syncDisconnect:     ()           => post(appendProject('/_localcloud/sync/auth/disconnect')),
syncBrowse:         (service)    => get(appendProject(`/_localcloud/sync/${service}/browse`)),
syncPreview:        (service, resource, limit = 5) =>
    get(appendProject(`/_localcloud/sync/${service}/preview`) + `&resource=${encodeURIComponent(resource)}&limit=${limit}`),
syncEstimate:       (service, body) => postJson(appendProject(`/_localcloud/sync/${service}/estimate`), body),
syncStart:          (service, body) => postJson(appendProject(`/_localcloud/sync/${service}/start`), body),
syncManifests:      ()           => get(appendProject('/_localcloud/sync/manifests')),
syncServiceManifests: (service)  => get(appendProject(`/_localcloud/sync/${service}/manifests`)),
syncDeleteManifest: (id)         => del(appendProject(`/_localcloud/sync/manifests/${id}`)),
```

**Step 2: Build and verify**

Run: `cd localcloud-console && npm run build`
Expected: Build succeeds

**Step 3: Commit**

```bash
git add localcloud-console/src/api.js
git commit -m "feat(console): add Data Mirror sync API methods"
```

---

### Task 10: Add "Remote Sync" mode tab to ServiceExplorer

**Files:**
- Modify: `localcloud-console/src/pages/ServiceExplorer.jsx`

**Step 1: Add third mode tab**

In the mode bar section, add a "Remote Sync" tab alongside existing "SQL Editor" and "Data Explorer":

```jsx
<button class={`se-mode-tab ${mode() === 'sync' ? 'active' : ''}`}
        onClick={() => setMode('sync')}>
    Remote Sync
</button>
```

Add the panel toggle:

```jsx
<div style={{ display: mode() === 'sync' ? '' : 'none' }}>
    <RemoteSyncPanel serviceId={activeService()} activeProject={props.activeProject} />
</div>
```

Import `RemoteSyncPanel` (created in next task).

**Step 2: Build and verify**

Run: `cd localcloud-console && npm run build`
Expected: Build succeeds (RemoteSyncPanel may be a placeholder initially)

**Step 3: Commit**

```bash
git add localcloud-console/src/pages/ServiceExplorer.jsx
git commit -m "feat(console): add Remote Sync mode tab to ServiceExplorer"
```

---

### Task 11: Create SchemaExplorer shared component

**Files:**
- Create: `localcloud-console/src/components/SchemaExplorer.jsx`

**Step 1: Extract schema tree from SQL Editor into reusable component**

The SQL Editor in `ServiceExplorer.jsx` has an inline schema tree (`.sql-explorer` section). Extract it into a standalone component that accepts `source` prop:

```jsx
// SchemaExplorer.jsx
import { createSignal, createEffect, For, Show } from 'solid-js';
import { api } from '../api.js';

export function SchemaExplorer(props) {
    // props: source ("local"|"remote"), serviceId, onSelect, syncManifests
    const [schema, setSchema] = createSignal(null);
    const [expanded, setExpanded] = createSignal({});
    const [selected, setSelected] = createSignal(null);
    const [loading, setLoading] = createSignal(false);
    const [error, setError] = createSignal(null);

    // Load schema when service changes
    createEffect(async () => {
        const svc = props.serviceId;
        if (!svc) return;
        setLoading(true);
        setError(null);
        try {
            let data;
            if (props.source === 'remote') {
                data = await api.syncBrowse(svc);
            } else {
                data = await api.schema(svc);
            }
            setSchema(data);
        } catch (e) {
            setError(e.message);
        } finally {
            setLoading(false);
        }
    });

    const toggle = (key) => setExpanded(prev => ({ ...prev, [key]: !prev[key] }));

    const select = (node) => {
        setSelected(node.id);
        props.onSelect?.(node);
    };

    const getSyncBadge = (resourceId) => {
        if (!props.syncManifests) return null;
        const manifest = props.syncManifests()?.find(m => m.resource_path === resourceId);
        if (!manifest) return null;
        const age = Date.now() - new Date(manifest.synced_at).getTime();
        const stale = age > 24 * 60 * 60 * 1000;
        return stale ? '⚠' : '✓';
    };

    return (
        <div class="schema-explorer">
            <div class="schema-explorer-header">
                <span class={`schema-source-badge ${props.source}`}>
                    {props.source === 'remote' ? '☁ REMOTE' : '💾 LOCAL'}
                </span>
            </div>

            <Show when={loading()}>
                <div class="loading-state"><div class="loading-spinner" /></div>
            </Show>

            <Show when={error()}>
                <div class="alert alert-error">{error()}</div>
            </Show>

            <Show when={schema()}>
                <div class="schema-explorer-tree">
                    {/* Render tree based on schema data */}
                    {/* Reuses .tree-row, .tree-row-db, .tree-row-tbl, .tree-row-col CSS classes */}
                    <For each={schema().nodes || []}>
                        {(node) => (
                            <div>
                                <div class={`tree-row tree-row-db ${expanded[node.id] ? 'expanded' : ''}`}
                                     onClick={() => toggle(node.id)}>
                                    <span class="tree-chevron">{expanded()[node.id] ? '▼' : '▸'}</span>
                                    <span class="tree-name">{node.name}</span>
                                    <span class="tree-badge">{node.children?.length || 0} tbl</span>
                                </div>
                                <Show when={expanded()[node.id]}>
                                    <For each={node.children || []}>
                                        {(table) => (
                                            <div>
                                                <div class={`tree-row tree-row-tbl ${selected() === table.id ? 'selected' : ''}`}
                                                     onClick={() => select(table)}>
                                                    <span class="tree-name">{table.name}</span>
                                                    <span class="tree-badge">
                                                        {table.metadata?.rowCount ? formatNumber(table.metadata.rowCount) + ' rows' : ''}
                                                    </span>
                                                    <Show when={getSyncBadge(table.id)}>
                                                        <span class={`sync-badge ${getSyncBadge(table.id) === '✓' ? 'synced' : 'stale'}`}>
                                                            {getSyncBadge(table.id)}
                                                        </span>
                                                    </Show>
                                                </div>
                                                <Show when={selected() === table.id && table.schema}>
                                                    <For each={table.schema}>
                                                        {(col) => (
                                                            <div class="tree-row tree-row-col">
                                                                <span class="tree-col-name">{col.name}</span>
                                                                <span class="tree-col-type">{col.type}</span>
                                                            </div>
                                                        )}
                                                    </For>
                                                </Show>
                                            </div>
                                        )}
                                    </For>
                                </Show>
                            </div>
                        )}
                    </For>
                </div>
            </Show>
        </div>
    );
}

function formatNumber(n) {
    if (n >= 1_000_000_000) return (n / 1_000_000_000).toFixed(1) + 'B';
    if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
    if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K';
    return String(n);
}
```

**Step 2: Add CSS for schema-explorer and sync badges**

Add to `localcloud-console/src/styles/components.css`:

```css
/* Schema Explorer */
.schema-explorer { display: flex; flex-direction: column; height: 100%; }
.schema-explorer-header { padding: 8px 12px; border-bottom: 1px solid var(--border); }
.schema-source-badge { font-size: 11px; font-weight: 600; text-transform: uppercase; }
.schema-source-badge.remote { color: var(--primary); }
.schema-source-badge.local { color: var(--text-secondary); }
.schema-explorer-tree { flex: 1; overflow-y: auto; padding: 4px 0; }

.sync-badge { font-size: 11px; margin-left: 4px; }
.sync-badge.synced { color: var(--success, #34a853); }
.sync-badge.stale { color: var(--warning, #fbbc04); }

.tree-row.selected { background: var(--hover); }
```

**Step 3: Build and verify**

Run: `cd localcloud-console && npm run build`
Expected: Build succeeds

**Step 4: Commit**

```bash
git add localcloud-console/src/components/SchemaExplorer.jsx \
        localcloud-console/src/styles/components.css
git commit -m "feat(console): add reusable SchemaExplorer component with local/remote source"
```

---

### Task 12: Create RemoteSyncPanel (main Remote Sync tab content)

**Files:**
- Create: `localcloud-console/src/components/RemoteSyncPanel.jsx`

**Step 1: Create the split-layout panel**

This is the main component for the Remote Sync tab. Left panel: SchemaExplorer (remote) + sync history. Right panel: preview / sync form / progress.

```jsx
import { createSignal, createEffect, Show, For } from 'solid-js';
import { api } from '../api.js';
import { SchemaExplorer } from './SchemaExplorer.jsx';
import { SyncFilterBuilder } from './SyncFilterBuilder.jsx';

export function RemoteSyncPanel(props) {
    // props: serviceId, activeProject
    const [connected, setConnected] = createSignal(false);
    const [authStatus, setAuthStatus] = createSignal(null);
    const [selectedResource, setSelectedResource] = createSignal(null);
    const [preview, setPreview] = createSignal(null);
    const [syncManifests, setSyncManifests] = createSignal([]);
    const [rightPanel, setRightPanel] = createSignal('preview'); // preview|sync|progress|history
    const [syncProgress, setSyncProgress] = createSignal(null);
    const [costEstimate, setCostEstimate] = createSignal(null);
    const [filters, setFilters] = createSignal([]);
    const [rowLimit, setRowLimit] = createSignal(1000000);

    // Check auth status
    createEffect(async () => {
        try {
            const status = await api.syncAuthStatus();
            setAuthStatus(status);
            setConnected(status.connected === true || status.connected === 'true');
        } catch (e) {
            setConnected(false);
        }
    });

    // Load manifests for this service
    createEffect(async () => {
        if (!connected()) return;
        try {
            const manifests = await api.syncServiceManifests(props.serviceId);
            setSyncManifests(manifests);
        } catch (e) { /* ignore */ }
    });

    // On resource select: load preview
    const handleResourceSelect = async (node) => {
        setSelectedResource(node);
        setRightPanel('preview');
        try {
            const result = await api.syncPreview(props.serviceId, node.id, 5);
            setPreview(result);
        } catch (e) {
            setPreview(null);
        }
    };

    // Start sync form
    const showSyncForm = () => setRightPanel('sync');

    // Estimate cost
    const estimateCost = async () => {
        const res = selectedResource();
        if (!res) return;
        try {
            const est = await api.syncEstimate(props.serviceId, {
                resource: res.id,
                source_project: authStatus()?.source_project,
                filters: filters(),
                row_limit: rowLimit()
            });
            setCostEstimate(est);
        } catch (e) {
            setCostEstimate({ error: e.message });
        }
    };

    // Execute sync
    const startSync = async () => {
        const res = selectedResource();
        if (!res) return;
        setRightPanel('progress');
        setSyncProgress({ percent: 0, rowsTransferred: 0 });
        try {
            const result = await api.syncStart(props.serviceId, {
                resource: res.id,
                source_project: authStatus()?.source_project,
                filters: filters(),
                row_limit: rowLimit()
            });
            setSyncProgress({ percent: 100, ...result });
            // Refresh manifests
            const manifests = await api.syncServiceManifests(props.serviceId);
            setSyncManifests(manifests);
            setRightPanel('preview');
        } catch (e) {
            setSyncProgress({ error: e.message });
        }
    };

    // Connect handler
    const handleConnect = async (sourceProject, authMethod, credentialData) => {
        await api.syncConnect({ source_project: sourceProject, auth_method: authMethod,
                                credential_data: credentialData });
        const status = await api.syncAuthStatus();
        setAuthStatus(status);
        setConnected(true);
    };

    return (
        <div class="sync-panel">
            <Show when={!connected()} fallback={
                <div class="sync-split">
                    {/* LEFT PANEL */}
                    <div class="sync-left">
                        <SchemaExplorer source="remote" serviceId={props.serviceId}
                                        onSelect={handleResourceSelect}
                                        syncManifests={syncManifests} />
                        <div class="sync-history-section">
                            <div class="sync-history-header">SYNC HISTORY</div>
                            <For each={syncManifests()}>
                                {(m) => (
                                    <div class="sync-history-item"
                                         onClick={() => { setSelectedResource(m); setRightPanel('history-detail'); }}>
                                        <div class="sync-history-name">{m.resource_path}</div>
                                        <div class="sync-history-meta">
                                            {formatNumber(m.row_count)} rows · {timeAgo(m.synced_at)}
                                            <span class={`sync-badge ${m.status === 'completed' ? 'synced' : ''}`}>
                                                {m.status === 'completed' ? '✓' : m.status}
                                            </span>
                                        </div>
                                    </div>
                                )}
                            </For>
                        </div>
                    </div>

                    {/* RIGHT PANEL */}
                    <div class="sync-right">
                        {/* Content based on rightPanel state */}
                        {/* preview, sync form, progress, history-detail */}
                        {/* Implementation follows design wireframes from design doc */}
                    </div>
                </div>
            }>
                {/* Not connected state */}
                <div class="sync-not-connected">
                    <h3>☁ Not Connected</h3>
                    <p>Connect to a GCP project to browse and sync remote data.</p>
                    <ConnectForm onConnect={handleConnect} />
                </div>
            </Show>
        </div>
    );
}

// ConnectForm, formatNumber, timeAgo helper functions...
```

This is a skeleton — full right panel content (preview table, sync form with SyncFilterBuilder, progress bar, history detail) follows the design wireframes from the design doc. Each state of `rightPanel` renders the corresponding UI.

**Step 2: Add CSS for sync panel layout**

```css
/* Sync Panel — split layout */
.sync-panel { height: 100%; }
.sync-split { display: flex; height: 100%; }
.sync-left { width: 300px; border-right: 1px solid var(--border); display: flex; flex-direction: column; }
.sync-right { flex: 1; padding: 16px; overflow-y: auto; }
.sync-history-section { border-top: 1px solid var(--border); padding: 8px 0; max-height: 200px; overflow-y: auto; }
.sync-history-header { padding: 8px 12px; font-size: 11px; font-weight: 600; color: var(--text-secondary); }
.sync-history-item { padding: 8px 12px; cursor: pointer; }
.sync-history-item:hover { background: var(--hover); }
.sync-history-name { font-size: 13px; font-weight: 500; }
.sync-history-meta { font-size: 11px; color: var(--text-secondary); }
.sync-not-connected { display: flex; flex-direction: column; align-items: center;
                       justify-content: center; height: 100%; text-align: center; }

/* Progress bar */
.sync-progress-bar { height: 6px; background: var(--border); border-radius: 3px; overflow: hidden; }
.sync-progress-fill { height: 100%; background: var(--primary); transition: width 0.3s; }
```

**Step 3: Build and verify**

Run: `cd localcloud-console && npm run build`
Expected: Build succeeds

**Step 4: Commit**

```bash
git add localcloud-console/src/components/RemoteSyncPanel.jsx \
        localcloud-console/src/styles/components.css
git commit -m "feat(console): add RemoteSyncPanel with split layout, preview, and sync flow"
```

---

### Task 13: Create SyncFilterBuilder component

**Files:**
- Create: `localcloud-console/src/components/SyncFilterBuilder.jsx`

**Step 1: Create schema-aware filter builder**

```jsx
import { createSignal, For, Show } from 'solid-js';

const OPERATORS_BY_TYPE = {
    STRING:    ['=', '!=', 'LIKE', 'IN'],
    TIMESTAMP: ['>=', '<=', '=', 'BETWEEN'],
    DATE:      ['>=', '<=', '=', 'BETWEEN'],
    INT64:     ['=', '!=', '>', '<', '>=', '<='],
    FLOAT64:   ['=', '!=', '>', '<', '>=', '<='],
    INTEGER:   ['=', '!=', '>', '<', '>=', '<='],
    FLOAT:     ['=', '!=', '>', '<', '>=', '<='],
    NUMERIC:   ['=', '!=', '>', '<', '>=', '<='],
    BOOL:      ['='],
};

export function SyncFilterBuilder(props) {
    // props: schema (array of {name, type}), onChange (filters => void)
    const [filters, setFilters] = createSignal([]);

    const addFilter = () => {
        const schema = props.schema || [];
        if (schema.length === 0) return;
        const newFilter = { column: schema[0].name, operator: '=', value: '',
                            columnType: schema[0].type };
        const updated = [...filters(), newFilter];
        setFilters(updated);
        props.onChange?.(updated);
    };

    const updateFilter = (index, field, value) => {
        const updated = filters().map((f, i) => {
            if (i !== index) return f;
            const newFilter = { ...f, [field]: value };
            // If column changed, update type and reset operator
            if (field === 'column') {
                const col = props.schema?.find(c => c.name === value);
                if (col) {
                    newFilter.columnType = col.type;
                    const ops = OPERATORS_BY_TYPE[col.type] || OPERATORS_BY_TYPE.STRING;
                    if (!ops.includes(newFilter.operator)) newFilter.operator = ops[0];
                }
            }
            return newFilter;
        });
        setFilters(updated);
        props.onChange?.(updated);
    };

    const removeFilter = (index) => {
        const updated = filters().filter((_, i) => i !== index);
        setFilters(updated);
        props.onChange?.(updated);
    };

    return (
        <div class="filter-builder">
            <For each={filters()}>
                {(filter, index) => {
                    const ops = () => OPERATORS_BY_TYPE[filter.columnType] || OPERATORS_BY_TYPE.STRING;
                    return (
                        <div class="filter-row">
                            <select class="filter-select" value={filter.column}
                                    onChange={e => updateFilter(index(), 'column', e.target.value)}>
                                <For each={props.schema || []}>
                                    {(col) => <option value={col.name}>{col.name}</option>}
                                </For>
                            </select>
                            <select class="filter-select filter-op" value={filter.operator}
                                    onChange={e => updateFilter(index(), 'operator', e.target.value)}>
                                <For each={ops()}>
                                    {(op) => <option value={op}>{op}</option>}
                                </For>
                            </select>
                            <input class="filter-input form-input" type="text" value={filter.value}
                                   onInput={e => updateFilter(index(), 'value', e.target.value)}
                                   placeholder="value" />
                            <button class="btn btn-icon filter-remove" onClick={() => removeFilter(index())}>×</button>
                        </div>
                    );
                }}
            </For>
            <button class="btn btn-secondary filter-add" onClick={addFilter}>+ Add filter</button>
        </div>
    );
}
```

**Step 2: Add CSS**

```css
.filter-builder { display: flex; flex-direction: column; gap: 8px; }
.filter-row { display: flex; gap: 4px; align-items: center; }
.filter-select { padding: 6px 8px; border: 1px solid var(--border); border-radius: 4px;
                  background: var(--surface); color: var(--text); font-size: 13px; }
.filter-op { width: 70px; }
.filter-input { flex: 1; }
.filter-remove { padding: 4px 8px; font-size: 16px; color: var(--text-secondary); }
.filter-add { align-self: flex-start; font-size: 13px; }
```

**Step 3: Build and verify**

Run: `cd localcloud-console && npm run build`
Expected: Build succeeds

**Step 4: Commit**

```bash
git add localcloud-console/src/components/SyncFilterBuilder.jsx \
        localcloud-console/src/styles/components.css
git commit -m "feat(console): add SyncFilterBuilder with schema-aware operators"
```

---

## Phase 5: Integration & Polish

### Task 14: Add auth connect UI (inline in Remote Sync + Settings)

**Files:**
- Create: `localcloud-console/src/components/ConnectForm.jsx`
- Modify: `localcloud-console/src/pages/Settings.jsx`

Implement the connect form with:
- "Sign in with Google" button (opens OAuth URL in new tab)
- Service Account key upload (drag & drop / file picker)
- Project dropdown (auto-populated after auth)
- Connection test + status display

Follow the design doc Section 6 wireframes.

**Commit:** `feat(console): add GCP connection form for Data Mirror auth`

---

### Task 15: Wire full right panel states in RemoteSyncPanel

**Files:**
- Modify: `localcloud-console/src/components/RemoteSyncPanel.jsx`

Complete all right panel states:
1. **Preview** — data table with first 5 rows + "Sync to Local" button
2. **Sync form** — SyncFilterBuilder + row limit + cost estimate + Start Sync button
3. **Progress** — progress bar with rows/bytes/elapsed/ETA + Cancel button
4. **History detail** — manifest info + Resync/Remove buttons

Follow design doc Section 3 wireframes. Use existing CSS classes (`.data-table`, `.btn-primary`, `.card`).

**Commit:** `feat(console): complete RemoteSyncPanel with all right panel states`

---

### Task 16: Refactor SQL Editor to use shared SchemaExplorer

**Files:**
- Modify: `localcloud-console/src/pages/ServiceExplorer.jsx`

Replace the inline schema tree in the SQL Editor section with `<SchemaExplorer source="local" />`. Verify SQL Editor still works — schema selection should fire the same `onSelect` callback that populates query suggestions.

**Commit:** `refactor(console): use shared SchemaExplorer in SQL Editor`

---

### Task 17: OAuth callback endpoint

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/sync/SyncApiService.java`

Add OAuth endpoints:
- `POST /_localcloud/sync/auth/start` — generates Google OAuth URL with `cloud-platform.read-only` scope, returns URL
- `GET /_localcloud/sync/auth/callback` — handles redirect, exchanges code for tokens, stores in DB, returns HTML "Connected!" page
- `POST /_localcloud/sync/auth/refresh` — refreshes access token using refresh_token
- `GET /_localcloud/sync/auth/projects` — lists GCP projects using token (Cloud Resource Manager API)

**Commit:** `feat(sync): add OAuth flow endpoints for Google sign-in`

---

### Task 18: End-to-end integration test

**Files:**
- Create: `localcloud-server/src/test/java/com/localcloud/sync/SyncIntegrationTest.java`

Spin up BigQuery emulator twice (different ports) — one as "remote source", one as "local target". Seed remote with test data. Execute sync via `SyncService`. Verify data appears in local emulator via browse API.

**Commit:** `test(sync): add end-to-end sync integration test`

---

### Task 19: Full build verification

**Step 1:** Run all Java tests
```bash
cd localcloud-server && ./gradlew test -i
```

**Step 2:** Build console
```bash
cd localcloud-console && npm run build
```

**Step 3:** Build Docker image
```bash
cd localcloud-server && ./gradlew shadowJar
docker compose build
```

**Step 4:** Manual smoke test
```bash
docker compose up -d
# Open http://localhost:8080
# Navigate to BigQuery > Remote Sync tab
# Verify "Not Connected" state renders
# Verify auth form appears
```

**Commit:** `chore: verify full build with Data Mirror feature`

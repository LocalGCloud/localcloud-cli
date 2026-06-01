package com.localcloud.sync;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class SyncManifestRepositoryTest {

    private DataSource dataSource;
    private Connection connection;
    private PreparedStatement deleteStmt;
    private PreparedStatement insertStmt;
    private PreparedStatement selectStmt;
    private PreparedStatement updateStmt;
    private SyncManifestRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        deleteStmt = mock(PreparedStatement.class);
        insertStmt = mock(PreparedStatement.class);
        selectStmt = mock(PreparedStatement.class);
        updateStmt = mock(PreparedStatement.class);

        when(dataSource.getConnection()).thenReturn(connection);

        repo = new SyncManifestRepository(dataSource);
    }

    // --- save ---

    @Test
    void save_insertsManifest() throws SQLException {
        when(connection.prepareStatement(contains("DELETE"))).thenReturn(deleteStmt);
        when(connection.prepareStatement(contains("INSERT"), eq(java.sql.Statement.RETURN_GENERATED_KEYS)))
                .thenReturn(insertStmt);

        ResultSet generatedKeys = mock(ResultSet.class);
        when(insertStmt.getGeneratedKeys()).thenReturn(generatedKeys);
        when(generatedKeys.next()).thenReturn(true);
        when(generatedKeys.getInt(1)).thenReturn(42);

        SyncManifest manifest = new SyncManifest(
                "my-project", "bigquery", "dataset.table",
                "prod-project", "[{\"column\":\"date\",\"op\":\">\",\"value\":\"2026-01-01\"}]",
                0, 0, 0.0, "pending", null);

        int id = repo.save(manifest);

        assertEquals(42, id);

        // Verify transaction behavior
        verify(connection).setAutoCommit(false);
        verify(connection).commit();
        verify(connection).setAutoCommit(true);

        // Verify delete targets the unique constraint columns
        verify(deleteStmt).setString(1, "my-project");
        verify(deleteStmt).setString(2, "bigquery");
        verify(deleteStmt).setString(3, "dataset.table");
        verify(deleteStmt).executeUpdate();

        // Verify insert with all fields
        verify(insertStmt).setString(1, "my-project");
        verify(insertStmt).setString(2, "bigquery");
        verify(insertStmt).setString(3, "dataset.table");
        verify(insertStmt).setString(4, "prod-project");
        verify(insertStmt).setString(5, "[{\"column\":\"date\",\"op\":\">\",\"value\":\"2026-01-01\"}]");
        verify(insertStmt).setLong(6, 0);
        verify(insertStmt).setLong(7, 0);
        verify(insertStmt).setDouble(8, 0.0);
        verify(insertStmt).setString(9, "pending");
        verify(insertStmt).setString(10, null);
        verify(insertStmt).executeUpdate();
    }

    @SuppressWarnings("unchecked")
    @Test
    void upsert_replacesSameResource() throws SQLException {
        // First save
        when(connection.prepareStatement(contains("DELETE"))).thenReturn(deleteStmt);
        when(connection.prepareStatement(contains("INSERT"), eq(java.sql.Statement.RETURN_GENERATED_KEYS)))
                .thenReturn(insertStmt);

        ResultSet generatedKeys = mock(ResultSet.class);
        when(insertStmt.getGeneratedKeys()).thenReturn(generatedKeys);
        when(generatedKeys.next()).thenReturn(true);
        when(generatedKeys.getInt(1)).thenReturn(1);

        SyncManifest first = new SyncManifest(
                "my-project", "bigquery", "dataset.table",
                "prod-project", "[]", 100, 1024, 0.05, "completed", null);

        repo.save(first);

        // Reset mocks for second save
        reset(connection, deleteStmt, insertStmt);
        PreparedStatement deleteStmt2 = mock(PreparedStatement.class);
        PreparedStatement insertStmt2 = mock(PreparedStatement.class);
        ResultSet generatedKeys2 = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(contains("DELETE"))).thenReturn(deleteStmt2);
        when(connection.prepareStatement(contains("INSERT"), eq(java.sql.Statement.RETURN_GENERATED_KEYS)))
                .thenReturn(insertStmt2);
        when(insertStmt2.getGeneratedKeys()).thenReturn(generatedKeys2);
        when(generatedKeys2.next()).thenReturn(true);
        when(generatedKeys2.getInt(1)).thenReturn(2);

        SyncManifest second = new SyncManifest(
                "my-project", "bigquery", "dataset.table",
                "prod-project", "[{\"limit\":50}]", 50, 512, 0.02, "pending", null);

        int id = repo.save(second);

        assertEquals(2, id);

        // Verify delete was called with same project+service+resource
        verify(deleteStmt2).setString(1, "my-project");
        verify(deleteStmt2).setString(2, "bigquery");
        verify(deleteStmt2).setString(3, "dataset.table");
        verify(deleteStmt2).executeUpdate();

        // Verify insert used new values
        verify(insertStmt2).setString(5, "[{\"limit\":50}]");
        verify(insertStmt2).setLong(6, 50);
        verify(insertStmt2).setLong(7, 512);
        verify(insertStmt2).executeUpdate();
    }

    // --- getAll ---

    @Test
    void getAll_returnsAllForProject() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);

        when(connection.prepareStatement(anyString())).thenReturn(selectStmt);
        when(selectStmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(3);
        when(meta.getColumnLabel(1)).thenReturn("id");
        when(meta.getColumnLabel(2)).thenReturn("service_id");
        when(meta.getColumnLabel(3)).thenReturn("status");

        // Two rows
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getObject(1)).thenReturn(1, 2);
        when(rs.getObject(2)).thenReturn("bigquery", "firestore");
        when(rs.getObject(3)).thenReturn("completed", "pending");

        List<Map<String, Object>> results = repo.getAll("my-project");

        assertEquals(2, results.size());
        assertEquals(1, results.get(0).get("id"));
        assertEquals("bigquery", results.get(0).get("service_id"));
        assertEquals("completed", results.get(0).get("status"));
        assertEquals(2, results.get(1).get("id"));
        assertEquals("firestore", results.get(1).get("service_id"));
        assertEquals("pending", results.get(1).get("status"));

        verify(selectStmt).setString(1, "my-project");
    }

    @Test
    void getAll_returnsEmptyListForUnknownProject() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(selectStmt);
        when(selectStmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        List<Map<String, Object>> results = repo.getAll("nonexistent");

        assertTrue(results.isEmpty());
    }

    // --- getByService ---

    @Test
    void getByService_filtersCorrectly() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);

        when(connection.prepareStatement(anyString())).thenReturn(selectStmt);
        when(selectStmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnLabel(1)).thenReturn("id");
        when(meta.getColumnLabel(2)).thenReturn("resource_path");

        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(5);
        when(rs.getObject(2)).thenReturn("dataset.users");

        List<Map<String, Object>> results = repo.getByService("my-project", "bigquery");

        assertEquals(1, results.size());
        assertEquals(5, results.get(0).get("id"));
        assertEquals("dataset.users", results.get(0).get("resource_path"));

        // Verify both project_id and service_id are set
        verify(selectStmt).setString(1, "my-project");
        verify(selectStmt).setString(2, "bigquery");
    }

    // --- getById ---

    @Test
    void getById_returnsManifest() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);

        when(connection.prepareStatement(anyString())).thenReturn(selectStmt);
        when(selectStmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnLabel(1)).thenReturn("id");
        when(meta.getColumnLabel(2)).thenReturn("status");

        when(rs.next()).thenReturn(true);
        when(rs.getObject(1)).thenReturn(7);
        when(rs.getObject(2)).thenReturn("completed");

        Map<String, Object> result = repo.getById(7);

        assertNotNull(result);
        assertEquals(7, result.get("id"));
        assertEquals("completed", result.get("status"));

        verify(selectStmt).setInt(1, 7);
    }

    @Test
    void getById_returnsNullWhenNotFound() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(selectStmt);
        when(selectStmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        Map<String, Object> result = repo.getById(999);

        assertNull(result);
    }

    // --- updateProgress ---

    @Test
    void updateStatus_changesStatusAndRowCount() throws SQLException {
        when(connection.prepareStatement(contains("UPDATE"))).thenReturn(updateStmt);

        repo.updateProgress(42, "completed", 1500, 65536, null);

        verify(updateStmt).setString(1, "completed");
        verify(updateStmt).setLong(2, 1500);
        verify(updateStmt).setLong(3, 65536);
        verify(updateStmt).setString(4, null);
        verify(updateStmt).setInt(5, 42);
        verify(updateStmt).executeUpdate();
    }

    @Test
    void updateProgress_setsErrorMessage() throws SQLException {
        when(connection.prepareStatement(contains("UPDATE"))).thenReturn(updateStmt);

        repo.updateProgress(10, "failed", 0, 0, "Connection refused");

        verify(updateStmt).setString(1, "failed");
        verify(updateStmt).setLong(2, 0);
        verify(updateStmt).setLong(3, 0);
        verify(updateStmt).setString(4, "Connection refused");
        verify(updateStmt).setInt(5, 10);
        verify(updateStmt).executeUpdate();
    }

    // --- delete ---

    @Test
    void delete_removesManifest() throws SQLException {
        when(connection.prepareStatement(contains("DELETE"))).thenReturn(deleteStmt);

        repo.delete(42);

        verify(deleteStmt).setInt(1, 42);
        verify(deleteStmt).executeUpdate();
    }

    // --- error handling ---

    @Test
    void save_rollsBackOnInsertFailure() throws SQLException {
        when(connection.prepareStatement(contains("DELETE"))).thenReturn(deleteStmt);
        when(connection.prepareStatement(contains("INSERT"), eq(java.sql.Statement.RETURN_GENERATED_KEYS)))
                .thenReturn(insertStmt);
        when(insertStmt.executeUpdate()).thenThrow(new SQLException("insert failed"));

        SyncManifest manifest = new SyncManifest(
                "my-project", "bigquery", "dataset.table",
                "prod-project", "[]", 0, 0, 0.0, "pending", null);

        assertThrows(SQLException.class, () -> repo.save(manifest));

        verify(connection).rollback();
    }
}

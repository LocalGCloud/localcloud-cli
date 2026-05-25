package com.localcloud.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * TDD test verifying that SchemaManager.initialize() includes DDL for
 * sync_manifests and sync_credentials tables.
 *
 * <p>Uses a mock Statement to capture all SQL executed by initialize(),
 * then asserts that the sync table CREATE TABLE statements are present.
 * This avoids H2 compatibility issues with PostgreSQL-specific syntax
 * (JSONB, partial indexes) in existing table DDL.
 */
class SchemaManagerSyncTablesTest {

    @Test
    void initialize_createsSyncManifestsTable() throws Exception {
        List<String> executedSql = captureExecutedSql();

        boolean found = executedSql.stream()
                .anyMatch(sql -> sql.contains("CREATE TABLE IF NOT EXISTS sync_manifests"));
        assertTrue(found, "initialize() should include CREATE TABLE for sync_manifests");
    }

    @Test
    void initialize_createsSyncCredentialsTable() throws Exception {
        List<String> executedSql = captureExecutedSql();

        boolean found = executedSql.stream()
                .anyMatch(sql -> sql.contains("CREATE TABLE IF NOT EXISTS sync_credentials"));
        assertTrue(found, "initialize() should include CREATE TABLE for sync_credentials");
    }

    @Test
    void initialize_createsAlloyDBMetadataTables() throws Exception {
        List<String> executedSql = captureExecutedSql();

        assertAll(
            () -> assertTrue(executedSql.stream().anyMatch(sql -> sql.contains("CREATE TABLE IF NOT EXISTS alloydb_clusters")),
                    "initialize() should include CREATE TABLE for alloydb_clusters"),
            () -> assertTrue(executedSql.stream().anyMatch(sql -> sql.contains("CREATE TABLE IF NOT EXISTS alloydb_instances")),
                    "initialize() should include CREATE TABLE for alloydb_instances"),
            () -> assertTrue(executedSql.stream().anyMatch(sql -> sql.contains("CREATE TABLE IF NOT EXISTS alloydb_databases")),
                    "initialize() should include CREATE TABLE for alloydb_databases"),
            () -> assertTrue(executedSql.stream().anyMatch(sql -> sql.contains("CREATE TABLE IF NOT EXISTS alloydb_backups")),
                    "initialize() should include CREATE TABLE for alloydb_backups"),
            () -> assertTrue(executedSql.stream().anyMatch(sql -> sql.contains("CREATE TABLE IF NOT EXISTS alloydb_users")),
                    "initialize() should include CREATE TABLE for alloydb_users")
        );
    }

    @Test
    void syncManifests_hasExpectedColumns() throws Exception {
        List<String> executedSql = captureExecutedSql();

        String ddl = executedSql.stream()
                .filter(sql -> sql.contains("CREATE TABLE IF NOT EXISTS sync_manifests"))
                .findFirst()
                .orElse("");

        assertAll(
            () -> assertTrue(ddl.contains("project_id"), "should have project_id column"),
            () -> assertTrue(ddl.contains("service_id"), "should have service_id column"),
            () -> assertTrue(ddl.contains("resource_path"), "should have resource_path column"),
            () -> assertTrue(ddl.contains("source_project"), "should have source_project column"),
            () -> assertTrue(ddl.contains("status"), "should have status column"),
            () -> assertTrue(ddl.contains("row_count"), "should have row_count column"),
            () -> assertTrue(ddl.contains("bytes_synced"), "should have bytes_synced column")
        );
    }

    @Test
    void syncCredentials_hasExpectedColumns() throws Exception {
        List<String> executedSql = captureExecutedSql();

        String ddl = executedSql.stream()
                .filter(sql -> sql.contains("CREATE TABLE IF NOT EXISTS sync_credentials"))
                .findFirst()
                .orElse("");

        assertAll(
            () -> assertTrue(ddl.contains("project_id"), "should have project_id column"),
            () -> assertTrue(ddl.contains("source_project"), "should have source_project column"),
            () -> assertTrue(ddl.contains("auth_method"), "should have auth_method column"),
            () -> assertTrue(ddl.contains("credential_data"), "should have credential_data column")
        );
    }

    /**
     * Run SchemaManager.initialize() with a mock connection and capture all SQL
     * statements that are executed via Statement.execute(String).
     */
    private List<String> captureExecutedSql() throws Exception {
        List<String> executedSql = new ArrayList<>();

        Statement mockStmt = mock(Statement.class);
        when(mockStmt.execute(anyString())).thenAnswer(inv -> {
            executedSql.add(inv.getArgument(0));
            return false;
        });

        PreparedStatement mockPs = mock(PreparedStatement.class);
        when(mockPs.executeUpdate()).thenReturn(0);

        Connection mockConn = mock(Connection.class);
        when(mockConn.createStatement()).thenReturn(mockStmt);
        when(mockConn.prepareStatement(anyString())).thenReturn(mockPs);

        PostgresDataSource mockDs = mock(PostgresDataSource.class);
        when(mockDs.getConnection()).thenReturn(mockConn);

        new SchemaManager(mockDs).initialize("test-project");

        return executedSql;
    }
}

package com.localcloud.sync;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SyncCredentialRepositoryTest {

    private DataSource dataSource;
    private Connection connection;
    private PreparedStatement deleteStmt;
    private PreparedStatement insertStmt;
    private PreparedStatement selectStmt;
    private SyncCredentialRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        deleteStmt = mock(PreparedStatement.class);
        insertStmt = mock(PreparedStatement.class);
        selectStmt = mock(PreparedStatement.class);

        when(dataSource.getConnection()).thenReturn(connection);

        repo = new SyncCredentialRepository(dataSource);
    }

    @Test
    void saveOAuth_storesCredential() throws SQLException {
        // save does: delete + insert inside a transaction
        when(connection.prepareStatement(contains("DELETE"))).thenReturn(deleteStmt);
        when(connection.prepareStatement(contains("INSERT"))).thenReturn(insertStmt);

        repo.save("my-project", "prod-project", "oauth", "{\"token\":\"abc\"}");

        // Verify transaction behavior
        verify(connection).setAutoCommit(false);
        verify(connection).commit();
        verify(connection).setAutoCommit(true);

        // Verify delete was called with project_id
        verify(deleteStmt).setString(1, "my-project");
        verify(deleteStmt).executeUpdate();

        // Verify insert with all fields
        verify(insertStmt).setString(1, "my-project");
        verify(insertStmt).setString(2, "prod-project");
        verify(insertStmt).setString(3, "oauth");
        verify(insertStmt).setString(4, "{\"token\":\"abc\"}");
        verify(insertStmt).executeUpdate();
    }

    @Test
    void saveServiceAccount_storesCredential() throws SQLException {
        when(connection.prepareStatement(contains("DELETE"))).thenReturn(deleteStmt);
        when(connection.prepareStatement(contains("INSERT"))).thenReturn(insertStmt);

        repo.save("my-project", "staging-project", "service_account", "{\"type\":\"service_account\",\"project_id\":\"staging\"}");

        verify(insertStmt).setString(1, "my-project");
        verify(insertStmt).setString(2, "staging-project");
        verify(insertStmt).setString(3, "service_account");
        verify(insertStmt).setString(4, "{\"type\":\"service_account\",\"project_id\":\"staging\"}");
        verify(insertStmt).executeUpdate();
    }

    @Test
    void getStatus_neverReturnsCredentialData() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(selectStmt);
        when(selectStmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString("source_project")).thenReturn("prod-project");
        when(rs.getString("auth_method")).thenReturn("oauth");
        when(rs.getString("created_at")).thenReturn("2026-04-24 10:00:00");

        Map<String, String> status = repo.getStatus("my-project");

        assertNotNull(status);
        assertEquals("prod-project", status.get("source_project"));
        assertEquals("oauth", status.get("auth_method"));
        assertEquals("2026-04-24 10:00:00", status.get("created_at"));
        assertEquals("true", status.get("connected"));

        // CRITICAL: credential_data must NEVER appear in returned status map
        assertNull(status.get("credential_data"));
        assertFalse(status.containsKey("credential_data"));
    }

    @Test
    void getStatus_returnsNullWhenNoCredential() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(selectStmt);
        when(selectStmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        Map<String, String> status = repo.getStatus("unknown-project");

        assertNull(status);
    }

    @Test
    void getCredentialData_returnsRawData() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(selectStmt);
        when(selectStmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString("credential_data")).thenReturn("{\"token\":\"secret123\"}");

        String data = repo.getCredentialData("my-project");

        assertEquals("{\"token\":\"secret123\"}", data);
        verify(selectStmt).setString(1, "my-project");
    }

    @Test
    void getCredentialData_returnsNullWhenNoCredential() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(selectStmt);
        when(selectStmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        String data = repo.getCredentialData("unknown-project");

        assertNull(data);
    }

    @Test
    void delete_clearsCredential() throws SQLException {
        when(connection.prepareStatement(contains("DELETE"))).thenReturn(deleteStmt);

        repo.delete("my-project");

        verify(deleteStmt).setString(1, "my-project");
        verify(deleteStmt).executeUpdate();
    }

    @SuppressWarnings("unchecked")
    @Test
    void save_upserts_replacesExisting() throws SQLException {
        // First save
        when(connection.prepareStatement(contains("DELETE"))).thenReturn(deleteStmt);
        when(connection.prepareStatement(contains("INSERT"))).thenReturn(insertStmt);

        repo.save("my-project", "prod-project", "oauth", "{\"token\":\"old\"}");

        // Reset mocks for second save
        reset(connection, deleteStmt, insertStmt);
        PreparedStatement deleteStmt2 = mock(PreparedStatement.class);
        PreparedStatement insertStmt2 = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(contains("DELETE"))).thenReturn(deleteStmt2);
        when(connection.prepareStatement(contains("INSERT"))).thenReturn(insertStmt2);

        repo.save("my-project", "new-project", "service_account", "{\"type\":\"sa\"}");

        // Verify second save deletes old and inserts new
        verify(deleteStmt2).setString(1, "my-project");
        verify(deleteStmt2).executeUpdate();
        verify(insertStmt2).setString(1, "my-project");
        verify(insertStmt2).setString(2, "new-project");
        verify(insertStmt2).setString(3, "service_account");
        verify(insertStmt2).setString(4, "{\"type\":\"sa\"}");
        verify(insertStmt2).executeUpdate();
    }

    @Test
    void save_rollsBackOnInsertFailure() throws SQLException {
        when(connection.prepareStatement(contains("DELETE"))).thenReturn(deleteStmt);
        when(connection.prepareStatement(contains("INSERT"))).thenReturn(insertStmt);
        when(insertStmt.executeUpdate()).thenThrow(new SQLException("insert failed"));

        assertThrows(SQLException.class, () ->
            repo.save("my-project", "prod", "oauth", "{}")
        );

        verify(connection).rollback();
    }
}

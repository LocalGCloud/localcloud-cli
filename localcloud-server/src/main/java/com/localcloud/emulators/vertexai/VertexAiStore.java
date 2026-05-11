package com.localcloud.emulators.vertexai;

import com.localcloud.persistence.PostgresDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Persists Vertex AI request/response traces for console/debugging workflows.
 */
public class VertexAiStore {

    private final PostgresDataSource dataSource;

    public VertexAiStore(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void recordRequest(String project, String location, String publisher, String model,
                              String method, String requestJson, String responseJson,
                              int promptTokens, int responseTokens, String backend) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO vertexai_requests " +
                             "(project_id, location_id, publisher, model_id, method, request_json, response_json, prompt_tokens, response_tokens, backend) " +
                             "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, project);
            ps.setString(2, location);
            ps.setString(3, publisher);
            ps.setString(4, model);
            ps.setString(5, method);
            ps.setString(6, requestJson);
            ps.setString(7, responseJson);
            ps.setInt(8, promptTokens);
            ps.setInt(9, responseTokens);
            ps.setString(10, backend);
            ps.executeUpdate();
        }
    }

    public void clearAll() {
        try (Connection conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM vertexai_requests");
        } catch (SQLException ignored) {
        }
    }
}

package com.localcloud.emulators.workflows;

import com.localcloud.persistence.PostgresDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * PostgreSQL persistence layer for Cloud Workflows.
 * Manages workflows and workflow_executions tables.
 */
public class WorkflowsStore {
    private static final Logger logger = LoggerFactory.getLogger(WorkflowsStore.class);
    private final PostgresDataSource dataSource;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WorkflowsStore(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
        ensureRevisionsTable();
    }

    private void ensureRevisionsTable() {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            // Ensure deleted_at column exists on workflows
            try {
                st.execute("ALTER TABLE workflows ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ");
            } catch (SQLException e) {
                logger.warn("Could not add deleted_at column: {}", e.getMessage());
            }
            try {
                st.execute("""
                    CREATE TABLE IF NOT EXISTS workflow_revisions (
                        id BIGSERIAL PRIMARY KEY,
                        workflow_id VARCHAR(256) NOT NULL,
                        project_id VARCHAR(128) NOT NULL,
                        location_id VARCHAR(64) NOT NULL,
                        revision_id INTEGER NOT NULL,
                        source_contents TEXT NOT NULL,
                        labels JSONB DEFAULT '{}',
                        description VARCHAR(1024) DEFAULT '',
                        service_account VARCHAR(256) DEFAULT '',
                        user_env_vars JSONB DEFAULT '{}',
                        created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
                    )
                """);
            } catch (SQLException e) {
                logger.warn("Could not create workflow_revisions table: {}", e.getMessage());
            }
            try {
                st.execute("""
                    CREATE INDEX IF NOT EXISTS idx_workflow_revisions_wf
                    ON workflow_revisions(project_id, location_id, workflow_id, revision_id DESC)
                """);
            } catch (SQLException e) {
                logger.warn("Could not create workflow_revisions index: {}", e.getMessage());
            }
        } catch (SQLException e) {
            logger.error("Failed to connect for schema migration: {}", e.getMessage());
        }
    }

    private void insertRevision(String projectId, String locationId, String workflowId, int revisionId,
                                String sourceContents, String labelsJson, String description,
                                String serviceAccount, String userEnvVarsJson) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO workflow_revisions (workflow_id, project_id, location_id, revision_id, " +
                "source_contents, labels, description, service_account, user_env_vars, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, CURRENT_TIMESTAMP)")) {
            ps.setString(1, workflowId);
            ps.setString(2, projectId);
            ps.setString(3, locationId);
            ps.setInt(4, revisionId);
            ps.setString(5, sourceContents != null ? sourceContents : "");
            ps.setString(6, labelsJson != null ? labelsJson : "{}");
            ps.setString(7, description != null ? description : "");
            ps.setString(8, serviceAccount != null ? serviceAccount : "");
            ps.setString(9, userEnvVarsJson != null ? userEnvVarsJson : "{}");
            ps.executeUpdate();
        }
    }

    // --- Workflow CRUD ---

    public void createWorkflow(String projectId, String locationId, String workflowId,
                               String sourceContents, String labelsJson, String serviceAccount) throws SQLException {
        createWorkflow(projectId, locationId, workflowId, sourceContents, labelsJson, serviceAccount,
                "", "LOG_NONE", "EXECUTION_HISTORY_BASIC", null, "{}", "{}");
    }

    public void createWorkflow(String projectId, String locationId, String workflowId,
                               String sourceContents, String labelsJson, String serviceAccount,
                               String description, String callLogLevel, String executionHistoryLevel,
                               String cryptoKeyName, String userEnvVarsJson, String tagsJson) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO workflows (project_id, location_id, workflow_id, source_contents, state, revision_id, " +
                "description, labels, service_account, call_log_level, execution_history_level, crypto_key_name, " +
                "user_env_vars, tags, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, 'ACTIVE', 1, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, workflowId);
            ps.setString(4, sourceContents);
            ps.setString(5, description != null ? description : "");
            ps.setString(6, labelsJson != null ? labelsJson : "{}");
            ps.setString(7, serviceAccount);
            ps.setString(8, callLogLevel != null && !callLogLevel.isBlank() ? callLogLevel : "LOG_NONE");
            ps.setString(9, executionHistoryLevel != null && !executionHistoryLevel.isBlank()
                    ? executionHistoryLevel : "EXECUTION_HISTORY_BASIC");
            ps.setString(10, cryptoKeyName);
            ps.setString(11, userEnvVarsJson != null ? userEnvVarsJson : "{}");
            ps.setString(12, tagsJson != null ? tagsJson : "{}");
            ps.executeUpdate();
        }
        // Insert initial revision
        insertRevision(projectId, locationId, workflowId, 1, sourceContents, labelsJson,
                description, serviceAccount, userEnvVarsJson);
    }

    public Map<String, Object> getWorkflow(String projectId, String locationId, String workflowId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM workflows WHERE project_id = ? AND location_id = ? AND workflow_id = ? AND state != 'DELETED'")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rowToMap(rs);
                return null;
            }
        }
    }

    public void updateWorkflow(String projectId, String locationId, String workflowId,
                               String sourceContents) throws SQLException {
        updateWorkflow(projectId, locationId, workflowId, sourceContents, null, null, null);
    }

    public void updateWorkflow(String projectId, String locationId, String workflowId,
                               String sourceContents, String labelsJson, String description,
                               String serviceAccount) throws SQLException {
        StringBuilder sql = new StringBuilder("UPDATE workflows SET ");
        sql.append("source_contents = COALESCE(?, source_contents), ");
        sql.append("revision_id = revision_id + 1, ");
        sql.append("updated_at = CURRENT_TIMESTAMP");

        // Optionally update additional fields if provided
        boolean hasLabels = labelsJson != null;
        boolean hasDescription = description != null;
        boolean hasServiceAccount = serviceAccount != null;
        if (hasLabels) sql.append(", labels = ?::jsonb");
        if (hasDescription) sql.append(", description = ?");
        if (hasServiceAccount) sql.append(", service_account = ?");

        sql.append(" WHERE project_id = ? AND location_id = ? AND workflow_id = ? AND state != 'DELETED'");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setString(idx++, sourceContents);
            if (hasLabels) ps.setString(idx++, labelsJson);
            if (hasDescription) ps.setString(idx++, description);
            if (hasServiceAccount) ps.setString(idx++, serviceAccount);
            ps.setString(idx++, projectId);
            ps.setString(idx++, locationId);
            ps.setString(idx++, workflowId);
            ps.executeUpdate();
        }

        // Insert new revision with the updated source
        int newRevId = getCurrentRevisionId(projectId, locationId, workflowId);
        String currentSource = getWorkflowSource(projectId, locationId, workflowId);
        String currentLabels = getWorkflowFieldString(projectId, locationId, workflowId, "labels");
        String currentDesc = getWorkflowFieldString(projectId, locationId, workflowId, "description");
        String currentSA = getWorkflowFieldString(projectId, locationId, workflowId, "service_account");
        String currentEnvVars = getWorkflowFieldString(projectId, locationId, workflowId, "user_env_vars");
        insertRevision(projectId, locationId, workflowId, newRevId, currentSource, currentLabels,
                currentDesc, currentSA, currentEnvVars);
    }

    public void deleteWorkflow(String projectId, String locationId, String workflowId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE workflows SET state = 'DELETED', deleted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP " +
                "WHERE project_id = ? AND location_id = ? AND workflow_id = ?")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, workflowId);
            ps.executeUpdate();
        }
    }

    public void undeleteWorkflow(String projectId, String locationId, String workflowId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE workflows SET state = 'ACTIVE', deleted_at = NULL, updated_at = CURRENT_TIMESTAMP " +
                "WHERE project_id = ? AND location_id = ? AND workflow_id = ? AND state = 'DELETED'")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, workflowId);
            int rows = ps.executeUpdate();
            if (rows == 0) throw new IllegalArgumentException("Workflow not found or not deleted: " + workflowId);
        }
    }

    public int purgeDeletedWorkflows() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            int total = 0;
            // Clean up related data before deleting workflows
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM workflow_step_entries WHERE execution_id IN " +
                    "(SELECT execution_id FROM workflow_executions WHERE workflow_id IN " +
                    "(SELECT workflow_id FROM workflows WHERE state = 'DELETED' AND deleted_at < CURRENT_TIMESTAMP - INTERVAL '30 days'))")) {
                total += ps.executeUpdate();
            } catch (SQLException e) {
                logger.debug("Purge: workflow_step_entries table not available (may not be created yet): {}", e.getMessage());
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM workflow_executions WHERE workflow_id IN " +
                    "(SELECT workflow_id FROM workflows WHERE state = 'DELETED' AND deleted_at < CURRENT_TIMESTAMP - INTERVAL '30 days')")) {
                total += ps.executeUpdate();
            } catch (SQLException e) {
                logger.debug("Purge: workflow_executions table not available: {}", e.getMessage());
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM workflow_revisions WHERE workflow_id IN " +
                    "(SELECT workflow_id FROM workflows WHERE state = 'DELETED' AND deleted_at < CURRENT_TIMESTAMP - INTERVAL '30 days')")) {
                total += ps.executeUpdate();
            } catch (SQLException e) {
                logger.debug("Purge: workflow_revisions table not available: {}", e.getMessage());
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM workflows WHERE state = 'DELETED' AND deleted_at < CURRENT_TIMESTAMP - INTERVAL '30 days'")) {
                total += ps.executeUpdate();
            }
            return total;
        }
    }

    public List<Map<String, Object>> listWorkflows(String projectId, String locationId, int pageSize) throws SQLException {
        return listWorkflows(projectId, locationId, pageSize, null, null, null);
    }

    public List<Map<String, Object>> listWorkflows(String projectId, String locationId, int pageSize,
                                                    String pageToken, String filter, String orderBy) throws SQLException {
        int actualPageSize = pageSize > 0 ? Math.min(pageSize, 1000) : 100;
        int offset = decodePageToken(pageToken);

        StringBuilder sql = new StringBuilder("SELECT * FROM workflows WHERE project_id = ? AND location_id = ? AND state != 'DELETED'");

        // Basic filter support
        if (filter != null && !filter.isBlank()) {
            String sqlCondition = parseFilterToSql(filter);
            if (!sqlCondition.isBlank()) {
                sql.append(" AND (").append(sqlCondition).append(")");
            }
        }

        // Order by
        String orderClause = "created_at DESC";
        if (orderBy != null && !orderBy.isBlank()) {
            orderClause = parseOrderBy(orderBy);
        }
        sql.append(" ORDER BY ").append(orderClause);
        sql.append(" LIMIT ? OFFSET ?");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setInt(3, actualPageSize + 1); // Fetch one extra to detect next page
            ps.setInt(4, offset);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> results = new ArrayList<>();
                while (rs.next()) results.add(rowToMap(rs));
                return results;
            }
        }
    }

    /**
     * Sanitize user input for SQL identifier/value use. Only allows alphanumeric,
     * underscore, dash, and asterisk (wildcard). Strips everything else.
     */
    private static String sanitize(String input) {
        if (input == null) return "";
        return input.replaceAll("[^a-zA-Z0-9_*-]", "");
    }

    private String parseOrderBy(String orderBy) {
        String trimmed = orderBy.trim().toLowerCase();
        String[] parts = trimmed.split("\\s+");
        String field = parts[0];
        String dir = parts.length > 1 ? parts[1] : "desc";
        if (!"asc".equals(dir) && !"desc".equals(dir)) dir = "desc";
        return switch (field) {
            case "create_time", "createtime" -> "created_at " + dir;
            case "update_time", "updatetime" -> "updated_at " + dir;
            case "name" -> "workflow_id " + dir;
            default -> "created_at " + dir;
        };
    }

    private int decodePageToken(String pageToken) {
        if (pageToken == null || pageToken.isBlank()) return 0;
        try {
            return Integer.parseInt(pageToken.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public String encodePageToken(int offset, int pageSize) {
        return "cursor-" + (offset + pageSize);
    }

    /**
     * Parse a CEL-inspired filter expression into a SQL WHERE condition.
     * Supports: state=VALUE, name="VALUE", labels.KEY=VALUE, AND, OR, NOT, parentheses.
     * Wildcards (*) in name values are converted to SQL LIKE patterns.
     */
    static String parseFilterToSql(String filter) {
        if (filter == null || filter.isBlank()) return "";
        try {
            return new FilterParser(filter.trim()).parse();
        } catch (Exception e) {
            logger.warn("Failed to parse filter '{}': {}", filter, e.getMessage());
            return "";
        }
    }

    /**
     * Simple recursive-descent parser for CEL-like filter expressions.
     * Grammar: expr = or_expr
     *          or_expr = and_expr ("OR" and_expr)*
     *          and_expr = not_expr ("AND" not_expr)*
     *          not_expr = "NOT" not_expr | primary
     *          primary = comp | "(" expr ")"
     *          comp = IDENTIFIER "=" value
     *          value = STRING | IDENTIFIER
     */
    private static class FilterParser {
        private final String input;
        private int pos;

        FilterParser(String input) { this.input = input; this.pos = 0; }

        String parse() {
            String result = orExpr();
            skipWhitespace();
            if (pos < input.length()) {
                throw new IllegalArgumentException("Unexpected character at position " + pos + ": '" + input.charAt(pos) + "'");
            }
            return result;
        }

        private String orExpr() {
            String left = andExpr();
            while (matchKeyword("OR")) {
                String right = andExpr();
                left = "(" + left + " OR " + right + ")";
            }
            return left;
        }

        private String andExpr() {
            String left = notExpr();
            while (matchKeyword("AND")) {
                String right = notExpr();
                left = "(" + left + " AND " + right + ")";
            }
            return left;
        }

        private String notExpr() {
            skipWhitespace();
            if (matchKeyword("NOT")) {
                return "NOT (" + primary() + ")";
            }
            return primary();
        }

        private String primary() {
            skipWhitespace();
            if (match("(")) {
                String inner = orExpr();
                expect(")");
                return "(" + inner + ")";
            }
            return comparison();
        }

        private String comparison() {
            skipWhitespace();
            String field = identifier();
            skipWhitespace();
            String op = operator();
            skipWhitespace();
            String value = value();
            return buildCondition(field, op, value);
        }

        private String identifier() {
            skipWhitespace();
            StringBuilder sb = new StringBuilder();
            if (pos < input.length() && (Character.isLetter(input.charAt(pos)) || input.charAt(pos) == '_')) {
                while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos))
                        || input.charAt(pos) == '_' || input.charAt(pos) == '.')) {
                    sb.append(input.charAt(pos));
                    pos++;
                }
            } else {
                throw new IllegalArgumentException("Expected identifier at position " + pos);
            }
            if (sb.isEmpty()) {
                throw new IllegalArgumentException("Empty identifier at position " + pos);
            }
            return sb.toString();
        }

        private String operator() {
            skipWhitespace();
            if (match("!=")) return "!=";
            if (match("<=")) return "<=";
            if (match(">=")) return ">=";
            if (match("=")) return "=";
            if (match("<")) return "<";
            if (match(">")) return ">";
            throw new IllegalArgumentException("Expected operator at position " + pos);
        }

        private String value() {
            skipWhitespace();
            if (pos < input.length() && (input.charAt(pos) == '"' || input.charAt(pos) == '\'')) {
                char quote = input.charAt(pos);
                pos++; // skip opening quote
                StringBuilder sb = new StringBuilder();
                while (pos < input.length() && input.charAt(pos) != quote) {
                    if (input.charAt(pos) == '\\' && pos + 1 < input.length()) {
                        pos++;
                        sb.append(input.charAt(pos));
                    } else {
                        sb.append(input.charAt(pos));
                    }
                    pos++;
                }
                if (pos >= input.length()) throw new IllegalArgumentException("Unterminated string");
                pos++; // skip closing quote
                return sb.toString();
            }
            // Unquoted identifier value
            StringBuilder sb = new StringBuilder();
            while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos))
                    || input.charAt(pos) == '_' || input.charAt(pos) == '-' || input.charAt(pos) == '*')) {
                sb.append(input.charAt(pos));
                pos++;
            }
            if (sb.isEmpty()) throw new IllegalArgumentException("Expected value at position " + pos);
            return sb.toString();
        }

        private String buildCondition(String field, String op, String value) {
            String sqlField = toSqlField(field);
            String sqlValue = sanitize(value);
            // Handle wildcards in LIKE comparisons
            boolean hasWildcard = value.contains("*");
            if (hasWildcard && "=".equals(op)) {
                return sqlField + " LIKE '" + sqlValue.replace("*", "%") + "'";
            }
            if (hasWildcard && "!=".equals(op)) {
                return sqlField + " NOT LIKE '" + sqlValue.replace("*", "%") + "'";
            }
            return sqlField + " " + op + " '" + sqlValue + "'";
        }

        private static String toSqlField(String field) {
            // Map CEL field names to SQL column names
            return switch (field) {
                case "name" -> "workflow_id";
                case "state" -> "state";
                case "createTime", "create_time" -> "created_at";
                case "updateTime", "update_time" -> "updated_at";
                default -> {
                    // labels.key → check if it's a labels access
                    if (field.startsWith("labels.")) {
                        String key = field.substring(7);
                        yield "labels->>'" + sanitize(key) + "'";
                    }
                    yield field; // pass through (risky but flexible)
                }
            };
        }

        private boolean matchKeyword(String keyword) {
            skipWhitespace();
            int saved = pos;
            if (input.regionMatches(true, pos, keyword, 0, keyword.length())) {
                int after = pos + keyword.length();
                // Keyword must be followed by whitespace, operator, paren, or EOF
                if (after >= input.length() || !Character.isLetterOrDigit(input.charAt(after))) {
                    pos = after;
                    return true;
                }
            }
            pos = saved;
            return false;
        }

        private boolean match(String s) {
            skipWhitespace();
            if (input.startsWith(s, pos)) {
                pos += s.length();
                return true;
            }
            return false;
        }

        private void expect(String s) {
            skipWhitespace();
            if (!match(s)) {
                throw new IllegalArgumentException("Expected '" + s + "' at position " + pos);
            }
        }

        private void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++;
        }
    }

    // --- Execution CRUD ---

    public String createExecution(String workflowId, String projectId, String locationId,
                                  String argument, String workflowRevisionId) throws SQLException {
        return createExecution(workflowId, projectId, locationId, argument, workflowRevisionId,
                "LOG_NONE", "{}");
    }

    public String createExecution(String workflowId, String projectId, String locationId,
                                  String argument, String workflowRevisionId,
                                  String callLogLevel, String labelsJson) throws SQLException {
        String executionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO workflow_executions (execution_id, workflow_id, project_id, location_id, state, argument, workflow_revision_id, call_log_level, labels, start_time) " +
                "VALUES (?, ?, ?, ?, 'QUEUED', ?::jsonb, ?, ?, ?::jsonb, CURRENT_TIMESTAMP)")) {
            ps.setString(1, executionId);
            ps.setString(2, workflowId);
            ps.setString(3, projectId);
            ps.setString(4, locationId);
            ps.setString(5, argument != null ? argument : "null");
            ps.setString(6, workflowRevisionId);
            ps.setString(7, callLogLevel != null && !callLogLevel.isBlank() ? callLogLevel : "LOG_NONE");
            ps.setString(8, labelsJson != null ? labelsJson : "{}");
            ps.executeUpdate();
        }
        return executionId;
    }

    public Map<String, Object> getExecution(String projectId, String locationId,
                                             String workflowId, String executionId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM workflow_executions WHERE execution_id = ? AND project_id = ? AND location_id = ? AND workflow_id = ?")) {
            ps.setString(1, executionId);
            ps.setString(2, projectId);
            ps.setString(3, locationId);
            ps.setString(4, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rowToMap(rs);
                return null;
            }
        }
    }

    public boolean updateExecutionState(String executionId, String state, String result, String error) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE workflow_executions SET state = ?, result = ?::jsonb, error = ?::jsonb, " +
                "state_error = CASE WHEN ? = 'FAILED' THEN ?::jsonb ELSE state_error END, " +
                "end_time = CASE WHEN ? IN ('SUCCEEDED','FAILED','CANCELLED') THEN CURRENT_TIMESTAMP ELSE end_time END, " +
                "duration_ms = CASE WHEN ? IN ('SUCCEEDED','FAILED','CANCELLED') THEN CAST(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - start_time)) * 1000 AS BIGINT) ELSE duration_ms END " +
                "WHERE execution_id = ? AND state NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')")) {
            ps.setString(1, state);
            ps.setString(2, result);
            ps.setString(3, error);
            ps.setString(4, state);
            ps.setString(5, error);
            ps.setString(6, state);
            ps.setString(7, state);
            ps.setString(8, executionId);
            int rowsUpdated = ps.executeUpdate();
            if (rowsUpdated == 0) {
                logger.warn("State update to '{}' skipped for execution {} — already in a terminal state", state, executionId);
            }
            return rowsUpdated > 0;
        }
    }

    // --- Execution step history ---

    public void saveStepEntries(String executionId, List<Map<String, Object>> history) throws SQLException {
        if (history == null || history.isEmpty()) return;
        Map<String, Object> execution = getExecutionById(executionId);
        if (execution == null) return;

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement delete = conn.prepareStatement(
                        "DELETE FROM workflow_step_entries WHERE execution_id = ?")) {
                    delete.setString(1, executionId);
                    delete.executeUpdate();
                }

                try (PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO workflow_step_entries (execution_id, project_id, location_id, workflow_id, " +
                        "step_name, step_type, state, start_time, end_time, duration_ms, entry_json) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)")) {
                    for (Map<String, Object> entry : history) {
                        long timestamp = asLong(entry.get("timestamp"), System.currentTimeMillis());
                        long durationMs = asLong(entry.get("duration_ms"), 0);
                        Timestamp endTime = new Timestamp(timestamp);
                        Timestamp startTime = new Timestamp(Math.max(0, timestamp - durationMs));

                        insert.setString(1, executionId);
                        insert.setString(2, String.valueOf(execution.get("project_id")));
                        insert.setString(3, String.valueOf(execution.get("location_id")));
                        insert.setString(4, String.valueOf(execution.get("workflow_id")));
                        insert.setString(5, String.valueOf(entry.getOrDefault("step", "")));
                        insert.setString(6, String.valueOf(entry.getOrDefault("type", "")));
                        insert.setString(7, String.valueOf(entry.getOrDefault("state", "SUCCEEDED")));
                        insert.setTimestamp(8, startTime);
                        insert.setTimestamp(9, endTime);
                        insert.setLong(10, durationMs);
                        insert.setString(11, objectMapper.writeValueAsString(entry));
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof SQLException se) throw se;
                throw new SQLException("Failed to save workflow step history", e);
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public List<Map<String, Object>> listStepEntries(String projectId, String locationId,
                                                     String workflowId, String executionId,
                                                     int pageSize) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM workflow_step_entries WHERE project_id = ? AND location_id = ? AND workflow_id = ? AND execution_id = ? " +
                "ORDER BY step_entry_id ASC LIMIT ?")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, workflowId);
            ps.setString(4, executionId);
            ps.setInt(5, pageSize > 0 ? pageSize : 100);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> results = new ArrayList<>();
                while (rs.next()) results.add(rowToMap(rs));
                return results;
            }
        }
    }

    public Map<String, Object> getStepEntry(String projectId, String locationId, String workflowId,
                                            String executionId, long stepEntryId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM workflow_step_entries WHERE project_id = ? AND location_id = ? AND workflow_id = ? AND execution_id = ? AND step_entry_id = ?")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, workflowId);
            ps.setString(4, executionId);
            ps.setLong(5, stepEntryId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rowToMap(rs);
                return null;
            }
        }
    }

    public int deleteExecutionHistory(String projectId, String locationId,
                                      String workflowId, String executionId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM workflow_step_entries WHERE project_id = ? AND location_id = ? AND workflow_id = ? AND execution_id = ?")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, workflowId);
            ps.setString(4, executionId);
            return ps.executeUpdate();
        }
    }

    public List<Map<String, Object>> listExecutions(String projectId, String locationId,
                                                     String workflowId, int pageSize) throws SQLException {
        return listExecutions(projectId, locationId, workflowId, pageSize, null, null);
    }

    public List<Map<String, Object>> listExecutions(String projectId, String locationId,
                                                     String workflowId, int pageSize,
                                                     String pageToken, String filter) throws SQLException {
        int actualPageSize = pageSize > 0 ? Math.min(pageSize, 1000) : 100;
        int offset = decodePageToken(pageToken);

        StringBuilder sql = new StringBuilder(
                "SELECT * FROM workflow_executions WHERE project_id = ? AND location_id = ? AND workflow_id = ?");

        // Basic filter support
        if (filter != null && !filter.isBlank()) {
            String sqlCondition = parseFilterToSql(filter);
            if (!sqlCondition.isBlank()) {
                sql.append(" AND (").append(sqlCondition).append(")");
            }
        }

        sql.append(" ORDER BY start_time DESC LIMIT ? OFFSET ?");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, workflowId);
            ps.setInt(4, actualPageSize + 1);
            ps.setInt(5, offset);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> results = new ArrayList<>();
                while (rs.next()) results.add(rowToMap(rs));
                return results;
            }
        }
    }

    public String getProjectIdForExecution(String executionId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT project_id FROM workflow_executions WHERE execution_id = ?")) {
            ps.setString(1, executionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("project_id");
                return null;
            }
        }
    }

    /**
     * Sweep orphaned executions (QUEUED or ACTIVE) from a previous instance.
     * Called on startup to clean up executions left in non-terminal states.
     * @return number of executions swept
     */
    public int sweepOrphanedExecutions() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE workflow_executions SET state = 'FAILED', error = ?::jsonb, end_time = CURRENT_TIMESTAMP " +
                "WHERE state IN ('QUEUED', 'ACTIVE')")) {
            ps.setString(1, "{\"code\":\"InstanceRestart\",\"message\":\"Execution orphaned after emulator restart\",\"tags\":[\"RecoveryError\"]}");
            return ps.executeUpdate();
        }
    }

    public Map<String, Object> getExecutionById(String executionId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM workflow_executions WHERE execution_id = ?")) {
            ps.setString(1, executionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rowToMap(rs);
                return null;
            }
        }
    }

    public void resetAll() {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("DELETE FROM workflow_step_entries");
            st.execute("DELETE FROM workflow_executions");
            st.execute("DELETE FROM workflow_revisions");
            st.execute("DELETE FROM workflows");
        } catch (SQLException e) {
            logger.error("Failed to reset workflows data", e);
        }
    }

    public void resetByProject(String projectId) {
        try (Connection conn = dataSource.getConnection()) {
            try (var ps = conn.prepareStatement("DELETE FROM workflow_step_entries WHERE project_id = ?")) {
                ps.setString(1, projectId);
                ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("DELETE FROM workflow_executions WHERE project_id = ?")) {
                ps.setString(1, projectId);
                ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("DELETE FROM workflow_revisions WHERE project_id = ?")) {
                ps.setString(1, projectId);
                ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("DELETE FROM workflows WHERE project_id = ?")) {
                ps.setString(1, projectId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.error("Failed to reset workflows for project {}", projectId, e);
        }
    }

    // --- Revision helpers ---

    private int getCurrentRevisionId(String projectId, String locationId, String workflowId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT revision_id FROM workflows WHERE project_id = ? AND location_id = ? AND workflow_id = ?")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("revision_id");
                return 1;
            }
        }
    }

    private String getWorkflowSource(String projectId, String locationId, String workflowId) throws SQLException {
        return getWorkflowField(projectId, locationId, workflowId, "source_contents");
    }

    private String getWorkflowFieldString(String projectId, String locationId, String workflowId, String field) throws SQLException {
        return getWorkflowField(projectId, locationId, workflowId, field);
    }

    private String getWorkflowField(String projectId, String locationId, String workflowId, String field) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT " + field + " FROM workflows WHERE project_id = ? AND location_id = ? AND workflow_id = ?")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Object val = rs.getObject(1);
                    return val != null ? String.valueOf(val) : "";
                }
                return "";
            }
        }
    }

    public List<Map<String, Object>> listWorkflowRevisions(String projectId, String locationId,
                                                            String workflowId) throws SQLException {
        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT workflow_id, revision_id, source_contents, labels, description, service_account, " +
                "user_env_vars, created_at FROM workflow_revisions " +
                "WHERE project_id = ? AND location_id = ? AND workflow_id = ? ORDER BY revision_id DESC")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> rev = new LinkedHashMap<>();
                    rev.put("workflow_id", rs.getString("workflow_id"));
                    rev.put("revision_id", rs.getInt("revision_id"));
                    rev.put("source_contents", rs.getString("source_contents"));
                    rev.put("labels", rs.getObject("labels"));
                    rev.put("description", rs.getString("description"));
                    rev.put("service_account", rs.getString("service_account"));
                    rev.put("user_env_vars", rs.getObject("user_env_vars"));
                    rev.put("created_at", rs.getTimestamp("created_at"));
                    results.add(rev);
                }
            }
        }
        return results;
    }

    // --- Seed support (UPSERT) ---

    public void upsertWorkflow(String projectId, String locationId, String workflowId,
                                String sourceContents) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO workflows (project_id, location_id, workflow_id, source_contents, state, revision_id, labels, user_env_vars, tags, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, 'ACTIVE', 1, '{}', '{}', '{}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) " +
                "ON CONFLICT (project_id, location_id, workflow_id) DO UPDATE SET " +
                "source_contents = EXCLUDED.source_contents, revision_id = workflows.revision_id + 1, updated_at = CURRENT_TIMESTAMP")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, workflowId);
            ps.setString(4, sourceContents);
            ps.executeUpdate();
        }
    }

    private long asLong(Object value, long fallback) {
        if (value instanceof Number n) return n.longValue();
        if (value != null) {
            try { return Long.parseLong(String.valueOf(value)); }
            catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    private Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            map.put(meta.getColumnName(i), rs.getObject(i));
        }
        return map;
    }
}

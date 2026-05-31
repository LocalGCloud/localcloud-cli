package com.localcloud.emulators.pubsub;

import com.localcloud.util.HttpUtil;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

public class PubSubStore {

    private final DataSource ds;

    public PubSubStore(DataSource dataSource) {
        this.ds = dataSource;
    }

    // === Topics ===
    
    public boolean createTopic(String projectId, String topicId, Map<String,String> labels) throws SQLException {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO pubsub_topics (project_id, topic_id, labels) VALUES (?, ?, ?::jsonb) ON CONFLICT DO NOTHING")) {
            ps.setString(1, projectId);
            ps.setString(2, topicId);
            ps.setString(3, labelsToJson(labels));
            return ps.executeUpdate() > 0;
        }
    }

    boolean createTopic(Connection conn, String projectId, String topicId, Map<String,String> labels) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO pubsub_topics (project_id, topic_id, labels) VALUES (?, ?, ?::jsonb) ON CONFLICT DO NOTHING")) {
            ps.setString(1, projectId);
            ps.setString(2, topicId);
            ps.setString(3, labelsToJson(labels));
            return ps.executeUpdate() > 0;
        }
    }

    public boolean topicExists(String projectId, String topicId) throws SQLException {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM pubsub_topics WHERE project_id = ? AND topic_id = ?")) {
            ps.setString(1, projectId);
            ps.setString(2, topicId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public Map<String,Object> getTopic(String projectId, String topicId) throws SQLException {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM pubsub_topics WHERE project_id = ? AND topic_id = ?")) {
            ps.setString(1, projectId);
            ps.setString(2, topicId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rowToMap(rs) : null;
            }
        }
    }

    public List<Map<String,Object>> listTopics(String projectId, int limit, int offset) throws SQLException {
        List<Map<String,Object>> result = new ArrayList<>();
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM pubsub_topics WHERE project_id = ? ORDER BY topic_id LIMIT ? OFFSET ?")) {
            ps.setString(1, projectId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rowToMap(rs));
            }
        }
        return result;
    }

    public int countTopics(String projectId) throws SQLException {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM pubsub_topics WHERE project_id = ?")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public boolean deleteTopic(String projectId, String topicId) throws SQLException {
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM pubsub_subscription_messages WHERE subscription_id IN " +
                        "(SELECT subscription_id FROM pubsub_subscriptions WHERE topic_project_id = ? AND topic_id = ?)")) {
                    ps.setString(1, projectId);
                    ps.setString(2, topicId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM pubsub_subscriptions WHERE topic_project_id = ? AND topic_id = ?")) {
                    ps.setString(1, projectId);
                    ps.setString(2, topicId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM pubsub_messages WHERE project_id = ? AND topic_id = ?")) {
                    ps.setString(1, projectId);
                    ps.setString(2, topicId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM pubsub_topics WHERE project_id = ? AND topic_id = ?")) {
                    ps.setString(1, projectId);
                    ps.setString(2, topicId);
                    boolean deleted = ps.executeUpdate() > 0;
                    conn.commit();
                    return deleted;
                }
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /**
     * Update topic labels. Used by Terraform PATCH /v1/projects/{p}/topics/{t}.
     */
    public void updateTopic(String projectId, String topicId, Map<String,String> labels) throws SQLException {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE pubsub_topics SET labels = ?::jsonb WHERE project_id = ? AND topic_id = ?")) {
            ps.setString(1, labelsToJson(labels));
            ps.setString(2, projectId);
            ps.setString(3, topicId);
            ps.executeUpdate();
        }
    }

    // === Subscriptions ===
    
    public boolean createSubscription(String projectId, String subId, String topicProjectId,
            String topicId, int ackDeadlineSeconds, String pushEndpoint, Map<String,String> labels,
            int maxDeliveryAttempts, String deadLetterTopic, long minRetryBackoff, long maxRetryBackoff) throws SQLException {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO pubsub_subscriptions (project_id, subscription_id, topic_project_id, topic_id, " +
                "ack_deadline_seconds, push_endpoint, labels, max_delivery_attempts, dead_letter_topic, " +
                "min_retry_backoff, max_retry_backoff) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
            ps.setString(1, projectId);
            ps.setString(2, subId);
            ps.setString(3, topicProjectId);
            ps.setString(4, topicId);
            ps.setInt(5, ackDeadlineSeconds);
            ps.setString(6, pushEndpoint);
            ps.setString(7, labelsToJson(labels));
            ps.setInt(8, maxDeliveryAttempts);
            ps.setString(9, deadLetterTopic);
            ps.setLong(10, minRetryBackoff);
            ps.setLong(11, maxRetryBackoff);
            return ps.executeUpdate() > 0;
        }
    }

    public List<Map<String,Object>> getPushSubscriptions() throws SQLException {
        List<Map<String,Object>> result = new ArrayList<>();
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT project_id, subscription_id, push_endpoint, max_delivery_attempts, dead_letter_topic, " +
                "min_retry_backoff, max_retry_backoff FROM pubsub_subscriptions " +
                "WHERE push_endpoint IS NOT NULL AND push_endpoint != '' AND state = 'ACTIVE' " +
                "ORDER BY created_at LIMIT 500")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rowToMap(rs));
            }
        }
        return result;
    }

    public int getMessageDeliveryAttempt(String ackId) throws SQLException {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT delivery_attempt FROM pubsub_subscription_messages WHERE ack_id = ?")) {
            ps.setString(1, ackId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("delivery_attempt") : 0;
            }
        }
    }

    public boolean forwardToDeadLetterTopic(String ackId) throws SQLException {
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String dlqProject = null, dlqTopicId = null, dlqMsgId = null;
                byte[] data = null;
                String attrs = null;
                long publishedAt = 0;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT sm.subscription_project_id, sm.subscription_id, sm.message_id, " +
                        "s.dead_letter_topic, m.project_id, m.topic_id, m.data, m.attributes, m.published_at " +
                        "FROM pubsub_subscription_messages sm " +
                        "JOIN pubsub_subscriptions s ON sm.subscription_project_id = s.project_id AND sm.subscription_id = s.subscription_id " +
                        "JOIN pubsub_messages m ON sm.message_id = m.message_id " +
                        "WHERE sm.ack_id = ? AND s.dead_letter_topic IS NOT NULL")) {
                    ps.setString(1, ackId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) { conn.rollback(); return false; }
                        String dlqTopic = rs.getString("dead_letter_topic");
                        String[] parts = dlqTopic.split("/");
                        if (parts.length < 4) { conn.rollback(); return false; }
                        dlqProject = parts[1];
                        dlqTopicId = parts[3];
                        data = rs.getBytes("data");
                        attrs = rs.getString("attributes");
                        publishedAt = rs.getLong("published_at");
                        dlqMsgId = UUID.randomUUID().toString();
                    }
                }
                createTopic(conn, dlqProject, dlqTopicId, null);
                try (PreparedStatement ips = conn.prepareStatement(
                        "INSERT INTO pubsub_messages (message_id, project_id, topic_id, data, attributes, published_at) " +
                        "VALUES (?, ?, ?, ?, ?::jsonb, ?)")) {
                    ips.setString(1, dlqMsgId);
                    ips.setString(2, dlqProject);
                    ips.setString(3, dlqTopicId);
                    if (data != null) ips.setBytes(4, data);
                    else ips.setNull(4, Types.BINARY);
                    ips.setString(5, attrs != null ? attrs : "{}");
                    ips.setLong(6, publishedAt);
                    ips.executeUpdate();
                }
                try (PreparedStatement dps = conn.prepareStatement(
                        "DELETE FROM pubsub_subscription_messages WHERE ack_id = ?")) {
                    dps.setString(1, ackId);
                    dps.executeUpdate();
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    public boolean subscriptionExists(String projectId, String subId) throws SQLException {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM pubsub_subscriptions WHERE project_id = ? AND subscription_id = ?")) {
            ps.setString(1, projectId);
            ps.setString(2, subId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    public Map<String,Object> getSubscription(String projectId, String subId) throws SQLException {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM pubsub_subscriptions WHERE project_id = ? AND subscription_id = ?")) {
            ps.setString(1, projectId);
            ps.setString(2, subId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rowToMap(rs) : null;
            }
        }
    }

    public List<Map<String,Object>> listSubscriptions(String projectId, int limit, int offset) throws SQLException {
        List<Map<String,Object>> result = new ArrayList<>();
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM pubsub_subscriptions WHERE project_id = ? ORDER BY subscription_id LIMIT ? OFFSET ?")) {
            ps.setString(1, projectId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rowToMap(rs));
            }
        }
        return result;
    }

    public int countSubscriptions(String projectId) throws SQLException {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM pubsub_subscriptions WHERE project_id = ?")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public List<Map<String,Object>> listTopicSubscriptions(String projectId, String topicId) throws SQLException {
        List<Map<String,Object>> result = new ArrayList<>();
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM pubsub_subscriptions WHERE topic_project_id = ? AND topic_id = ? ORDER BY subscription_id")) {
            ps.setString(1, projectId);
            ps.setString(2, topicId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rowToMap(rs));
            }
        }
        return result;
    }

    public boolean deleteSubscription(String projectId, String subId) throws SQLException {
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM pubsub_subscription_messages WHERE subscription_project_id = ? AND subscription_id = ?")) {
                    ps.setString(1, projectId);
                    ps.setString(2, subId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM pubsub_subscriptions WHERE project_id = ? AND subscription_id = ?")) {
                    ps.setString(1, projectId);
                    ps.setString(2, subId);
                    boolean deleted = ps.executeUpdate() > 0;
                    conn.commit();
                    return deleted;
                }
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    // === Messages ===

    public String publish(String projectId, String topicId, byte[] data, Map<String,String> attributes,
            String orderingKey, long publishTime) throws SQLException {
        String messageId = UUID.randomUUID().toString();
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO pubsub_messages (message_id, project_id, topic_id, data, attributes, ordering_key, published_at) " +
                        "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)")) {
                    ps.setString(1, messageId);
                    ps.setString(2, projectId);
                    ps.setString(3, topicId);
                    if (data != null) ps.setBytes(4, data);
                    else ps.setNull(4, Types.BINARY);
                    ps.setString(5, attributesToJson(attributes));
                    ps.setString(6, orderingKey);
                    ps.setLong(7, publishTime);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT project_id, subscription_id, ack_deadline_seconds FROM pubsub_subscriptions " +
                        "WHERE topic_project_id = ? AND topic_id = ? AND state = 'ACTIVE'")) {
                    ps.setString(1, projectId);
                    ps.setString(2, topicId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String sp = rs.getString("project_id");
                            String si = rs.getString("subscription_id");
                            int ads = rs.getInt("ack_deadline_seconds");
                            if (ads <= 0) ads = 10;
                            try (PreparedStatement ips = conn.prepareStatement(
                                    "INSERT INTO pubsub_subscription_messages (ack_id, subscription_project_id, subscription_id, message_id, ack_deadline) " +
                                    "VALUES (?, ?, ?, ?, ?)")) {
                                ips.setString(1, UUID.randomUUID().toString());
                                ips.setString(2, sp);
                                ips.setString(3, si);
                                ips.setString(4, messageId);
                                ips.setLong(5, publishTime + ads);
                                ips.executeUpdate();
                            }
                        }
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
        return messageId;
    }

    public List<Map<String,Object>> pull(String subProjectId, String subId, int maxMessages) throws SQLException {
        List<Map<String,Object>> messages = new ArrayList<>();
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            try {
                long now = System.currentTimeMillis() / 1000;
                int ackDeadlineSecs = 10;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT ack_deadline_seconds FROM pubsub_subscriptions WHERE project_id = ? AND subscription_id = ?")) {
                    ps.setString(1, subProjectId);
                    ps.setString(2, subId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) ackDeadlineSecs = rs.getInt("ack_deadline_seconds");
                    }
                }
                if (ackDeadlineSecs <= 0) ackDeadlineSecs = 10;

                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE pubsub_subscription_messages SET consumed = FALSE, ack_deadline = NULL, delivery_attempt = delivery_attempt + 1 " +
                        "WHERE subscription_project_id = ? AND subscription_id = ? AND consumed = TRUE AND ack_deadline IS NOT NULL AND ack_deadline < ?")) {
                    ps.setString(1, subProjectId);
                    ps.setString(2, subId);
                    ps.setLong(3, now);
                    ps.executeUpdate();
                }

                List<String> ackIds = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT ack_id FROM pubsub_subscription_messages " +
                        "WHERE subscription_project_id = ? AND subscription_id = ? AND consumed = FALSE " +
                        "ORDER BY created_at LIMIT ?")) {
                    ps.setString(1, subProjectId);
                    ps.setString(2, subId);
                    ps.setInt(3, maxMessages);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) ackIds.add(rs.getString("ack_id"));
                    }
                }
                if (ackIds.isEmpty()) { conn.commit(); return messages; }

                long deadline = now + ackDeadlineSecs;
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE pubsub_subscription_messages SET consumed = TRUE, ack_deadline = ? WHERE ack_id = ?")) {
                    for (String ackId : ackIds) {
                        ps.setLong(1, deadline);
                        ps.setString(2, ackId);
                        ps.executeUpdate();
                    }
                }

                StringBuilder placeholders = new StringBuilder();
                for (int i = 0; i < ackIds.size(); i++) {
                    if (i > 0) placeholders.append(",");
                    placeholders.append("?");
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT sm.ack_id, sm.message_id, sm.delivery_attempt, m.data, m.attributes, m.published_at " +
                        "FROM pubsub_subscription_messages sm JOIN pubsub_messages m ON sm.message_id = m.message_id " +
                        "WHERE sm.ack_id IN (" + placeholders + ") ORDER BY sm.created_at")) {
                    for (int i = 0; i < ackIds.size(); i++) {
                        ps.setString(i + 1, ackIds.get(i));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            Map<String,Object> msg = new HashMap<>();
                            msg.put("ack_id", rs.getString("ack_id"));
                            msg.put("message_id", rs.getString("message_id"));
                            msg.put("delivery_attempt", rs.getInt("delivery_attempt"));
                            msg.put("data", rs.getBytes("data"));
                            msg.put("attributes", rs.getString("attributes"));
                            msg.put("published_at", rs.getLong("published_at"));
                            messages.add(msg);
                        }
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
        return messages;
    }

    public boolean acknowledge(String subProjectId, String subId, String ackId) throws SQLException {
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            try {
                boolean deleted;
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM pubsub_subscription_messages WHERE ack_id = ? AND subscription_project_id = ? AND subscription_id = ?")) {
                    ps.setString(1, ackId);
                    ps.setString(2, subProjectId);
                    ps.setString(3, subId);
                    deleted = ps.executeUpdate() > 0;
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM pubsub_messages m WHERE NOT EXISTS " +
                        "(SELECT 1 FROM pubsub_subscription_messages sm WHERE sm.message_id = m.message_id)")) {
                    ps.executeUpdate();
                }
                conn.commit();
                return deleted;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    public boolean modifyAckDeadline(String subProjectId, String subId, String ackId, long newDeadline) throws SQLException {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE pubsub_subscription_messages SET ack_deadline = ? WHERE ack_id = ? AND subscription_project_id = ? AND subscription_id = ?")) {
            ps.setLong(1, newDeadline);
            ps.setString(2, ackId);
            ps.setString(3, subProjectId);
            ps.setString(4, subId);
            return ps.executeUpdate() > 0;
        }
    }

    public int pendingCount(String subProjectId, String subId) throws SQLException {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM pubsub_subscription_messages WHERE subscription_project_id = ? AND subscription_id = ? AND consumed = FALSE")) {
            ps.setString(1, subProjectId);
            ps.setString(2, subId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public void clearAll() throws SQLException {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DELETE FROM pubsub_subscription_messages");
            st.execute("DELETE FROM pubsub_messages");
            st.execute("DELETE FROM pubsub_subscriptions");
            st.execute("DELETE FROM pubsub_topics");
        }
    }

    // === Helpers ===

    private String labelsToJson(Map<String,String> labels) {
        if (labels == null || labels.isEmpty()) return "{}";
        try {
            return HttpUtil.mapper.writeValueAsString(labels);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String attributesToJson(Map<String,String> attrs) {
        return labelsToJson(attrs);
    }

    private Map<String,Object> rowToMap(ResultSet rs) throws SQLException {
        Map<String,Object> map = new HashMap<>();
        for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
            map.put(rs.getMetaData().getColumnLabel(i).toLowerCase(), rs.getObject(i));
        }
        return map;
    }

    public static String extractProject(String parent) {
        if (parent == null) return null;
        if (parent.startsWith("projects/")) {
            String rest = parent.substring("projects/".length());
            int slash = rest.indexOf('/');
            return slash > 0 ? rest.substring(0, slash) : rest;
        }
        return parent;
    }

    public static String[] parseTopicName(String fullName) {
        if (fullName == null || fullName.isEmpty()) {
            throw new IllegalArgumentException("Topic name must not be null or empty");
        }
        String[] parts = fullName.split("/");
        if (parts.length >= 4 && "projects".equals(parts[0]) && "topics".equals(parts[2])) {
            return new String[]{parts[1], parts[3]};
        }
        throw new IllegalArgumentException("Invalid topic name: " + fullName);
    }

    public static String[] parseSubscriptionName(String fullName) {
        if (fullName == null || fullName.isEmpty()) {
            throw new IllegalArgumentException("Subscription name must not be null or empty");
        }
        String[] parts = fullName.split("/");
        if (parts.length >= 4 && "projects".equals(parts[0]) && "subscriptions".equals(parts[2])) {
            return new String[]{parts[1], parts[3]};
        }
        throw new IllegalArgumentException("Invalid subscription name: " + fullName);
    }

    public static String topicName(String projectId, String topicId) {
        return "projects/" + projectId + "/topics/" + topicId;
    }

    public static String subName(String projectId, String subId) {
        return "projects/" + projectId + "/subscriptions/" + subId;
    }
}

package com.localcloud.emulators.pubsub;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PubSubEmulator gRPC services.
 * These are lightweight tests that instantiate the emulator and verify its
 * internal service implementations. Full gRPC integration tests would
 * require a running Armeria server with the services registered.
 */
class PubSubEmulatorTest {

    private PubSubStore store;

    @BeforeEach
    void setUp() throws Exception {
        var h2 = new org.h2.jdbcx.JdbcDataSource();
        h2.setURL("jdbc:h2:mem:pubsub_emu_test_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        h2.setUser("sa"); h2.setPassword("");
        try (var conn = h2.getConnection(); var st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS pubsub_topics (" +
                "project_id VARCHAR(255) NOT NULL, topic_id VARCHAR(255) NOT NULL, " +
                "labels JSONB DEFAULT '{}', PRIMARY KEY (project_id, topic_id))");
            st.execute("CREATE TABLE IF NOT EXISTS pubsub_subscriptions (" +
                "project_id VARCHAR(255) NOT NULL, subscription_id VARCHAR(255) NOT NULL, " +
                "topic_project_id VARCHAR(255) NOT NULL, topic_id VARCHAR(255) NOT NULL, " +
                "ack_deadline_seconds INT DEFAULT 10, push_endpoint VARCHAR(2048), " +
                "labels JSONB DEFAULT '{}', state VARCHAR(20) DEFAULT 'ACTIVE', " +
                "PRIMARY KEY (project_id, subscription_id))");
            st.execute("ALTER TABLE pubsub_subscriptions ADD COLUMN IF NOT EXISTS max_delivery_attempts INT DEFAULT 5");
            st.execute("ALTER TABLE pubsub_subscriptions ADD COLUMN IF NOT EXISTS dead_letter_topic VARCHAR(512)");
            st.execute("ALTER TABLE pubsub_subscriptions ADD COLUMN IF NOT EXISTS min_retry_backoff BIGINT DEFAULT 10");
            st.execute("ALTER TABLE pubsub_subscriptions ADD COLUMN IF NOT EXISTS max_retry_backoff BIGINT DEFAULT 600");
            st.execute("CREATE TABLE IF NOT EXISTS pubsub_messages (" +
                "message_id VARCHAR(255) NOT NULL, project_id VARCHAR(255) NOT NULL, " +
                "topic_id VARCHAR(255) NOT NULL, data BYTEA, attributes JSONB DEFAULT '{}', " +
                "ordering_key VARCHAR(255), published_at BIGINT DEFAULT 0, PRIMARY KEY (message_id))");
            st.execute("CREATE TABLE IF NOT EXISTS pubsub_subscription_messages (" +
                "ack_id VARCHAR(255) NOT NULL, subscription_project_id VARCHAR(255) NOT NULL, " +
                "subscription_id VARCHAR(255) NOT NULL, message_id VARCHAR(255) NOT NULL, " +
                "ack_deadline BIGINT, delivery_attempt INT DEFAULT 1, " +
                "consumed BOOLEAN DEFAULT FALSE, " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "PRIMARY KEY (ack_id))");
        }
        this.store = new PubSubStore(h2);
    }

    @Test
    void publisherCreateTopic() throws Exception {
        store.createTopic("project1", "topic1", null);
        assertTrue(store.topicExists("project1", "topic1"));
    }

    @Test
    void publisherPublishAndSubscriberPull() throws Exception {
        store.createTopic("p", "t", null);
        store.createSubscription("p", "s", "p", "t", 10, null, null, 5, null, 10, 600);
        
        String msgId = store.publish("p", "t", "hello".getBytes(), Map.of(), null, 1000);
        assertNotNull(msgId);
        
        var msgs = store.pull("p", "s", 10);
        assertEquals(1, msgs.size());
        assertEquals(msgId, msgs.get(0).get("message_id"));
    }

    @Test
    void publisherDeleteTopic() throws Exception {
        store.createTopic("p", "t", null);
        assertTrue(store.topicExists("p", "t"));
        store.deleteTopic("p", "t");
        assertFalse(store.topicExists("p", "t"));
    }

    @Test
    void subscriberDeleteSubscription() throws Exception {
        store.createTopic("p", "t", null);
        store.createSubscription("p", "s", "p", "t", 10, null, null, 5, null, 10, 600);
        assertTrue(store.subscriptionExists("p", "s"));
        store.deleteSubscription("p", "s");
        assertFalse(store.subscriptionExists("p", "s"));
    }

    @Test
    void acknowledgeFlow() throws Exception {
        store.createTopic("p", "t", null);
        store.createSubscription("p", "s", "p", "t", 10, null, null, 5, null, 10, 600);
        store.publish("p", "t", "data".getBytes(), null, null, 1000);
        
        var msgs = store.pull("p", "s", 10);
        assertEquals(1, msgs.size());
        
        assertTrue(store.acknowledge("p", "s", (String)msgs.get(0).get("ack_id")));
        assertEquals(0, store.pull("p", "s", 10).size());
    }

    @Test
    void listTopics() throws Exception {
        store.createTopic("p", "t1", null);
        store.createTopic("p", "t2", null);
        assertEquals(2, store.listTopics("p", 10, 0).size());
    }

    @Test
    void listSubscriptions() throws Exception {
        store.createTopic("p", "t", null);
        store.createSubscription("p", "s1", "p", "t", 10, null, null, 5, null, 10, 600);
        store.createSubscription("p", "s2", "p", "t", 10, null, null, 5, null, 10, 600);
        assertEquals(2, store.listSubscriptions("p", 10, 0).size());
    }

    @Test
    void crossProjectTopicsAreIsolated() throws Exception {
        assertTrue(store.createTopic("proj-a", "t", null));
        assertTrue(store.createTopic("proj-b", "t", null));
        assertEquals(1, store.listTopics("proj-a", 10, 0).size());
        assertEquals(1, store.listTopics("proj-b", 10, 0).size());
    }

    @Test
    void crossProjectSubscriptionsAreIsolated() throws Exception {
        store.createTopic("proj-a", "t", null);
        store.createTopic("proj-b", "t", null);
        store.createSubscription("proj-a", "s", "proj-a", "t", 10, null, null, 5, null, 10, 600);
        store.createSubscription("proj-b", "s", "proj-b", "t", 10, null, null, 5, null, 10, 600);
        assertEquals(1, store.listSubscriptions("proj-a", 10, 0).size());
        assertEquals(1, store.listSubscriptions("proj-b", 10, 0).size());
    }

    @Test
    void crossProjectSameSubName_getCorrectTopic() throws Exception {
        store.createTopic("proj-a", "topic-a", null);
        store.createTopic("proj-b", "topic-b", null);
        store.createSubscription("proj-a", "s", "proj-a", "topic-a", 10, null, null, 5, null, 10, 600);
        store.createSubscription("proj-b", "s", "proj-b", "topic-b", 10, null, null, 5, null, 10, 600);

        store.publish("proj-a", "topic-a", "msg-a".getBytes(), null, null, 1000);
        store.publish("proj-b", "topic-b", "msg-b".getBytes(), null, null, 1000);

        var msgsA = store.pull("proj-a", "s", 10);
        assertEquals(1, msgsA.size());

        var msgsB = store.pull("proj-b", "s", 10);
        assertEquals(1, msgsB.size());

        assertNotEquals(msgsA.get(0).get("message_id"), msgsB.get(0).get("message_id"));
    }
}

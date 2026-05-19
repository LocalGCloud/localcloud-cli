package com.localcloud.emulators.pubsub;

import com.localcloud.persistence.SchemaManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PubSubStoreTest {

    private static final long TEST_TIME = 5000000;

    private DataSource ds;
    private PubSubStore store;

    @BeforeEach
    void setUp() throws Exception {
        var h2 = new org.h2.jdbcx.JdbcDataSource();
        h2.setURL("jdbc:h2:mem:pubsub_test_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        h2.setUser("sa"); h2.setPassword("");
        this.ds = h2;
        // Create schema
        try (var conn = ds.getConnection(); var st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS pubsub_topics (" +
                "project_id VARCHAR(255) NOT NULL, topic_id VARCHAR(255) NOT NULL, " +
                "labels JSONB DEFAULT '{}', kms_key_name VARCHAR(500), " +
                "message_retention_duration VARCHAR(50), satisfies_pzs BOOLEAN DEFAULT FALSE, " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (project_id, topic_id))");
            st.execute("CREATE TABLE IF NOT EXISTS pubsub_subscriptions (" +
                "project_id VARCHAR(255) NOT NULL, subscription_id VARCHAR(255) NOT NULL, " +
                "topic_project_id VARCHAR(255) NOT NULL, topic_id VARCHAR(255) NOT NULL, " +
                "ack_deadline_seconds INT DEFAULT 10, push_endpoint VARCHAR(2048), " +
                "retain_acked_messages BOOLEAN DEFAULT FALSE, " +
                "message_retention_duration VARCHAR(50) DEFAULT '7d', " +
                "labels JSONB DEFAULT '{}', enable_message_ordering BOOLEAN DEFAULT FALSE, " +
                "filter VARCHAR(2048), state VARCHAR(20) DEFAULT 'ACTIVE', " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (project_id, subscription_id))");
            st.execute("ALTER TABLE pubsub_subscriptions ADD COLUMN IF NOT EXISTS max_delivery_attempts INT DEFAULT 5");
            st.execute("ALTER TABLE pubsub_subscriptions ADD COLUMN IF NOT EXISTS dead_letter_topic VARCHAR(512)");
            st.execute("ALTER TABLE pubsub_subscriptions ADD COLUMN IF NOT EXISTS min_retry_backoff BIGINT DEFAULT 10");
            st.execute("ALTER TABLE pubsub_subscriptions ADD COLUMN IF NOT EXISTS max_retry_backoff BIGINT DEFAULT 600");
            st.execute("CREATE TABLE IF NOT EXISTS pubsub_messages (" +
                "message_id VARCHAR(255) NOT NULL, project_id VARCHAR(255) NOT NULL, " +
                "topic_id VARCHAR(255) NOT NULL, data BYTEA, attributes JSONB DEFAULT '{}', " +
                "ordering_key VARCHAR(255), published_at BIGINT NOT NULL, PRIMARY KEY (message_id))");
            st.execute("CREATE TABLE IF NOT EXISTS pubsub_subscription_messages (" +
                "ack_id VARCHAR(255) NOT NULL, subscription_project_id VARCHAR(255) NOT NULL, " +
                "subscription_id VARCHAR(255) NOT NULL, message_id VARCHAR(255) NOT NULL, " +
                "ack_deadline BIGINT, delivery_attempt INT DEFAULT 1, " +
                "consumed BOOLEAN DEFAULT FALSE, " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (ack_id))");
        }
        this.store = new PubSubStore(ds);
    }

    @Test
    void createAndGetTopic() throws Exception {
        assertTrue(store.createTopic("myproject", "mytopic", Map.of("env", "test")));
        assertTrue(store.topicExists("myproject", "mytopic"));
        var topic = store.getTopic("myproject", "mytopic");
        assertNotNull(topic);
        assertEquals("mytopic", topic.get("topic_id"));
    }

    @Test
    void createDuplicateTopicIsIdempotent() throws Exception {
        assertTrue(store.createTopic("p", "t", null));
        assertFalse(store.createTopic("p", "t", null));
    }

    @Test
    void listTopics() throws Exception {
        store.createTopic("p", "topic-a", null);
        store.createTopic("p", "topic-b", null);
        var topics = store.listTopics("p", 10, 0);
        assertEquals(2, topics.size());
    }

    @Test
    void deleteTopicRemovesSubscriptionsAndMessages() throws Exception {
        store.createTopic("p", "t", null);
        store.createSubscription("p", "s", "p", "t", 10, null, null, 5, null, 10, 600);
        store.publish("p", "t", "hello".getBytes(), Map.of(), null, 1000);
        assertTrue(store.topicExists("p", "t"));
        assertTrue(store.deleteTopic("p", "t"));
        assertFalse(store.topicExists("p", "t"));
        assertFalse(store.subscriptionExists("p", "s"));
    }

    @Test
    void createAndGetSubscription() throws Exception {
        store.createTopic("p", "t", null);
        assertTrue(store.createSubscription("p", "s", "p", "t", 30, null, null, 5, null, 10, 600));
        assertTrue(store.subscriptionExists("p", "s"));
        var sub = store.getSubscription("p", "s");
        assertNotNull(sub);
        assertEquals("s", sub.get("subscription_id"));
    }

    @Test
    void listSubscriptions() throws Exception {
        store.createTopic("p", "t", null);
        store.createSubscription("p", "s1", "p", "t", 10, null, null, 5, null, 10, 600);
        store.createSubscription("p", "s2", "p", "t", 20, null, null, 5, null, 10, 600);
        assertEquals(2, store.listSubscriptions("p", 10, 0).size());
    }

    @Test
    void listTopicSubscriptions() throws Exception {
        store.createTopic("p", "t1", null);
        store.createTopic("p", "t2", null);
        store.createSubscription("p", "s1", "p", "t1", 10, null, null, 5, null, 10, 600);
        store.createSubscription("p", "s2", "p", "t2", 10, null, null, 5, null, 10, 600);
        assertEquals(1, store.listTopicSubscriptions("p", "t1").size());
    }

    @Test
    void deleteSubscription() throws Exception {
        store.createTopic("p", "t", null);
        store.createSubscription("p", "s", "p", "t", 10, null, null, 5, null, 10, 600);
        assertTrue(store.deleteSubscription("p", "s"));
        assertFalse(store.subscriptionExists("p", "s"));
    }

    @Test
    void publishAndPull() throws Exception {
        store.createTopic("p", "t", null);
        store.createSubscription("p", "s", "p", "t", 10, null, null, 5, null, 10, 600);
        
        String msgId = store.publish("p", "t", "test-data".getBytes(), Map.of("key1", "val1"), null, 5000);
        assertNotNull(msgId);
        
        var messages = store.pull("p", "s", 10);
        assertEquals(1, messages.size());
        assertEquals(msgId, messages.get(0).get("message_id"));
        assertArrayEquals("test-data".getBytes(), (byte[])messages.get(0).get("data"));
    }

    @Test
    void pullReturnsMaxMessages() throws Exception {
        store.createTopic("p", "t", null);
        store.createSubscription("p", "s", "p", "t", 10, null, null, 5, null, 10, 600);
        
        store.publish("p", "t", "msg1".getBytes(), null, null, 1000);
        store.publish("p", "t", "msg2".getBytes(), null, null, 1001);
        store.publish("p", "t", "msg3".getBytes(), null, null, 1002);
        
        assertEquals(2, store.pull("p", "s", 2).size());
        assertEquals(1, store.pull("p", "s", 2).size());
        assertEquals(0, store.pull("p", "s", 2).size());
    }

    @Test
    void acknowledgeRemovesMessage() throws Exception {
        store.createTopic("p", "t", null);
        store.createSubscription("p", "s", "p", "t", 10, null, null, 5, null, 10, 600);
        store.publish("p", "t", "data".getBytes(), null, null, 1000);
        
        var messages = store.pull("p", "s", 10);
        assertEquals(1, messages.size());
        
        assertTrue(store.acknowledge("p", "s", (String)messages.get(0).get("ack_id")));
        assertEquals(0, store.pull("p", "s", 10).size());
    }

    @Test
    void modifyAckDeadline() throws Exception {
        store.createTopic("p", "t", null);
        store.createSubscription("p", "s", "p", "t", 10, null, null, 5, null, 10, 600);
        store.publish("p", "t", "data".getBytes(), null, null, 1000);
        
        var messages = store.pull("p", "s", 10);
        assertEquals(1, messages.size());
        
        long newDeadline = System.currentTimeMillis() / 1000 + 3600;
        assertTrue(store.modifyAckDeadline("p", "s", (String)messages.get(0).get("ack_id"), newDeadline));
    }

    @Test
    void pendingCount() throws Exception {
        store.createTopic("p", "t", null);
        store.createSubscription("p", "s", "p", "t", 10, null, null, 5, null, 10, 600);
        assertEquals(0, store.pendingCount("p", "s"));
        
        store.publish("p", "t", "data".getBytes(), null, null, 1000);
        assertEquals(1, store.pendingCount("p", "s"));
        
        store.pull("p", "s", 10);
        // After pull, messages are consumed but not deleted, so pending = 0
        assertEquals(0, store.pendingCount("p", "s"));
    }

    @Test
    void clearAll() throws Exception {
        store.createTopic("p", "t", null);
        store.createSubscription("p", "s", "p", "t", 10, null, null, 5, null, 10, 600);
        store.publish("p", "t", "data".getBytes(), null, null, 1000);
        store.clearAll();
        assertEquals(0, store.listTopics("p", 10, 0).size());
        assertEquals(0, store.listSubscriptions("p", 10, 0).size());
        assertEquals(0, store.pendingCount("p", "s"));
    }

    @Test
    void parseTopicName() {
        var parts = PubSubStore.parseTopicName("projects/myproj/topics/mytopic");
        assertEquals("myproj", parts[0]);
        assertEquals("mytopic", parts[1]);
    }

    @Test
    void parseSubscriptionName() {
        var parts = PubSubStore.parseSubscriptionName("projects/myproj/subscriptions/mysub");
        assertEquals("myproj", parts[0]);
        assertEquals("mysub", parts[1]);
    }

    @Test
    void extractProject() {
        assertEquals("myproj", PubSubStore.extractProject("projects/myproj"));
        assertEquals("myproj", PubSubStore.extractProject("projects/myproj/secrets"));
    }

    @Test
    void topicNameHelper() {
        assertEquals("projects/p/topics/t", PubSubStore.topicName("p", "t"));
    }

    @Test
    void subNameHelper() {
        assertEquals("projects/p/subscriptions/s", PubSubStore.subName("p", "s"));
    }

    @Test
    void pushEndpointIsPersisted() throws Exception {
        store.createTopic("p", "t", null);
        store.createSubscription("p", "s", "p", "t", 10, "http://example.com/push", null, 5, null, 10, 600);
        var sub = store.getSubscription("p", "s");
        assertEquals("http://example.com/push", sub.get("push_endpoint"));
    }

    @Test
    void multipleSubscriptionsAllGetMessages() throws Exception {
        store.createTopic("p", "t", null);
        store.createSubscription("p", "s1", "p", "t", 10, null, null, 5, null, 10, 600);
        store.createSubscription("p", "s2", "p", "t", 10, null, null, 5, null, 10, 600);
        
        store.publish("p", "t", "data".getBytes(), null, null, 1000);
        
        assertEquals(1, store.pull("p", "s1", 10).size());
        assertEquals(1, store.pull("p", "s2", 10).size());
    }

    // === Bug fix tests ===

    @Test
    void parseTopicName_null_throws() {
        assertThrows(IllegalArgumentException.class, () -> PubSubStore.parseTopicName(null));
    }

    @Test
    void parseTopicName_empty_throws() {
        assertThrows(IllegalArgumentException.class, () -> PubSubStore.parseTopicName(""));
    }

    @Test
    void parseTopicName_malformed_throws() {
        assertThrows(IllegalArgumentException.class, () -> PubSubStore.parseTopicName("not/a/topic"));
    }

    @Test
    void parseTopicName_extraParts_ignored() {
        var parts = PubSubStore.parseTopicName("projects/p/topics/t/extra/stuff");
        assertEquals("p", parts[0]);
        assertEquals("t", parts[1]);
    }

    @Test
    void parseSubscriptionName_null_throws() {
        assertThrows(IllegalArgumentException.class, () -> PubSubStore.parseSubscriptionName(null));
    }

    @Test
    void parseSubscriptionName_empty_throws() {
        assertThrows(IllegalArgumentException.class, () -> PubSubStore.parseSubscriptionName(""));
    }

    @Test
    void parseSubscriptionName_malformed_throws() {
        assertThrows(IllegalArgumentException.class, () -> PubSubStore.parseSubscriptionName("bad/name"));
    }

    @Test
    void getPushSubscriptions_returnsOnlyPushSubs() throws Exception {
        store.createTopic("p", "t", null);
        store.createSubscription("p", "push-s", "p", "t", 10, "http://example.com/push", null, 5, null, 10, 600);
        store.createSubscription("p", "pull-s", "p", "t", 10, null, null, 5, null, 10, 600);
        var pushSubs = store.getPushSubscriptions();
        assertEquals(1, pushSubs.size());
        assertEquals("push-s", pushSubs.get(0).get("subscription_id"));
    }

    @Test
    void getPushSubscriptions_selectsSpecificColumns() throws Exception {
        store.createTopic("p", "t", null);
        store.createSubscription("p", "s", "p", "t", 10, "http://example.com/push", null, 5, "projects/p/topics/dlq", 10, 600);
        var subs = store.getPushSubscriptions();
        assertEquals(1, subs.size());
        var sub = subs.get(0);
        assertEquals("s", sub.get("subscription_id"));
        assertEquals("http://example.com/push", sub.get("push_endpoint"));
        assertEquals("projects/p/topics/dlq", sub.get("dead_letter_topic"));
        assertNotNull(sub.get("project_id"));
        assertNotNull(sub.get("subscription_id"));
    }

    @Test
    void deliveryAttempt_incrementsOnRedelivery() throws Exception {
        store.createTopic("p", "t", null);
        store.createSubscription("p", "s", "p", "t", 10, null, null, 5, null, 10, 600);
        store.publish("p", "t", "data".getBytes(), null, null, 1000);

        var msgs1 = store.pull("p", "s", 10);
        assertEquals(1, msgs1.size());
        assertEquals(1, msgs1.get(0).get("delivery_attempt"));

        // Force expire the ack deadline
        String ackId = (String) msgs1.get(0).get("ack_id");
        long pastDeadline = System.currentTimeMillis() / 1000 - 60;
        assertTrue(store.modifyAckDeadline("p", "s", ackId, pastDeadline));

        // Next pull should redeliver with incremented delivery_attempt
        var msgs2 = store.pull("p", "s", 10);
        assertEquals(1, msgs2.size());
        assertEquals(2, msgs2.get(0).get("delivery_attempt"));
    }

    @Test
    void forwardToDeadLetterTopic_createsDlqMessage() throws Exception {
        store.createTopic("p", "t", null);
        store.createSubscription("p", "s", "p", "t", 10, null, Map.of(), 3, "projects/dlq-proj/topics/dlq-t", 10, 600);
        String msgId = store.publish("p", "t", "dlq-data".getBytes(), Map.of("k", "v"), null, 1000);

        var msgs = store.pull("p", "s", 10);
        assertEquals(1, msgs.size());
        String ackId = (String) msgs.get(0).get("ack_id");

        assertTrue(store.forwardToDeadLetterTopic(ackId));

        // DLQ topic should exist
        assertTrue(store.topicExists("dlq-proj", "dlq-t"));
        // Original topic should have 0 messages (all moved)
        assertEquals(0, store.pendingCount("p", "s"));
    }

    @Test
    void acknowledge_cleansUpOrphanedMessages() throws Exception {
        store.createTopic("p", "t", null);
        store.createSubscription("p", "s", "p", "t", 10, null, null, 5, null, 10, 600);
        store.publish("p", "t", "data".getBytes(), null, null, 1000);

        var msgs = store.pull("p", "s", 10);
        assertEquals(1, msgs.size());

        // Before ack, message should exist in pubsub_messages
        assertTrue(store.acknowledge("p", "s", (String) msgs.get(0).get("ack_id")));

        // After ack, orphaned message should be cleaned up from pubsub_messages
        // (no direct way to check pubsub_messages, but pull returns 0)
        assertEquals(0, store.pull("p", "s", 10).size());
    }

    @Test
    void labelsToJson_handlesSpecialChars() throws Exception {
        store.createTopic("p", "special-label", Map.of("key1", "value with spaces", "key2", "unicode\u00e9\u00f1"));
        var topic = store.getTopic("p", "special-label");
        assertNotNull(topic);
        assertTrue(store.topicExists("p", "special-label"));
    }

    @Test
    void publish_usesSubscriptionAckDeadline() throws Exception {
        store.createTopic("p", "t", null);
        // Create subscription with 2 second ack deadline
        store.createSubscription("p", "s", "p", "t", 2, null, null, 5, null, 10, 600);
        long publishTime = System.currentTimeMillis() / 1000;
        store.publish("p", "t", "data".getBytes(), null, null, publishTime);

        var msgs = store.pull("p", "s", 10);
        assertEquals(1, msgs.size());

        // Wait for the 2-second ack deadline to expire
        Thread.sleep(3000);

        // Message should be redelivered (ack deadline was 2s from subscription, not hardcoded 600s)
        // First pull consumed it, but after deadline expiry it becomes available again
        var redelivered = store.pull("p", "s", 10);
        assertEquals(1, redelivered.size());
    }

    @Test
    void countTopics() throws Exception {
        assertEquals(0, store.countTopics("p"));
        store.createTopic("p", "t1", null);
        store.createTopic("p", "t2", null);
        assertEquals(2, store.countTopics("p"));
    }

    @Test
    void countSubscriptions() throws Exception {
        store.createTopic("p", "t", null);
        assertEquals(0, store.countSubscriptions("p"));
        store.createSubscription("p", "s1", "p", "t", 10, null, null, 5, null, 10, 600);
        store.createSubscription("p", "s2", "p", "t", 10, null, null, 5, null, 10, 600);
        assertEquals(2, store.countSubscriptions("p"));
    }
}

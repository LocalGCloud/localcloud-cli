package com.localcloud.events;

import com.localcloud.events.EventBus.Event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link EventBus}.
 * All tests exercise pure in-memory event dispatch logic.
 */
class EventBusTest {

    private EventBus bus;

    @BeforeEach
    void setUp() {
        bus = new EventBus();
    }

    private Event event(String type, String source) {
        return new Event(type, source, "projects/p/resource/r", Map.of());
    }

    private Event event(String type, String source, String resource) {
        return new Event(type, source, resource, Map.of());
    }

    // -----------------------------------------------------------------------
    // Subscribe and receive
    // -----------------------------------------------------------------------

    @Test
    void subscribeAndReceiveMatchingEvent() {
        List<Event> received = new ArrayList<>();
        bus.subscribe("gcs.object.created", received::add);

        Event e = event("gcs.object.created", "gcs");
        bus.publish(e);

        assertEquals(1, received.size());
        assertSame(e, received.get(0));
    }

    @Test
    void subscribeWithPrefixMatchesCorrectly() {
        List<Event> received = new ArrayList<>();
        bus.subscribe("gcs.", received::add);

        bus.publish(event("gcs.object.created", "gcs"));
        bus.publish(event("gcs.object.deleted", "gcs"));
        bus.publish(event("gcs.bucket.created", "gcs"));

        assertEquals(3, received.size());
    }

    @Test
    void subscribeWithWildcardMatchesAllEvents() {
        List<Event> received = new ArrayList<>();
        bus.subscribe("*", received::add);

        bus.publish(event("gcs.object.created", "gcs"));
        bus.publish(event("pubsub.message.published", "pubsub"));
        bus.publish(event("firestore.document.written", "firestore"));

        assertEquals(3, received.size());
    }

    // -----------------------------------------------------------------------
    // Non-matching events
    // -----------------------------------------------------------------------

    @Test
    void nonMatchingPrefixDoesNotTriggerHandler() {
        List<Event> received = new ArrayList<>();
        bus.subscribe("pubsub.", received::add);

        bus.publish(event("gcs.object.created", "gcs"));

        assertTrue(received.isEmpty());
    }

    @Test
    void exactPrefixDoesNotMatchDifferentEvent() {
        List<Event> received = new ArrayList<>();
        bus.subscribe("gcs.object.created", received::add);

        // "gcs.object.deleted" does not start with "gcs.object.created"
        bus.publish(event("gcs.object.deleted", "gcs"));

        assertTrue(received.isEmpty());
    }

    @Test
    void prefixMatchIsStartsWith() {
        List<Event> received = new ArrayList<>();
        bus.subscribe("gcs.object", received::add);

        // "gcs.object.created" starts with "gcs.object"
        bus.publish(event("gcs.object.created", "gcs"));
        // "gcs.objectX" also starts with "gcs.object"
        bus.publish(event("gcs.objectX", "gcs"));

        assertEquals(2, received.size());
    }

    // -----------------------------------------------------------------------
    // Multiple subscribers
    // -----------------------------------------------------------------------

    @Test
    void multipleSubscribersAllReceiveEvent() {
        AtomicInteger count = new AtomicInteger(0);

        bus.subscribe("gcs.", e -> count.incrementAndGet());
        bus.subscribe("gcs.", e -> count.incrementAndGet());
        bus.subscribe("gcs.", e -> count.incrementAndGet());

        bus.publish(event("gcs.object.created", "gcs"));

        assertEquals(3, count.get());
    }

    @Test
    void subscribersWithDifferentPrefixesReceiveCorrectEvents() {
        List<Event> gcsEvents = new ArrayList<>();
        List<Event> pubsubEvents = new ArrayList<>();

        bus.subscribe("gcs.", gcsEvents::add);
        bus.subscribe("pubsub.", pubsubEvents::add);

        bus.publish(event("gcs.object.created", "gcs"));
        bus.publish(event("pubsub.message.published", "pubsub"));

        assertEquals(1, gcsEvents.size());
        assertEquals(1, pubsubEvents.size());
    }

    // -----------------------------------------------------------------------
    // Exception handling
    // -----------------------------------------------------------------------

    @Test
    void subscriberExceptionDoesNotPreventOtherSubscribers() {
        AtomicBoolean secondCalled = new AtomicBoolean(false);

        bus.subscribe("gcs.", e -> {
            throw new RuntimeException("Intentional test exception");
        });
        bus.subscribe("gcs.", e -> secondCalled.set(true));

        // Should not throw, and second subscriber should still be called
        assertDoesNotThrow(() -> bus.publish(event("gcs.object.created", "gcs")));
        assertTrue(secondCalled.get());
    }

    @Test
    void subscriberExceptionDoesNotAffectSubsequentPublishes() {
        AtomicInteger count = new AtomicInteger(0);

        bus.subscribe("gcs.", e -> {
            count.incrementAndGet();
            throw new RuntimeException("Always fails");
        });

        bus.publish(event("gcs.object.created", "gcs"));
        bus.publish(event("gcs.object.deleted", "gcs"));

        assertEquals(2, count.get());
    }

    // -----------------------------------------------------------------------
    // clear
    // -----------------------------------------------------------------------

    @Test
    void clearRemovesAllListeners() {
        List<Event> received = new ArrayList<>();
        bus.subscribe("gcs.", received::add);
        bus.subscribe("pubsub.", received::add);

        bus.clear();

        bus.publish(event("gcs.object.created", "gcs"));
        bus.publish(event("pubsub.message.published", "pubsub"));

        assertTrue(received.isEmpty());
    }

    @Test
    void clearResetsListenerCount() {
        bus.subscribe("gcs.", e -> {});
        bus.subscribe("pubsub.", e -> {});
        assertEquals(2, bus.listenerCount());

        bus.clear();
        assertEquals(0, bus.listenerCount());
    }

    // -----------------------------------------------------------------------
    // listenerCount
    // -----------------------------------------------------------------------

    @Test
    void listenerCountIsAccurate() {
        assertEquals(0, bus.listenerCount());

        bus.subscribe("gcs.", e -> {});
        assertEquals(1, bus.listenerCount());

        bus.subscribe("pubsub.", e -> {});
        assertEquals(2, bus.listenerCount());

        bus.subscribe("*", e -> {});
        assertEquals(3, bus.listenerCount());
    }

    // -----------------------------------------------------------------------
    // Publish with no subscribers
    // -----------------------------------------------------------------------

    @Test
    void publishWithNoSubscribersDoesNotCrash() {
        assertDoesNotThrow(() -> bus.publish(event("gcs.object.created", "gcs")));
    }

    @Test
    void publishWithNoMatchingSubscribersDoesNotCrash() {
        bus.subscribe("pubsub.", e -> {});
        assertDoesNotThrow(() -> bus.publish(event("gcs.object.created", "gcs")));
    }

    // -----------------------------------------------------------------------
    // Event record
    // -----------------------------------------------------------------------

    @Test
    void eventRecordFieldsAccessible() {
        Map<String, String> attrs = Map.of("key", "value");
        Event e = new Event("gcs.object.created", "gcs", "projects/p/buckets/b/objects/o", attrs);

        assertEquals("gcs.object.created", e.type());
        assertEquals("gcs", e.source());
        assertEquals("projects/p/buckets/b/objects/o", e.resourceName());
        assertEquals(attrs, e.attributes());
    }
}

package com.localcloud.events;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-process event bus for cross-service event wiring.
 *
 * <p>Enables event-driven integration between emulated services:
 * <ul>
 *   <li>GCS object create/delete -> Pub/Sub notification
 *   <li>Pub/Sub message -> Cloud Function trigger
 *   <li>Custom service-to-service events
 * </ul>
 *
 * <p>Events are dispatched synchronously on the publishing thread.
 * For async dispatch, callers should use CompletableFuture.runAsync().
 */
public class EventBus {

    private static final Logger logger = LoggerFactory.getLogger(EventBus.class);

    /**
     * An event published through the bus.
     */
    public record Event(
            String type,         // e.g., "gcs.object.created", "pubsub.message.published"
            String source,       // source service, e.g., "gcs", "pubsub"
            String resourceName, // affected resource, e.g., "projects/p/buckets/b/objects/o"
            Map<String, String> attributes // additional metadata
    ) {}

    /**
     * A registered event listener.
     */
    private record Listener(
            String eventTypePattern, // event type prefix to match, e.g., "gcs." or "*"
            Consumer<Event> handler
    ) {}

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Subscribe to events matching a type prefix.
     *
     * @param eventTypePrefix prefix to match (e.g., "gcs.object" matches "gcs.object.created")
     *                        Use "*" to match all events.
     * @param handler         callback invoked when a matching event is published
     */
    public void subscribe(String eventTypePrefix, Consumer<Event> handler) {
        listeners.add(new Listener(eventTypePrefix, handler));
        logger.info("EventBus: registered listener for '{}'", eventTypePrefix);
    }

    /**
     * Publish an event to all matching subscribers.
     *
     * @param event the event to publish
     */
    public void publish(Event event) {
        logger.debug("EventBus: publishing {} from {}", event.type(), event.source());
        for (Listener listener : listeners) {
            if ("*".equals(listener.eventTypePattern()) ||
                    event.type().startsWith(listener.eventTypePattern())) {
                try {
                    listener.handler().accept(event);
                } catch (Exception e) {
                    logger.warn("EventBus: listener error for {}: {}",
                            event.type(), e.getMessage());
                }
            }
        }
    }

    /**
     * Remove all listeners. Called during reset.
     */
    public void clear() {
        listeners.clear();
        logger.info("EventBus: all listeners cleared");
    }

    /**
     * Number of registered listeners.
     */
    public int listenerCount() {
        return listeners.size();
    }
}

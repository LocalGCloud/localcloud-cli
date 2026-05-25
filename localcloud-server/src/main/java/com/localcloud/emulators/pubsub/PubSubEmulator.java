package com.localcloud.emulators.pubsub;

import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;
import com.google.pubsub.v1.*;
import com.localcloud.emulators.AbstractEmulator;
import com.localcloud.persistence.PostgresDataSource;
import com.localcloud.util.HttpUtil;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class PubSubEmulator extends AbstractEmulator {

    private static final Logger log = LoggerFactory.getLogger(PubSubEmulator.class);

    private final PubSubStore store;
    private final PublisherServiceImpl publisherService;
    private final SubscriberServiceImpl subscriberService;
    private final PubSubNotifier notifier;
    private final PushDeliveryLoop pushLoop;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public PubSubEmulator(PostgresDataSource dataSource) {
        super("pubsub", "Pub/Sub", 8080, "grpc", "PUBSUB_EMULATOR_HOST");
        this.store = new PubSubStore(dataSource.getDataSource());
        this.notifier = new PubSubNotifier();
        this.publisherService = new PublisherServiceImpl();
        this.subscriberService = new SubscriberServiceImpl();
        this.pushLoop = new PushDeliveryLoop();
    }

    @Override
    protected void doStart() {
        running.set(true);
        Thread.ofVirtual().start(pushLoop);
        log.info("Pub/Sub emulator gRPC services ready, push delivery active");
    }

    @Override
    protected void doStop() {
        running.set(false);
    }

    @Override
    protected void doReset() {
        try {
            store.clearAll();
        } catch (Exception e) {
            log.error("Failed to reset Pub/Sub store", e);
        }
    }

    public PublisherServiceImpl getPublisherService() { return publisherService; }
    public SubscriberServiceImpl getSubscriberService() { return subscriberService; }
    public PubSubStore getStore() { return store; }
    public PubSubNotifier getNotifier() { return notifier; }

    // ========== Push Delivery Loop ==========

    private class PushDeliveryLoop implements Runnable {
        private final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        private final AtomicInteger consecutiveErrors = new AtomicInteger(0);

        @Override
        public void run() {
            while (running.get()) {
                try {
                    deliverPushMessages();
                    consecutiveErrors.set(0);
                } catch (Exception e) {
                    int errs = consecutiveErrors.incrementAndGet();
                    if (errs > 10) {
                        log.warn("Push delivery cycle error ({} consecutive): {}", errs, e.getMessage());
                    } else {
                        log.debug("Push delivery cycle error: {}", e.getMessage());
                    }
                }
                try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
            }
        }

        private void deliverPushMessages() throws Exception {
            var pushSubs = store.getPushSubscriptions();
            for (var sub : pushSubs) {
                String subProjectId = (String) sub.get("project_id");
                String subId = (String) sub.get("subscription_id");
                String pushEndpoint = (String) sub.get("push_endpoint");
                int maxAttempts = sub.get("max_delivery_attempts") instanceof Number
                        ? ((Number) sub.get("max_delivery_attempts")).intValue() : 5;
                String dlqTopic = (String) sub.get("dead_letter_topic");
                long minBackoff = sub.get("min_retry_backoff") instanceof Number
                        ? ((Number) sub.get("min_retry_backoff")).longValue() : 10;
                long maxBackoff = sub.get("max_retry_backoff") instanceof Number
                        ? ((Number) sub.get("max_retry_backoff")).longValue() : 600;

                if (pushEndpoint == null || pushEndpoint.isEmpty()) continue;

                var messages = store.pull(subProjectId, subId, 100);
                for (var msg : messages) {
                    msg.put("subscription_name", PubSubStore.subName(subProjectId, subId));
                    String ackId = (String) msg.get("ack_id");
                    int attempt = msg.get("delivery_attempt") instanceof Number
                            ? ((Number) msg.get("delivery_attempt")).intValue() : 1;

                    if (attempt > maxAttempts) {
                        if (dlqTopic != null && !dlqTopic.isEmpty()) {
                            log.info("Forwarding to DLQ {}: ackId={}, attempt={}", dlqTopic, ackId, attempt);
                            store.forwardToDeadLetterTopic(ackId);
                        } else {
                            store.acknowledge(subProjectId, subId, ackId);
                        }
                        continue;
                    }

                    long backoff = Math.min(minBackoff * (long) Math.pow(2, attempt - 1), maxBackoff);
                    if (attempt > 1) {
                        try { Thread.sleep(backoff * 1000L); } catch (InterruptedException e) { break; }
                    }

                    try {
                        String body = buildPushBody(msg);
                        HttpRequest req = HttpRequest.newBuilder()
                                .uri(URI.create(pushEndpoint))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .timeout(Duration.ofSeconds(10))
                                .build();
                        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                            store.acknowledge(subProjectId, subId, ackId);
                        }
                    } catch (Exception e) {
                        log.debug("Push delivery failed for {}: {}", pushEndpoint, e.getMessage());
                    }
                }
            }
        }

        @SuppressWarnings("unchecked")
        private String buildPushBody(Map<String,Object> msg) {
            try {
                byte[] data = (byte[]) msg.get("data");
                String encodedData = data != null
                        ? Base64.getEncoder().encodeToString(data)
                        : "";
                Map<String,Object> message = new HashMap<>();
                message.put("data", encodedData);
                message.put("messageId", msg.get("message_id"));
                Map<String,Object> attributes = new HashMap<>();
                Object rawAttributes = msg.get("attributes");
                if (rawAttributes instanceof String attrsJson && attrsJson.length() > 2) {
                    try {
                        Object parsed = HttpUtil.mapper.readValue(attrsJson, Object.class);
                        if (parsed instanceof Map<?, ?> m) {
                            m.forEach((k, v) -> attributes.put(String.valueOf(k), String.valueOf(v)));
                        }
                    } catch (Exception ignored) {
                        // attributes JSON was malformed; leave attributes empty
                    }
                }
                message.put("attributes", attributes);
                Map<String,Object> body = new HashMap<>();
                body.put("message", message);
                body.put("subscription", msg.get("subscription_name"));
                return HttpUtil.mapper.writeValueAsString(body);
            } catch (Exception e) {
                return "{}";
            }
        }
    }

    // ========== Publisher Service ==========

    public class PublisherServiceImpl extends PublisherGrpc.PublisherImplBase {

        @Override
        public void createTopic(Topic request, StreamObserver<Topic> obs) {
            incrementRequestCount();
            try {
                String[] parts = PubSubStore.parseTopicName(request.getName());
                Map<String,String> labels = new HashMap<>();
                request.getLabelsMap().forEach((k,v) -> labels.put(k, v));
                if (!store.createTopic(parts[0], parts[1], labels)) {
                    obs.onError(Status.ALREADY_EXISTS.withDescription("Topic already exists: " + request.getName()).asRuntimeException());
                    return;
                }
                obs.onNext(request);
                obs.onCompleted();
            } catch (Exception e) {
                obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void getTopic(GetTopicRequest request, StreamObserver<Topic> obs) {
            incrementRequestCount();
            try {
                String[] parts = PubSubStore.parseTopicName(request.getTopic());
                var data = store.getTopic(parts[0], parts[1]);
                if (data == null) {
                    obs.onError(Status.NOT_FOUND.withDescription("Topic not found: " + request.getTopic()).asRuntimeException());
                    return;
                }
                obs.onNext(buildTopic(request.getTopic()));
                obs.onCompleted();
            } catch (Exception e) {
                obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void listTopics(ListTopicsRequest request, StreamObserver<ListTopicsResponse> obs) {
            incrementRequestCount();
            try {
                String projectId = PubSubStore.extractProject(request.getProject());
                int pageSize = request.getPageSize() > 0 ? request.getPageSize() : 100;
                int offset = 0;
                if (!request.getPageToken().isEmpty()) {
                    offset = Integer.parseInt(new String(Base64.getDecoder().decode(request.getPageToken())));
                }
                var topics = store.listTopics(projectId, pageSize, offset);
                var builder = ListTopicsResponse.newBuilder();
                for (var t : topics) {
                    builder.addTopics(buildTopic("projects/" + t.get("project_id") + "/topics/" + t.get("topic_id")));
                }
                if (topics.size() == pageSize && offset + pageSize < store.countTopics(projectId)) {
                    builder.setNextPageToken(Base64.getEncoder().encodeToString(String.valueOf(offset + pageSize).getBytes()));
                }
                obs.onNext(builder.build());
                obs.onCompleted();
            } catch (Exception e) {
                obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void listTopicSubscriptions(ListTopicSubscriptionsRequest request, StreamObserver<ListTopicSubscriptionsResponse> obs) {
            incrementRequestCount();
            try {
                String[] parts = PubSubStore.parseTopicName(request.getTopic());
                var subs = store.listTopicSubscriptions(parts[0], parts[1]);
                var builder = ListTopicSubscriptionsResponse.newBuilder();
                for (var s : subs) {
                    builder.addSubscriptions("projects/" + s.get("project_id") + "/subscriptions/" + s.get("subscription_id"));
                }
                obs.onNext(builder.build());
                obs.onCompleted();
            } catch (Exception e) {
                obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void deleteTopic(DeleteTopicRequest request, StreamObserver<Empty> obs) {
            incrementRequestCount();
            try {
                String[] parts = PubSubStore.parseTopicName(request.getTopic());
                store.deleteTopic(parts[0], parts[1]);
                obs.onNext(Empty.getDefaultInstance());
                obs.onCompleted();
            } catch (Exception e) {
                obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void publish(PublishRequest request, StreamObserver<PublishResponse> obs) {
            incrementRequestCount();
            try {
                String[] parts = PubSubStore.parseTopicName(request.getTopic());
                long now = System.currentTimeMillis() / 1000;
                List<String> ids = new ArrayList<>();
                for (PubsubMessage msg : request.getMessagesList()) {
                    Map<String,String> attrs = new HashMap<>();
                    msg.getAttributesMap().forEach((k,v) -> attrs.put(k, v));
                    String id = store.publish(parts[0], parts[1],
                            msg.getData().toByteArray(), attrs,
                            msg.getOrderingKey(), now);
                    ids.add(id);
                }
                var subs = store.listTopicSubscriptions(parts[0], parts[1]);
                for (var s : subs) {
                    notifier.notify(parts[0] + "/" + s.get("subscription_id"));
                }
                obs.onNext(PublishResponse.newBuilder().addAllMessageIds(ids).build());
                obs.onCompleted();
            } catch (Exception e) {
                obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void detachSubscription(DetachSubscriptionRequest request, StreamObserver<DetachSubscriptionResponse> obs) {
            obs.onNext(DetachSubscriptionResponse.getDefaultInstance());
            obs.onCompleted();
        }

        @Override
        public void updateTopic(UpdateTopicRequest request, StreamObserver<Topic> obs) {
            incrementRequestCount();
            try {
                String[] parts = PubSubStore.parseTopicName(request.getTopic().getName());
                var data = store.getTopic(parts[0], parts[1]);
                if (data == null) {
                    obs.onError(Status.NOT_FOUND.withDescription("Topic not found").asRuntimeException());
                    return;
                }
                obs.onNext(buildTopic(request.getTopic().getName()));
                obs.onCompleted();
            } catch (Exception e) {
                obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        private Topic buildTopic(String name) {
            return Topic.newBuilder().setName(name).build();
        }
    }

    // ========== Subscriber Service ==========

    public class SubscriberServiceImpl extends SubscriberGrpc.SubscriberImplBase {

        @Override
        public void createSubscription(Subscription request, StreamObserver<Subscription> obs) {
            incrementRequestCount();
            try {
                String[] subParts = PubSubStore.parseSubscriptionName(request.getName());
                String topicFull = request.getTopic();
                String[] topicParts = PubSubStore.parseTopicName(topicFull);

                if (!store.topicExists(topicParts[0], topicParts[1])) {
                    obs.onError(Status.NOT_FOUND.withDescription("Topic not found: " + topicFull).asRuntimeException());
                    return;
                }

                String pushEndpoint = null;
                if (request.hasPushConfig()) pushEndpoint = request.getPushConfig().getPushEndpoint();

                int maxDeliveryAttempts = request.hasDeadLetterPolicy()
                        ? request.getDeadLetterPolicy().getMaxDeliveryAttempts() : 5;
                String deadLetterTopic = request.hasDeadLetterPolicy()
                        ? request.getDeadLetterPolicy().getDeadLetterTopic() : null;

                long minRetryBackoff = 10;
                long maxRetryBackoff = 600;
                if (request.hasRetryPolicy()) {
                    minRetryBackoff = request.getRetryPolicy().getMinimumBackoff().getSeconds();
                    maxRetryBackoff = request.getRetryPolicy().getMaximumBackoff().getSeconds();
                }

                Map<String,String> labels = new HashMap<>();
                request.getLabelsMap().forEach((k,v) -> labels.put(k, v));

                store.createSubscription(subParts[0], subParts[1], topicParts[0], topicParts[1],
                        request.getAckDeadlineSeconds(), pushEndpoint, labels,
                        maxDeliveryAttempts, deadLetterTopic, minRetryBackoff, maxRetryBackoff);

                obs.onNext(buildSubscription(request.getName(), topicFull, request.getAckDeadlineSeconds(), pushEndpoint));
                obs.onCompleted();
            } catch (Exception e) {
                obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void getSubscription(GetSubscriptionRequest request, StreamObserver<Subscription> obs) {
            incrementRequestCount();
            try {
                String[] parts = PubSubStore.parseSubscriptionName(request.getSubscription());
                var data = store.getSubscription(parts[0], parts[1]);
                if (data == null) {
                    obs.onError(Status.NOT_FOUND.withDescription("Subscription not found: " + request.getSubscription()).asRuntimeException());
                    return;
                }
                String topic = PubSubStore.topicName((String)data.get("topic_project_id"), (String)data.get("topic_id"));
                String pushEndpoint = (String)data.get("push_endpoint");
                obs.onNext(buildSubscription(request.getSubscription(), topic,
                        data.get("ack_deadline_seconds") instanceof Number ? ((Number)data.get("ack_deadline_seconds")).intValue() : 10,
                        pushEndpoint));
                obs.onCompleted();
            } catch (Exception e) {
                obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void listSubscriptions(ListSubscriptionsRequest request, StreamObserver<ListSubscriptionsResponse> obs) {
            incrementRequestCount();
            try {
                String projectId = PubSubStore.extractProject(request.getProject());
                int pageSize = request.getPageSize() > 0 ? request.getPageSize() : 100;
                int offset = 0;
                if (!request.getPageToken().isEmpty()) {
                    offset = Integer.parseInt(new String(Base64.getDecoder().decode(request.getPageToken())));
                }
                var subs = store.listSubscriptions(projectId, pageSize, offset);
                var builder = ListSubscriptionsResponse.newBuilder();
                for (var s : subs) {
                    String topic = PubSubStore.topicName((String)s.get("topic_project_id"), (String)s.get("topic_id"));
                    String pushEndpoint = (String)s.get("push_endpoint");
                    builder.addSubscriptions(buildSubscription(
                            PubSubStore.subName((String)s.get("project_id"), (String)s.get("subscription_id")),
                            topic,
                            s.get("ack_deadline_seconds") instanceof Number ? ((Number)s.get("ack_deadline_seconds")).intValue() : 10,
                            pushEndpoint));
                }
                if (subs.size() == pageSize && offset + pageSize < store.countSubscriptions(projectId)) {
                    builder.setNextPageToken(Base64.getEncoder().encodeToString(String.valueOf(offset + pageSize).getBytes()));
                }
                obs.onNext(builder.build());
                obs.onCompleted();
            } catch (Exception e) {
                obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void deleteSubscription(DeleteSubscriptionRequest request, StreamObserver<Empty> obs) {
            incrementRequestCount();
            try {
                String[] parts = PubSubStore.parseSubscriptionName(request.getSubscription());
                store.deleteSubscription(parts[0], parts[1]);
                obs.onNext(Empty.getDefaultInstance());
                obs.onCompleted();
            } catch (Exception e) {
                obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void updateSubscription(UpdateSubscriptionRequest request, StreamObserver<Subscription> obs) {
            incrementRequestCount();
            try {
                String subName = request.getSubscription().getName();
                String[] parts = PubSubStore.parseSubscriptionName(subName);
                var data = store.getSubscription(parts[0], parts[1]);
                if (data == null) {
                    obs.onError(Status.NOT_FOUND.withDescription("Subscription not found").asRuntimeException());
                    return;
                }
                String topic = PubSubStore.topicName((String)data.get("topic_project_id"), (String)data.get("topic_id"));
                String pushEndpoint = (String)data.get("push_endpoint");
                obs.onNext(buildSubscription(subName, topic,
                        data.get("ack_deadline_seconds") instanceof Number ? ((Number)data.get("ack_deadline_seconds")).intValue() : 10,
                        pushEndpoint));
                obs.onCompleted();
            } catch (Exception e) {
                obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void pull(PullRequest request, StreamObserver<PullResponse> obs) {
            incrementRequestCount();
            try {
                String[] parts = PubSubStore.parseSubscriptionName(request.getSubscription());
                var messages = store.pull(parts[0], parts[1], request.getMaxMessages());

                var builder = PullResponse.newBuilder();
                for (var m : messages) {
                    byte[] data = (byte[])m.get("data");
                    PubsubMessage.Builder msgBuilder = PubsubMessage.newBuilder();
                    if (data != null) msgBuilder.setData(com.google.protobuf.ByteString.copyFrom(data));
                    msgBuilder.setMessageId((String)m.get("message_id"));
                    if (m.get("published_at") != null) {
                        msgBuilder.setPublishTime(Timestamp.newBuilder()
                                .setSeconds((Long)m.get("published_at")).build());
                    }
                    if (m.get("attributes") != null) {
                        String attrsJson = (String)m.get("attributes");
                        if (attrsJson != null && attrsJson.length() > 2) {
                            String inner = attrsJson.substring(1, attrsJson.length()-1);
                            for (String pair : inner.split(",")) {
                                String[] kv = pair.split(":", 2);
                                if (kv.length == 2) {
                                    String k = kv[0].trim().replaceAll("^\"|\"$", "");
                                    String v = kv[1].trim().replaceAll("^\"|\"$", "");
                                    msgBuilder.putAttributes(k, v);
                                }
                            }
                        }
                    }

                    builder.addReceivedMessages(ReceivedMessage.newBuilder()
                            .setAckId((String)m.get("ack_id"))
                            .setMessage(msgBuilder)
                            .setDeliveryAttempt((int)m.get("delivery_attempt")));
                }
                obs.onNext(builder.build());
                obs.onCompleted();
            } catch (Exception e) {
                obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public StreamObserver<StreamingPullRequest> streamingPull(StreamObserver<StreamingPullResponse> responseObserver) {
            var serverObserver = (ServerCallStreamObserver<StreamingPullResponse>) responseObserver;
            return new StreamObserver<>() {
                private volatile String subscriptionName;
                private final AtomicBoolean streamActive = new AtomicBoolean(false);

                @Override
                public void onNext(StreamingPullRequest request) {
                    if (subscriptionName == null && !request.getSubscription().isEmpty()) {
                        subscriptionName = request.getSubscription();
                        log.debug("StreamingPull started for {}", subscriptionName);
                        if (streamActive.compareAndSet(false, true)) {
                            Thread.ofVirtual().start(() -> {
                                try {
                                    while (!serverObserver.isCancelled()) {
                                        String[] parts = PubSubStore.parseSubscriptionName(subscriptionName);
                                        var messages = store.pull(parts[0], parts[1], 100);
                                        if (!messages.isEmpty()) {
                                            var builder = StreamingPullResponse.newBuilder();
                                            for (var m : messages) {
                                                PubsubMessage.Builder msgBuilder = PubsubMessage.newBuilder();
                                                byte[] data = (byte[])m.get("data");
                                                if (data != null) msgBuilder.setData(com.google.protobuf.ByteString.copyFrom(data));
                                                msgBuilder.setMessageId((String)m.get("message_id"));
                                                builder.addReceivedMessages(ReceivedMessage.newBuilder()
                                                        .setAckId((String)m.get("ack_id"))
                                                        .setMessage(msgBuilder)
                                                        .setDeliveryAttempt((int)m.get("delivery_attempt")));
                                            }
                                            if (serverObserver.isCancelled()) return;
                                            serverObserver.onNext(builder.build());
                                        } else {
                                            notifier.waitFor(subscriptionName, 2, TimeUnit.SECONDS);
                                        }
                                    }
                                } catch (Exception e) {
                                    if (!serverObserver.isCancelled()) {
                                        log.debug("StreamingPull error (may be normal shutdown): {}", e.getMessage());
                                    }
                                } finally {
                                    streamActive.set(false);
                                }
                            });
                        }
                    }
                    if (!request.getAckIdsList().isEmpty()) {
                        try {
                            String[] parts = PubSubStore.parseSubscriptionName(subscriptionName);
                            for (String ackId : request.getAckIdsList()) {
                                store.acknowledge(parts[0], parts[1], ackId);
                            }
                        } catch (Exception e) {
                            log.warn("Failed to ack messages on stream: {}", e.getMessage());
                        }
                    }
                    List<Integer> deadlineSecs = request.getModifyDeadlineSecondsList();
                    List<String> deadlineAckIds = request.getModifyDeadlineAckIdsList();
                    if (!deadlineSecs.isEmpty() && !deadlineAckIds.isEmpty()) {
                        try {
                            String[] parts = PubSubStore.parseSubscriptionName(subscriptionName);
                            int size = Math.min(deadlineSecs.size(), deadlineAckIds.size());
                            for (int idx = 0; idx < size; idx++) {
                                long deadline = System.currentTimeMillis() / 1000 + deadlineSecs.get(idx);
                                store.modifyAckDeadline(parts[0], parts[1], deadlineAckIds.get(idx), deadline);
                            }
                        } catch (Exception e) {
                            log.warn("Failed to modify ack deadline on stream: {}", e.getMessage());
                        }
                    }
                }

                @Override
                public void onError(Throwable t) {
                    if (subscriptionName != null) {
                        log.debug("StreamingPull error for {}: {}", subscriptionName, t.getMessage());
                    } else {
                        log.debug("StreamingPull error before subscription set: {}", t.getMessage());
                    }
                }

                @Override
                public void onCompleted() {
                    log.debug("StreamingPull completed for {}", subscriptionName);
                }
            };
        }

        @Override
        public void acknowledge(AcknowledgeRequest request, StreamObserver<Empty> obs) {
            incrementRequestCount();
            try {
                String[] parts = PubSubStore.parseSubscriptionName(request.getSubscription());
                for (String ackId : request.getAckIdsList()) {
                    store.acknowledge(parts[0], parts[1], ackId);
                }
                obs.onNext(Empty.getDefaultInstance());
                obs.onCompleted();
            } catch (Exception e) {
                obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void modifyAckDeadline(ModifyAckDeadlineRequest request, StreamObserver<Empty> obs) {
            incrementRequestCount();
            try {
                String[] parts = PubSubStore.parseSubscriptionName(request.getSubscription());
                long deadline = System.currentTimeMillis() / 1000 + request.getAckDeadlineSeconds();
                for (String ackId : request.getAckIdsList()) {
                    store.modifyAckDeadline(parts[0], parts[1], ackId, deadline);
                }
                obs.onNext(Empty.getDefaultInstance());
                obs.onCompleted();
            } catch (Exception e) {
                obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void modifyPushConfig(ModifyPushConfigRequest request, StreamObserver<Empty> obs) {
            obs.onNext(Empty.getDefaultInstance());
            obs.onCompleted();
        }

        @Override
        public void createSnapshot(CreateSnapshotRequest request, StreamObserver<Snapshot> obs) {
            obs.onNext(Snapshot.newBuilder().setName(request.getName()).build());
            obs.onCompleted();
        }
        @Override
        public void getSnapshot(GetSnapshotRequest request, StreamObserver<Snapshot> obs) {
            obs.onNext(Snapshot.newBuilder().setName(request.getSnapshot()).build());
            obs.onCompleted();
        }
        @Override
        public void listSnapshots(ListSnapshotsRequest request, StreamObserver<ListSnapshotsResponse> obs) {
            obs.onNext(ListSnapshotsResponse.getDefaultInstance());
            obs.onCompleted();
        }
        @Override
        public void updateSnapshot(UpdateSnapshotRequest request, StreamObserver<Snapshot> obs) {
            obs.onNext(request.getSnapshot());
            obs.onCompleted();
        }
        @Override
        public void deleteSnapshot(DeleteSnapshotRequest request, StreamObserver<Empty> obs) {
            obs.onNext(Empty.getDefaultInstance());
            obs.onCompleted();
        }
        @Override
        public void seek(SeekRequest request, StreamObserver<SeekResponse> obs) {
            obs.onNext(SeekResponse.getDefaultInstance());
            obs.onCompleted();
        }

        private Subscription buildSubscription(String name, String topicFull, int ackDeadlineSecs, String pushEndpoint) {
            var builder = Subscription.newBuilder()
                    .setName(name)
                    .setTopic(topicFull)
                    .setAckDeadlineSeconds(ackDeadlineSecs);
            if (pushEndpoint != null && !pushEndpoint.isEmpty()) {
                builder.setPushConfig(PushConfig.newBuilder().setPushEndpoint(pushEndpoint).build());
            }
            return builder.build();
        }
    }

    // ========== Notifier for StreamingPull ==========

    public static class PubSubNotifier {
        private final ConcurrentHashMap<String, CompletableFuture<Void>> futures = new ConcurrentHashMap<>();

        public void notify(String subscriptionKey) {
            CompletableFuture<Void> f = futures.get(subscriptionKey);
            if (f != null) f.complete(null);
        }

        public void waitFor(String subscriptionKey, long timeout, TimeUnit unit) throws Exception {
            CompletableFuture<Void> f = new CompletableFuture<>();
            futures.put(subscriptionKey, f);
            try {
                f.get(timeout, unit);
            } catch (java.util.concurrent.TimeoutException e) {
            } finally {
                futures.remove(subscriptionKey);
            }
        }
    }
}

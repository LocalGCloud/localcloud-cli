"""Google Cloud Pub/Sub demo using the official Python SDK."""

import os
import uuid

import grpc
from google.auth.credentials import AnonymousCredentials
from google.cloud import pubsub_v1


def _make_publisher() -> pubsub_v1.PublisherClient:
    """Create a Publisher client pointing at LocalCloud."""
    host = os.environ.get("PUBSUB_EMULATOR_HOST", "localhost:8085")
    channel = grpc.insecure_channel(host)
    transport = pubsub_v1.PublisherClient.get_transport_class("grpc")(
        host=f"http://{host}",
        credentials=AnonymousCredentials(),
        channel=channel,
    )
    return pubsub_v1.PublisherClient(transport=transport)


def _make_subscriber() -> pubsub_v1.SubscriberClient:
    """Create a Subscriber client pointing at LocalCloud."""
    host = os.environ.get("PUBSUB_EMULATOR_HOST", "localhost:8085")
    channel = grpc.insecure_channel(host)
    transport = pubsub_v1.SubscriberClient.get_transport_class("grpc")(
        host=f"http://{host}",
        credentials=AnonymousCredentials(),
        channel=channel,
    )
    return pubsub_v1.SubscriberClient(transport=transport)


def run(project_id: str, keep_data: bool = False) -> list[tuple[str, bool, str]]:
    """Run Pub/Sub demo operations. Returns list of (operation, success, detail)."""
    results = []
    publisher = _make_publisher()
    subscriber = _make_subscriber()

    topic_id = f"demo-topic-{uuid.uuid4().hex[:8]}"
    sub_id = f"demo-sub-{uuid.uuid4().hex[:8]}"
    topic_path = publisher.topic_path(project_id, topic_id)
    sub_path = subscriber.subscription_path(project_id, sub_id)

    # 1. Create topic
    try:
        publisher.create_topic(request={"name": topic_path})
        results.append(("Create topic", True, topic_id))
    except Exception as e:
        results.append(("Create topic", False, str(e)))
        return results

    # 2. Create subscription
    try:
        subscriber.create_subscription(
            request={"name": sub_path, "topic": topic_path}
        )
        results.append(("Create subscription", True, sub_id))
    except Exception as e:
        results.append(("Create subscription", False, str(e)))

    # 3. Publish messages
    messages = ["message-1", "message-2", "message-3"]
    try:
        futures = []
        for msg in messages:
            future = publisher.publish(topic_path, msg.encode("utf-8"))
            futures.append(future)
        for f in futures:
            f.result(timeout=10)
        results.append(("Publish messages", True, f"{len(messages)} messages"))
    except Exception as e:
        results.append(("Publish messages", False, str(e)))

    # 4. Pull and acknowledge
    try:
        response = subscriber.pull(
            request={"subscription": sub_path, "max_messages": 10},
            timeout=10,
        )
        received = [msg.message.data.decode("utf-8") for msg in response.received_messages]
        ack_ids = [msg.ack_id for msg in response.received_messages]
        if ack_ids:
            subscriber.acknowledge(
                request={"subscription": sub_path, "ack_ids": ack_ids}
            )
        assert set(messages) == set(received), f"expected {messages}, got {received}"
        results.append(("Pull & ack messages", True, f"{len(received)} received"))
    except Exception as e:
        results.append(("Pull & ack messages", False, str(e)))

    # 5. Get topic
    try:
        topic = publisher.get_topic(request={"topic": topic_path})
        assert topic.name == topic_path, f"expected {topic_path}, got {topic.name}"
        results.append(("Get topic", True, topic_id))
    except Exception as e:
        results.append(("Get topic", False, str(e)))

    # 6. List topics
    try:
        topics = list(publisher.list_topics(request={"project": f"projects/{project_id}"}))
        topic_names = [t.name for t in topics]
        assert topic_path in topic_names, f"{topic_path} not in {topic_names}"
        results.append(("List topics", True, f"{len(topics)} topic(s)"))
    except Exception as e:
        results.append(("List topics", False, str(e)))

    # 7. Get subscription
    try:
        sub = subscriber.get_subscription(request={"subscription": sub_path})
        assert sub.name == sub_path, f"expected {sub_path}, got {sub.name}"
        assert sub.topic == topic_path, f"expected topic {topic_path}, got {sub.topic}"
        results.append(("Get subscription", True, sub_id))
    except Exception as e:
        results.append(("Get subscription", False, str(e)))

    # 8. List subscriptions
    try:
        subs = list(subscriber.list_subscriptions(request={"project": f"projects/{project_id}"}))
        sub_names = [s.name for s in subs]
        assert sub_path in sub_names, f"{sub_path} not in {sub_names}"
        results.append(("List subscriptions", True, f"{len(subs)} subscription(s)"))
    except Exception as e:
        results.append(("List subscriptions", False, str(e)))

    # 9. Publish with attributes
    try:
        future = publisher.publish(
            topic_path,
            b"event-payload",
            event_type="user.signup",
            source="demo-service",
            priority="high",
        )
        future.result(timeout=10)
        # Pull and verify attributes
        response = subscriber.pull(
            request={"subscription": sub_path, "max_messages": 10},
            timeout=10,
        )
        attr_msg = None
        for rm in response.received_messages:
            if rm.message.data == b"event-payload":
                attr_msg = rm.message
                break
        assert attr_msg is not None, "attribute message not received"
        assert attr_msg.attributes.get("event_type") == "user.signup", \
            f"expected event_type=user.signup, got {attr_msg.attributes}"
        assert attr_msg.attributes.get("source") == "demo-service", \
            f"expected source=demo-service, got {attr_msg.attributes}"
        # Ack all
        ack_ids = [rm.ack_id for rm in response.received_messages]
        if ack_ids:
            subscriber.acknowledge(request={"subscription": sub_path, "ack_ids": ack_ids})
        results.append(("Publish with attributes", True, "3 attributes verified"))
    except Exception as e:
        results.append(("Publish with attributes", False, str(e)))

    # 10. Fanout — 2 subscriptions on same topic
    try:
        sub_id_2 = f"demo-sub2-{uuid.uuid4().hex[:8]}"
        sub_path_2 = subscriber.subscription_path(project_id, sub_id_2)
        subscriber.create_subscription(
            request={"name": sub_path_2, "topic": topic_path}
        )
        # Publish one message
        future = publisher.publish(topic_path, b"fanout-test")
        future.result(timeout=10)
        # Both subscriptions should receive the message
        resp1 = subscriber.pull(
            request={"subscription": sub_path, "max_messages": 10}, timeout=10,
        )
        resp2 = subscriber.pull(
            request={"subscription": sub_path_2, "max_messages": 10}, timeout=10,
        )
        data1 = [m.message.data for m in resp1.received_messages]
        data2 = [m.message.data for m in resp2.received_messages]
        assert b"fanout-test" in data1, f"sub1 didn't get fanout msg: {data1}"
        assert b"fanout-test" in data2, f"sub2 didn't get fanout msg: {data2}"
        # Ack both
        for sub_p, resp in [(sub_path, resp1), (sub_path_2, resp2)]:
            aids = [m.ack_id for m in resp.received_messages]
            if aids:
                subscriber.acknowledge(request={"subscription": sub_p, "ack_ids": aids})
        # Cleanup sub2
        subscriber.delete_subscription(request={"subscription": sub_path_2})
        results.append(("Fanout (2 subscriptions)", True, "both received"))
    except Exception as e:
        results.append(("Fanout (2 subscriptions)", False, str(e)))

    # 11. Delete subscription
    if not keep_data:
        try:
            subscriber.delete_subscription(request={"subscription": sub_path})
            results.append(("Delete subscription", True, sub_id))
        except Exception as e:
            results.append(("Delete subscription", False, str(e)))
    else:
        results.append(("Skip cleanup", True, "data preserved for inspection"))

    # 12. Delete topic
    if not keep_data:
        try:
            publisher.delete_topic(request={"topic": topic_path})
            results.append(("Delete topic", True, topic_id))
        except Exception as e:
            results.append(("Delete topic", False, str(e)))
    else:
        results.append(("Skip cleanup", True, "data preserved for inspection"))

    return results

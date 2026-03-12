"""Google Cloud Tasks demo using the official Python SDK."""

import json
import time
import uuid

import grpc
from google.auth.credentials import AnonymousCredentials
from google.cloud import tasks_v2
from google.protobuf import timestamp_pb2


def _make_client() -> tasks_v2.CloudTasksClient:
    """Create a Cloud Tasks client pointing at LocalCloud."""
    import os

    host = os.environ.get("CLOUD_TASKS_EMULATOR_HOST", "localhost:8080")
    channel = grpc.insecure_channel(host)
    transport = tasks_v2.CloudTasksClient.get_transport_class("grpc")(
        host=f"http://{host}",
        credentials=AnonymousCredentials(),
        channel=channel,
    )
    return tasks_v2.CloudTasksClient(transport=transport)


def run(project_id: str) -> list[tuple[str, bool, str]]:
    """Run Cloud Tasks demo operations. Returns list of (operation, success, detail)."""
    results = []
    client = _make_client()
    location = "us-central1"
    queue_id = f"demo-queue-{uuid.uuid4().hex[:8]}"
    parent = f"projects/{project_id}/locations/{location}"
    queue_name = f"{parent}/queues/{queue_id}"

    # 1. Create queue
    try:
        client.create_queue(
            request={"parent": parent, "queue": {"name": queue_name}}
        )
        results.append(("Create queue", True, queue_id))
    except Exception as e:
        results.append(("Create queue", False, str(e)))
        return results

    # 2. Create HTTP task
    task_name = None
    try:
        task = {
            "http_request": {
                "http_method": tasks_v2.HttpMethod.POST,
                "url": "https://httpbin.org/post",
                "headers": {"Content-Type": "application/json"},
                "body": json.dumps({"hello": "localcloud"}).encode(),
            }
        }
        created = client.create_task(request={"parent": queue_name, "task": task})
        task_name = created.name
        results.append(("Create HTTP task", True, task_name.split("/")[-1]))
    except Exception as e:
        results.append(("Create HTTP task", False, str(e)))

    # 3. List tasks
    try:
        tasks_list = list(client.list_tasks(request={"parent": queue_name}))
        results.append(("List tasks", True, f"{len(tasks_list)} task(s)"))
    except Exception as e:
        results.append(("List tasks", False, str(e)))

    # 4. Get task details
    if task_name:
        try:
            got = client.get_task(request={"name": task_name})
            results.append(("Get task details", True, got.name.split("/")[-1]))
        except Exception as e:
            results.append(("Get task details", False, str(e)))

    # 5. Delete task
    if task_name:
        try:
            client.delete_task(request={"name": task_name})
            results.append(("Delete task", True, task_name.split("/")[-1]))
        except Exception as e:
            results.append(("Delete task", False, str(e)))

    # 6. Get queue
    try:
        queue = client.get_queue(request={"name": queue_name})
        assert queue.state == tasks_v2.Queue.State.RUNNING, f"expected RUNNING, got {queue.state}"
        results.append(("Get queue", True, f"state={queue.state.name}"))
    except Exception as e:
        results.append(("Get queue", False, str(e)))

    # 7. List queues
    try:
        queues = list(client.list_queues(request={"parent": parent}))
        queue_names_list = [q.name for q in queues]
        assert queue_name in queue_names_list, f"{queue_name} not in {queue_names_list}"
        results.append(("List queues", True, f"{len(queues)} queue(s)"))
    except Exception as e:
        results.append(("List queues", False, str(e)))

    # 8. Run task (force immediate execution)
    try:
        run_task = {
            "http_request": {
                "http_method": tasks_v2.HttpMethod.POST,
                "url": "https://httpbin.org/post",
                "body": b'{"run": "now"}',
            }
        }
        created_run = client.create_task(request={"parent": queue_name, "task": run_task})
        ran = client.run_task(request={"name": created_run.name})
        results.append(("Run task", True, ran.name.split("/")[-1]))
    except Exception as e:
        results.append(("Run task", False, str(e)))

    # 9. Pause queue
    try:
        client.pause_queue(request={"name": queue_name})
        results.append(("Pause queue", True, queue_id))
    except Exception as e:
        results.append(("Pause queue", False, str(e)))

    # 10. Resume queue
    try:
        client.resume_queue(request={"name": queue_name})
        results.append(("Resume queue", True, queue_id))
    except Exception as e:
        results.append(("Resume queue", False, str(e)))

    # 11. Create task with schedule_time and custom headers
    try:
        schedule_ts = timestamp_pb2.Timestamp()
        schedule_ts.FromSeconds(int(time.time()) + 3600)  # 1 hour from now
        scheduled_task = {
            "http_request": {
                "http_method": tasks_v2.HttpMethod.POST,
                "url": "https://httpbin.org/post",
                "headers": {
                    "Content-Type": "application/json",
                    "X-Custom-Header": "localcloud-demo",
                    "X-Request-Id": str(uuid.uuid4()),
                },
                "body": json.dumps({"scheduled": True}).encode(),
            },
            "schedule_time": schedule_ts,
        }
        created_scheduled = client.create_task(
            request={"parent": queue_name, "task": scheduled_task}
        )
        assert created_scheduled.schedule_time is not None, "schedule_time not set"
        assert created_scheduled.http_request.headers.get("X-Custom-Header") == "localcloud-demo", \
            f"custom header missing: {created_scheduled.http_request.headers}"
        results.append(("Scheduled task with headers", True,
                        f"scheduled={created_scheduled.schedule_time}"))
    except Exception as e:
        results.append(("Scheduled task with headers", False, str(e)))

    # 12. Delete queue
    try:
        client.delete_queue(request={"name": queue_name})
        results.append(("Delete queue", True, queue_id))
    except Exception as e:
        results.append(("Delete queue", False, str(e)))

    return results

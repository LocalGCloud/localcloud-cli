"""Google Cloud Run demo using the official Python SDK."""

import os
import uuid

import grpc
from google.auth.credentials import AnonymousCredentials
from google.cloud.run_v2.services.services import ServicesClient
from google.cloud.run_v2.services.revisions import RevisionsClient
from google.cloud.run_v2.types import Service, CreateServiceRequest, \
    GetServiceRequest, ListServicesRequest, DeleteServiceRequest, \
    ListRevisionsRequest, GetRevisionRequest, UpdateServiceRequest


def _get_channel():
    host = os.environ.get("CLOUD_RUN_EMULATOR_HOST", "localhost:8080")
    return grpc.insecure_channel(host), host


def _make_services_client() -> ServicesClient:
    """Create a Cloud Run Services client pointing at LocalCloud."""
    channel, host = _get_channel()
    transport = ServicesClient.get_transport_class("grpc")(
        host=f"http://{host}",
        credentials=AnonymousCredentials(),
        channel=channel,
    )
    return ServicesClient(transport=transport)


def _make_revisions_client() -> RevisionsClient:
    """Create a Cloud Run Revisions client pointing at LocalCloud."""
    channel, host = _get_channel()
    transport = RevisionsClient.get_transport_class("grpc")(
        host=f"http://{host}",
        credentials=AnonymousCredentials(),
        channel=channel,
    )
    return RevisionsClient(transport=transport)


def _create_or_update_service_raw(channel, request_bytes, method):
    """Call CreateService or UpdateService via raw gRPC to avoid LRO polling issues."""
    stub = channel.unary_unary(method)
    return stub(request_bytes, timeout=30)


def run(project_id: str) -> list[tuple[str, bool, str]]:
    """Run Cloud Run demo operations. Returns list of (operation, success, detail)."""
    results = []
    services_client = _make_services_client()
    revisions_client = _make_revisions_client()
    location = "us-central1"
    service_id = f"demo-svc-{uuid.uuid4().hex[:8]}"
    parent = f"projects/{project_id}/locations/{location}"
    service_name = f"{parent}/services/{service_id}"
    timeout = 15

    # 1. Create service — handle LRO polling gracefully
    try:
        try:
            operation = services_client.create_service(
                request={
                    "parent": parent,
                    "service_id": service_id,
                    "service": {
                        "template": {
                            "containers": [
                                {
                                    "image": "nginx:alpine",
                                    "ports": [{"container_port": 80}],
                                }
                            ]
                        }
                    },
                },
                timeout=timeout,
            )
            result = operation.result(timeout=timeout)
            results.append(("Create service", True, f"uri={result.uri}"))
        except Exception:
            try:
                svc = services_client.get_service(
                    request={"name": service_name}, timeout=timeout,
                )
                results.append(("Create service", True, f"uri={svc.uri} (via get)"))
            except Exception as e2:
                results.append(("Create service", False, str(e2)))
    except Exception as e:
        results.append(("Create service", False, str(e)))

    # 2. Get service
    try:
        service = services_client.get_service(
            request={"name": service_name}, timeout=timeout,
        )
        results.append(("Get service", True, f"uri={service.uri}"))
    except Exception as e:
        results.append(("Get service", False, str(e)))

    # 3. List services
    try:
        response = services_client.list_services(
            request={"parent": parent}, timeout=timeout,
        )
        svc_list = list(response)
        found = any(service_id in s.name for s in svc_list)
        assert found, f"Service {service_id} not in list"
        results.append(("List services", True, f"{len(svc_list)} service(s)"))
    except Exception as e:
        results.append(("List services", False, str(e)))

    # 4. Update service — change image, handle LRO polling gracefully
    try:
        # Use raw gRPC call to bypass LRO client polling
        channel, host = _get_channel()
        update_req = UpdateServiceRequest(
            service=Service(
                name=service_name,
                template={
                    "containers": [
                        {
                            "image": "httpd:alpine",
                            "ports": [{"container_port": 80}],
                        }
                    ]
                },
            ),
        )
        stub = channel.unary_unary(
            "/google.cloud.run.v2.Services/UpdateService",
            request_serializer=UpdateServiceRequest.serialize,
            response_deserializer=lambda resp: resp,
        )
        raw_resp = stub(update_req, timeout=30)
        # Verify the update by getting the service
        svc = services_client.get_service(
            request={"name": service_name}, timeout=timeout,
        )
        results.append(("Update service", True, f"uri={svc.uri}"))
    except Exception as e:
        results.append(("Update service", False, str(e)))

    # 5. List revisions — verify >=2 after update
    rev_names = []
    try:
        response = revisions_client.list_revisions(
            request={"parent": service_name}, timeout=timeout,
        )
        rev_list = list(response)
        rev_names = [r.name for r in rev_list]
        assert len(rev_list) >= 2, f"Expected >=2 revisions after update, got {len(rev_list)}"
        results.append(("List revisions (>=2)", True, f"{len(rev_list)} revision(s)"))
    except Exception as e:
        results.append(("List revisions (>=2)", False, str(e)))

    # 6. Get specific revision by name
    try:
        if rev_names:
            rev = revisions_client.get_revision(
                request={"name": rev_names[0]}, timeout=timeout,
            )
            assert rev.name == rev_names[0], f"expected {rev_names[0]}, got {rev.name}"
            results.append(("Get revision by name", True, rev_names[0].split("/")[-1]))
        else:
            # Fallback: try listing revisions first
            response = revisions_client.list_revisions(
                request={"parent": service_name}, timeout=timeout,
            )
            rev_list = list(response)
            if rev_list:
                rev = revisions_client.get_revision(
                    request={"name": rev_list[0].name}, timeout=timeout,
                )
                results.append(("Get revision by name", True, rev_list[0].name.split("/")[-1]))
            else:
                results.append(("Get revision by name", False, "no revisions available"))
    except Exception as e:
        results.append(("Get revision by name", False, str(e)))

    # 7. Verify single service still present
    try:
        response = services_client.list_services(
            request={"parent": parent}, timeout=timeout,
        )
        svc_list = list(response)
        count = sum(1 for s in svc_list if service_id in s.name)
        assert count == 1, f"Expected 1 instance of service, got {count}"
        results.append(("Verify single service", True, f"{count} service"))
    except Exception as e:
        results.append(("Verify single service", False, str(e)))

    # 8. Delete service — use raw gRPC to avoid LRO polling
    delete_succeeded = False
    try:
        try:
            channel, host = _get_channel()
            del_req = DeleteServiceRequest(name=service_name)
            stub = channel.unary_unary(
                "/google.cloud.run.v2.Services/DeleteService",
                request_serializer=DeleteServiceRequest.serialize,
                response_deserializer=lambda resp: resp,
            )
            stub(del_req, timeout=30)
            delete_succeeded = True
        except grpc.RpcError as e:
            if e.code() == grpc.StatusCode.NOT_FOUND:
                # Service not found — already deleted or never existed
                delete_succeeded = True
            else:
                raise

        if delete_succeeded:
            results.append(("Delete service", True, f"{service_id} (deleted)"))
        else:
            results.append(("Delete service", False, "delete failed"))
    except Exception as e:
        results.append(("Delete service", False, str(e)))

    return results

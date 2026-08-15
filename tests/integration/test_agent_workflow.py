from __future__ import annotations

import json
from pathlib import Path
from typing import Any
from uuid import uuid4

import pytest

from integration._support import assert_loopback_url, controller_for, write_config
from localcloud_cli.cli import main
from localcloud_cli.config import DEFAULT_PROJECT, default_resource_names
from localcloud_cli.docker_runtime import (
    MANAGED_LABEL,
    _container_environment,
)
from localcloud_cli.java_client import JavaMcpClient
from localcloud_cli.mcp_stdio import McpAdapter


pytestmark = pytest.mark.docker

PROJECT = "agent-project-1"
SECOND_PROJECT = "agent-project-2"
USER = "integration-agent"


def _invoke(
    capsys: pytest.CaptureFixture[str], *arguments: str
) -> dict[str, Any]:
    code = main([*arguments, "--verbose"])
    captured = capsys.readouterr()
    assert code == 0, captured.err
    assert not captured.err
    return json.loads(captured.out)


def _invoke_error(
    capsys: pytest.CaptureFixture[str], *arguments: str
) -> dict[str, Any]:
    code = main([*arguments, "--verbose"])
    captured = capsys.readouterr()
    assert code == 2, captured.out
    return json.loads(captured.err)


def _resource_inventory(java: JavaMcpClient, service: str) -> str:
    return json.dumps(
        java.tool("localcloud_browse_resources", {"service": service}),
        sort_keys=True,
    )


def test_shared_volume_projects_seed_persistence_mcp_and_ownership(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    config_dir = tmp_path / "config"
    data_volume = f"integration-{uuid4().hex[:12]}"
    config_dir.mkdir()
    _controller, runtime, image = controller_for(tmp_path)
    monkeypatch.setenv("LOCALCLOUD_HOME", str(tmp_path / "cli-home"))
    monkeypatch.setenv("LOCALCLOUD_IMAGE", image)
    (config_dir / "seed.yaml").write_text(
        """services:
  secretmanager:
    secrets:
      - name: configured-secret
        versions:
          - data: configured-value
            state: ENABLED
  pubsub:
    topics:
      - name: configured-topic
""",
        encoding="utf-8",
    )
    config = write_config(
        config_dir,
        image,
        services=["secretmanager", "pubsub"],
        data_volume=data_volume,
        project=PROJECT,
        user=USER,
    )
    unrelated_volume = runtime.client.volumes.create(
        name=f"localcloud-unrelated-{uuid4().hex[:12]}",
        labels={MANAGED_LABEL: "true"},
    )

    try:
        started = _invoke(capsys, "start", str(config.config_path))
        assert started["status"] == "started"
        assert started["data_volume"] == config.data_volume
        assert started["project"] == PROJECT
        assert started["user"] == USER
        assert started["services"] == ["secretmanager", "pubsub"]
        assert "id" not in started
        assert_loopback_url(started["container"]["url"])
        assert started["sdk_env"]["LOCALCLOUD_USER"] == USER
        assert (
            started["sdk_env"]["LOCALCLOUD_PRINCIPAL"]
            == "integration-agent@localcloud.invalid"
        )
        assert started["mcp"]["direct_url"].endswith("/mcp")
        assert started["mcp"]["headers"] == {
            "X-LocalCloud-Project": PROJECT,
            "X-LocalCloud-User": USER,
        }

        adapter = McpAdapter(config)
        initialized = adapter.handle(
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": "2025-11-25",
                    "capabilities": {},
                    "clientInfo": {"name": "integration", "version": "1"},
                },
            }
        )
        tools = adapter.handle(
            {
                "jsonrpc": "2.0",
                "id": 2,
                "method": "tools/list",
                "params": {},
            }
        )
        listed = adapter.handle(
            {
                "jsonrpc": "2.0",
                "id": 3,
                "method": "tools/call",
                "params": {
                    "name": "localcloud_list_services",
                    "arguments": {},
                },
            }
        )
        assert initialized["result"]["serverInfo"]["name"] == "localcloud"
        tool_names = {tool["name"] for tool in tools["result"]["tools"]}
        assert "localcloud_list_services" in tool_names
        assert not {
            "localcloud_plan_environment",
            "localcloud_acquire_environment",
            "localcloud_list_environments",
        }.intersection(tool_names)
        assert listed["result"].get("isError") is not True

        java = JavaMcpClient(
            started["container"]["url"], started["project"], started["user"]
        )
        assert "configured-secret" in _resource_inventory(java, "secretmanager")
        assert "configured-topic" in _resource_inventory(java, "pubsub")
        java.seed_project(
            """services:
  secretmanager:
    secrets:
      - name: persistent-extra
        versions:
          - data: extra-value
            state: ENABLED
"""
        )
        assert "persistent-extra" in _resource_inventory(java, "secretmanager")

        second = _invoke(
            capsys,
            "start",
            str(config.config_path),
            "--project-id",
            SECOND_PROJECT,
            "--user",
            "second-agent",
        )
        assert second["container"]["name"] == started["container"]["name"]
        assert second["network"]["name"] == started["network"]["name"]
        assert second["mount"]["source"] == started["mount"]["source"]
        assert second["project"] == SECOND_PROJECT
        assert second["user"] == "second-agent"
        assert second["mcp"]["headers"] == {
            "X-LocalCloud-Project": SECOND_PROJECT,
            "X-LocalCloud-User": "second-agent",
        }
        second_java = JavaMcpClient(
            second["container"]["url"], second["project"], second["user"]
        )
        assert "configured-secret" in _resource_inventory(
            second_java, "secretmanager"
        )
        second_java.seed_project(
            """services:
  secretmanager:
    secrets:
      - name: second-project-extra
        versions:
          - data: other-value
            state: ENABLED
"""
        )

        _invoke(capsys, "stop", "--data-volume", config.data_volume)
        restarted = _invoke(capsys, "start", str(config.config_path))
        java = JavaMcpClient(
            restarted["container"]["url"],
            restarted["project"],
            restarted["user"],
        )
        secrets = _resource_inventory(java, "secretmanager")
        assert "configured-secret" in secrets
        assert "persistent-extra" in secrets

        reset = _invoke(capsys, "reset", str(config.config_path))
        java = JavaMcpClient(
            reset["container"]["url"], reset["project"], reset["user"]
        )
        secrets = _resource_inventory(java, "secretmanager")
        assert "configured-secret" in secrets
        assert "persistent-extra" not in secrets
        second_java = JavaMcpClient(
            reset["container"]["url"], SECOND_PROJECT, "second-agent"
        )
        assert "second-project-extra" in _resource_inventory(
            second_java, "secretmanager"
        )

        changed_path = config_dir / "ephemeral.yaml"
        changed = write_config(
            config_dir,
            image,
            services=["secretmanager"],
            data_volume=config.data_volume,
            project=PROJECT,
            user=USER,
            data="ephemeral",
            seed=None,
            name=changed_path.name,
        )
        replacement = _invoke(capsys, "start", str(changed_path))
        assert replacement["container"]["name"] == started["container"]["name"]
        assert replacement["services"] == ["secretmanager"]
        assert replacement["data"] == "ephemeral"
        _invoke(capsys, "stop", "--data-volume", config.data_volume)
        assert runtime.resolve(changed) is None
        names = default_resource_names(changed.data_volume)
        for collection, name in (
            (runtime.client.networks, names["network"]),
            (runtime.client.volumes, changed.data_volume),
        ):
            with pytest.raises(Exception):
                collection.get(name)

        unknown_path = config_dir / "unknown.yaml"
        write_config(
            config_dir,
            image,
            services=["not-a-localcloud-service"],
            data_volume=config.data_volume,
            project=PROJECT,
            user=USER,
            data="ephemeral",
            seed=None,
            name=unknown_path.name,
        )
        unknown = _invoke_error(capsys, "start", str(unknown_path))
        assert unknown["code"] == "container_start_failed"
        assert "Unknown service" in unknown["details"]["logs"]

        collision_name = default_resource_names(config.data_volume)["container"]
        collision = runtime.client.containers.create(
            image,
            name=collision_name,
            command=["sleep", "300"],
            labels={},
        )
        try:
            ownership = _invoke_error(
                capsys, "start", str(config.config_path)
            )
            assert ownership["code"] == "ownership_mismatch"
            collision.reload()
        finally:
            collision.remove(force=True)

        unrelated_volume.reload()
        assert unrelated_volume.attrs.get("Labels") == {MANAGED_LABEL: "true"}
    finally:
        current = runtime.resolve(config)
        if current is not None:
            runtime.remove(config, current, remove_volume=True)
        unrelated_volume.remove(force=True)


def test_cli_adopts_external_container_by_named_data_volume(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    config_dir = tmp_path / "external-config"
    config_dir.mkdir()
    _controller, runtime, image = controller_for(tmp_path)
    data_volume = f"external-{uuid4().hex[:12]}"
    network_name = f"external-network-{uuid4().hex[:12]}"
    container_name = f"external-runtime-{uuid4().hex[:12]}"
    config = write_config(
        config_dir,
        image,
        services=["secretmanager"],
        data_volume=data_volume,
        project=DEFAULT_PROJECT,
        user=USER,
        seed=None,
    )
    monkeypatch.chdir(config_dir)
    monkeypatch.setenv("LOCALCLOUD_HOME", str(tmp_path / "external-cli-home"))
    monkeypatch.setenv("LOCALCLOUD_IMAGE", image)

    volume = runtime.client.volumes.create(name=data_volume, labels={})
    network = runtime.client.networks.create(network_name, labels={})
    image_record = runtime.client.images.get(image)
    container = runtime.client.containers.run(
        image,
        detach=True,
        name=container_name,
        labels={},
        environment=_container_environment(config, network_name),
        mem_limit=config.memory,
        network=network_name,
        ports=runtime._port_bindings(config, image_record),
        volumes={
            data_volume: {
                "bind": "/var/lib/localcloud",
                "mode": "rw",
            }
        },
    )
    try:
        current = runtime.resolve(
            config, preferred_container_id=container.id, require=True
        )
        assert current is not None
        runtime.wait_ready(current.url, container=container)

        status = _invoke(
            capsys, "status", "--data-volume", data_volume
        )
        assert status["origin"] == "attached"
        assert status["ownership"] == {
            "container": "attached",
            "network": "attached",
            "data_volume": "attached",
        }
        assert status["container"]["id"] == container.id

        started = _invoke(capsys, "start", str(config.config_path))
        assert started["status"] == "already_running"
        assert started["container"]["id"] == container.id

        restarted = _invoke(capsys, "restart", str(config.config_path))
        assert restarted["status"] == "restarted"
        assert restarted["container"]["id"] == container.id

        stopped = _invoke(
            capsys, "stop", "--data-volume", data_volume
        )
        assert stopped["status"] == "stopped"
        container.reload()
        assert container.status == "exited"

        resumed = _invoke(capsys, "start", str(config.config_path))
        assert resumed["container"]["id"] == container.id

        rejected = _invoke_error(
            capsys,
            "reset",
            str(config.config_path),
            "--all-projects",
        )
        assert rejected["code"] == "ownership_forbidden"
        container.reload()
        assert container.status == "running"
    finally:
        container.remove(force=True)
        network.remove()
        volume.remove(force=True)

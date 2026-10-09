from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any
import pytest

from localcloud_cli.cli import _execute, _parser
from localcloud_cli.config import LocalCloudConfig, load_config
from localcloud_cli.constants import DEFAULT_DATA_VOLUME, DEFAULT_PROJECT, DEFAULT_USER
from localcloud_cli.errors import HostError
from localcloud_cli.mcp_install import (
    build_mcp_server_config,
    get_client_config_path,
    install_mcp_server,
    resolve_localcloud_command,
    update_mcp_config_file,
)
from localcloud_cli.mcp_stdio import McpAdapter


def _test_config(
    *,
    data_volume: str = DEFAULT_DATA_VOLUME,
    project: str = DEFAULT_PROJECT,
    user: str = DEFAULT_USER,
) -> LocalCloudConfig:
    return LocalCloudConfig(
        data_volume=data_volume,
        config_path=None,
        project=project,
        user=user,
        services=None,
        data="persistent",
        image="agentcloud/localcloud:latest",
        memory="4g",
        docker_socket=False,
        transparent_network=False,
        environment={},
        container_name="localcloud",
        network_name="localcloud",
        diagnostics=(),
    )


class _AutoStartMockController:
    def __init__(self, *, initial_state: str = "stopped"):
        self.state = initial_state
        self.start_calls: list[dict[str, Any]] = []
        self.target_calls: list[dict[str, Any]] = []
        self.ensured_projects: list[str] = []

    def target(
        self,
        config: LocalCloudConfig,
        *,
        readiness_timeout: float | None = None,
        on_url_resolved: Any = None,
        ensure_project: bool = False,
    ) -> dict[str, Any]:
        self.target_calls.append({
            "config": config,
            "ensure_project": ensure_project,
        })
        if self.state == "stopped":
            raise HostError("runtime_not_running", "runtime not running")

        if config.project not in self.ensured_projects:
            if ensure_project:
                self.ensured_projects.append(config.project)
            else:
                raise HostError("unknown_project", f"Project {config.project} not found")

        if on_url_resolved:
            on_url_resolved("http://127.0.0.1:49080")
        return {
            "url": "http://127.0.0.1:49080",
            "endpoint_map": {"5380": 49080},
        }

    def start(
        self,
        config: LocalCloudConfig,
        *,
        ensure_project: bool = False,
        allow_replace: bool = True,
        **kwargs: Any,
    ) -> dict[str, Any]:
        self.start_calls.append({
            "config": config,
            "ensure_project": ensure_project,
            "allow_replace": allow_replace,
        })
        self.state = "running"
        if ensure_project:
            self.ensured_projects.append(config.project)
        return {"status": "started"}


def test_mcp_adapter_autostarts_stopped_runtime(monkeypatch: pytest.MonkeyPatch) -> None:
    from localcloud_cli import mcp_stdio

    class FakeJavaClient:
        def __init__(self, *args: Any, **kwargs: Any):
            pass

    monkeypatch.setattr(mcp_stdio, "JavaMcpClient", FakeJavaClient)

    controller = _AutoStartMockController(initial_state="stopped")
    config = _test_config(project="agent-test-proj")

    adapter = McpAdapter(config, controller=controller, auto_start=True)

    assert len(controller.start_calls) == 1
    assert controller.start_calls[0]["ensure_project"] is True
    assert controller.start_calls[0]["allow_replace"] is False
    assert len(controller.target_calls) == 1
    assert "agent-test-proj" in controller.ensured_projects
    assert adapter.mcp_url == "http://127.0.0.1:49080/mcp"


def test_mcp_adapter_no_start_raises_without_starting(monkeypatch: pytest.MonkeyPatch) -> None:
    from localcloud_cli import mcp_stdio

    class FakeJavaClient:
        def __init__(self, *args: Any, **kwargs: Any):
            pass

    monkeypatch.setattr(mcp_stdio, "JavaMcpClient", FakeJavaClient)

    controller = _AutoStartMockController(initial_state="stopped")
    config = _test_config()

    with pytest.raises(HostError) as caught:
        McpAdapter(config, controller=controller, auto_start=False)

    assert caught.value.code == "runtime_not_running"
    assert len(controller.start_calls) == 0


def test_mcp_adapter_auto_provisions_missing_project_on_running_runtime(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from localcloud_cli import mcp_stdio

    class FakeJavaClient:
        def __init__(self, *args: Any, **kwargs: Any):
            pass

    monkeypatch.setattr(mcp_stdio, "JavaMcpClient", FakeJavaClient)

    controller = _AutoStartMockController(initial_state="running")
    config = _test_config(project="newly-introduced-project")

    adapter = McpAdapter(config, controller=controller, auto_start=True)

    assert len(controller.start_calls) == 1
    assert len(controller.target_calls) == 1
    assert "newly-introduced-project" in controller.ensured_projects
    assert adapter.mcp_url == "http://127.0.0.1:49080/mcp"


def test_build_mcp_server_config_omits_default_data_volume() -> None:
    config = _test_config(
        data_volume=DEFAULT_DATA_VOLUME,
        project="my-team-project",
        user=DEFAULT_USER,
    )
    server_config = build_mcp_server_config(config, command_override="localcloud")

    assert server_config["command"] == "localcloud"
    assert server_config["args"] == [
        "mcp",
        "--project-id",
        "my-team-project",
    ]


def test_build_mcp_server_config_includes_custom_data_volume_and_user() -> None:
    config = _test_config(
        data_volume="isolated-volume",
        project="isolated-project",
        user="custom-agent",
    )
    server_config = build_mcp_server_config(config, command_override="/custom/path/localcloud")

    assert server_config["command"] == "/custom/path/localcloud"
    assert server_config["args"] == [
        "mcp",
        "--data-volume",
        "isolated-volume",
        "--project-id",
        "isolated-project",
        "--user",
        "custom-agent",
    ]


def test_install_mcp_server_for_cursor(tmp_path: Path) -> None:
    config = _test_config(project="cursor-proj")
    result = install_mcp_server(
        config,
        client="cursor",
        directory=tmp_path,
        is_global=False,
        command_override="localcloud",
    )

    expected_path = tmp_path / ".cursor" / "mcp.json"
    assert result["status"] == "installed"
    assert Path(result["config_path"]) == expected_path
    assert expected_path.is_file()

    saved = json.loads(expected_path.read_text(encoding="utf-8"))
    assert "localcloud" in saved["mcpServers"]
    assert saved["mcpServers"]["localcloud"]["args"] == ["mcp", "--project-id", "cursor-proj"]


def test_install_mcp_server_preserves_existing_servers(tmp_path: Path) -> None:
    target_file = tmp_path / ".cursor" / "mcp.json"
    target_file.parent.mkdir(parents=True)
    target_file.write_text(
        json.dumps({
            "mcpServers": {
                "existing-server": {
                    "command": "node",
                    "args": ["server.js"],
                }
            }
        }),
        encoding="utf-8",
    )

    config = _test_config(project="my-new-proj")
    install_mcp_server(
        config,
        client="cursor",
        directory=tmp_path,
        is_global=False,
        command_override="localcloud",
    )

    saved = json.loads(target_file.read_text(encoding="utf-8"))
    assert "existing-server" in saved["mcpServers"]
    assert "localcloud" in saved["mcpServers"]
    assert saved["mcpServers"]["localcloud"]["args"] == ["mcp", "--project-id", "my-new-proj"]


def test_cli_mcp_install_dispatch(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.chdir(tmp_path)
    args = _parser().parse_args([
        "mcp",
        "install",
        "--client",
        "cursor",
        "--project",
        "--project-id",
        "cli-test-project",
        "--data-volume",
        DEFAULT_DATA_VOLUME,
    ])
    result = _execute(args)

    assert result["status"] == "installed"
    assert result["client"] == "cursor"
    target_file = tmp_path / ".cursor" / "mcp.json"
    assert target_file.is_file()
    saved = json.loads(target_file.read_text(encoding="utf-8"))
    assert saved["mcpServers"]["localcloud"]["args"] == ["mcp", "--project-id", "cli-test-project"]


def test_cli_mcp_install_dispatch_with_command_path(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.chdir(tmp_path)
    args = _parser().parse_args([
        "mcp",
        "install",
        "--client",
        "cursor",
        "--project",
        "--command-path",
        "/opt/homebrew/bin/lc",
    ])
    result = _execute(args)

    assert result["status"] == "installed"
    target_file = tmp_path / ".cursor" / "mcp.json"
    saved = json.loads(target_file.read_text(encoding="utf-8"))
    assert saved["mcpServers"]["localcloud"]["command"] == "/opt/homebrew/bin/lc"


def test_cli_mcp_install_dispatch_with_bare(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.chdir(tmp_path)
    args = _parser().parse_args([
        "mcp",
        "install",
        "--client",
        "cursor",
        "--global",
        "--bare",
    ])
    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    result = _execute(args)

    assert result["status"] == "installed"
    target_file = tmp_path / ".cursor" / "mcp.json"
    saved = json.loads(target_file.read_text(encoding="utf-8"))
    assert saved["mcpServers"]["localcloud"]["command"] == "localcloud"


def test_resolve_localcloud_command_prefers_system_over_venv(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    fake_brew = tmp_path / "bin" / "localcloud"
    fake_brew.parent.mkdir(parents=True)
    fake_brew.touch(mode=0o755)

    assert resolve_localcloud_command(prefer_bare=True) == "localcloud"

    # Mock candidate check so that fake_brew is recognized as the candidate
    from localcloud_cli import mcp_install
    monkeypatch.setattr(
        mcp_install,
        "shutil",
        type("FakeShutil", (), {"which": staticmethod(lambda cmd, path=None: str(fake_brew))})(),
    )
    res = resolve_localcloud_command(prefer_bare=False)
    # On macOS or Linux where /opt/homebrew or fake_brew exists, it returns an absolute path
    assert Path(res).is_absolute()


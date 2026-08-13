from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from localcloud_cli import __version__

from localcloud_cli.cli import _execute, _parser, main
from localcloud_cli.errors import HostError


class FakeController:
    instance: "FakeController"

    def __init__(self) -> None:
        type(self).instance = self
        self.calls: list[tuple[str, Any]] = []
        self.remembered: str | None = None

    def remembered_config(self, instance: str) -> str | None:
        self.calls.append(("remembered_config", instance))
        return self.remembered

    def start(self, config: Any) -> dict[str, Any]:
        self.calls.append(("start", config))
        return {"status": "started", "instance": config.instance}

    def restart(self, config: Any) -> dict[str, Any]:
        self.calls.append(("restart", config))
        return {"status": "restarted"}

    def reset(self, config: Any, *, all_projects: bool = False) -> dict[str, Any]:
        self.calls.append(("reset", (config, all_projects)))
        return {"status": "reset", "reset_scope": "all_projects" if all_projects else "project"}

    def stop(self, instance: str) -> dict[str, Any]:
        self.calls.append(("stop", instance))
        return {"status": "stopped"}

    def status(self, instance: str) -> dict[str, Any]:
        self.calls.append(("status", instance))
        return {"status": "running"}

    def logs(self, instance: str, tail: int = 200) -> dict[str, Any]:
        self.calls.append(("logs", (instance, tail)))
        return {"status": "logs", "logs": "output"}

    def target(self, instance: str, project: str, user: str) -> dict[str, Any]:
        self.calls.append(("target", (instance, project, user)))
        return {
            "instance": instance,
            "url": "http://127.0.0.1:49080",
            "endpoint_map": {"24080": 49080},
            "project": project,
            "user": user,
        }

    def doctor(self) -> dict[str, Any]:
        self.calls.append(("doctor", None))
        return {"status": "ok"}


@pytest.fixture(autouse=True)
def fake_controller(monkeypatch: pytest.MonkeyPatch) -> None:
    import localcloud_cli.controller as controller_module

    monkeypatch.setattr(controller_module, "Controller", FakeController)


def test_help_surface_uses_instance_project_and_user_only() -> None:
    help_text = _parser().format_help()
    lifecycle = _parser().parse_args(
        [
            "start",
            "--instance",
            "team-a",
            "--project-id",
            "agent-project-1",
            "--user",
            "alice",
        ]
    )

    assert "Shared LocalCloud instance" in help_text
    assert "--" + "work" + "space" not in help_text
    assert lifecycle.instance == "team-a"
    assert lifecycle.project_id == "agent-project-1"
    assert lifecycle.user == "alice"

def test_version_output_is_exact(
    capsys: pytest.CaptureFixture[str],
) -> None:
    with pytest.raises(SystemExit) as caught:
        _parser().parse_args(["--version"])

    assert caught.value.code == 0
    assert capsys.readouterr().out == f"localcloud {__version__}\n"


def test_start_dispatch_applies_cli_context_and_resource_names(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    config = tmp_path / "custom.yaml"
    config.write_text(
        "project: yaml-project-1\nuser: yaml-user\n",
        encoding="utf-8",
    )
    monkeypatch.chdir(tmp_path)
    args = _parser().parse_args(
        [
            "start",
            str(config),
            "--instance",
            "team-a",
            "--project-id",
            "cli-project-1",
            "--user",
            "cli-user",
            "--container-name",
            "custom-container",
            "--network-name",
            "custom-network",
            "--volume-name",
            "custom-volume",
        ]
    )

    result = _execute(args)
    call, selected = FakeController.instance.calls[-1]
    assert result == {"status": "started", "instance": "team-a"}
    assert call == "start"
    assert selected.project == "cli-project-1"
    assert selected.user == "cli-user"
    assert selected.container_name == "custom-container"
    assert selected.network_name == "custom-network"
    assert selected.volume_name == "custom-volume"


def test_local_config_precedes_remembered_config(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    local = tmp_path / "localcloud.yaml"
    remembered = tmp_path / "remembered.yaml"
    local.write_text("project: current-project-1\n", encoding="utf-8")
    remembered.write_text("project: remembered-project-1\n", encoding="utf-8")
    monkeypatch.chdir(tmp_path)
    controller = FakeController()
    controller.remembered = str(remembered)

    selected = __import__("localcloud_cli.cli", fromlist=["_command_config"])._command_config(
        controller, _parser().parse_args(["start"])
    )
    assert selected.project == "current-project-1"
    assert not any(call[0] == "remembered_config" for call in controller.calls)


def test_remembered_config_is_used_when_local_file_is_absent(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    remembered = tmp_path / "remembered.yaml"
    remembered.write_text("project: remembered-project-1\n", encoding="utf-8")
    monkeypatch.chdir(tmp_path)
    controller = FakeController()
    controller.remembered = str(remembered)

    selected = __import__("localcloud_cli.cli", fromlist=["_command_config"])._command_config(
        controller, _parser().parse_args(["start"])
    )
    assert selected.project == "remembered-project-1"
    assert controller.calls == [("remembered_config", "default")]


def test_reset_all_projects_dispatches_explicit_scope() -> None:
    result = _execute(_parser().parse_args(["reset", "--all-projects"]))
    assert result["reset_scope"] == "all_projects"
    call, values = FakeController.instance.calls[-1]
    assert call == "reset"
    assert values[1] is True


def test_instance_only_commands_dispatch_without_context_config() -> None:
    assert _execute(_parser().parse_args(["stop", "--instance", "team-a"])) == {
        "status": "stopped"
    }
    assert FakeController.instance.calls == [("stop", "team-a")]


def test_console_encodes_selected_project_and_user(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.chdir(tmp_path)
    opened: list[str] = []
    monkeypatch.setattr("localcloud_cli.cli.webbrowser.open", opened.append)

    result = _execute(
        _parser().parse_args(
            [
                "console",
                "--project-id",
                "agent-project-1",
                "--user",
                "alice+agent@example.test",
            ]
        )
    )
    expected = (
        "http://127.0.0.1:49080?project=agent-project-1&"
        "user=alice%2Bagent%40example.test"
    )
    assert opened == [expected]
    assert result["url"] == expected


def test_env_dispatch_preserves_context(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.chdir(tmp_path)
    calls: list[tuple[Any, ...]] = []

    def environment_config(*args: Any, **kwargs: Any) -> str:
        calls.append((*args, kwargs))
        return "export LOCALCLOUD_PROJECT=agent-project-1"

    monkeypatch.setattr("localcloud_cli.endpoints.environment_config", environment_config)
    result = _execute(
        _parser().parse_args(
            ["env", "--project-id", "agent-project-1", "--user", "alice"]
        )
    )
    assert result == "export LOCALCLOUD_PROJECT=agent-project-1"
    assert calls[0][1:3] == ("agent-project-1", "alice")


def test_mcp_dispatch_passes_instance_project_and_user(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.chdir(tmp_path)
    calls: list[tuple[str, str, str]] = []
    monkeypatch.setattr(
        "localcloud_cli.mcp_stdio.run",
        lambda instance, project, user: calls.append((instance, project, user)),
    )

    _execute(
        _parser().parse_args(
            [
                "mcp",
                "--instance",
                "team-a",
                "--project-id",
                "agent-project-1",
                "--user",
                "alice",
            ]
        )
    )
    assert calls == [("team-a", "agent-project-1", "alice")]


def test_main_returns_structured_host_error(
    capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    def fail(_self: FakeController, _instance: str) -> dict[str, Any]:
        raise HostError("instance_not_running", "start it")

    monkeypatch.setattr(FakeController, "status", fail)
    assert main(["status"]) == 2
    error = json.loads(capsys.readouterr().err)
    assert error["error"] is True
    assert error["code"] == "instance_not_running"

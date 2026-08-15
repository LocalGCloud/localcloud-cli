from __future__ import annotations

import json
from importlib.metadata import distribution
from pathlib import Path
from typing import Any

import pytest

from localcloud_cli import __version__
from localcloud_cli.cli import _command_config, _execute, _parser, main
from localcloud_cli.errors import HostError


class FakeController:
    instance: "FakeController"

    def __init__(self) -> None:
        type(self).instance = self
        self.calls: list[tuple[str, Any]] = []
        self.remembered: str | None = None

    def remembered_config(self, config: Any) -> str | None:
        self.calls.append(("remembered_config", config))
        return self.remembered

    def start(self, config: Any) -> dict[str, Any]:
        self.calls.append(("start", config))
        return {"status": "started", "data_volume": config.data_volume}

    def restart(self, config: Any) -> dict[str, Any]:
        self.calls.append(("restart", config))
        return {"status": "restarted", "data_volume": config.data_volume}

    def reset(self, config: Any, *, all_projects: bool = False) -> dict[str, Any]:
        self.calls.append(("reset", (config, all_projects)))
        return {
            "status": "reset",
            "data_volume": config.data_volume,
            "reset_scope": "all_projects" if all_projects else "project",
        }

    def stop(self, config: Any) -> dict[str, Any]:
        self.calls.append(("stop", config))
        return {"status": "stopped", "data_volume": config.data_volume}

    def status(self, config: Any) -> dict[str, Any]:
        self.calls.append(("status", config))
        return {"status": "running", "data_volume": config.data_volume}

    def logs(self, config: Any, tail: int = 200) -> dict[str, Any]:
        self.calls.append(("logs", (config, tail)))
        return {"status": "logs", "data_volume": config.data_volume, "logs": "output"}

    def target(self, config: Any) -> dict[str, Any]:
        self.calls.append(("target", config))
        return {
            "data_volume": config.data_volume,
            "url": "http://127.0.0.1:49080",
            "endpoint_map": {"24080": 49080},
            "project": config.project,
            "user": config.user,
        }

    def doctor(self) -> dict[str, Any]:
        self.calls.append(("doctor", None))
        return {"status": "ok"}


@pytest.fixture(autouse=True)
def fake_controller(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    import localcloud_cli.controller as controller_module

    monkeypatch.setenv("LOCALCLOUD_HOME", str(tmp_path / "home"))
    monkeypatch.setattr(controller_module, "Controller", FakeController)


def test_help_surface_uses_data_volume_project_and_user() -> None:
    parser = _parser()
    help_text = parser.format_help()
    lifecycle = parser.parse_args(
        [
            "start",
            "--data-volume",
            "team-data",
            "--project-id",
            "agent-project-1",
            "--user",
            "alice",
        ]
    )

    assert "Run Google Cloud-compatible services locally in Docker" in help_text
    assert "lc is an alias for localcloud; both commands behave identically." in help_text
    assert lifecycle.data_volume == "team-data"
    assert lifecycle.project_id == "agent-project-1"
    assert lifecycle.user == "alice"


@pytest.mark.parametrize("command", ["start", "restart", "reset", "stop", "status", "logs", "console", "env", "mcp"])
def test_every_runtime_command_accepts_data_volume(command: str) -> None:
    args = _parser().parse_args([command, "--data-volume", "team-data"])
    assert args.data_volume == "team-data"


def test_obsolete_instance_and_volume_flags_are_rejected() -> None:
    with pytest.raises(SystemExit):
        _parser().parse_args(["status", "--instance", "default"])
    with pytest.raises(SystemExit):
        _parser().parse_args(["start", "--volume-name", "legacy"])


def test_version_output_is_exact(capsys: pytest.CaptureFixture[str]) -> None:
    with pytest.raises(SystemExit) as caught:
        _parser().parse_args(["--version"])

    assert caught.value.code == 0
    assert capsys.readouterr().out == f"localcloud {__version__}\n"


def test_console_commands_share_the_canonical_entry_point() -> None:
    scripts = {
        entry.name: entry
        for entry in distribution("localcloud-cli").entry_points
        if entry.group == "console_scripts"
    }

    assert scripts["lc"].value == scripts["localcloud"].value == "localcloud_cli.cli:main"
    assert scripts["lc"].load() is scripts["localcloud"].load() is main


def test_start_dispatch_applies_context_and_managed_resource_names(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    config_path = tmp_path / "custom.yaml"
    config_path.write_text(
        "project: yaml-project-1\nuser: yaml-user\n", encoding="utf-8"
    )
    monkeypatch.chdir(tmp_path)
    args = _parser().parse_args(
        [
            "start",
            str(config_path),
            "--data-volume",
            "team-data",
            "--project-id",
            "cli-project-1",
            "--user",
            "cli-user",
            "--container-name",
            "custom-container",
            "--network-name",
            "custom-network",
        ]
    )

    result = _execute(args)
    call, selected = FakeController.instance.calls[-1]
    assert result == {"status": "started", "data_volume": "team-data"}
    assert call == "start"
    assert selected.data_volume == "team-data"
    assert selected.project == "cli-project-1"
    assert selected.user == "cli-user"
    assert selected.container_name == "custom-container"
    assert selected.network_name == "custom-network"


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

    selected = _command_config(controller, _parser().parse_args(["start"]))

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

    selected = _command_config(controller, _parser().parse_args(["start"]))

    assert selected.project == "remembered-project-1"
    assert controller.calls[0][0] == "remembered_config"
    assert controller.calls[0][1].data_volume == "localcloud-data"


def test_reset_all_projects_dispatches_explicit_scope() -> None:
    result = _execute(_parser().parse_args(["reset", "--all-projects"]))
    assert result["reset_scope"] == "all_projects"
    call, values = FakeController.instance.calls[-1]
    assert call == "reset"
    assert values[1] is True


def test_reset_progress_uses_resolved_data_volume(
    capsys: pytest.CaptureFixture[str],
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    config = tmp_path / "team.yaml"
    config.write_text(
        "data_volume: team-data\nproject: team-project-1\n", encoding="utf-8"
    )
    monkeypatch.chdir(tmp_path)

    assert main(["reset", str(config)]) == 0
    captured = capsys.readouterr()
    assert "data volume 'team-data'" in captured.err
    assert "instance" not in captured.err.lower()


def test_stop_dispatches_loaded_data_volume_config() -> None:
    assert _execute(
        _parser().parse_args(["stop", "--data-volume", "team-data"])
    ) == {"status": "stopped", "data_volume": "team-data"}
    call, config = FakeController.instance.calls[-1]
    assert call == "stop"
    assert config.data_volume == "team-data"


def test_console_encodes_selected_project_and_user(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.chdir(tmp_path)
    opened: list[str] = []
    monkeypatch.setattr("localcloud_cli.cli.webbrowser.open", opened.append)

    result = _execute(
        _parser().parse_args(
            [
                "console",
                "--data-volume",
                "team-data",
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
    assert result["data_volume"] == "team-data"
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


def test_main_env_json_prints_valid_json(
    capsys: pytest.CaptureFixture[str],
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(
        "localcloud_cli.endpoints.environment_config",
        lambda *_args, **_kwargs: {"GOOGLE_CLOUD_PROJECT": "agent-project-1"},
    )

    assert main(["env", "--format", "json"]) == 0
    captured = capsys.readouterr()
    assert json.loads(captured.out) == {
        "GOOGLE_CLOUD_PROJECT": "agent-project-1"
    }
    assert "Processing" in captured.err


def test_mcp_dispatch_passes_full_runtime_config(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.chdir(tmp_path)
    calls: list[Any] = []
    monkeypatch.setattr("localcloud_cli.mcp_stdio.run", calls.append)

    _execute(
        _parser().parse_args(
            [
                "mcp",
                "--data-volume",
                "team-data",
                "--project-id",
                "agent-project-1",
                "--user",
                "alice",
            ]
        )
    )

    assert len(calls) == 1
    assert calls[0].data_volume == "team-data"
    assert calls[0].project == "agent-project-1"
    assert calls[0].user == "alice"


def test_native_guide_and_mcp_do_not_emit_lifecycle_status(
    capsys: pytest.CaptureFixture[str],
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.chdir(tmp_path)
    assert main(["guide"]) == 0
    guide = capsys.readouterr()
    assert guide.out
    assert guide.err == ""

    monkeypatch.setattr("localcloud_cli.mcp_stdio.run", lambda _config: None)
    assert main(["mcp"]) == 0
    mcp = capsys.readouterr()
    assert mcp.out == ""
    assert mcp.err == ""


def test_main_returns_concise_host_error_by_default(
    capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    def fail(_self: FakeController, _config: Any) -> dict[str, Any]:
        raise HostError("runtime_not_running", "start it")

    monkeypatch.setattr(FakeController, "status", fail)
    assert main(["status"]) == 2
    captured = capsys.readouterr()
    assert "Processing" in captured.err
    assert "Failed" in captured.err
    assert "Error [runtime_not_running] start it" in captured.err


def test_main_returns_structured_host_error_when_verbose(
    capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    def fail(_self: FakeController, _config: Any) -> dict[str, Any]:
        raise HostError("runtime_not_running", "start it")

    monkeypatch.setattr(FakeController, "status", fail)
    assert main(["status", "--verbose"]) == 2
    error = json.loads(capsys.readouterr().err)
    assert error["error"] is True
    assert error["code"] == "runtime_not_running"

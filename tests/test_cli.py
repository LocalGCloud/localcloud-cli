from __future__ import annotations

import io
import json
import sys
from importlib.metadata import distribution
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

import localcloud_cli
from localcloud_cli import __version__, version_string
from localcloud_cli.cli import (
    _command_config,
    _execute,
    _parser,
    main,
)
from localcloud_cli.constants import DEFAULTS_CONFIG_LABEL
from localcloud_cli.entrypoint import main as entrypoint_main
from localcloud_cli.errors import HostError


class FakeController:
    instance: "FakeController"

    def __init__(self) -> None:
        type(self).instance = self
        self.calls: list[tuple[str, Any]] = []
        self.pull_calls: list[tuple[str, bool]] = []
        self.tail_calls: list[tuple[str, float | None]] = []
        self.dry_run_calls: list[tuple[str, bool]] = []
        self.ensure_project_calls: list[tuple[str, bool]] = []
        self.port_confirmation_calls: list[tuple[str, bool]] = []
        self.remembered: str | None = None
        self.remembered_by_volume: dict[str, str | None] = {}

    def remembered_config(self, config: Any) -> str | None:
        self.calls.append(("remembered_config", config))
        return self.remembered_by_volume.get(config.data_volume, self.remembered)

    def start(
        self,
        config: Any,
        *,
        pull: bool = False,
        ensure_project: bool = False,
        tail: float | None = None,
        observer: Any | None = None,
        dry_run: bool = False,
        confirm_port_mapping: Any | None = None,
    ) -> dict[str, Any] | str:
        self.calls.append(("start", config))
        self.pull_calls.append(("start", pull))
        self.ensure_project_calls.append(("start", ensure_project))
        self.tail_calls.append(("start", tail))
        self.dry_run_calls.append(("start", dry_run))
        self.port_confirmation_calls.append(
            ("start", confirm_port_mapping is not None)
        )
        if observer is not None and hasattr(observer, "debug"):
            observer.debug(
                "docker run -d --name localcloud "
                "-p 127.0.0.1:5380-5405:5380-5405/tcp "
                "agentcloud/localcloud:latest"
            )
        if dry_run:
            return "# action: create\ndocker run -d --name localcloud"
        return {"status": "started", "data_volume": config.data_volume}

    def restart(
        self,
        config: Any,
        *,
        pull: bool = False,
        ensure_project: bool = False,
        tail: float | None = None,
        observer: Any | None = None,
        dry_run: bool = False,
        confirm_port_mapping: Any | None = None,
    ) -> dict[str, Any] | str:
        self.calls.append(("restart", config))
        self.pull_calls.append(("restart", pull))
        self.ensure_project_calls.append(("restart", ensure_project))
        self.tail_calls.append(("restart", tail))
        self.dry_run_calls.append(("restart", dry_run))
        self.port_confirmation_calls.append(
            ("restart", confirm_port_mapping is not None)
        )
        if observer is not None and hasattr(observer, "debug"):
            observer.debug(
                "docker run -d --name localcloud "
                "-p 127.0.0.1:5380-5405:5380-5405/tcp "
                "agentcloud/localcloud:latest"
            )
        if dry_run:
            return "# action: restart\ndocker restart -t 20 localcloud"
        return {"status": "restarted", "data_volume": config.data_volume}
    def reset(
        self,
        config: Any,
        *,
        all_projects: bool = False,
        observer: Any | None = None,
        dry_run: bool = False,
    ) -> dict[str, Any] | str:
        _ = observer
        self.calls.append(("reset", (config, all_projects)))
        self.dry_run_calls.append(("reset", dry_run))
        if dry_run:
            return "# action: reset\n[LocalCloud API] reset project"
        return {
            "status": "reset",
            "data_volume": config.data_volume,
            "reset_scope": "all_projects" if all_projects else "project",
        }

    def stop(
        self,
        config: Any,
        *,
        observer: Any | None = None,
        dry_run: bool = False,
    ) -> dict[str, Any] | str:
        _ = observer
        self.calls.append(("stop", config))
        self.dry_run_calls.append(("stop", dry_run))
        if dry_run:
            return "# action: stop\ndocker stop -t 20 localcloud"
        return {"status": "stopped", "data_volume": config.data_volume}

    def status(self, config: Any) -> dict[str, Any]:
        self.calls.append(("status", config))
        return {"status": "running", "data_volume": config.data_volume}

    def logs(self, config: Any, tail: int = 200) -> dict[str, Any]:
        self.calls.append(("logs", (config, tail)))
        return {"status": "logs", "data_volume": config.data_volume, "logs": "output"}

    def target(self, config: Any, *, ensure_project: bool = False) -> dict[str, Any]:
        self.calls.append(("target", config))
        self.ensure_project_calls.append(("target", ensure_project))
        return {
            "data_volume": config.data_volume,
            "url": "http://127.0.0.1:49080",
            "connect_url": "http://127.0.0.1:49080",
            "endpoint_map": {"5380": 49080},
            "project": config.project,
            "user": config.user,
        }

    # Setup reports doctor --fix sees, one per setup_report() call (last repeats).
    setup_reports: list[tuple[Any, list[Any]]] = []

    def setup_report(self) -> tuple[Any, list[Any]]:
        from localcloud_cli.host_checks import DockerHost

        self.calls.append(("setup_report", None))
        reports = type(self).setup_reports
        if not reports:
            return DockerHost(), []
        return reports.pop(0) if len(reports) > 1 else reports[0]

    def doctor(self, setup: tuple[Any, list[Any]] | None = None) -> dict[str, Any]:
        self.calls.append(("doctor", None))
        result: dict[str, Any] = {
            "status": "ok",
            "default_image": "agentcloud/localcloud:latest (Local: ID: qualified , sha256:qualified)",
        }
        if setup is not None:
            result["setup_findings"] = [finding.to_dict() for finding in setup[1]]
        return result

    def cleanup(self, *, confirm: bool | None = None, dry_run: bool = False) -> dict[str, Any]:
        is_dry_run = not confirm if confirm is not None else dry_run
        self.calls.append(("cleanup", not is_dry_run))
        return {"status": "ok", "dry_run": is_dry_run}


@pytest.fixture(autouse=True)
def fake_controller(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    import localcloud_cli.controller as controller_module

    monkeypatch.setenv("LOCALCLOUD_HOME", str(tmp_path / "home"))
    monkeypatch.setattr(controller_module, "Controller", FakeController)
    monkeypatch.setattr(FakeController, "setup_reports", [])


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

    with pytest.raises(SystemExit) as caught:
        _parser().parse_args(["-v"])

    assert caught.value.code == 0
    assert capsys.readouterr().out == f"localcloud {__version__}\n"


def test_public_version_output_is_exact(
    capsys: pytest.CaptureFixture[str],
) -> None:
    assert entrypoint_main(["--version"]) == 0
    assert capsys.readouterr().out == f"localcloud {__version__}\n"

    assert entrypoint_main(["-v"]) == 0
    assert capsys.readouterr().out == f"localcloud {__version__}\n"

    assert main(["-v"]) == 0
    assert capsys.readouterr().out == f"localcloud {__version__}\n"


def test_version_output_includes_embedded_release_provenance(
    capsys: pytest.CaptureFixture[str],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(localcloud_cli, "__release_commit__", "0123456789ab")
    monkeypatch.setattr(localcloud_cli, "__release_date__", "2026-08-31")
    expected = (
        f"localcloud {__version__} "
        "(commit 0123456789ab, released 2026-08-31)"
    )

    assert version_string() == expected
    assert entrypoint_main(["--version"]) == 0
    assert capsys.readouterr().out == f"{expected}\n"

    assert entrypoint_main(["-v"]) == 0
    assert capsys.readouterr().out == f"{expected}\n"

    with pytest.raises(SystemExit) as caught:
        _parser().parse_args(["--version"])
    assert caught.value.code == 0
    assert capsys.readouterr().out == f"{expected}\n"

    with pytest.raises(SystemExit) as caught:
        _parser().parse_args(["-v"])
    assert caught.value.code == 0
    assert capsys.readouterr().out == f"{expected}\n"


def test_load_release_metadata_valid(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    (tmp_path / "_release.json").write_text(
        '{"commit": "0123456789ab", "release_date": "2026-08-31"}\n',
        encoding="utf-8",
    )
    monkeypatch.setattr(localcloud_cli, "__file__", str(tmp_path / "__init__.py"))
    assert localcloud_cli._load_release_metadata() == ("0123456789ab", "2026-08-31")


@pytest.mark.parametrize(
    "content",
    [
        "{}",
        '{"commit": "", "release_date": ""}',
        '{"commit": "short", "release_date": "2026-08-31"}',
        '{"commit": "0123456789ab", "release_date": "invalid-date"}',
        '{"commit": 123, "release_date": "2026-08-31"}',
        "not json",
    ],
)
def test_load_release_metadata_invalid(
    content: str,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    (tmp_path / "_release.json").write_text(content, encoding="utf-8")
    monkeypatch.setattr(localcloud_cli, "__file__", str(tmp_path / "__init__.py"))
    assert localcloud_cli._load_release_metadata() == (None, None)


def test_load_release_metadata_missing_file(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(localcloud_cli, "__file__", str(tmp_path / "nonexistent" / "__init__.py"))
    assert localcloud_cli._load_release_metadata() == (None, None)


def test_guide_fast_path_preserves_cli_error_handling(
    capsys: pytest.CaptureFixture[str],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def fail_to_render() -> str:
        raise RuntimeError("broken guide")

    monkeypatch.setattr(
        "localcloud_cli.agent_guide.render_agent_guide",
        fail_to_render,
    )

    assert entrypoint_main(["guide"]) == 1
    captured = capsys.readouterr()
    assert captured.out == ""
    assert "Error [unexpected_error]" in captured.err
    assert "broken guide" in captured.err


def test_console_commands_share_the_canonical_entry_point() -> None:
    scripts = {
        entry.name: entry
        for entry in distribution("localcloud-cli").entry_points
        if entry.group == "console_scripts"
    }

    assert (
        scripts["lc"].value
        == scripts["localcloud"].value
        == "localcloud_cli.entrypoint:main"
    )
    assert (
        scripts["lc"].load()
        is scripts["localcloud"].load()
        is entrypoint_main
    )


def test_start_dispatch_applies_context_and_managed_resource_names(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    config_path = tmp_path / "custom.yaml"
    config_path.write_text(
        "context:\n  project: yaml-project-1\n  user: yaml-user\n",
        encoding="utf-8",
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
    local.write_text(
        "context:\n  project: current-project-1\n", encoding="utf-8"
    )
    remembered.write_text(
        "context:\n  project: remembered-project-1\n", encoding="utf-8"
    )
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
    remembered.write_text(
        "context:\n  project: remembered-project-1\n", encoding="utf-8"
    )
    monkeypatch.chdir(tmp_path)
    controller = FakeController()
    controller.remembered = str(remembered)

    selected = _command_config(controller, _parser().parse_args(["start"]))

    assert selected.project == "remembered-project-1"
    assert controller.calls[0][0] == "remembered_config"
    assert controller.calls[0][1].data_volume == "localcloud-data"


def test_stale_implicit_active_runtime_falls_back_to_default_volume(
    tmp_path: Path,
) -> None:
    home = tmp_path / "home"
    home.mkdir()
    (home / "active-runtime.json").write_text(
        json.dumps(
            {
                "schema_version": 1,
                "data_volume": "stale-pr-data",
                "image": "agentcloud/localcloud:latest",
                "container_id": "missing-container",
            }
        ),
        encoding="utf-8",
    )
    controller = FakeController()
    controller.remembered_by_volume["localcloud-data"] = DEFAULTS_CONFIG_LABEL

    selected = _command_config(controller, _parser().parse_args(["stop"]))

    assert selected.data_volume == "localcloud-data"
    attempted_volumes = [
        call[1].data_volume
        for call in controller.calls
        if call[0] == "remembered_config"
    ]
    assert attempted_volumes == ["stale-pr-data", "localcloud-data"]


def test_valid_implicit_active_runtime_with_defaults_remains_selected(
    tmp_path: Path,
) -> None:
    home = tmp_path / "home"
    home.mkdir()
    (home / "active-runtime.json").write_text(
        json.dumps(
            {
                "schema_version": 1,
                "data_volume": "team-data",
                "image": "agentcloud/localcloud:latest",
                "container_id": "team-container",
            }
        ),
        encoding="utf-8",
    )
    controller = FakeController()
    controller.remembered_by_volume["team-data"] = DEFAULTS_CONFIG_LABEL

    selected = _command_config(controller, _parser().parse_args(["stop"]))

    assert selected.data_volume == "team-data"
    attempted_volumes = [
        call[1].data_volume
        for call in controller.calls
        if call[0] == "remembered_config"
    ]
    assert attempted_volumes == ["team-data"]


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
        "context:\n"
        "  project: team-project-1\n"
        "host:\n"
        "  data_volume: team-data\n",
        encoding="utf-8",
    )
    monkeypatch.chdir(tmp_path)

    assert main(["reset", str(config)]) == 0
    captured = capsys.readouterr()
    assert "data volume: 'team-data'" in captured.err
    assert "instance" not in captured.err.lower()


def test_stop_dispatches_loaded_data_volume_config() -> None:
    assert _execute(
        _parser().parse_args(["stop", "--data-volume", "team-data"])
    ) == {"status": "stopped", "data_volume": "team-data"}
    call, config = FakeController.instance.calls[-1]
    assert call == "stop"
    assert config.data_volume == "team-data"


def test_main_stop_reports_not_running_without_stopped_container_details(
    capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    def stop(
        _self: FakeController,
        config: Any,
        *,
        observer: Any | None = None,
        dry_run: bool = False,
    ) -> dict[str, Any]:
        _ = observer, dry_run
        return {
            "status": "not_running",
            "data_volume": config.data_volume,
            "container": {
                "name": config.container_name,
                "id": "a1b2c3d4e5f6",
                "state": "exited",
            },
        }

    monkeypatch.setattr(FakeController, "stop", stop)

    assert main(["stop"]) == 0
    captured = capsys.readouterr()
    assert "LocalCloud runtime was not running" in captured.err
    assert "LocalCloud runtime stopped" not in captured.err
    assert "Container" not in captured.out


def test_main_stop_reports_stopped_container_name_and_id(
    capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    def stop(
        _self: FakeController,
        config: Any,
        *,
        observer: Any | None = None,
        dry_run: bool = False,
    ) -> dict[str, Any]:
        _ = observer, dry_run
        return {
            "status": "stopped",
            "data_volume": config.data_volume,
            "container": {
                "name": config.container_name,
                "id": "a1b2c3d4e5f6",
                "state": "exited",
            },
        }

    monkeypatch.setattr(FakeController, "stop", stop)

    assert main(["stop"]) == 0
    captured = capsys.readouterr()
    assert "LocalCloud runtime stopped" in captured.err
    assert "Container     localcloud" in captured.out
    assert "Container ID  a1b2c3d4e5f6" in captured.out


def test_console_encodes_selected_project_and_user(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.chdir(tmp_path)
    opened: list[str] = []
    monkeypatch.setattr(
        "webbrowser.open",
        lambda url: opened.append(url) or True,
    )

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


def test_console_reports_manual_url_when_browser_cannot_open(
    capsys: pytest.CaptureFixture[str],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr("webbrowser.open", lambda _url: False)

    assert main(["console"]) == 2
    captured = capsys.readouterr()
    assert "Error [console_open_failed]" in captured.err
    assert "open the URL manually" in captured.err
    assert "http://127.0.0.1:49080" in captured.err
    assert captured.out == ""


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
    calls: list[tuple[Any, float, bool, Any]] = []

    def run(
        config: Any, *, connect_timeout: float, auto_start: bool, prepare: Any
    ) -> None:
        calls.append((config, connect_timeout, auto_start, prepare))

    monkeypatch.setattr("localcloud_cli.mcp_stdio.run", run)

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
                "--connect-timeout",
                "2.5",
            ]
        )
    )

    assert len(calls) == 1
    config, connect_timeout, auto_start, prepare = calls[0]
    assert config.data_volume == "team-data"
    assert config.project == "agent-project-1"
    assert config.user == "alice"
    assert connect_timeout == 2.5
    assert auto_start is True
    # The bridge connects to Docker itself and resolves the same config there.
    controller, prepared = prepare()
    assert isinstance(controller, FakeController)
    assert (prepared.data_volume, prepared.project, prepared.user) == (
        "team-data",
        "agent-project-1",
        "alice",
    )


def test_mcp_starts_its_bridge_when_docker_is_unavailable(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    import localcloud_cli.controller as controller_module

    monkeypatch.chdir(tmp_path)
    calls: list[Any] = []

    def unavailable() -> None:
        raise HostError("docker_unavailable", "Docker is not reachable")

    monkeypatch.setattr(controller_module, "Controller", unavailable)
    monkeypatch.setattr(
        "localcloud_cli.mcp_stdio.run",
        lambda config, **kwargs: calls.append((config, kwargs)),
    )

    _execute(_parser().parse_args(["mcp", "--no-start"]))

    config, kwargs = calls[0]
    assert config.project == "local-gcp-project"
    assert kwargs["auto_start"] is False
    with pytest.raises(HostError) as caught:
        kwargs["prepare"]()
    assert caught.value.code == "docker_unavailable"


def test_native_guide_and_mcp_do_not_emit_lifecycle_status(
    capsys: pytest.CaptureFixture[str],
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.chdir(tmp_path)
    assert entrypoint_main(["guide"]) == 0
    guide = capsys.readouterr()
    assert guide.out
    assert guide.err == ""

    monkeypatch.setattr(
        "localcloud_cli.mcp_stdio.run",
        lambda _config, **_kwargs: None,
    )
    assert main(["mcp"]) == 0
    mcp = capsys.readouterr()
    assert mcp.out == ""
    assert mcp.err == ""


def test_main_mcp_interrupt_closes_cleanly_in_terminal(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def interrupt(_config: Any, **_kwargs: Any) -> None:
        raise KeyboardInterrupt

    def exit_process(code: int) -> None:
        raise SystemExit(code)

    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr("localcloud_cli.mcp_stdio.run", interrupt)
    monkeypatch.setattr("localcloud_cli.cli.os._exit", exit_process)
    stderr = _TtyBuffer()
    monkeypatch.setattr("localcloud_cli.cli.sys.stderr", stderr)

    with pytest.raises(SystemExit) as caught:
        main(["mcp"])

    assert caught.value.code == 130
    assert stderr.getvalue() == "MCP connection closed.\n"


def test_main_mcp_interrupt_is_silent_when_not_interactive(
    capsys: pytest.CaptureFixture[str],
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def interrupt(_config: Any, **_kwargs: Any) -> None:
        raise KeyboardInterrupt

    def exit_process(code: int) -> None:
        raise SystemExit(code)

    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr("localcloud_cli.mcp_stdio.run", interrupt)
    monkeypatch.setattr("localcloud_cli.cli.os._exit", exit_process)

    with pytest.raises(SystemExit) as caught:
        main(["mcp"])

    assert caught.value.code == 130
    captured = capsys.readouterr()
    assert captured.out == ""
    assert captured.err == ""


def test_mcp_connect_timeout_defaults_and_requires_positive_finite_seconds() -> None:
    parser = _parser()

    assert parser.parse_args(["mcp"]).connect_timeout == 10.0
    assert parser.parse_args(["mcp", "--connect-timeout", "2.5"]).connect_timeout == 2.5

    for invalid in ("0", "-1", "nan", "inf", "invalid"):
        with pytest.raises(SystemExit):
            parser.parse_args(["mcp", "--connect-timeout", invalid])


def test_main_non_mcp_interrupt_exits_cleanly(
    capsys: pytest.CaptureFixture[str],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def interrupt(_self: FakeController, _config: Any) -> dict[str, Any]:
        raise KeyboardInterrupt

    monkeypatch.setattr(FakeController, "status", interrupt)

    assert main(["status"]) == 130
    captured = capsys.readouterr()
    assert "interrupted" in captured.err.lower()


def test_main_progress_command_interrupt_exits_cleanly(
    capsys: pytest.CaptureFixture[str],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def interrupt(_self: FakeController, _config: Any, **_kwargs: Any) -> dict[str, Any]:
        raise KeyboardInterrupt

    monkeypatch.setattr(FakeController, "start", interrupt)

    assert main(["start"]) == 130
    captured = capsys.readouterr()
    assert "interrupted" in captured.err.lower()


def test_entrypoint_main_interrupt_exits_cleanly(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def interrupt(_args: list[str]) -> int:
        raise KeyboardInterrupt

    monkeypatch.setattr("localcloud_cli.cli.main", interrupt)

    assert entrypoint_main(["status"]) == 130


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


def test_dry_run_sends_and_counts_no_telemetry(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    import localcloud_cli.telemetry as telemetry

    batches: list[list[dict[str, Any]]] = []
    monkeypatch.delenv("LOCALCLOUD_TELEMETRY")
    monkeypatch.setattr(
        telemetry, "_deliver", lambda events, _environment: batches.append(events) or True
    )

    assert main(["start", "--dry-run"]) == 0
    assert batches == []
    assert not (tmp_path / "home" / "telemetry.json").exists()


def test_main_reports_startup_failure_to_telemetry(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    import localcloud_cli.telemetry as telemetry

    def fail(_self: FakeController, _config: Any, **_kwargs: Any) -> dict[str, Any]:
        raise HostError("docker_unavailable", "Docker is not running")

    batches: list[list[dict[str, Any]]] = []
    monkeypatch.delenv("LOCALCLOUD_TELEMETRY")
    monkeypatch.setattr(
        telemetry, "_deliver", lambda events, _environment: batches.append(events) or True
    )
    monkeypatch.setattr(FakeController, "start", fail)

    assert main(["start"]) == 2
    assert [event["event"] for event in batches[0]] == ["cli_startup_error", "cli_heartbeat"]
    assert batches[0][0]["properties"]["error_code"] == "docker_unavailable"
    assert (tmp_path / "home" / "telemetry.json").exists()


@pytest.mark.parametrize("opted_out", [None, "local", "recorded"])
def test_main_reports_docker_failure_before_config_resolution(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, opted_out: str | None
) -> None:
    import localcloud_cli.telemetry as telemetry

    def unavailable(_self: FakeController) -> None:
        raise HostError("docker_unavailable", "Docker is not running")

    batches: list[list[dict[str, Any]]] = []
    monkeypatch.delenv("LOCALCLOUD_TELEMETRY")
    workdir = tmp_path / "work"
    workdir.mkdir()
    monkeypatch.chdir(workdir)
    opt_out = "version: 1\nhost:\n  environment:\n    LOCALCLOUD_TELEMETRY: false\n"
    if opted_out == "local":
        (workdir / "localcloud.yaml").write_text(opt_out)
    elif opted_out == "recorded":
        # Only the runtime remembers this config, which needs Docker to read;
        # telemetry's record of the last Docker-backed run stands in for it.
        remembered = tmp_path / "elsewhere" / "localcloud.yaml"
        remembered.parent.mkdir()
        remembered.write_text(opt_out)
        state = tmp_path / "home" / "telemetry.json"
        state.parent.mkdir(exist_ok=True)
        state.write_text(json.dumps({"config_path": str(remembered)}))
    monkeypatch.setattr(
        telemetry, "_deliver", lambda events, _environment: batches.append(events) or True
    )
    monkeypatch.setattr(FakeController, "__init__", unavailable)

    assert main(["start"]) == 2
    if opted_out:
        assert batches == []
    else:
        assert batches[0][0]["properties"]["error_code"] == "docker_unavailable"


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



def test_main_invalid_fields_lists_recovery_choices(
    capsys: pytest.CaptureFixture[str],
) -> None:
    assert main(["status", "--fields", "nope"]) == 2

    error = capsys.readouterr().err
    assert "Unsupported summary field for status: nope" in error
    assert "Fields: nope" in error
    assert "Valid Fields:" in error
    assert "container.state" in error


def test_status_help_lists_valid_summary_fields(
    capsys: pytest.CaptureFixture[str],
) -> None:
    with pytest.raises(SystemExit) as caught:
        _parser().parse_args(["status", "--help"])

    assert caught.value.code == 0
    help_text = capsys.readouterr().out
    assert "Valid paths:" in help_text
    assert "container.state" in help_text

def test_main_reports_unexpected_exception_as_clean_error(
    capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    def fail(_self: FakeController, _config: Any) -> dict[str, Any]:
        raise ValueError("boom")

    monkeypatch.setattr(FakeController, "status", fail)
    assert main(["status"]) == 1
    captured = capsys.readouterr()
    assert "Error [unexpected_error]" in captured.err
    assert "boom" in captured.err


def test_main_reraises_unexpected_exception_when_debug(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def fail(_self: FakeController, _config: Any) -> dict[str, Any]:
        raise ValueError("boom")

    monkeypatch.setattr(FakeController, "status", fail)
    with pytest.raises(ValueError, match="boom"):
        main(["status", "--debug"])


@pytest.mark.parametrize("command", ["env", "mcp"])
def test_native_commands_accept_debug_but_reject_summary_flags(command: str) -> None:
    parser = _parser()
    assert parser.parse_args([command, "--debug"]).debug is True
    with pytest.raises(SystemExit):
        parser.parse_args([command, "--verbose"])
    with pytest.raises(SystemExit):
        parser.parse_args([command, "--fields", "status"])


@pytest.mark.parametrize("command", ["cleanup", "console"])
def test_structured_commands_without_extra_fields_reject_fields(command: str) -> None:
    parser = _parser()
    assert parser.parse_args([command, "--verbose"]).verbose is True
    with pytest.raises(SystemExit):
        parser.parse_args([command, "--fields", "status"])


def test_cleanup_dispatches_to_controller() -> None:
    result = _execute(_parser().parse_args(["cleanup"]))
    assert result == {"status": "ok", "dry_run": False}
    call, confirm = FakeController.instance.calls[-1]
    assert call == "cleanup"
    assert confirm is True

    result = _execute(_parser().parse_args(["cleanup", "--dry-run"]))
    assert result == {"status": "ok", "dry_run": True}
    call, confirm = FakeController.instance.calls[-1]
    assert call == "cleanup"
    assert confirm is False


def test_main_cleanup_partial_returns_failure_with_result(
    capsys: pytest.CaptureFixture[str],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        FakeController,
        "cleanup",
        lambda _self, **_kwargs: {
            "status": "partial",
            "dry_run": False,
            "failures": [{"kind": "network", "name": "broken-network"}],
        },
    )

    assert main(["cleanup"]) == 1
    captured = capsys.readouterr()
    assert "Failed" in captured.err
    assert "LocalCloud cleanup completed with failures" in captured.err
    assert "Status    Partial" in captured.out
    assert "Failures" in captured.out

def test_restart_and_start_pull_flags() -> None:
    parser = _parser()
    assert parser.parse_args(["restart"]).pull is False
    assert parser.parse_args(["restart", "--no-pull"]).pull is False
    assert parser.parse_args(["restart", "--pull"]).pull is True
    assert parser.parse_args(["start"]).pull is True
    assert parser.parse_args(["start", "--no-pull"]).pull is False
    assert parser.parse_args(["start", "--pull"]).pull is True


def test_restart_dispatch_passes_pull_flag() -> None:
    _execute(_parser().parse_args(["restart", "--pull"]))
    assert FakeController.instance.pull_calls[-1] == ("restart", True)

    _execute(_parser().parse_args(["restart", "--no-pull"]))
    assert FakeController.instance.pull_calls[-1] == ("restart", False)

    _execute(_parser().parse_args(["restart"]))
    assert FakeController.instance.pull_calls[-1] == ("restart", False)

    _execute(_parser().parse_args(["start", "--pull"]))
    assert FakeController.instance.pull_calls[-1] == ("start", True)

    _execute(_parser().parse_args(["start", "--no-pull"]))
    assert FakeController.instance.pull_calls[-1] == ("start", False)

    _execute(_parser().parse_args(["start"]))
    assert FakeController.instance.pull_calls[-1] == ("start", True)


def test_dry_run_pull_flag_dispatch() -> None:
    # Dry run without explicit --pull should pass pull=False
    _execute(_parser().parse_args(["start", "--dry-run"]))
    assert FakeController.instance.pull_calls[-1] == ("start", False)

    # Dry run with explicit --pull passes pull=True (for controller to reject)
    _execute(_parser().parse_args(["start", "--dry-run", "--pull"]))
    assert FakeController.instance.pull_calls[-1] == ("start", True)

    _execute(_parser().parse_args(["restart", "--dry-run"]))
    assert FakeController.instance.pull_calls[-1] == ("restart", False)

    _execute(_parser().parse_args(["restart", "--dry-run", "--pull"]))
    assert FakeController.instance.pull_calls[-1] == ("restart", True)

def test_restart_and_start_tls_flags() -> None:
    parser = _parser()
    assert parser.parse_args(["restart"]).tls is None
    assert parser.parse_args(["restart", "--no-tls"]).tls is False
    assert parser.parse_args(["restart", "--tls"]).tls is True
    assert parser.parse_args(["start"]).tls is None
    assert parser.parse_args(["start", "--no-tls"]).tls is False
    assert parser.parse_args(["start", "--tls"]).tls is True


@pytest.mark.parametrize("command", ["start", "restart", "reset"])
def test_local_only_flag_reaches_runtime_config(command: str) -> None:
    configs = []
    for flags, expected in (([], False), (["--local-only"], True)):
        args = _parser().parse_args([command, *flags])
        assert args.local_only is expected
        _execute(args)
        config = FakeController.instance.calls[-1][1]
        if command == "reset":
            config, _all_projects = config
        assert config.local_only is expected
        configs.append(config)
    assert configs[0].config_hash != configs[1].config_hash


def test_start_and_restart_tls_dispatch_defaults_to_disabled() -> None:
    _execute(_parser().parse_args(["start"]))
    _, config = FakeController.instance.calls[-1]
    assert "LOCALCLOUD_TLS_ENABLED" not in config.environment

    _execute(_parser().parse_args(["start", "--no-tls"]))
    _, config = FakeController.instance.calls[-1]
    assert config.environment["LOCALCLOUD_TLS_ENABLED"] == "false"

    _execute(_parser().parse_args(["restart", "--tls"]))
    _, config = FakeController.instance.calls[-1]
    assert config.environment["LOCALCLOUD_TLS_ENABLED"] == "true"


def test_strict_port_validation_flag_reaches_runtime_config() -> None:
    _execute(_parser().parse_args(["start", "--strict-port-validation"]))
    _, config = FakeController.instance.calls[-1]
    assert config.strict_port_validation is True

    _execute(_parser().parse_args(["reset"]))
    reset_config, _all_projects = FakeController.instance.calls[-1][1]
    assert reset_config.strict_port_validation is False


def test_restart_and_start_memory_image_services_flags() -> None:
    parser = _parser()
    args = parser.parse_args(
        [
            "start",
            "--memory",
            "8g",
            "--image",
            "myrepo/localcloud:dev",
            "--services",
            "gcs,pubsub",
        ]
    )
    assert args.memory == "8g"
    assert args.image == "myrepo/localcloud:dev"
    assert args.services == ["gcs", "pubsub"]

    assert parser.parse_args(["restart", "--services", "default"]).services == "default"
    assert parser.parse_args(["start"]).memory is None
    assert parser.parse_args(["start"]).image is None
    assert parser.parse_args(["start"]).services is None


def test_start_and_restart_memory_image_services_dispatch() -> None:
    _execute(
        _parser().parse_args(
            [
                "start",
                "--memory",
                "8g",
                "--image",
                "myrepo/localcloud:dev",
                "--services",
                "gcs,pubsub",
            ]
        )
    )
    _, config = FakeController.instance.calls[-1]
    assert config.memory == "8g"
    assert config.image == "myrepo/localcloud:dev"
    assert config.services == ("gcs", "pubsub")

    _execute(_parser().parse_args(["restart", "--services", "default"]))
    _, config = FakeController.instance.calls[-1]
    assert config.services is None


@pytest.mark.parametrize("command", ["start", "restart", "reset", "stop"])
def test_mutating_commands_accept_and_dispatch_dry_run(command: str) -> None:
    args = _parser().parse_args([command, "--dry-run"])
    assert args.dry_run is True

    result = _execute(args)

    assert isinstance(result, str)
    assert FakeController.instance.dry_run_calls[-1] == (command, True)


def test_start_and_restart_tail_flags() -> None:
    parser = _parser()
    assert parser.parse_args(["start"]).tail == 5.0
    assert parser.parse_args(["start", "--tail"]).tail == -1.0
    assert parser.parse_args(["start", "--tail", "10"]).tail == 10.0
    assert parser.parse_args(["start", "--tail", "0"]).tail == 0.0
    assert parser.parse_args(["restart"]).tail == 5.0
    assert parser.parse_args(["restart", "--tail"]).tail == -1.0
    assert parser.parse_args(["restart", "--tail", "15.5"]).tail == 15.5

    with pytest.raises(SystemExit):
        parser.parse_args(["start", "--tail", "-5"])
    with pytest.raises(SystemExit):
        parser.parse_args(["start", "--tail", "invalid"])


def test_accept_dynamic_ports_flag_dispatches_noninteractive_confirmation() -> None:
    args = _parser().parse_args(["restart", "--accept-dynamic-ports"])

    _execute(args)

    assert args.accept_dynamic_ports is True
    assert FakeController.instance.port_confirmation_calls[-1] == (
        "restart",
        True,
    )


def test_port_mapping_confirmation_prompts_with_exact_mapping() -> None:
    from localcloud_cli.cli import _ExecutionObserver
    from localcloud_cli.output import LifecycleReporter

    class TtyBuffer(io.StringIO):
        def isatty(self) -> bool:
            return True

    output = TtyBuffer()
    input_stream = TtyBuffer("yes\n")
    reporter = LifecycleReporter(
        stream=output,
        environ={"TERM": "xterm-256color"},
        fps=1,
    )
    reporter.start("Planning LocalCloud ports…")
    observer = _ExecutionObserver(reporter, input_stream=input_stream)
    plan = SimpleNamespace(
        alternative_port_mappings=lambda: (
            ("127.0.0.1", 5508, 5380, "tcp"),
            ("127.0.0.1", 5509, 5381, "tcp"),
        )
    )

    assert observer.confirm_port_mapping(plan) is True
    reporter.close()

    rendered = output.getvalue()
    assert "127.0.0.1:5508 -> 5380/tcp" in rendered
    assert "Continue with these mappings? [y/N] " in rendered
    assert "\x1b[?25h" in rendered


def test_port_mapping_confirmation_fails_closed_without_tty() -> None:
    from localcloud_cli.cli import _ExecutionObserver
    from localcloud_cli.output import LifecycleReporter

    reporter = LifecycleReporter(
        stream=io.StringIO(),
        environ={"TERM": "dumb"},
    )
    observer = _ExecutionObserver(
        reporter,
        input_stream=io.StringIO("yes\n"),
    )
    plan = SimpleNamespace(
        alternative_port_mappings=lambda: (
            ("127.0.0.1", 5508, 5380, "tcp"),
        )
    )

    with pytest.raises(HostError) as caught:
        observer.confirm_port_mapping(plan)

    assert caught.value.code == "port_mapping_confirmation_required"
    assert caught.value.details["override"] == "--accept-dynamic-ports"


def test_debug_flag_parsing() -> None:
    parser = _parser()
    assert parser.parse_args(["start", "--debug"]).debug is True
    assert parser.parse_args(["start"]).debug is False
    assert parser.parse_args(["restart", "--debug"]).debug is True
    assert parser.parse_args(["status", "--debug"]).debug is True


def test_port_range_flag_parsing() -> None:
    parser = _parser()
    for command in ("start", "restart", "reset"):
        args = parser.parse_args([command, "--port-range", "6000-6099"])
        assert args.port_range == "6000-6099"
    assert parser.parse_args(["start"]).port_range is None


def test_explicit_project_id_requests_api_ensure_only_for_start_and_restart() -> None:
    _execute(_parser().parse_args(["start"]))
    assert FakeController.instance.ensure_project_calls == [("start", False)]

    _execute(
        _parser().parse_args(
            ["start", "--project-id", "explicit-project-1"]
        )
    )
    assert FakeController.instance.ensure_project_calls == [("start", True)]

    _execute(
        _parser().parse_args(
            ["restart", "--project-id", "explicit-project-1"]
        )
    )
    assert FakeController.instance.ensure_project_calls == [("restart", True)]


def _git_repository(path: Path) -> Path:
    import subprocess

    path.mkdir(parents=True)
    subprocess.run(["git", "init", "-q", str(path)], check=True)
    return path


@pytest.mark.usefixtures("real_git")
def test_commands_in_a_git_repository_select_and_create_its_project(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    repository = _git_repository(tmp_path / "Orders Service")
    (repository / "api").mkdir()
    monkeypatch.chdir(repository / "api")
    monkeypatch.setattr(
        "localcloud_cli.endpoints.environment_config", lambda *_args, **_kwargs: ""
    )

    for command in ("start", "restart", "env"):
        _execute(_parser().parse_args([command]))
        controller = FakeController.instance
        assert controller.calls[-1][1].project == "orders-service"
        assert controller.ensure_project_calls == [
            ("target" if command == "env" else command, True)
        ]


@pytest.mark.usefixtures("real_git")
def test_commands_outside_a_repository_keep_the_default_project(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(
        "localcloud_cli.endpoints.environment_config", lambda *_args, **_kwargs: ""
    )

    _execute(_parser().parse_args(["env"]))

    assert FakeController.instance.calls[0][1].project == "local-gcp-project"
    assert FakeController.instance.ensure_project_calls == [("target", False)]


def test_debug_start_enables_container_debug_and_startup_metrics() -> None:
    _execute(_parser().parse_args(["start", "--debug"]))

    start_config = next(
        value
        for name, value in FakeController.instance.calls
        if name == "start"
    )
    assert start_config.environment["LOCALCLOUD_LOG_LEVEL"] == "DEBUG"
    assert start_config.environment["LOCALCLOUD_STARTUP_METRICS"] == "true"


def test_start_dispatch_passes_tail_flag() -> None:
    _execute(_parser().parse_args(["start", "--tail", "10"]))
    assert FakeController.instance.tail_calls[-1] == ("start", 10.0)

    _execute(_parser().parse_args(["start", "--tail"]))
    assert FakeController.instance.tail_calls[-1] == ("start", -1.0)

    _execute(_parser().parse_args(["start"]))
    assert FakeController.instance.tail_calls[-1] == ("start", 5.0)


def test_observer_runtime_logs_deduplication_and_line_streaming() -> None:
    from localcloud_cli.cli import _ExecutionObserver
    from localcloud_cli.output import LifecycleReporter

    stream = io.StringIO()
    reporter = LifecycleReporter(stream=stream, environ={"TERM": "dumb"})
    reporter.start("Starting LocalCloud…")
    observer = _ExecutionObserver(reporter, debug=True)

    observer.runtime_logs("line 1\nline 2\n")
    observer.runtime_logs("line 2\nline 3\nline 4\n")
    observer.runtime_logs("line 3\nline 4\n")
    observer.runtime_logs("2026-08-18T10:00:00Z unique log\n")

    reporter.succeed("Done")
    output = stream.getvalue()
    assert "line 1\n" in output
    assert "line 2\n" in output
    assert "line 3\n" in output
    assert "line 4\n" in output
    assert "2026-08-18T10:00:00Z unique log\n" in output
    assert output.count("line 2\n") == 1
    assert output.count("line 3\n") == 1


def test_observer_debug_mode_writes_debug_lines() -> None:
    from localcloud_cli.cli import _ExecutionObserver
    from localcloud_cli.output import LifecycleReporter

    # Debug enabled
    stream_enabled = io.StringIO()
    rep_enabled = LifecycleReporter(stream=stream_enabled, environ={"TERM": "dumb"})
    rep_enabled.start("Starting…")
    obs_enabled = _ExecutionObserver(rep_enabled, debug=True)
    obs_enabled.debug("docker run -d --name localcloud")
    assert "[debug] docker run -d --name localcloud\n" in stream_enabled.getvalue()

    # Debug disabled
    stream_disabled = io.StringIO()
    rep_disabled = LifecycleReporter(stream=stream_disabled, environ={"TERM": "dumb"})
    rep_disabled.start("Starting…")
    obs_disabled = _ExecutionObserver(rep_disabled, debug=False)
    obs_disabled.debug("docker run -d --name localcloud")
    assert "[debug]" not in stream_disabled.getvalue()


def test_observer_warning_survives_disabled_verbose_reporter() -> None:
    from localcloud_cli.cli import _ExecutionObserver
    from localcloud_cli.output import LifecycleReporter

    stream = io.StringIO()
    reporter = LifecycleReporter(
        stream=stream,
        verbose=True,
        environ={"TERM": "dumb"},
    )
    assert reporter.enabled is False

    _ExecutionObserver(reporter).warning("image metadata drift")

    assert stream.getvalue() == "Warning: image metadata drift\n"


class _TtyBuffer(io.StringIO):
    def isatty(self) -> bool:
        return True


@pytest.mark.parametrize(
    ("command", "message"),
    [
        (
            "start",
            "Starting LocalCloud container on data volume: 'localcloud-data'",
        ),
        (
            "restart",
            "Restarting LocalCloud container on data volume: 'localcloud-data'",
        ),
        (
            "stop",
            "Stopping LocalCloud container on data volume: 'localcloud-data'",
        ),
    ],
)
def test_observer_lifecycle_commands_skip_artwork_panel(
    command: str,
    message: str,
) -> None:
    from localcloud_cli.cli import _ExecutionObserver
    from localcloud_cli.output import LifecycleReporter

    args = SimpleNamespace(all_projects=False)
    config = SimpleNamespace(
        data_volume="localcloud-data",
        project="local-gcp-project",
        user="local-developer",
        services=None,
        data="persistent",
        config_path=None,
    )
    stream = _TtyBuffer()
    reporter = LifecycleReporter(
        stream=stream,
        environ={"TERM": "xterm", "NO_COLOR": "1"},
    )

    _ExecutionObserver(reporter).config(command, config, args)
    reporter.succeed("Done")

    assert reporter._panel is None
    assert message in reporter._message
    assert "LocalCloud v" not in stream.getvalue()


def test_observer_starting_transition_skips_artwork_panel() -> None:
    from localcloud_cli.cli import _ExecutionObserver
    from localcloud_cli.output import LifecycleReporter

    config = SimpleNamespace(
        data_volume="localcloud-data",
        project="local-gcp-project",
    )
    stream = _TtyBuffer()
    reporter = LifecycleReporter(
        stream=stream,
        environ={"TERM": "xterm", "NO_COLOR": "1"},
    )

    _ExecutionObserver(reporter).starting(config)
    reporter.succeed("Done")

    assert reporter._panel is None
    assert "Starting LocalCloud container on data volume: 'localcloud-data'" in reporter._message
    assert "LocalCloud v" not in stream.getvalue()


def test_execution_observer_stopping_updates_task_message() -> None:
    from localcloud_cli.cli import _ExecutionObserver
    from localcloud_cli.output import LifecycleReporter

    config = SimpleNamespace(
        data_volume="localcloud-data",
        project="local-gcp-project",
    )
    current = SimpleNamespace(
        name="localcloud",
        container_id="c123456",
    )
    stream = _TtyBuffer()
    reporter = LifecycleReporter(
        stream=stream,
        environ={"TERM": "xterm", "NO_COLOR": "1"},
    )

    _ExecutionObserver(reporter).stopping(config, current)
    reporter.succeed("Done")

    assert reporter._panel is None
    assert "Found running container 'localcloud'; stopping it…" in reporter._message
    assert "LocalCloud v" not in stream.getvalue()


@pytest.mark.parametrize(
    ("command", "expected"),
    [
        (
            "logs",
            "Reading 1 recent log line from data volume: 'localcloud-data'",
        ),
        (
            "console",
            "Opening LocalCloud console for project 'local-gcp-project' "
            "on data volume: 'localcloud-data'",
        ),
        (
            "env",
            "Generating shell SDK configuration for project 'local-gcp-project'",
        ),
    ],
)
def test_observer_native_commands_use_resolved_context_without_artwork(
    command: str,
    expected: str,
) -> None:
    from localcloud_cli.cli import _ExecutionObserver
    from localcloud_cli.output import LifecycleReporter

    args = SimpleNamespace(all_projects=False, tail=1, format="shell")
    config = SimpleNamespace(
        data_volume="localcloud-data",
        project="local-gcp-project",
        user="local-developer",
        services=None,
        data="persistent",
        config_path=None,
    )
    reporter = LifecycleReporter(
        stream=_TtyBuffer(),
        environ={"TERM": "xterm", "NO_COLOR": "1"},
    )

    _ExecutionObserver(reporter).config(command, config, args)

    assert reporter._panel is None
    assert expected in reporter._message


@pytest.mark.parametrize(
    ("command", "expected_heading"),
    [
        ("reset", "Resetting project data"),
        ("status", "Checking LocalCloud status"),
    ],
)
def test_observer_retains_artwork_panel_for_other_runtime_commands(
    command: str,
    expected_heading: str,
) -> None:
    from localcloud_cli.cli import _ExecutionObserver
    from localcloud_cli.output import LifecycleReporter, PanelContext

    args = SimpleNamespace(all_projects=False)
    config = SimpleNamespace(
        data_volume="localcloud-data",
        project="local-gcp-project",
        user="local-developer",
        services=None,
        data="persistent",
        config_path=None,
    )
    stream = _TtyBuffer()
    reporter = LifecycleReporter(
        stream=stream,
        environ={"TERM": "xterm", "NO_COLOR": "1"},
    )

    _ExecutionObserver(reporter).config(command, config, args)

    assert isinstance(reporter._panel, PanelContext)
    assert reporter._panel.heading == expected_heading
    assert "LocalCloud v" in stream.getvalue()


def test_observer_uses_all_projects_reset_heading() -> None:
    from localcloud_cli.cli import _ExecutionObserver
    from localcloud_cli.output import LifecycleReporter

    args = SimpleNamespace(all_projects=True)
    config = SimpleNamespace(
        data_volume="localcloud-data",
        project="local-gcp-project",
        user="local-developer",
        services=None,
        data="persistent",
        config_path=None,
    )
    reporter = LifecycleReporter(
        stream=_TtyBuffer(),
        environ={"TERM": "xterm", "NO_COLOR": "1"},
    )

    _ExecutionObserver(reporter).config("reset", config, args)

    assert reporter._panel is not None
    assert reporter._panel.heading == "Resetting all LocalCloud data"


def test_observer_retains_artwork_panel_for_doctor(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    from localcloud_cli.cli import _ExecutionObserver
    from localcloud_cli.output import LifecycleReporter, PanelContext

    monkeypatch.chdir(tmp_path)
    stream = _TtyBuffer()
    reporter = LifecycleReporter(
        stream=stream,
        environ={"TERM": "xterm", "NO_COLOR": "1"},
    )

    _ExecutionObserver(reporter).doctor(
        SimpleNamespace(data_volume=None, project_id=None, user=None)
    )

    assert isinstance(reporter._panel, PanelContext)
    assert reporter._panel.heading == "Checking LocalCloud setup"
    assert "LocalCloud v" in stream.getvalue()


def test_observer_renders_consolidated_image_pull_progress() -> None:
    from localcloud_cli.cli import _ExecutionObserver
    from localcloud_cli.output import LifecycleReporter

    now = [0.0]
    stream = io.StringIO()
    reporter = LifecycleReporter(
        stream=stream,
        environ={"TERM": "dumb"},
        clock=lambda: now[0],
    )
    reporter.start("Starting LocalCloud…")
    observer = _ExecutionObserver(reporter)

    observer.image_pull(
        "example/localcloud:latest",
        status="Downloading",
        layer="layer-a",
        current=5 * 1024 * 1024,
        total=10 * 1024 * 1024,
    )
    now[0] = 2.1
    observer.image_pull(
        "example/localcloud:latest",
        status="Downloading",
        layer="layer-b",
        current=15 * 1024 * 1024,
        total=30 * 1024 * 1024,
    )
    reporter.succeed("Done")

    output = stream.getvalue()
    assert "Downloading 50% overall · 20.0 MiB / 40.0 MiB · 2 layers" in output
    assert "layer-a" not in output
    assert "layer-b" not in output

    class TtyBuffer(io.StringIO):
        def isatty(self) -> bool:
            return True

    tty_reporter = LifecycleReporter(
        stream=TtyBuffer(),
        environ={"TERM": "xterm", "NO_COLOR": "1"},
        width=80,
    )
    tty_observer = _ExecutionObserver(tty_reporter)
    tty_observer.image_pull(
        "registry.example.com/organization/localcloud:latest",
        status="Downloading",
        layer="layer-a",
        current=5 * 1024 * 1024,
        total=10 * 1024 * 1024,
    )

    frame = tty_reporter._frame(elapsed=1.0)[0]
    assert "50%" in frame
    assert "5.0 MiB / 10.0 MiB" in frame


def test_observer_throttles_plain_image_pull_updates() -> None:
    from localcloud_cli.cli import _ExecutionObserver
    from localcloud_cli.output import LifecycleReporter

    now = [0.0]
    stream = io.StringIO()
    reporter = LifecycleReporter(
        stream=stream,
        environ={"TERM": "dumb"},
        clock=lambda: now[0],
    )
    reporter.start("Starting LocalCloud…")
    observer = _ExecutionObserver(reporter)

    observer.image_pull("example/image:latest", status="Contacting registry")
    observer.image_pull(
        "example/image:latest",
        status="Downloading",
        layer="layer-a",
        current=1,
        total=100,
    )
    now[0] = 0.5
    observer.image_pull(
        "example/image:latest",
        status="Downloading",
        layer="layer-b",
        current=50,
        total=100,
    )
    now[0] = 2.1
    observer.image_pull(
        "example/image:latest",
        status="Downloading",
        layer="layer-b",
        current=75,
        total=100,
    )
    observer.image_pull("example/image:latest", status="Pull complete")
    reporter.succeed("Done")

    output = stream.getvalue()
    assert "layer-a" not in output
    assert "layer-b" not in output
    assert "1% overall" in output
    assert "38% overall" in output
    assert "2 layers" in output
    assert output.count("Processing") == 5


def test_observer_reports_extraction_as_a_separate_pull_phase() -> None:
    from localcloud_cli.cli import _ExecutionObserver
    from localcloud_cli.output import LifecycleReporter

    now = [0.0]
    stream = io.StringIO()
    reporter = LifecycleReporter(
        stream=stream,
        environ={"TERM": "dumb"},
        clock=lambda: now[0],
    )
    observer = _ExecutionObserver(reporter)

    observer.image_pull(
        "example/image:latest",
        status="Downloading",
        layer="layer-a",
        current=50,
        total=100,
    )
    now[0] = 2.1
    observer.image_pull(
        "example/image:latest",
        status="Extracting",
        layer="layer-a",
        current=25,
        total=100,
    )

    output = stream.getvalue()
    assert "Downloading 50% overall" in output
    assert "Fetching image 'example/image:latest' · Extracting · 1 layer" in output


def test_observer_only_reports_image_downloaded_for_final_pull_completion() -> None:
    from localcloud_cli.cli import _ExecutionObserver
    from localcloud_cli.output import LifecycleReporter

    class TtyBuffer(io.StringIO):
        def isatty(self) -> bool:
            return True

    reporter = LifecycleReporter(
        stream=TtyBuffer(),
        environ={"TERM": "xterm", "NO_COLOR": "1"},
    )
    observer = _ExecutionObserver(reporter)

    observer.image_pull(
        "example/image:latest",
        status="Downloading",
        layer="layer-a",
        current=50,
        total=100,
    )
    observer.image_pull(
        "example/image:latest",
        status="Pull complete",
        layer="layer-a",
    )

    assert "Downloaded image" not in reporter._message
    assert "Downloading 100% overall" in reporter._message

    observer.image_pull("example/image:latest", status="Pull complete")

    assert reporter._message == "Downloaded image 'example/image:latest'…"


def test_observer_resets_consolidated_progress_for_the_next_image() -> None:
    from localcloud_cli.cli import _ExecutionObserver
    from localcloud_cli.output import LifecycleReporter

    stream = io.StringIO()
    reporter = LifecycleReporter(stream=stream, environ={"TERM": "dumb"})
    observer = _ExecutionObserver(reporter)

    observer.image_pull(
        "example/first:latest",
        status="Downloading",
        layer="first-layer",
        current=50,
        total=100,
    )
    observer.image_pull(
        "example/second:latest",
        status="Downloading",
        layer="second-layer",
        current=10,
        total=100,
    )

    output = stream.getvalue()
    assert "Downloading 50% overall" in output
    assert "image 'example/first:latest'" in output
    assert "Downloading 10% overall" in output
    assert "image 'example/second:latest'" in output
    assert "Downloading 30% overall" not in output


def test_main_start_debug_prints_copyable_ranged_docker_command(
    capsys: pytest.CaptureFixture[str],
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    monkeypatch.chdir(tmp_path)
    assert main(["start", "--debug"]) == 0
    captured = capsys.readouterr()
    assert "[debug] Lifecycle action" not in captured.err
    assert "[debug] Published ports" not in captured.err
    assert "[debug] docker run -d --name localcloud" in captured.err
    assert "5380-5405:5380-5405/tcp" in captured.err


def test_main_start_dry_run_prints_native_plan(
    capsys: pytest.CaptureFixture[str],
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    monkeypatch.chdir(tmp_path)
    assert main(["start", "--dry-run"]) == 0
    captured = capsys.readouterr()
    assert "# action: create" in captured.out
    assert "docker run -d --name localcloud" in captured.out
    assert "dry-run completed" in captured.err

def test_main_start_when_already_running_reports_guidance_and_skips_panel(
    capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(
        FakeController,
        "start",
        lambda _self, config, **_kwargs: {
            "status": "already_running",
            "data_volume": config.data_volume,
        },
    )
    assert main(["start"]) == 0
    captured = capsys.readouterr()
    assert "LocalCloud runtime is already running" in captured.err
    assert "LocalCloud v" not in captured.err


def test_main_doctor_success_message(
    capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.chdir(tmp_path)
    assert main(["doctor"]) == 0
    captured = capsys.readouterr()
    assert "LocalCloud is ready to start" in captured.err


def test_main_without_args_defaults_to_help_and_includes_required_note(
    capsys: pytest.CaptureFixture[str],
) -> None:
    assert main([]) == 0
    captured = capsys.readouterr()
    assert captured.err == ""
    assert "usage: localcloud [-h] [-v] <command> ..." in captured.out
    assert "-v, --version" in captured.out
    assert "<command>      one of the <command> is required" in captured.out
    assert "Note: One of the <command> is required." in captured.out
    assert "localcloud: error: the following arguments are required: command" not in captured.err


def test_main_none_argv_defaults_to_help(
    capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(sys, "argv", ["localcloud"])
    assert main() == 0
    captured = capsys.readouterr()
    assert captured.err == ""
    assert "usage: localcloud [-h] [-v] <command> ..." in captured.out
    assert "-v, --version" in captured.out
    assert "Note: One of the <command> is required." in captured.out


def test_entrypoint_main_without_args_defaults_to_help(
    capsys: pytest.CaptureFixture[str],
) -> None:
    assert entrypoint_main([]) == 0
    captured = capsys.readouterr()
    assert captured.err == ""
    assert "usage: localcloud [-h] [-v] <command> ..." in captured.out
    assert "-v, --version" in captured.out
    assert "Note: One of the <command> is required." in captured.out


def test_parser_without_args_defaults_to_help(
    capsys: pytest.CaptureFixture[str],
) -> None:
    with pytest.raises(SystemExit) as caught:
        _parser().parse_args([])

    assert caught.value.code == 0
    captured = capsys.readouterr()
    assert "usage: localcloud [-h] [-v] <command> ..." in captured.out
    assert "-v, --version" in captured.out
    assert "<command>      one of the <command> is required" in captured.out
    assert "Note: One of the <command> is required." in captured.out


def test_help_flags_include_command_required_note(
    capsys: pytest.CaptureFixture[str],
) -> None:
    assert main(["-h"]) == 0
    help_h = capsys.readouterr().out
    assert "Note: One of the <command> is required." in help_h

    assert main(["--help"]) == 0
    help_long = capsys.readouterr().out
    assert "Note: One of the <command> is required." in help_long



# --- Docker host setup checks: doctor --fix, start offer, failure diagnostics ---


class _Tty(io.StringIO):
    def isatty(self) -> bool:
        return True


@pytest.fixture
def terminal(monkeypatch: pytest.MonkeyPatch) -> _Tty:
    """Run main() as if in a terminal; returns the reporter's stream."""
    import localcloud_cli.cli as cli_module
    from localcloud_cli.output import LifecycleReporter

    stream = _Tty()
    monkeypatch.setattr(
        cli_module,
        "LifecycleReporter",
        lambda *, verbose=False: LifecycleReporter(
            stream=stream, verbose=verbose, environ={"TERM": "dumb"}
        ),
    )
    monkeypatch.setattr(sys, "stdin", _Tty(""))
    return stream


def _answers(monkeypatch: pytest.MonkeyPatch, *lines: str) -> None:
    monkeypatch.setattr(sys, "stdin", _Tty("".join(f"{line}\n" for line in lines)))


@pytest.fixture
def commands(monkeypatch: pytest.MonkeyPatch) -> list[tuple[str, ...]]:
    """Fix commands run (never really); a command named 'fail' exits 3."""
    import localcloud_cli.host_checks as host_checks

    ran: list[tuple[str, ...]] = []

    def run(command: Any) -> int:
        ran.append(tuple(command))
        return 3 if "fail" in command else 0

    monkeypatch.setattr(host_checks, "_run_streaming", run)
    return ran


def _mac_probe(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    *,
    tools: tuple[str, ...] = (),
    environ: dict[str, str] | None = None,
) -> Any:
    import localcloud_cli.host_checks as host_checks

    def run(argv: Any, _timeout: float) -> Any:
        return SimpleNamespace(returncode=0, stdout="1\n" if argv[0] == "sysctl" else "")

    probe = host_checks.HostProbe(
        environ=environ or {},
        home=tmp_path / "mac-home",
        system="Darwin",
        run=run,
        which=lambda name: f"/opt/homebrew/bin/{name}" if name in tools else None,
        applications=(),
    )
    monkeypatch.setattr(host_checks, "default_probe", lambda: probe)
    return probe


def _docker_down(monkeypatch: pytest.MonkeyPatch, attempts: int) -> dict[str, int]:
    """FakeController() raises docker_unavailable for the first `attempts` calls."""
    from localcloud_cli.host_checks import docker_unavailable_error

    original = FakeController.__init__
    calls = {"n": 0}

    def init(self: FakeController) -> None:
        calls["n"] += 1
        if calls["n"] <= attempts:
            raise docker_unavailable_error(
                RuntimeError("FileNotFoundError(2, 'No such file or directory')")
            )
        original(self)

    monkeypatch.setattr(FakeController, "__init__", init)
    return calls


def _finding(identifier: str = "rosetta_off", *, deletes_data: bool = False, command: str = "colima") -> Any:
    from localcloud_cli.host_checks import Finding, Fix

    return Finding(
        identifier,
        "warning",
        f"{identifier} message",
        fix=Fix(
            summary=f"Fix {identifier}",
            commands=((command, "stop"), (command, "start", "--vz-rosetta")),
            notes=("Colima restarts.",),
            deletes_data=deletes_data,
        ),
    )


def _host() -> Any:
    from localcloud_cli.host_checks import DockerHost

    return DockerHost(provider="colima", profile="default", vm_type="vz", rosetta=False)


def test_doctor_fix_needs_a_terminal(
    capsys: pytest.CaptureFixture[str], commands: list[tuple[str, ...]]
) -> None:
    FakeController.setup_reports = [(_host(), [_finding()])]

    assert main(["doctor", "--fix"]) == 2

    assert "Error [fix_confirmation_required]" in capsys.readouterr().err
    assert commands == []


def test_doctor_fix_with_verbose_never_prompts(
    terminal: _Tty, capsys: pytest.CaptureFixture[str], commands: list[tuple[str, ...]]
) -> None:
    FakeController.setup_reports = [(_host(), [_finding()])]

    assert main(["doctor", "--fix", "--verbose"]) == 2

    error = json.loads(capsys.readouterr().err)
    assert error["code"] == "fix_confirmation_required"
    assert commands == []


def test_doctor_fix_applies_a_confirmed_fix_and_reports_it(
    monkeypatch: pytest.MonkeyPatch,
    terminal: _Tty,
    capsys: pytest.CaptureFixture[str],
    commands: list[tuple[str, ...]],
) -> None:
    from localcloud_cli.host_checks import DockerHost

    FakeController.setup_reports = [(_host(), [_finding()]), (DockerHost(provider="colima"), [])]
    _answers(monkeypatch, "y")

    assert main(["doctor", "--fix"]) == 0

    assert commands == [("colima", "stop"), ("colima", "start", "--vz-rosetta")]
    shown = terminal.getvalue()
    assert "rosetta_off message" in shown
    assert "  colima start --vz-rosetta" in shown
    assert "Fix rosetta_off? [y/N] " in shown
    assert "$ colima stop" in shown
    out = capsys.readouterr().out
    assert "Fixes   Applied: Fix rosetta_off" in out or "Applied: Fix rosetta_off" in out
    # Doctor re-inspects after a fix, so the report shows the host as fixed.
    assert [call for call, _ in FakeController.instance.calls].count("setup_report") == 2
    assert "Setup" not in out


def test_doctor_fix_declined_runs_nothing(
    monkeypatch: pytest.MonkeyPatch,
    terminal: _Tty,
    capsys: pytest.CaptureFixture[str],
    commands: list[tuple[str, ...]],
) -> None:
    FakeController.setup_reports = [(_host(), [_finding()])]
    _answers(monkeypatch, "n")

    assert main(["doctor", "--fix"]) == 0

    assert commands == []
    out = capsys.readouterr().out
    assert "Declined: Fix rosetta_off" in out
    assert "rosetta_off message" in out


@pytest.mark.parametrize(("answer", "applied"), [("y", False), ("yes", False), ("delete", True)])
def test_data_deleting_fix_needs_the_word_delete(
    monkeypatch: pytest.MonkeyPatch,
    terminal: _Tty,
    commands: list[tuple[str, ...]],
    answer: str,
    applied: bool,
) -> None:
    FakeController.setup_reports = [(_host(), [_finding("qemu_vm", deletes_data=True)])]
    _answers(monkeypatch, answer)

    assert main(["doctor", "--fix"]) == 0

    assert bool(commands) is applied
    shown = terminal.getvalue()
    assert "can delete every Docker image, container and volume" in shown
    assert "Type 'delete' to continue" in shown


def test_doctor_fix_lists_localcloud_volumes_before_deleting(
    monkeypatch: pytest.MonkeyPatch, terminal: _Tty, commands: list[tuple[str, ...]]
) -> None:
    import localcloud_cli.host_checks as host_checks
    from localcloud_cli.docker_runtime import MANAGED_LABEL

    volumes = [SimpleNamespace(name="team-data", attrs={"Labels": {MANAGED_LABEL: "true"}})]
    client = SimpleNamespace(volumes=SimpleNamespace(list=lambda: volumes))
    monkeypatch.setattr(host_checks, "_docker_client", lambda: client)
    FakeController.setup_reports = [(_host(), [_finding("qemu_vm", deletes_data=True)])]
    _answers(monkeypatch, "")

    assert main(["doctor", "--fix"]) == 0

    assert "LocalCloud volumes there: team-data" in terminal.getvalue()
    assert commands == []


def test_doctor_fix_failure_reports_the_command_and_exit_code(
    monkeypatch: pytest.MonkeyPatch,
    terminal: _Tty,
    capsys: pytest.CaptureFixture[str],
    commands: list[tuple[str, ...]],
) -> None:
    FakeController.setup_reports = [(_host(), [_finding(command="fail")])]
    _answers(monkeypatch, "y")

    assert main(["doctor", "--fix"]) == 1

    assert commands == [("fail", "stop")]
    captured = capsys.readouterr()
    assert "Failed: Fix rosetta_off ('fail stop' exited with 3)" in captured.out
    assert "A setup fix failed; see Fixes" in terminal.getvalue()


def test_doctor_without_docker_explains_and_shows_the_fix(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    _mac_probe(monkeypatch, tmp_path, tools=("brew",))
    _docker_down(monkeypatch, attempts=10)

    assert main(["doctor"]) == 2

    err = capsys.readouterr().err
    assert (
        "Error [docker_unavailable] Docker is not installed. Run 'lc doctor --fix' to "
        "install Colima, a free, lightweight Docker engine."
    ) in err
    assert "Setup  Fix: Install Colima with Homebrew and start it; run 'lc doctor --fix'" in err
    assert "Setup  Docker is not installed." not in err


def test_doctor_fix_installs_docker_then_reports(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    terminal: _Tty,
    capsys: pytest.CaptureFixture[str],
    commands: list[tuple[str, ...]],
) -> None:
    import localcloud_cli.cli as cli_module

    _mac_probe(monkeypatch, tmp_path, tools=("brew",))
    calls = _docker_down(monkeypatch, attempts=2)
    monkeypatch.setattr(cli_module, "_DOCKER_POLL_SECONDS", 0.0)
    _answers(monkeypatch, "y")

    assert main(["doctor", "--fix"]) == 0

    assert commands == [
        ("brew", "install", "colima", "docker"),
        ("colima", "start", "--cpu", "4", "--memory", "8", "--vm-type", "vz", "--vz-rosetta"),
    ]
    assert calls["n"] == 3  # failed, failed while starting, connected
    assert "Applied: Install Colima with Homebrew and start it" in capsys.readouterr().out


def test_start_offers_to_start_docker_and_continues(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    terminal: _Tty,
    commands: list[tuple[str, ...]],
) -> None:
    _mac_probe(monkeypatch, tmp_path, tools=("colima",), environ={"DOCKER_CONTEXT": "colima"})
    _docker_down(monkeypatch, attempts=1)
    _answers(monkeypatch, "y")

    assert main(["start"]) == 0

    assert commands == [("colima", "start")]
    assert "Colima is installed but not running." in terminal.getvalue()
    assert "Start Colima? [y/N] " in terminal.getvalue()
    assert [call for call, _ in FakeController.instance.calls][-1] == "start"


def test_start_gives_up_when_docker_does_not_come_up(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    terminal: _Tty,
    capsys: pytest.CaptureFixture[str],
    commands: list[tuple[str, ...]],
) -> None:
    import localcloud_cli.cli as cli_module

    _mac_probe(monkeypatch, tmp_path, tools=("colima",), environ={"DOCKER_CONTEXT": "colima"})
    _docker_down(monkeypatch, attempts=100)
    monkeypatch.setattr(cli_module, "_DOCKER_WAIT_SECONDS", 0.0)
    _answers(monkeypatch, "y")

    assert main(["start"]) == 2

    assert commands == [("colima", "start")]
    assert "Error [docker_unavailable]" in capsys.readouterr().err


def test_start_declined_keeps_the_error(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    terminal: _Tty,
    capsys: pytest.CaptureFixture[str],
    commands: list[tuple[str, ...]],
) -> None:
    _mac_probe(monkeypatch, tmp_path, tools=("colima",), environ={"DOCKER_CONTEXT": "colima"})
    _docker_down(monkeypatch, attempts=10)
    _answers(monkeypatch, "n")

    assert main(["start"]) == 2

    assert commands == []
    err = capsys.readouterr().err
    assert "Error [docker_unavailable] Colima is installed but not running." in err
    # Offered once, before the controller; not again after the failure.
    assert terminal.getvalue().count("Start Colima? [y/N]") == 1


@pytest.mark.parametrize("argv", [["start"], ["start", "--verbose"]])
def test_start_without_a_terminal_never_offers(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
    commands: list[tuple[str, ...]],
    argv: list[str],
) -> None:
    _mac_probe(monkeypatch, tmp_path, tools=("colima",), environ={"DOCKER_CONTEXT": "colima"})
    _docker_down(monkeypatch, attempts=10)

    assert main(argv) == 2

    assert commands == []
    err = capsys.readouterr().err
    if "--verbose" in argv:
        details = json.loads(err)["details"]
        assert details["reason"] == "not_running"
        assert [item["id"] for item in details["setup_findings"]] == ["docker_not_running"]
    else:
        assert "Setup  Fix: Start Colima; run 'lc doctor --fix'" in err


def test_failed_start_runs_the_checks_and_offers_the_fix(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    terminal: _Tty,
    capsys: pytest.CaptureFixture[str],
    commands: list[tuple[str, ...]],
) -> None:
    import localcloud_cli.host_checks as host_checks

    probe = _mac_probe(monkeypatch, tmp_path, tools=("colima",))
    colima = probe.home / ".colima" / "default"
    colima.mkdir(parents=True)
    (colima / "colima.yaml").write_text("vmType: vz\nrosetta: false\n")
    monkeypatch.setattr(
        host_checks, "_docker_client", lambda: SimpleNamespace(info=lambda: {"Name": "colima"})
    )

    def fail(_self: FakeController, _config: Any, **_kwargs: Any) -> None:
        raise HostError("health_timeout", "LocalCloud did not become healthy")

    monkeypatch.setattr(FakeController, "start", fail)
    _answers(monkeypatch, "y")

    assert main(["start"]) == 2

    err = capsys.readouterr().err
    assert "Error [health_timeout] LocalCloud did not become healthy" in err
    assert "Setup  Colima has Rosetta turned off" in err
    assert "Turn on Rosetta in Colima? [y/N] " in terminal.getvalue()
    assert commands == [("colima", "stop"), ("colima", "start", "--vz-rosetta")]
    assert "Applied: Turn on Rosetta in Colima" in err
    assert "Run 'lc start' again." in err


def test_start_with_log_errors_reports_findings_in_json(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    import localcloud_cli.host_checks as host_checks

    probe = _mac_probe(monkeypatch, tmp_path, tools=("colima",))
    colima = probe.home / ".colima" / "default"
    colima.mkdir(parents=True)
    (colima / "colima.yaml").write_text("vmType: qemu\n")
    monkeypatch.setattr(
        host_checks, "_docker_client", lambda: SimpleNamespace(info=lambda: {"Name": "colima"})
    )

    def started(_self: FakeController, _config: Any, **_kwargs: Any) -> dict[str, Any]:
        return {"status": "started", "logs": "[FAILED] Service BigQuery (bigquery) failed"}

    monkeypatch.setattr(FakeController, "start", started)

    assert main(["start", "--verbose"]) == 0

    result = json.loads(capsys.readouterr().out)
    assert [item["id"] for item in result["setup_findings"]] == ["qemu_vm"]


def test_healthy_start_runs_no_checks(monkeypatch: pytest.MonkeyPatch) -> None:
    import localcloud_cli.host_checks as host_checks

    def never(*_args: Any, **_kwargs: Any) -> None:
        raise AssertionError("setup checks ran for a healthy start")

    monkeypatch.setattr(host_checks, "inspect", never)

    assert main(["start"]) == 0
    assert main(["start", "--dry-run"]) == 0


def test_failed_start_reports_finding_ids_to_telemetry(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    import localcloud_cli.telemetry as telemetry

    _mac_probe(monkeypatch, tmp_path, tools=("colima",), environ={"DOCKER_CONTEXT": "colima"})
    _docker_down(monkeypatch, attempts=10)
    batches: list[list[dict[str, Any]]] = []
    monkeypatch.delenv("LOCALCLOUD_TELEMETRY")
    monkeypatch.setattr(
        telemetry, "_deliver", lambda events, _environment: batches.append(events) or True
    )

    assert main(["start"]) == 2

    properties = batches[0][0]["properties"]
    assert properties["error_code"] == "docker_unavailable"
    assert properties["setup_findings"] == ["docker_not_running"]

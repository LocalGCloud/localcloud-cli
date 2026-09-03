from __future__ import annotations

import io
import json
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
                "-p 127.0.0.1:5365-5376:5365-5376/tcp "
                "jaysen2apache/localcloud:latest"
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
                "-p 127.0.0.1:5365-5376:5365-5376/tcp "
                "jaysen2apache/localcloud:latest"
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

    def target(self, config: Any) -> dict[str, Any]:
        self.calls.append(("target", config))
        return {
            "data_volume": config.data_volume,
            "url": "http://127.0.0.1:49080",
            "connect_url": "http://127.0.0.1:49080",
            "endpoint_map": {"5365": 49080},
            "project": config.project,
            "user": config.user,
        }

    def doctor(self) -> dict[str, Any]:
        self.calls.append(("doctor", None))
        return {
            "status": "ok",
            "default_image": "jaysen2apache/localcloud:latest (Local: ID: qualified , sha256:qualified)",
        }

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


def test_public_version_output_is_exact(
    capsys: pytest.CaptureFixture[str],
) -> None:
    assert entrypoint_main(["--version"]) == 0
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

    with pytest.raises(SystemExit) as caught:
        _parser().parse_args(["--version"])
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
                "image": "jaysen2apache/localcloud:latest",
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
                "image": "jaysen2apache/localcloud:latest",
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
    calls: list[tuple[Any, float]] = []

    def run(config: Any, *, connect_timeout: float) -> None:
        calls.append((config, connect_timeout))

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
    config, connect_timeout = calls[0]
    assert config.data_volume == "team-data"
    assert config.project == "agent-project-1"
    assert config.user == "alice"
    assert connect_timeout == 2.5


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


def test_main_non_mcp_interrupt_still_propagates(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def interrupt(_self: FakeController, _config: Any) -> dict[str, Any]:
        raise KeyboardInterrupt

    monkeypatch.setattr(FakeController, "status", interrupt)

    with pytest.raises(KeyboardInterrupt):
        main(["status"])


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
    assert parser.parse_args(["restart"]).pull is True
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
    assert FakeController.instance.pull_calls[-1] == ("restart", True)

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

def test_restart_and_start_tls_flags() -> None:
    parser = _parser()
    assert parser.parse_args(["restart"]).tls is None
    assert parser.parse_args(["restart", "--no-tls"]).tls is False
    assert parser.parse_args(["restart", "--tls"]).tls is True
    assert parser.parse_args(["start"]).tls is None
    assert parser.parse_args(["start", "--no-tls"]).tls is False
    assert parser.parse_args(["start", "--tls"]).tls is True


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
            ("127.0.0.1", 5508, 5365, "tcp"),
            ("127.0.0.1", 5509, 5366, "tcp"),
        )
    )

    assert observer.confirm_port_mapping(plan) is True
    reporter.close()

    rendered = output.getvalue()
    assert "127.0.0.1:5508 -> 5365/tcp" in rendered
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
            ("127.0.0.1", 5508, 5365, "tcp"),
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
    assert "5365-5376:5365-5376/tcp" in captured.err


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

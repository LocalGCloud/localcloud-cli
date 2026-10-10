from __future__ import annotations

import json
from pathlib import Path
import subprocess
from typing import Any

import pytest

from localcloud_cli import mcp_install
from localcloud_cli.cli import _execute, _parser
from localcloud_cli.config import LocalCloudConfig
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
        self.target_calls.append({"config": config, "ensure_project": ensure_project})
        if self.state == "stopped":
            raise HostError("runtime_not_running", "runtime not running")
        if config.project not in self.ensured_projects:
            if ensure_project:
                self.ensured_projects.append(config.project)
            else:
                raise HostError("unknown_project", f"Project {config.project} not found")
        if on_url_resolved:
            on_url_resolved("http://127.0.0.1:49080")
        return {"url": "http://127.0.0.1:49080", "endpoint_map": {"5380": 49080}}

    def start(
        self,
        config: LocalCloudConfig,
        *,
        ensure_project: bool = False,
        allow_replace: bool = True,
        **kwargs: Any,
    ) -> dict[str, Any]:
        self.start_calls.append(
            {"config": config, "ensure_project": ensure_project, "allow_replace": allow_replace}
        )
        self.state = "running"
        if ensure_project:
            self.ensured_projects.append(config.project)
        return {"status": "started"}


@pytest.fixture
def fake_java(monkeypatch: pytest.MonkeyPatch) -> None:
    from localcloud_cli import mcp_stdio

    class FakeJavaClient:
        def __init__(self, *args: Any, **kwargs: Any):
            pass

    monkeypatch.setattr(mcp_stdio, "JavaMcpClient", FakeJavaClient)


@pytest.fixture
def home(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    home = tmp_path / "home"
    home.mkdir()
    monkeypatch.setattr(Path, "home", lambda: home)
    monkeypatch.delenv("XDG_CONFIG_HOME", raising=False)
    monkeypatch.delenv("CLINE_MCP_SETTINGS_PATH", raising=False)
    monkeypatch.setattr(mcp_install.shutil, "which", lambda *_args, **_kwargs: None)
    monkeypatch.setattr(mcp_install, "_MACOS_APPLICATIONS", tmp_path / "Applications")
    return home


# Bridge auto-start ---------------------------------------------------------


@pytest.mark.usefixtures("fake_java")
def test_mcp_adapter_autostarts_stopped_runtime() -> None:
    controller = _AutoStartMockController(initial_state="stopped")

    adapter = McpAdapter(
        _test_config(project="agent-test-proj"), controller=controller, auto_start=True
    )

    assert controller.start_calls[0]["ensure_project"] is True
    assert controller.start_calls[0]["allow_replace"] is False
    assert len(controller.target_calls) == 1
    assert "agent-test-proj" in controller.ensured_projects
    assert adapter.mcp_url == "http://127.0.0.1:49080/mcp"


@pytest.mark.usefixtures("fake_java")
def test_mcp_adapter_no_start_raises_without_starting() -> None:
    controller = _AutoStartMockController(initial_state="stopped")

    with pytest.raises(HostError) as caught:
        McpAdapter(_test_config(), controller=controller, auto_start=False)

    assert caught.value.code == "runtime_not_running"
    assert controller.start_calls == []


@pytest.mark.usefixtures("fake_java")
def test_mcp_adapter_auto_provisions_missing_project_on_running_runtime() -> None:
    controller = _AutoStartMockController(initial_state="running")

    adapter = McpAdapter(
        _test_config(project="newly-introduced-project"),
        controller=controller,
        auto_start=True,
    )

    assert len(controller.start_calls) == 1
    assert "newly-introduced-project" in controller.ensured_projects
    assert adapter.mcp_url == "http://127.0.0.1:49080/mcp"


@pytest.mark.usefixtures("fake_java")
def test_mcp_adapter_without_auto_start_creates_a_repository_project() -> None:
    controller = _AutoStartMockController(initial_state="running")
    config = _test_config(project="orders-service")
    from dataclasses import replace

    McpAdapter(
        replace(config, project_source="git"), controller=controller, auto_start=False
    )

    assert controller.start_calls == []
    assert controller.target_calls[0]["ensure_project"] is True


# Server entry --------------------------------------------------------------


def test_server_entry_pins_only_explicit_values() -> None:
    assert build_mcp_server_config("localcloud") == {
        "command": "localcloud",
        "args": ["mcp"],
    }
    assert build_mcp_server_config(
        "/custom/localcloud",
        data_volume="isolated-volume",
        project="isolated-project",
        user="custom-agent",
    )["args"] == [
        "mcp",
        "--data-volume",
        "isolated-volume",
        "--project-id",
        "isolated-project",
        "--user",
        "custom-agent",
    ]


def test_server_entry_rejects_an_invalid_project() -> None:
    with pytest.raises(HostError) as caught:
        build_mcp_server_config("localcloud", project="No")
    assert caught.value.code == "invalid_config"


# Client configuration paths ------------------------------------------------


@pytest.mark.parametrize(
    ("client", "relative"),
    [
        ("cursor", ".cursor/mcp.json"),
        ("claude-code", ".claude.json"),
        ("gemini", ".gemini/settings.json"),
        ("antigravity", ".gemini/config/mcp_config.json"),
        ("windsurf", ".config/devin/mcp_config.json"),
        ("cline", ".cline/data/settings/cline_mcp_settings.json"),
    ],
)
def test_user_level_paths(home: Path, client: str, relative: str) -> None:
    assert get_client_config_path(client) == home / relative


@pytest.mark.parametrize(
    ("client", "relative"),
    [
        ("cursor", ".cursor/mcp.json"),
        ("claude-code", ".mcp.json"),
        ("gemini", ".gemini/settings.json"),
        ("antigravity", ".agents/mcp_config.json"),
    ],
)
def test_project_level_paths(tmp_path: Path, client: str, relative: str) -> None:
    assert (
        get_client_config_path(client, scope="project", directory=tmp_path)
        == tmp_path / relative
    )


@pytest.mark.parametrize("client", ["claude-desktop", "claude", "windsurf", "cline"])
def test_clients_without_project_configuration_reject_project_scope(
    tmp_path: Path, client: str
) -> None:
    with pytest.raises(HostError) as caught:
        get_client_config_path(client, scope="project", directory=tmp_path)
    assert caught.value.code == "unsupported_scope"


def test_claude_desktop_path_on_macos(home: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(mcp_install.sys, "platform", "darwin")
    assert get_client_config_path("claude") == (
        home / "Library" / "Application Support" / "Claude" / "claude_desktop_config.json"
    )


def test_windsurf_keeps_an_existing_codeium_config(home: Path) -> None:
    legacy = home / ".codeium" / "windsurf" / "mcp_config.json"
    legacy.parent.mkdir(parents=True)
    legacy.write_text("{}", encoding="utf-8")

    assert get_client_config_path("windsurf") == legacy


def test_windsurf_follows_xdg_config_home(
    home: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(mcp_install.sys, "platform", "linux")
    monkeypatch.setenv("XDG_CONFIG_HOME", str(home / "xdg"))
    assert get_client_config_path("windsurf") == home / "xdg" / "devin" / "mcp_config.json"


def test_cline_uses_its_override_or_an_unmigrated_extension_file(
    home: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(mcp_install.sys, "platform", "darwin")
    legacy = (
        home
        / "Library/Application Support/Code/User/globalStorage"
        / "saoudrizwan.claude-dev/settings/cline_mcp_settings.json"
    )
    legacy.parent.mkdir(parents=True)
    legacy.write_text("{}", encoding="utf-8")
    assert get_client_config_path("cline") == legacy

    monkeypatch.setenv("CLINE_MCP_SETTINGS_PATH", str(home / "custom.json"))
    assert get_client_config_path("cline") == home / "custom.json"


def test_unknown_client_is_rejected() -> None:
    with pytest.raises(HostError) as caught:
        get_client_config_path("notepad")
    assert caught.value.code == "unsupported_client"


# Config file merge ---------------------------------------------------------


def test_merge_reports_installed_updated_and_unchanged(tmp_path: Path) -> None:
    path = tmp_path / "settings.json"
    path.write_text(
        json.dumps(
            {"theme": "dark", "mcpServers": {"other": {"command": "node", "args": []}}}
        ),
        encoding="utf-8",
    )
    entry = {"command": "localcloud", "args": ["mcp"]}

    assert update_mcp_config_file(path, "localcloud", entry) == "installed"
    assert update_mcp_config_file(path, "localcloud", entry) == "unchanged"
    changed = {"command": "/opt/homebrew/bin/localcloud", "args": ["mcp"]}
    assert update_mcp_config_file(path, "localcloud", changed) == "updated"

    saved = json.loads(path.read_text(encoding="utf-8"))
    assert saved["theme"] == "dark"
    assert saved["mcpServers"]["other"] == {"command": "node", "args": []}
    assert saved["mcpServers"]["localcloud"] == changed
    backup = json.loads((tmp_path / "settings.json.bak").read_text(encoding="utf-8"))
    assert backup["mcpServers"]["localcloud"] == entry
    assert not list(tmp_path.glob(".settings.json.tmp.*"))


@pytest.mark.parametrize(
    "content",
    ['["not", "an", "object"]', '{"mcpServers": []}', "{broken"],
)
def test_merge_refuses_files_it_cannot_merge_and_leaves_them_alone(
    tmp_path: Path, content: str
) -> None:
    path = tmp_path / "mcp.json"
    path.write_text(content, encoding="utf-8")

    with pytest.raises(HostError) as caught:
        update_mcp_config_file(path, "localcloud", {"command": "localcloud", "args": []})

    assert caught.value.code == "invalid_client_config"
    assert path.read_text(encoding="utf-8") == content


# Claude Code ---------------------------------------------------------------


class _ClaudeCli:
    def __init__(self, *, fail: str | None = None):
        self.calls: list[tuple[list[str], Path]] = []
        self.fail = fail

    def __call__(self, argv: list[str], *, cwd: Path, **_kwargs: Any) -> Any:
        self.calls.append((argv[1:], cwd))
        failed = self.fail is not None and argv[2] == self.fail
        return subprocess.CompletedProcess(
            argv, 1 if failed else 0, stdout="", stderr="boom" if failed else ""
        )


def _install_claude(
    home: Path, monkeypatch: pytest.MonkeyPatch, cli: _ClaudeCli, **kwargs: Any
) -> dict[str, Any]:
    monkeypatch.setattr(
        mcp_install.shutil, "which", lambda name, **_kw: "/bin/claude" if name == "claude" else None
    )
    return mcp_install._install_claude_code(
        "localcloud",
        {"command": "localcloud", "args": ["mcp"]},
        run=cli,
        **{"scope": "user", "directory": home, **kwargs},
    )


def test_claude_code_is_installed_with_its_cli(home: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    cli = _ClaudeCli()

    result = _install_claude(home, monkeypatch, cli)

    assert result["status"] == "installed"
    assert result["method"] == "claude-cli"
    assert cli.calls == [
        (
            ["mcp", "add-json", "--scope", "user", "localcloud",
             '{"command": "localcloud", "args": ["mcp"]}'],
            home,
        )
    ]


def test_claude_code_replaces_a_different_entry(home: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    (home / ".claude.json").write_text(
        json.dumps({"mcpServers": {"localcloud": {"command": "old", "args": []}}}),
        encoding="utf-8",
    )
    cli = _ClaudeCli()

    result = _install_claude(home, monkeypatch, cli)

    assert result["status"] == "updated"
    assert [call[0][:2] for call in cli.calls] == [["mcp", "remove"], ["mcp", "add-json"]]


def test_claude_code_leaves_a_matching_entry_alone(home: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    (home / ".claude.json").write_text(
        json.dumps({"mcpServers": {"localcloud": {"command": "localcloud", "args": ["mcp"]}}}),
        encoding="utf-8",
    )
    cli = _ClaudeCli()

    assert _install_claude(home, monkeypatch, cli)["status"] == "unchanged"
    assert cli.calls == []


def test_claude_code_cli_failure_is_reported_not_worked_around(
    home: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    with pytest.raises(HostError) as caught:
        _install_claude(home, monkeypatch, _ClaudeCli(fail="add-json"))

    assert caught.value.code == "client_cli_failed"
    assert "boom" in caught.value.message
    assert not (home / ".claude.json").exists()


def test_claude_code_project_scope_runs_in_the_repository(
    home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    cli = _ClaudeCli()
    repository = tmp_path / "repo"
    repository.mkdir()

    _install_claude(home, monkeypatch, cli, scope="project", directory=repository)

    assert cli.calls[0][0][2:4] == ["--scope", "project"]
    assert cli.calls[0][1] == repository


def test_claude_code_without_its_cli_writes_the_config_file(home: Path) -> None:
    result = install_mcp_server(client="claude-code", command_override="localcloud")

    assert result["config_path"] == str(home / ".claude.json")
    saved = json.loads((home / ".claude.json").read_text(encoding="utf-8"))
    assert saved["mcpServers"]["localcloud"] == {"command": "localcloud", "args": ["mcp"]}


# install_mcp_server --------------------------------------------------------


def test_project_install_writes_a_portable_command(home: Path, tmp_path: Path) -> None:
    result = install_mcp_server(client="cursor", is_global=False, directory=tmp_path)

    saved = json.loads((tmp_path / ".cursor" / "mcp.json").read_text(encoding="utf-8"))
    assert result["scope"] == "project"
    assert saved["mcpServers"]["localcloud"] == {"command": "localcloud", "args": ["mcp"]}


def test_all_configures_only_detected_clients(home: Path) -> None:
    (home / ".cursor").mkdir()
    (home / ".gemini").mkdir()
    (home / ".gemini" / "settings.json").write_text("{}", encoding="utf-8")

    result = install_mcp_server(client="all", command_override="localcloud")

    assert [(r["client"], r["status"]) for r in result["results"]] == [
        ("cursor", "installed"),
        ("gemini", "installed"),
    ]
    assert not (home / ".claude.json").exists()


def test_all_with_project_scope_skips_user_only_clients(home: Path, tmp_path: Path) -> None:
    (home / ".cursor").mkdir()
    (home / ".codeium" / "windsurf").mkdir(parents=True)

    result = install_mcp_server(client="all", is_global=False, directory=tmp_path)

    assert [(r["client"], r["status"]) for r in result["results"]] == [
        ("cursor", "installed"),
        ("windsurf", "skipped"),
    ]


def test_all_without_detected_clients_writes_nothing(home: Path) -> None:
    with pytest.raises(HostError) as caught:
        install_mcp_server(client="all", command_override="localcloud")

    assert caught.value.code == "no_mcp_clients_detected"
    assert list(home.iterdir()) == []


# CLI -----------------------------------------------------------------------


def test_cli_mcp_install_dispatch(home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.chdir(tmp_path)
    result = _execute(
        _parser().parse_args(
            ["mcp", "install", "--client", "cursor", "--project", "--project-id", "cli-test-project"]
        )
    )

    assert (result["status"], result["client"], result["scope"]) == ("installed", "cursor", "project")
    saved = json.loads((tmp_path / ".cursor" / "mcp.json").read_text(encoding="utf-8"))
    assert saved["mcpServers"]["localcloud"]["args"] == ["mcp", "--project-id", "cli-test-project"]


@pytest.mark.usefixtures("real_git")
def test_cli_user_install_in_a_repository_does_not_pin_its_project(
    home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    repository = tmp_path / "orders-service"
    repository.mkdir()
    subprocess.run(["git", "init", "-q", str(repository)], check=True)
    (repository / "localcloud.yaml").write_text(
        "context:\n  project: pinned-project\n", encoding="utf-8"
    )
    monkeypatch.chdir(repository)

    _execute(_parser().parse_args(["mcp", "install", "--client", "cursor", "--bare"]))

    saved = json.loads((home / ".cursor" / "mcp.json").read_text(encoding="utf-8"))
    assert saved["mcpServers"]["localcloud"] == {"command": "localcloud", "args": ["mcp"]}


def test_cli_mcp_install_dispatch_with_command_path(
    home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.chdir(tmp_path)
    result = _execute(
        _parser().parse_args(
            ["mcp", "install", "--client", "cursor", "--project", "--command-path", "/opt/homebrew/bin/lc"]
        )
    )

    assert result["status"] == "installed"
    saved = json.loads((tmp_path / ".cursor" / "mcp.json").read_text(encoding="utf-8"))
    assert saved["mcpServers"]["localcloud"]["command"] == "/opt/homebrew/bin/lc"


def test_cli_mcp_install_dispatch_with_bare(
    home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.chdir(tmp_path)
    result = _execute(
        _parser().parse_args(["mcp", "install", "--client", "cursor", "--global", "--bare"])
    )

    assert result["status"] == "installed"
    saved = json.loads((home / ".cursor" / "mcp.json").read_text(encoding="utf-8"))
    assert saved["mcpServers"]["localcloud"]["command"] == "localcloud"


def test_cli_mcp_install_output_names_each_result(
    home: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    from localcloud_cli.cli import main

    (home / ".cursor").mkdir()
    (home / ".codeium" / "windsurf").mkdir(parents=True)

    assert main(["mcp", "install", "--client", "all", "--bare"]) == 0
    assert main(["mcp", "install", "--client", "all", "--bare"]) == 0

    lines = capsys.readouterr().out.splitlines()
    assert lines[0] == (
        f"Installed LocalCloud MCP for cursor (user) in {home / '.cursor' / 'mcp.json'}."
    )
    assert lines[-1].startswith("Already up to date: LocalCloud MCP for windsurf (user)")


def test_resolve_localcloud_command_prefers_system_over_venv(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    fake_brew = tmp_path / "bin" / "localcloud"
    fake_brew.parent.mkdir(parents=True)
    fake_brew.touch(mode=0o755)

    assert resolve_localcloud_command(prefer_bare=True) == "localcloud"

    monkeypatch.setattr(
        mcp_install,
        "shutil",
        type("FakeShutil", (), {"which": staticmethod(lambda cmd, path=None: str(fake_brew))})(),
    )
    assert Path(resolve_localcloud_command(prefer_bare=False)).is_absolute()

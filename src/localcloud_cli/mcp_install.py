from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from typing import Any, Callable, Literal

from .config import validate_data_volume, validate_project, validate_user
from .errors import HostError


Scope = Literal["user", "project"]

# Canonical client names, in the order `--client all` reports them.
CLIENTS: tuple[str, ...] = (
    "cursor",
    "claude-code",
    "claude-desktop",
    "gemini",
    "antigravity",
    "windsurf",
    "cline",
)
CLIENT_ALIASES = {"claude": "claude-desktop"}
SUPPORTED_CLIENTS: tuple[str, ...] = (*CLIENTS, *CLIENT_ALIASES)

# Clients that read a configuration file inside the repository.
_PROJECT_SCOPED = {"cursor", "claude-code", "gemini", "antigravity"}
_MACOS_APPLICATIONS = Path("/Applications")


def resolve_localcloud_command(*, prefer_bare: bool = False) -> str:
    """Resolve the best executable path or binary name for localcloud CLI.

    Prefers globally installed system binaries (Homebrew, /usr/local/bin, ~/.local/bin)
    or standard system PATH lookups outside any temporary virtualenv, ensuring
    user-level agent configurations don't break when a repo virtualenv is deleted.
    """
    if prefer_bare:
        return "localcloud"

    # 1. Check well-known system package-manager paths (Homebrew on Apple Silicon or Intel, ~/.local/bin)
    candidate_system_paths = [
        Path("/opt/homebrew/bin/localcloud"),
        Path("/opt/homebrew/bin/lc"),
        Path("/usr/local/bin/localcloud"),
        Path("/usr/local/bin/lc"),
        Path.home() / ".local" / "bin" / "localcloud",
        Path.home() / ".local" / "bin" / "lc",
    ]
    for candidate in candidate_system_paths:
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)

    # 2. Check system PATH excluding active virtualenv
    venv_dir = Path(sys.prefix).resolve()
    base_dir = Path(sys.base_prefix).resolve()
    is_in_venv = venv_dir != base_dir

    if is_in_venv:
        raw_paths = [p for p in os.environ.get("PATH", "").split(os.pathsep) if p.strip()]
        non_venv_paths: list[str] = []
        for p in raw_paths:
            try:
                resolved = Path(p).resolve()
                if not (resolved == venv_dir / "bin" or resolved == venv_dir / "Scripts" or resolved == venv_dir):
                    non_venv_paths.append(p)
            except Exception:
                non_venv_paths.append(p)

        search_path = os.pathsep.join(non_venv_paths)
        which_bin = shutil.which("localcloud", path=search_path) or shutil.which("lc", path=search_path)
        if which_bin:
            return which_bin

    # 3. If running inside a virtualenv and no system binary exists, fall back to venv binary
    if is_in_venv:
        venv_bin = Path(sys.prefix) / "bin" / "localcloud"
        if venv_bin.is_file() and os.access(venv_bin, os.X_OK):
            return str(venv_bin)
        venv_exe = Path(sys.prefix) / "Scripts" / "localcloud.exe"
        if venv_exe.is_file():
            return str(venv_exe)

    # 4. Standard PATH lookup
    which_bin = shutil.which("localcloud") or shutil.which("lc")
    if which_bin:
        return which_bin

    return "localcloud"


def build_mcp_server_config(
    command: str,
    *,
    data_volume: str | None = None,
    project: str | None = None,
    user: str | None = None,
) -> dict[str, Any]:
    """The client's server entry. Only explicitly chosen values are pinned; the
    bridge otherwise selects the project from the workspace it serves."""
    args = ["mcp"]
    if data_volume is not None:
        args.extend(["--data-volume", validate_data_volume(data_volume)])
    if project is not None:
        args.extend(["--project-id", validate_project(project)])
    if user is not None:
        args.extend(["--user", validate_user(user)])
    return {"command": command, "args": args}


def normalize_client(client: str) -> str:
    name = client.lower().replace("_", "-")
    name = CLIENT_ALIASES.get(name, name)
    if name not in CLIENTS:
        raise HostError(
            "unsupported_client",
            f"Unsupported MCP client: {client!r}. Supported clients: "
            f"{', '.join(SUPPORTED_CLIENTS)}, all",
            {"client": client},
        )
    return name


def get_client_config_path(
    client: str,
    *,
    scope: Scope = "user",
    directory: Path | None = None,
) -> Path:
    """The configuration file `client` reads for `scope`."""
    name = normalize_client(client)
    if scope == "project":
        if name not in _PROJECT_SCOPED:
            raise HostError(
                "unsupported_scope",
                f"{name} has no project-level MCP configuration; install it without --project",
                {"client": name},
            )
        base = directory if directory is not None else Path.cwd()
        return base / {
            "cursor": Path(".cursor") / "mcp.json",
            "claude-code": Path(".mcp.json"),
            "gemini": Path(".gemini") / "settings.json",
            "antigravity": Path(".agents") / "mcp_config.json",
        }[name]

    home = Path.home()
    if name == "claude-desktop":
        if sys.platform == "darwin":
            return home / "Library" / "Application Support" / "Claude" / "claude_desktop_config.json"
        if sys.platform == "win32":
            return _appdata() / "Claude" / "claude_desktop_config.json"
        return home / ".config" / "Claude" / "claude_desktop_config.json"
    if name == "claude-code":
        return home / ".claude.json"
    if name == "cursor":
        return home / ".cursor" / "mcp.json"
    if name == "gemini":
        return home / ".gemini" / "settings.json"
    if name == "antigravity":
        return home / ".gemini" / "config" / "mcp_config.json"
    if name == "windsurf":
        return _first_existing(_windsurf_paths())
    return _first_existing(_cline_paths())


def _appdata() -> Path:
    return Path(os.environ.get("APPDATA") or Path.home() / "AppData" / "Roaming")


def _first_existing(candidates: list[Path]) -> Path:
    """The first candidate that exists, else the first (current) location."""
    return next((path for path in candidates if path.is_file()), candidates[0])


def _windsurf_paths() -> list[Path]:
    # Windsurf became Devin Desktop; its MCP docs name ~/.config/devin, while
    # existing installs keep ~/.codeium/windsurf (or ~/.codeium) in use.
    home = Path.home()
    if sys.platform == "win32":
        current = _appdata() / "devin" / "mcp_config.json"
    else:
        config_home = os.environ.get("XDG_CONFIG_HOME")
        base = Path(config_home) if config_home else home / ".config"
        current = base / "devin" / "mcp_config.json"
    return [
        current,
        home / ".codeium" / "windsurf" / "mcp_config.json",
        home / ".codeium" / "mcp_config.json",
    ]


def _cline_paths() -> list[Path]:
    # Cline's VS Code, JetBrains and CLI builds share ~/.cline/data/settings;
    # older extension builds keep the file in VS Code's globalStorage until they
    # migrate it.
    override = os.environ.get("CLINE_MCP_SETTINGS_PATH")
    if override:
        return [Path(override).expanduser()]
    home = Path.home()
    if sys.platform == "darwin":
        code = home / "Library" / "Application Support" / "Code"
    elif sys.platform == "win32":
        code = _appdata() / "Code"
    else:
        code = home / ".config" / "Code"
    return [
        home / ".cline" / "data" / "settings" / "cline_mcp_settings.json",
        code / "User" / "globalStorage" / "saoudrizwan.claude-dev" / "settings" / "cline_mcp_settings.json",
    ]


def is_client_installed(client: str) -> bool:
    """Whether `client` appears to be installed for this user."""
    name = normalize_client(client)
    home = Path.home()
    which = shutil.which

    def app(*names: str) -> bool:
        return sys.platform == "darwin" and any(
            (_MACOS_APPLICATIONS / f"{app_name}.app").exists() for app_name in names
        )

    if name == "claude-desktop":
        return get_client_config_path(name).parent.is_dir() or app("Claude")
    if name == "claude-code":
        return bool(which("claude")) or (home / ".claude.json").is_file()
    if name == "cursor":
        return bool(which("cursor")) or (home / ".cursor").is_dir() or app("Cursor")
    if name == "gemini":
        return bool(which("gemini")) or (home / ".gemini" / "settings.json").is_file()
    if name == "antigravity":
        return (
            bool(which("antigravity"))
            or (home / ".gemini" / "config").is_dir()
            or app("Antigravity")
        )
    if name == "windsurf":
        return (
            bool(which("windsurf"))
            or any(path.parent.is_dir() for path in _windsurf_paths()[:2])
            or app("Windsurf", "Devin")
        )
    return any(path.parent.parent.is_dir() for path in _cline_paths())


def _read_json_object(path: Path) -> dict[str, Any]:
    if not path.is_file():
        return {}
    try:
        content = path.read_text(encoding="utf-8").strip()
        parsed = json.loads(content) if content else {}
    except (OSError, ValueError) as error:
        raise HostError(
            "invalid_client_config",
            f"Existing configuration at {path} could not be parsed as JSON",
            {"path": str(path), "cause": str(error)},
        ) from error
    if not isinstance(parsed, dict):
        raise HostError(
            "invalid_client_config",
            f"Existing configuration at {path} is not a JSON object",
            {"path": str(path)},
        )
    servers = parsed.get("mcpServers")
    if servers is not None and not isinstance(servers, dict):
        raise HostError(
            "invalid_client_config",
            f"'mcpServers' in {path} is not a JSON object",
            {"path": str(path)},
        )
    return parsed


def update_mcp_config_file(
    config_path: Path,
    server_name: str,
    server_config: dict[str, Any],
) -> str:
    """Merge the server entry into the client's JSON config file, keeping every
    other setting and server. Writes atomically and keeps a `.bak` copy.

    Returns "installed", "updated", or "unchanged".
    """
    existing_data = _read_json_object(config_path)
    mcp_servers = existing_data.setdefault("mcpServers", {})
    current = mcp_servers.get(server_name)
    if current == server_config:
        return "unchanged"
    action = "installed" if current is None else "updated"
    mcp_servers[server_name] = server_config

    config_path.parent.mkdir(parents=True, exist_ok=True)
    if config_path.is_file():
        shutil.copy2(config_path, config_path.with_suffix(config_path.suffix + ".bak"))
    tmp_path = config_path.parent / f".{config_path.name}.tmp.{os.getpid()}"
    try:
        with open(tmp_path, "w", encoding="utf-8") as handle:
            json.dump(existing_data, handle, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp_path, config_path)
    finally:
        tmp_path.unlink(missing_ok=True)
    return action


def _install_claude_code(
    server_name: str,
    server_config: dict[str, Any],
    *,
    scope: Scope,
    directory: Path,
    run: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
) -> dict[str, Any]:
    """Claude Code owns ~/.claude.json, so its `claude mcp` commands make the
    change when the CLI is installed; the file is written directly otherwise."""
    config_path = get_client_config_path("claude-code", scope=scope, directory=directory)
    claude = shutil.which("claude")
    if claude is None:
        action = update_mcp_config_file(config_path, server_name, server_config)
        return {"status": action, "config_path": str(config_path)}

    current = _read_json_object(config_path).get("mcpServers", {}).get(server_name)
    if current == server_config:
        return {"status": "unchanged", "config_path": str(config_path)}

    def claude_mcp(*arguments: str) -> None:
        completed = run(
            [claude, "mcp", *arguments],
            cwd=directory,
            capture_output=True,
            text=True,
            check=False,
        )
        if completed.returncode != 0:
            output = (completed.stderr or completed.stdout or "").strip()
            raise HostError(
                "client_cli_failed",
                f"'claude mcp {arguments[0]}' failed: {output or 'no output'}",
                {"command": ["claude", "mcp", *arguments]},
            )

    if current is not None:
        claude_mcp("remove", "--scope", scope, server_name)
    claude_mcp("add-json", "--scope", scope, server_name, json.dumps(server_config))
    return {
        "status": "installed" if current is None else "updated",
        "config_path": str(config_path),
        "method": "claude-cli",
    }


def _install_one(
    client: str,
    server_name: str,
    server_config: dict[str, Any],
    *,
    scope: Scope,
    directory: Path,
) -> dict[str, Any]:
    if client == "claude-code":
        result = _install_claude_code(
            server_name, server_config, scope=scope, directory=directory
        )
    else:
        config_path = get_client_config_path(client, scope=scope, directory=directory)
        action = update_mcp_config_file(config_path, server_name, server_config)
        result = {"status": action, "config_path": str(config_path)}
    return {"client": client, "scope": scope, **result, "server": server_config}


def install_mcp_server(
    *,
    client: str = "cursor",
    is_global: bool = True,
    directory: Path | None = None,
    data_volume: str | None = None,
    project: str | None = None,
    user: str | None = None,
    server_name: str = "localcloud",
    command_override: str | None = None,
    prefer_bare: bool = False,
) -> dict[str, Any]:
    """Install the LocalCloud MCP server entry for one client, or with
    client="all" for every supported client installed on this machine."""
    scope: Scope = "user" if is_global else "project"
    workspace = directory if directory is not None else Path.cwd()
    # A repository's config is shared, so it names the command, not this
    # machine's path to it.
    command = command_override or resolve_localcloud_command(
        prefer_bare=prefer_bare or scope == "project"
    )
    server_config = build_mcp_server_config(
        command, data_volume=data_volume, project=project, user=user
    )

    if client.lower() != "all":
        return _install_one(
            normalize_client(client),
            server_name,
            server_config,
            scope=scope,
            directory=workspace,
        )

    detected = [name for name in CLIENTS if is_client_installed(name)]
    if not detected:
        raise HostError(
            "no_mcp_clients_detected",
            "No supported MCP client was found on this machine; pass --client NAME "
            f"({', '.join(CLIENTS)})",
        )
    results: list[dict[str, Any]] = []
    for name in detected:
        if scope == "project" and name not in _PROJECT_SCOPED:
            results.append(
                {
                    "client": name,
                    "scope": scope,
                    "status": "skipped",
                    "reason": "no project-level MCP configuration",
                }
            )
            continue
        try:
            results.append(
                _install_one(
                    name, server_name, server_config, scope=scope, directory=workspace
                )
            )
        except HostError as error:
            results.append(
                {"client": name, "scope": scope, "status": "failed", "error": error.message}
            )
    if not any(r["status"] in {"installed", "updated", "unchanged"} for r in results):
        raise HostError(
            "mcp_install_failed",
            "LocalCloud MCP could not be installed for any detected client",
            {"results": results},
        )
    return {"status": "installed", "results": results}

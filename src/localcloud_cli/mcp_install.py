from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from typing import Any

from .config import LocalCloudConfig
from .constants import DEFAULT_DATA_VOLUME, DEFAULT_PROJECT, DEFAULT_USER
from .errors import HostError


SUPPORTED_CLIENTS: tuple[str, ...] = (
    "claude",
    "claude-desktop",
    "claude-code",
    "cursor",
    "gemini",
    "antigravity",
    "windsurf",
    "cline",
)


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
    config: LocalCloudConfig,
    *,
    command_override: str | None = None,
    prefer_bare_command: bool = False,
    explicit_project: bool = False,
) -> dict[str, Any]:
    """Generate the MCP server configuration dictionary."""
    command = command_override or resolve_localcloud_command(prefer_bare=prefer_bare_command)
    args = ["mcp"]
    if config.data_volume != DEFAULT_DATA_VOLUME:
        args.extend(["--data-volume", config.data_volume])
    if explicit_project or config.project != DEFAULT_PROJECT:
        args.extend(["--project-id", config.project])
    if config.user != DEFAULT_USER:
        args.extend(["--user", config.user])

    return {
        "command": command,
        "args": args,
    }


def get_client_config_path(
    client: str,
    *,
    directory: Path | None = None,
    is_global: bool = True,
) -> Path:
    """Determine the configuration file path for a target client."""
    home = Path.home()
    norm_client = client.lower().replace("_", "-")

    if norm_client in ("claude", "claude-desktop"):
        if sys.platform == "darwin":
            return home / "Library" / "Application Support" / "Claude" / "claude_desktop_config.json"
        elif sys.platform == "win32":
            appdata = Path(os.environ.get("APPDATA", home / "AppData" / "Roaming"))
            return appdata / "Claude" / "claude_desktop_config.json"
        else:
            return home / ".config" / "Claude" / "claude_desktop_config.json"

    if norm_client == "claude-code":
        if is_global or directory is None:
            return home / ".claude.json"
        return directory / ".mcp.json"

    if norm_client == "cursor":
        if is_global or directory is None:
            return home / ".cursor" / "mcp.json"
        return directory / ".cursor" / "mcp.json"

    if norm_client in ("gemini", "antigravity"):
        if is_global or directory is None:
            return home / ".gemini" / "antigravity" / "mcp_config.json"
        return directory / ".agents" / "mcp_config.json"

    if norm_client == "windsurf":
        return home / ".codeium" / "windsurf" / "mcp_config.json"

    if norm_client in ("cline", "roo"):
        return home / ".cline" / "mcp_settings.json"

    raise HostError(
        "unsupported_client",
        f"Unsupported MCP client: {client!r}. Supported clients: {', '.join(sorted(set(SUPPORTED_CLIENTS)))}",
        {"client": client},
    )


def update_mcp_config_file(
    config_path: Path,
    server_name: str,
    server_config: dict[str, Any],
) -> str:
    """Read, modify, and write back the client's MCP configuration JSON file atomically.

    Returns:
        'installed' if a new server entry was created, or 'updated' if existing.
    """
    config_path.parent.mkdir(parents=True, exist_ok=True)
    existing_data: dict[str, Any] = {}
    action = "installed"
    if config_path.is_file():
        try:
            content = config_path.read_text(encoding="utf-8").strip()
            if content:
                parsed = json.loads(content)
                if isinstance(parsed, dict):
                    existing_data = parsed
        except Exception as error:
            raise HostError(
                "invalid_client_config",
                f"Existing configuration at {config_path} could not be parsed as JSON",
                {"path": str(config_path), "cause": str(error)},
            ) from error

    mcp_servers = existing_data.setdefault("mcpServers", {})
    if not isinstance(mcp_servers, dict):
        mcp_servers = {}
        existing_data["mcpServers"] = mcp_servers

    if server_name in mcp_servers:
        action = "updated"
    mcp_servers[server_name] = server_config

    tmp_path = config_path.with_suffix(f".tmp.{os.getpid()}")
    try:
        tmp_path.write_text(json.dumps(existing_data, indent=2) + "\n", encoding="utf-8")
        os.replace(tmp_path, config_path)
    finally:
        if tmp_path.exists():
            try:
                tmp_path.unlink()
            except Exception:
                pass

    return action


def _install_claude_code(
    server_name: str,
    server_config: dict[str, Any],
    *,
    is_global: bool = True,
    directory: Path | None = None,
) -> dict[str, Any]:
    """Install into Claude Code via CLI command if available, or config file fallback."""
    claude_bin = shutil.which("claude")
    if claude_bin:
        scope = "user" if is_global else "project"
        cmd = [
            claude_bin,
            "mcp",
            "add",
            "--scope",
            scope,
            server_name,
            "--",
            server_config["command"],
            *server_config.get("args", []),
        ]
        try:
            subprocess.run(cmd, check=True, capture_output=True, text=True)
            return {
                "status": "installed",
                "client": "claude-code",
                "method": "cli",
                "scope": scope,
                "server": server_config,
            }
        except Exception:
            # Fall back to file write
            pass

    target_path = get_client_config_path("claude-code", directory=directory, is_global=is_global)
    action = update_mcp_config_file(target_path, server_name, server_config)
    return {
        "status": action,
        "client": "claude-code",
        "method": "file",
        "config_path": str(target_path),
        "server": server_config,
    }


def install_mcp_server(
    config: LocalCloudConfig,
    *,
    client: str = "cursor",
    directory: Path | None = None,
    is_global: bool = True,
    server_name: str = "localcloud",
    command_override: str | None = None,
    prefer_bare: bool = False,
    explicit_project: bool = False,
) -> dict[str, Any]:
    """Install LocalCloud MCP server configuration for a given client."""
    prefer_bare_cmd = prefer_bare or (not is_global)
    server_config = build_mcp_server_config(
        config,
        command_override=command_override,
        prefer_bare_command=prefer_bare_cmd,
        explicit_project=explicit_project,
    )

    norm_client = client.lower().replace("_", "-")

    if norm_client == "all":
        results: list[dict[str, Any]] = []
        target_clients = ["cursor", "claude-code", "claude-desktop", "gemini", "windsurf"]
        for target in target_clients:
            try:
                if target == "claude-code":
                    res = _install_claude_code(
                        server_name,
                        server_config,
                        is_global=is_global,
                        directory=directory,
                    )
                    results.append(res)
                else:
                    path = get_client_config_path(target, directory=directory, is_global=is_global)
                    action = update_mcp_config_file(path, server_name, server_config)
                    results.append({
                        "status": action,
                        "client": target,
                        "config_path": str(path),
                        "server": server_config,
                    })
            except Exception as err:
                results.append({
                    "status": "failed",
                    "client": target,
                    "error": str(err),
                })
        return {
            "status": "installed",
            "results": results,
        }

    if norm_client == "claude-code":
        return _install_claude_code(
            server_name,
            server_config,
            is_global=is_global,
            directory=directory,
        )

    target_path = get_client_config_path(norm_client, directory=directory, is_global=is_global)
    action = update_mcp_config_file(target_path, server_name, server_config)

    return {
        "status": action,
        "client": norm_client,
        "config_path": str(target_path),
        "server": server_config,
    }

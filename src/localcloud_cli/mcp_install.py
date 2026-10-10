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

    if norm_client == "gemini":
        if is_global or directory is None:
            return home / ".gemini" / "settings.json"
        return directory / ".agents" / "mcp_config.json"

    if norm_client == "antigravity":
        if is_global or directory is None:
            return home / ".gemini" / "antigravity" / "mcp_config.json"
        return directory / ".agents" / "mcp_config.json"

    if norm_client == "windsurf":
        if is_global or directory is None:
            return home / ".codeium" / "windsurf" / "mcp_config.json"
        return directory / ".codeium" / "windsurf" / "mcp_config.json"

    if norm_client == "cline":
        if is_global or directory is None:
            return home / ".cline" / "mcp_settings.json"
        return directory / ".cline" / "mcp_settings.json"

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
    """Read, modify, and write back the client's MCP configuration JSON file atomically with backup.

    Returns:
        'installed' if a new server entry was created,
        'updated' if an existing entry was modified,
        'unchanged' if existing entry already matches server_config.
    """
    config_path.parent.mkdir(parents=True, exist_ok=True)
    existing_data: dict[str, Any] = {}
    action = "installed"
    if config_path.is_file():
        try:
            content = config_path.read_text(encoding="utf-8").strip()
            if content:
                parsed = json.loads(content)
                if not isinstance(parsed, dict):
                    raise HostError(
                        "invalid_client_config",
                        f"Existing configuration at {config_path} is not a JSON object",
                        {"path": str(config_path)},
                    )
                existing_data = parsed
        except HostError:
            raise
        except Exception as error:
            raise HostError(
                "invalid_client_config",
                f"Existing configuration at {config_path} could not be parsed as JSON",
                {"path": str(config_path), "cause": str(error)},
            ) from error

    mcp_servers = existing_data.get("mcpServers")
    if mcp_servers is None:
        mcp_servers = {}
        existing_data["mcpServers"] = mcp_servers
    elif not isinstance(mcp_servers, dict):
        raise HostError(
            "invalid_client_config",
            f"'mcpServers' in {config_path} is not a JSON object",
            {"path": str(config_path)},
        )

    if server_name in mcp_servers:
        if mcp_servers[server_name] == server_config:
            return "unchanged"
        action = "updated"
    mcp_servers[server_name] = server_config

    # Create backup if original file exists
    if config_path.is_file():
        backup_path = config_path.with_suffix(config_path.suffix + ".bak")
        try:
            shutil.copy2(config_path, backup_path)
        except Exception:
            pass

    # Atomic write with fsync
    tmp_path = config_path.parent / f".{config_path.name}.tmp.{os.getpid()}"
    try:
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(existing_data, f, indent=2)
            f.write("\n")
            f.flush()
            os.fsync(f.fileno())
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


def _is_client_installed(client: str, directory: Path | None = None) -> bool:
    """Check if the client is installed or present on this machine."""
    norm = client.lower().replace("_", "-")
    home = Path.home()
    if norm in ("claude", "claude-desktop"):
        if sys.platform == "darwin":
            return (home / "Library" / "Application Support" / "Claude").is_dir() or Path("/Applications/Claude.app").exists()
        elif sys.platform == "win32":
            appdata = Path(os.environ.get("APPDATA", home / "AppData" / "Roaming"))
            return (appdata / "Claude").is_dir()
        else:
            return (home / ".config" / "Claude").is_dir()
    if norm == "claude-code":
        return bool(shutil.which("claude")) or (home / ".claude.json").exists() or (home / ".claude").is_dir()
    if norm == "cursor":
        return bool(shutil.which("cursor")) or (home / ".cursor").is_dir() or (directory is not None and (directory / ".cursor").is_dir())
    if norm in ("gemini", "antigravity"):
        return bool(shutil.which("gemini")) or (home / ".gemini").is_dir() or (directory is not None and (directory / ".agents").is_dir())
    if norm == "windsurf":
        return bool(shutil.which("windsurf")) or (home / ".codeium" / "windsurf").is_dir() or (directory is not None and (directory / ".codeium" / "windsurf").is_dir())
    if norm == "cline":
        return (home / ".cline").is_dir() or (home / "Library" / "Application Support" / "Code" / "User" / "globalStorage" / "saoudrizwan.claude-dev").is_dir() or (directory is not None and (directory / ".cline").is_dir())
    return False


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
        all_candidates = ["cursor", "claude-code", "claude-desktop", "gemini", "windsurf", "cline"]
        installed_candidates = [c for c in all_candidates if _is_client_installed(c, directory)]
        target_clients = installed_candidates if installed_candidates else all_candidates
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
        has_success = any(r.get("status") in ("installed", "updated", "unchanged") for r in results)
        return {
            "status": "installed" if has_success else "failed",
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

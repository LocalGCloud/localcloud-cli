"""Update the installed CLI through the channel that owns it."""

from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import sys
from typing import NoReturn

from .errors import HostError


_INSTALLER = "https://local.cloud/install.sh"
_BOOTSTRAP = r'''
set -eu
temporary_dir=$(mktemp -d "${TMPDIR:-/tmp}/localcloud-update.XXXXXX")
trap 'rm -rf "$temporary_dir"' 0
trap 'exit 1' HUP INT TERM
curl --proto '=https' --proto-redir '=https' -fsSL \
    --connect-timeout 15 --max-time 300 "$1" -o "$temporary_dir/install.sh"
sh "$temporary_dir/install.sh" --install-dir "$2" --no-start --no-modify-path
'''


def _managed_directory(executable: Path) -> Path | None:
    if executable.name != "localcloud":
        return None
    # Current installers use a versioned bundle; older ones installed one binary.
    bundled = executable.parent.name.startswith(".localcloud-runtime-")
    directory = executable.parent.parent if bundled else executable.parent
    marker = directory / ".localcloud-script-install"
    if marker.is_symlink() or not marker.is_file():
        return None
    lines = marker.read_text(encoding="utf-8").splitlines()
    if not lines or lines[0] != "managed-by=localcloud-install.sh":
        return None
    if bundled and f"runtime={executable.parent.name}" not in lines:
        return None
    return directory


def update() -> NoReturn:
    if not getattr(sys, "frozen", False):
        raise HostError(
            "update_unsupported_install",
            "This CLI runs from Python or source. Update it using the package "
            "manager or source checkout that installed it.",
        )

    executable = Path(sys.executable).resolve()
    environment = os.environ.copy()
    for key in ("LD_LIBRARY_PATH", "LIBPATH"):
        original = environment.pop(f"{key}_ORIG", None)
        if original is None:
            environment.pop(key, None)
        else:
            environment[key] = original
    environment["PYINSTALLER_RESET_ENVIRONMENT"] = "1"

    try:
        directory = _managed_directory(executable)
        if directory is not None:
            # The installer must resolve this installation even if PATH shadows it.
            environment["PATH"] = f"{directory}{os.pathsep}{os.environ.get('PATH', os.defpath)}"
            print("Updating LocalCloud CLI to the latest release...", flush=True)
            os.execve(
                "/bin/sh",
                ["sh", "-c", _BOOTSTRAP, "localcloud-update", _INSTALLER, str(directory)],
                environment,
            )

        brew = shutil.which("brew")
        if brew:
            prefix = subprocess.run(
                [brew, "--prefix", "localcloud"],
                capture_output=True, text=True, timeout=30, env=environment,
            )
            if prefix.returncode == 0 and prefix.stdout.strip():
                formula = Path(prefix.stdout.strip()).resolve()
                if executable.is_relative_to(formula):
                    print("Updating LocalCloud CLI with Homebrew...", flush=True)
                    os.execve(brew, [brew, "upgrade", "localcloud"], environment)
    except (OSError, UnicodeError, subprocess.TimeoutExpired) as error:
        raise HostError("update_failed", f"Could not update LocalCloud CLI: {error}") from error

    raise HostError(
        "update_unsupported_install",
        "This executable is not owned by the LocalCloud installer or Homebrew. "
        "Replace it using the latest standalone release at "
        "https://github.com/LocalGCloud/localcloud-cli/releases/latest, "
        "or update it using the process that installed it.",
    )

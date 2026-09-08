from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys
from types import SimpleNamespace

import pytest

from localcloud_cli import update as updater
from localcloud_cli.cli import _parser, main
from localcloud_cli.errors import HostError


@pytest.mark.parametrize("bundled", [False, True])
def test_script_update_preserves_directory_and_sanitizes_environment(tmp_path, monkeypatch, bundled):
    directory = tmp_path / "bin with quote's"
    runtime = directory / ".localcloud-runtime-0.1.0" if bundled else directory
    runtime.mkdir(parents=True)
    executable = runtime / "localcloud"
    executable.touch()
    (directory / ".localcloud-script-install").write_text(
        "managed-by=localcloud-install.sh\nversion=0.1.0\n"
        + (f"runtime={runtime.name}\n" if bundled else "")
    )
    alias = directory / "lc"
    alias.symlink_to(executable)
    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.setattr(sys, "executable", str(alias))
    monkeypatch.setenv("LD_LIBRARY_PATH", "/old/bundle")
    monkeypatch.setenv("LD_LIBRARY_PATH_ORIG", "/system/libraries")
    monkeypatch.setenv("LIBPATH", "/old/bundle")
    monkeypatch.delenv("LIBPATH_ORIG", raising=False)
    monkeypatch.setenv("LOCALCLOUD_INSTALL_DIR", "/wrong-directory")

    def execute(path, args, env):
        assert path == "/bin/sh"
        assert args[-2:] == ["https://local.cloud/install.sh", str(directory)]
        assert env["PATH"].split(os.pathsep)[0] == str(directory)
        assert env["LD_LIBRARY_PATH"] == "/system/libraries"
        assert "LIBPATH" not in env
        assert env["PYINSTALLER_RESET_ENVIRONMENT"] == "1"
        raise SystemExit(0)

    monkeypatch.setattr(os, "execve", execute)
    with pytest.raises(SystemExit) as result:
        updater.update()
    assert result.value.code == 0


@pytest.mark.parametrize("marker", ["missing", "invalid", "symlink", "wrong-runtime"])
def test_manual_install_is_never_overwritten(tmp_path, monkeypatch, marker):
    runtime = tmp_path / ".localcloud-runtime-0.1.0"
    runtime.mkdir()
    executable = runtime / "localcloud"
    executable.touch()
    managed = tmp_path / ".localcloud-script-install"
    if marker == "invalid":
        managed.write_text("unrelated\n")
    elif marker == "wrong-runtime":
        managed.write_text("managed-by=localcloud-install.sh\nruntime=.localcloud-runtime-0.2.0\n")
    elif marker == "symlink":
        other = tmp_path / "marker"
        other.write_text("managed-by=localcloud-install.sh\nruntime=.localcloud-runtime-0.1.0\n")
        managed.symlink_to(other)
    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.setattr(sys, "executable", str(executable))
    monkeypatch.setattr(updater.shutil, "which", lambda _: None)
    monkeypatch.setattr(os, "execve", lambda *_: pytest.fail("unexpected installation"))
    with pytest.raises(HostError, match="not owned"):
        updater.update()


@pytest.mark.parametrize("owned", [False, True])
def test_homebrew_updates_only_its_own_executable(tmp_path, monkeypatch, owned):
    formula = tmp_path / "Cellar/localcloud/0.1.0"
    executable = formula / "libexec/localcloud-runtime/localcloud" if owned else tmp_path / "localcloud"
    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.setattr(sys, "executable", str(executable))
    monkeypatch.setattr(updater.shutil, "which", lambda _: "/brew/bin/brew")

    def prefix(args, **kwargs):
        assert args == ["/brew/bin/brew", "--prefix", "localcloud"]
        assert kwargs["timeout"] == 30
        return SimpleNamespace(returncode=0, stdout=str(formula) + "\n")

    def execute(path, args, env):
        assert owned
        assert path == "/brew/bin/brew"
        assert args == [path, "upgrade", "localcloud"]
        raise SystemExit(0)

    monkeypatch.setattr(subprocess, "run", prefix)
    monkeypatch.setattr(os, "execve", execute)
    with pytest.raises(SystemExit if owned else HostError):
        updater.update()


def test_update_dispatch_does_not_construct_controller(monkeypatch, capsys):
    import localcloud_cli.controller

    monkeypatch.setattr(localcloud_cli.controller, "Controller", lambda: pytest.fail("Docker used"))
    monkeypatch.setattr(sys, "frozen", False, raising=False)
    assert _parser().parse_args(["update"]).command == "update"
    assert main(["update"]) == 2
    assert "Python or source" in capsys.readouterr().err


@pytest.mark.parametrize("download_status, install_status", [(0, 0), (22, 0), (0, 7)])
def test_shell_handoff_cleans_up_and_propagates_failures(tmp_path, download_status, install_status):
    mock_bin = tmp_path / "bin"
    mock_bin.mkdir()
    fixture = tmp_path / "installer.sh"
    arguments = tmp_path / "arguments"
    fixture.write_text('printf "%s\\n" "$@" > "$ARGUMENTS"\nexit "$INSTALL_STATUS"\n')
    curl = mock_bin / "curl"
    curl.write_text(
        '#!/bin/sh\nset -eu\n'
        'while [ "$1" != "-o" ]; do shift; done\n'
        'cp "$FIXTURE" "$2"\nexit "$DOWNLOAD_STATUS"\n'
    )
    curl.chmod(0o755)
    directory = tmp_path / "bin with quote's $(touch bad)"
    result = subprocess.run(
        ["/bin/sh", "-c", updater._BOOTSTRAP, "localcloud-update", updater._INSTALLER, str(directory)],
        env={
            **os.environ, "PATH": f"{mock_bin}{os.pathsep}{os.defpath}",
            "TMPDIR": str(tmp_path), "FIXTURE": str(fixture), "ARGUMENTS": str(arguments),
            "DOWNLOAD_STATUS": str(download_status), "INSTALL_STATUS": str(install_status),
        },
        capture_output=True, text=True, timeout=10,
    )
    assert result.returncode == (download_status or install_status), result.stderr
    assert not list(tmp_path.glob("localcloud-update.*"))
    if download_status:
        assert not arguments.exists()  # A partial download must never be executed.
    else:
        assert arguments.read_text().splitlines() == [
            "--install-dir", str(directory), "--no-start", "--no-modify-path",
        ]

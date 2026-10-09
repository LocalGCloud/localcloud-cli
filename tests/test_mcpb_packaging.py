from __future__ import annotations

import importlib.util
from pathlib import Path
import subprocess

import pytest


SCRIPT = Path(__file__).parents[1] / "scripts" / "build-mcpb.py"
spec = importlib.util.spec_from_file_location("build_mcpb", SCRIPT)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


@pytest.mark.parametrize("system,machine,platform", [
    ("Darwin", "arm64", "darwin-arm64"),
    ("Darwin", "x86_64", "darwin-amd64"),
    ("Linux", "aarch64", "linux-arm64"),
    ("Linux", "arm64", "linux-arm64"),
    ("Linux", "x86_64", "linux-amd64"),
])
def test_bundle_launcher_selects_native_cli_and_preserves_arguments(tmp_path, system, machine, platform):
    bundle = tmp_path / "bundle with spaces"
    bundle.mkdir()
    launcher = bundle / "launcher.sh"
    launcher.write_text(module.LAUNCHER)
    cli = bundle / "bin" / platform / "localcloud"
    cli.parent.mkdir(parents=True)
    cli.write_text("#!/bin/sh\nprintf '%s\\n' \"$@\"\n")
    cli.chmod(0o755)
    tools = tmp_path / "tools"
    tools.mkdir()
    uname = tools / "uname"
    uname.write_text(f"#!/bin/sh\ncase $1 in -s) echo {system};; -m) echo {machine};; esac\n")
    uname.chmod(0o755)
    result = subprocess.run(["/bin/sh", str(launcher), "--project-id", "project with spaces"],
                            env={"PATH": str(tools) + ":/usr/bin:/bin"}, capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
    assert result.stdout.splitlines() == ["mcp", "--project-id", "project with spaces"]


def test_bundle_launcher_rejects_unsupported_platform(tmp_path):
    launcher = tmp_path / "launcher.sh"
    launcher.write_text(module.LAUNCHER)
    uname = tmp_path / "uname"
    uname.write_text("#!/bin/sh\necho unsupported\n")
    uname.chmod(0o755)
    result = subprocess.run(["/bin/sh", str(launcher)], env={"PATH": str(tmp_path)},
                            capture_output=True, text=True)
    assert result.returncode == 1
    assert "supports macOS and Linux" in result.stderr
    assert result.stdout == ""

from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from zipfile import ZipFile

import pytest

ROOT = Path(__file__).resolve().parents[1]
PLUGIN = ROOT / "plugins/localcloud"
spec = importlib.util.spec_from_file_location("marketplace_builder", ROOT / "scripts/build-marketplace-plugin.py")
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


@pytest.fixture
def plugin(tmp_path):
    return Path(shutil.copytree(PLUGIN, tmp_path / "plugin"))


def test_reproducible_allowlisted_package_preserves_launcher_mode(plugin, tmp_path):
    first = tmp_path / "one.zip"
    second = tmp_path / "two.zip"
    receipt = builder.build_package(plugin, first)
    builder.build_package(plugin, second)
    assert first.read_bytes() == second.read_bytes()
    assert receipt["version"] == "0.1.0"
    with ZipFile(first) as archive:
        assert set(archive.namelist()) == set(builder.FILES)
        assert archive.getinfo(builder.LAUNCHER).external_attr >> 16 & 0o777 == 0o755
        assert archive.read("LICENSE") == (ROOT / "LICENSE").read_bytes()
    assert first.with_suffix(".zip.sha256").read_text().startswith(receipt["sha256"])


@pytest.mark.parametrize("extra", ["Dockerfile", "runtime.jar", ".env"])
def test_package_refuses_payload_outside_allowlist(plugin, tmp_path, extra):
    (plugin / extra).write_text("unapproved test fixture")
    with pytest.raises(ValueError, match="allowlist"):
        builder.build_package(plugin, tmp_path / "plugin.zip")
    assert not (tmp_path / "plugin.zip").exists()


def test_package_rejects_symlink_before_reading_target(plugin, tmp_path):
    outside = tmp_path / "outside"
    outside.write_text("private test fixture")
    (plugin / "README.md").unlink()
    (plugin / "README.md").symlink_to(outside)
    with pytest.raises(ValueError, match="symlink"):
        builder.build_package(plugin, tmp_path / "plugin.zip")


def test_manifests_and_catalogs_expose_actual_mcp():
    portable = json.loads((PLUGIN / "plugin.json").read_text())
    claude = json.loads((PLUGIN / ".claude-plugin/plugin.json").read_text())
    assert portable["name"] == claude["name"] == "localcloud"
    assert portable["version"] == claude["version"]
    assert portable["author"]["email"] == "orbitor.cloud@gmail.com"
    interface = portable["extensions"]["com.openai"]["interface"]
    assert len(interface["displayName"]) <= 30
    assert len(interface["shortDescription"]) <= 30
    assert len(interface["longDescription"]) <= 4000
    assert len(interface["defaultPrompt"]) == 3
    assert all(len(p) <= 128 for p in interface["defaultPrompt"])
    assert (PLUGIN / interface["logo"]).is_file()
    assert "hooks" not in portable and "hooks" not in claude
    assert claude["mcpServers"] == "./.mcp.json"
    stdio = json.loads((PLUGIN / "mcp.json").read_text())["mcpServers"]["localcloud"]
    assert stdio == {"type": "stdio", "command": "./scripts/launch-localcloud.sh", "args": ["mcp"]}
    legacy = json.loads((PLUGIN / ".mcp.json").read_text())["mcpServers"]["localcloud"]
    assert legacy["command"] == "${CLAUDE_PLUGIN_ROOT}/scripts/launch-localcloud.sh"
    assert legacy["args"] == ["mcp"]
    for name in [".agents/plugins/marketplace.json", ".claude-plugin/marketplace.json"]:
        catalog = json.loads((ROOT / name).read_text())
        assert catalog["name"] == "localcloud"
        entry = catalog["plugins"][0]
        source = entry["source"]
        path = source if isinstance(source, str) else source["path"]
        assert path == "./plugins/localcloud"


def _sandbox_launcher(tmp_path):
    # Map host installation paths into a fixture; never touch the real CLI.
    source = (PLUGIN / builder.LAUNCHER).read_text()
    for prefix in ["/opt/homebrew/bin", "/usr/local/bin"]:
        source = source.replace(prefix, str(tmp_path / prefix.strip("/").replace("/", "-")))
    launch = tmp_path / "launcher.sh"
    launch.write_text(source)
    launch.chmod(0o755)
    return launch


def _stub_cli(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        f"#!{sys.executable}\n"
        "import json, os, signal, sys\n"
        "print(json.dumps({'pid': os.getpid(), 'args': sys.argv[1:]}), flush=True)\n"
        "signal.pause()\n"
    )
    path.chmod(0o755)


@pytest.mark.parametrize("from_path", [True, False])
def test_launcher_executes_cli_preserving_arguments_and_pid(tmp_path, from_path):
    launch = _sandbox_launcher(tmp_path)
    location = tmp_path / ("bin" if from_path else "opt-homebrew-bin") / "localcloud"
    _stub_cli(location)
    env = {**os.environ, "PATH": str(tmp_path / "bin")}
    process = subprocess.Popen([str(launch), "mcp", "--project-id", "two words"], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        result = json.loads(process.stdout.readline())
        assert result == {"pid": process.pid, "args": ["mcp", "--project-id", "two words"]}
    finally:
        process.terminate()
        stdout, stderr = process.communicate(timeout=5)
    assert stdout == "" and stderr == ""


def test_launcher_missing_cli_has_actionable_stderr_only(tmp_path):
    launch = _sandbox_launcher(tmp_path)
    result = subprocess.run([str(launch), "mcp"], env={"PATH": str(tmp_path / "empty")}, capture_output=True, text=True)
    assert result.returncode == 127
    assert result.stdout == ""
    assert "Install it from https://local.cloud/docs/mcp/" in result.stderr

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
import yaml

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
    assert receipt["version"] == json.loads((plugin / "plugin.json").read_text())["version"]
    with ZipFile(first) as archive:
        assert set(archive.namelist()) == set(builder.FILES)
        assert archive.getinfo(builder.LAUNCHER).external_attr >> 16 & 0o777 == 0o755
        assert archive.getinfo(builder.CLAUDE_LAUNCHER).external_attr >> 16 & 0o777 == 0o755
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
    assert legacy["command"] == "${CLAUDE_PLUGIN_ROOT}/scripts/launch-claude.sh"
    assert legacy["args"] == []
    for name in [".agents/plugins/marketplace.json", ".claude-plugin/marketplace.json"]:
        catalog = json.loads((ROOT / name).read_text())
        assert catalog["name"] == "localcloud"
        entry = catalog["plugins"][0]
        source = entry["source"]
        path = source if isinstance(source, str) else source["path"]
        assert path == "./plugins/localcloud"


def _sandbox_launcher(tmp_path, launcher=builder.LAUNCHER):
    # Map host installation paths into a fixture; never touch the real CLI.
    source = (PLUGIN / launcher).read_text()
    for prefix in ["/opt/homebrew/bin", "/usr/local/bin", "/home/linuxbrew/.linuxbrew/bin"]:
        source = source.replace(prefix, str(tmp_path / prefix.strip("/").replace("/", "-")))
    source = source.replace('"${HOME:-}/.local/bin/localcloud"', '"' + str(tmp_path / "home/.local/bin/localcloud") + '"')
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


@pytest.mark.parametrize("installation", ["bin", "opt-homebrew-bin", "home-linuxbrew-.linuxbrew-bin", "home/.local/bin"])
def test_launcher_executes_cli_preserving_arguments_and_pid(tmp_path, installation):
    launch = _sandbox_launcher(tmp_path)
    location = tmp_path / installation / "localcloud"
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


@pytest.mark.parametrize("installation", ["bin", "bin with spaces"])
def test_claude_launcher_executes_fixed_mcp_command_and_preserves_pid(tmp_path, installation):
    launch = _sandbox_launcher(tmp_path, builder.CLAUDE_LAUNCHER)
    _stub_cli(tmp_path / installation / "localcloud")
    process = subprocess.Popen([str(launch)], env={**os.environ, "PATH": str(tmp_path / installation)}, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        assert json.loads(process.stdout.readline()) == {"pid": process.pid, "args": ["mcp"]}
    finally:
        process.terminate()
        stdout, stderr = process.communicate(timeout=5)
    assert stdout == "" and stderr == ""


def test_claude_launcher_is_literal_and_missing_cli_reports_stderr_only(tmp_path):
    source = (PLUGIN / builder.CLAUDE_LAUNCHER).read_text()
    assert "$" not in source and "`" not in source
    launch = _sandbox_launcher(tmp_path, builder.CLAUDE_LAUNCHER)
    result = subprocess.run([str(launch)], env={"PATH": str(tmp_path / "empty")}, capture_output=True, text=True)
    assert result.returncode == 127 and result.stdout == ""
    assert "localcloud" in result.stderr


@pytest.mark.skipif(shutil.which("sha256sum") is None, reason="release runner supplies sha256sum")
def test_release_workflow_plugin_checksums_do_not_break_homebrew(tmp_path):
    workflow = yaml.safe_load((ROOT / ".github/workflows/cli-release.yml").read_text())
    steps = workflow["jobs"]["publish-release"]["steps"]
    assets = [row["asset"] for row in workflow["jobs"]["build-native"]["strategy"]["matrix"]["include"]]
    for name in assets:
        (tmp_path / name).write_bytes(b"native archive fixture")
    builder.build_package(PLUGIN, tmp_path / "localcloud-plugin.zip")
    checksum_script = next(step["run"] for step in steps if step.get("name") == "Verify asset set and generate checksums")
    subprocess.run(["/bin/bash", "-e", "-o", "pipefail", "-c", checksum_script], cwd=tmp_path, check=True)
    subprocess.run([sys.executable, str(ROOT / "scripts/render-homebrew-formula.py"), "--version", "0.1.10", "--checksums", str(tmp_path / "SHA256SUMS"), "--output", str(tmp_path / "localcloud.rb")], check=True)
    assert "localcloud-plugin.zip" not in (tmp_path / "SHA256SUMS").read_text()
    assert (tmp_path / "localcloud-plugin.zip.sha256").is_file()
    signing_script = next(step["run"] for step in steps if step.get("name") == "Keyless-sign release files")
    assert "localcloud-plugin.zip.sha256" in signing_script

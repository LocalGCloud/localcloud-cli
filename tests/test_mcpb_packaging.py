from __future__ import annotations

import importlib.util
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

import pytest


SCRIPT = Path(__file__).parents[1] / "scripts" / "build-mcpb.py"
HANDSHAKE_CHECK = Path(__file__).parents[1] / "scripts" / "check-mcp-handshake.py"
spec = importlib.util.spec_from_file_location("build_mcpb", SCRIPT)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
check_spec = importlib.util.spec_from_file_location("check_mcpb", SCRIPT.with_name("check-mcpb.py"))
assert check_spec and check_spec.loader
check = importlib.util.module_from_spec(check_spec)
check_spec.loader.exec_module(check)
ANSWERING_BRIDGE = """
for line in sys.stdin:
    message = json.loads(line)
    if message.get("method") == "initialize":
        result = {"protocolVersion": message["params"]["protocolVersion"],
                  "capabilities": {}, "serverInfo": {"name": "localcloud", "version": "0"}}
    elif message.get("method") == "tools/list":
        result = {"tools": TOOLS}
    else:
        continue
    print(json.dumps({"jsonrpc": "2.0", "id": message["id"], "result": result}), flush=True)
"""


def _bridge(path, body):
    path.write_text(f"#!{sys.executable}\nimport json\nimport os\nimport sys\nimport time\n{body}")
    path.chmod(0o755)
    return path


def _check_handshake(bridge, timeout):
    return subprocess.run([sys.executable, str(HANDSHAKE_CHECK), str(bridge), "--timeout", timeout],
                          capture_output=True, text=True, timeout=30)


@pytest.mark.parametrize("system,machine,platform", [
    ("Darwin", "arm64", "darwin-arm64"),
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


@pytest.mark.parametrize("system,machine", [
    ("unsupported", "unsupported"),
    ("Darwin", "x86_64"),
], ids=["unknown", "intel-mac"])
def test_bundle_launcher_rejects_unsupported_platform(tmp_path, system, machine):
    launcher = tmp_path / "launcher.sh"
    launcher.write_text(module.LAUNCHER)
    uname = tmp_path / "uname"
    uname.write_text(f"#!/bin/sh\ncase $1 in -s) echo {system};; -m) echo {machine};; esac\n")
    uname.chmod(0o755)
    result = subprocess.run(["/bin/sh", str(launcher)], env={"PATH": str(tmp_path)},
                            capture_output=True, text=True)
    assert result.returncode == 1
    assert result.stderr == (
        "LocalCloud MCP supports macOS on Apple Silicon and Linux on ARM64 or x86_64.\n"
    )
    assert result.stdout == ""


def test_bundle_ships_one_cli_per_supported_platform():
    assert module.PLATFORMS == ("darwin-arm64", "linux-arm64", "linux-amd64")
    selected = [word.removeprefix("platform=") for word in module.LAUNCHER.split()
                if word.startswith("platform=")]
    assert selected == list(module.PLATFORMS)


def _bundle(tmp_path, *, extra=None, missing=None, manifest=None, mode=0o100755):
    files = {
        "manifest.json": json.dumps(manifest if manifest is not None else {
            "manifest_version": "0.3", "name": "localcloud-mcp", "version": "0.1.12",
            "server": {"type": "binary", "entry_point": "launcher.sh",
                       "mcp_config": {"command": "${__dirname}/launcher.sh", "args": []}},
        }),
        "launcher.sh": module.LAUNCHER,
        "icon.png": "icon", "README.md": "readme", "LICENSE": "license", "THIRD_PARTY_NOTICES": "notices",
    }
    for platform in check.PLATFORMS:
        for name in ("localcloud", "localcloud-runtime/localcloud", "LICENSE", "THIRD_PARTY_NOTICES"):
            files[f"bin/{platform}/{name}"] = "fixture"
    files.pop(missing, None)
    if extra:
        files.update(extra)
    archive = tmp_path / "fixture.mcpb"
    with zipfile.ZipFile(archive, "w") as bundle:
        for name, content in files.items():
            info = zipfile.ZipInfo(name)
            info.external_attr = mode << 16
            bundle.writestr(info, content)
    return archive, hashlib.sha256(archive.read_bytes()).hexdigest()


def test_check_bundle_extracts_verified_bytes_and_validates_the_extracted_manifest(tmp_path, monkeypatch):
    archive, digest = _bundle(tmp_path)
    validated = []

    def validate(argv, *, check):
        assert check is True
        assert argv[:4] == ["npx", "--yes", "@anthropic-ai/mcpb@2.1.2", "validate"]
        manifest = Path(argv[4])
        assert manifest.is_file()
        assert (manifest.parent / "bin/linux-amd64/localcloud-runtime/localcloud").is_file()
        validated.append(json.loads(manifest.read_text())["version"])

    monkeypatch.setattr(check.subprocess, "run", validate)
    result = check.check_bundle(archive, "0.1.12", digest)
    assert result["sha256"] == digest
    assert result["bytes"] == archive.stat().st_size
    assert validated == ["0.1.12"]


@pytest.mark.parametrize("options,error", [
    ({"extra": {"../outside": "bad"}}, "unsafe MCPB member"),
    ({"extra": {"/outside": "bad"}}, "unsafe MCPB member"),
    ({"extra": {"bin/linux-amd64/localcloud-runtime/_internal/secret.go": "private"}}, "unexpected source"),
    ({"extra": {"bin/linux-amd64/localcloud-runtime/_internal/.env": "secret"}}, "unexpected source"),
    ({"extra": {"bin/linux-amd64/localcloud-runtime/_internal/key.pem": "-----BEGIN RSA PRIVATE KEY-----"}}, "private key"),
    ({"missing": "bin/linux-arm64/localcloud"}, "incomplete MCPB"),
    ({"mode": 0o100644}, "not executable"),
    ({"mode": 0o120755}, "unsafe MCPB member"),
    ({"manifest": {"version": "0.1.13"}}, "version mismatch"),
    ({"manifest": []}, "version mismatch"),
    ({"manifest": {"version": "0.1.12", "server": {"entry_point": "missing"}}}, "does not launch"),
])
def test_check_bundle_rejects_bad_payloads_before_extraction(tmp_path, monkeypatch, options, error):
    archive, digest = _bundle(tmp_path, **options)
    monkeypatch.setattr(check.zipfile.ZipFile, "extractall", lambda *a: pytest.fail("extracted unsafe payload"))
    with pytest.raises(ValueError, match=error):
        check.check_bundle(archive, "0.1.12", digest)
    assert not (tmp_path.parent / "outside").exists()


def test_check_bundle_rejects_wrong_hash_invalid_zip_and_duplicate_members(tmp_path):
    archive, digest = _bundle(tmp_path)
    with pytest.raises(ValueError, match="SHA-256 mismatch"):
        check.check_bundle(archive, "0.1.12", "0" * 64)
    with zipfile.ZipFile(archive, "a") as bundle, pytest.warns(UserWarning, match="Duplicate"):
        bundle.writestr("manifest.json", "{}")
    with pytest.raises(ValueError, match="duplicate"):
        check.check_bundle(archive, "0.1.12", hashlib.sha256(archive.read_bytes()).hexdigest())
    archive.write_bytes(b"not a zip")
    with pytest.raises(zipfile.BadZipFile):
        check.check_bundle(archive, "0.1.12", hashlib.sha256(archive.read_bytes()).hexdigest())


def test_check_bundle_propagates_official_manifest_validation_failure(tmp_path, monkeypatch):
    archive, digest = _bundle(tmp_path)

    def reject(argv, **kwargs):
        raise subprocess.CalledProcessError(1, argv)

    monkeypatch.setattr(check.subprocess, "run", reject)
    with pytest.raises(subprocess.CalledProcessError):
        check.check_bundle(archive, "0.1.12", digest)


def test_check_bundle_detects_corrupt_member_bytes_even_with_a_matching_archive_hash(tmp_path):
    archive, _ = _bundle(tmp_path)
    with zipfile.ZipFile(archive) as bundle:
        member = bundle.getinfo("README.md")
        offset = member.header_offset + 30 + len(member.filename.encode()) + len(member.extra)
    content = bytearray(archive.read_bytes())
    content[offset] ^= 1
    archive.write_bytes(content)
    with pytest.raises(zipfile.BadZipFile, match="CRC"):
        check.check_bundle(archive, "0.1.12", hashlib.sha256(content).hexdigest())


def test_mcp_handshake_check_accepts_a_bridge_that_answers_without_docker(tmp_path):
    bridge = _bridge(
        tmp_path / "bridge",
        'assert sys.argv[1:] == ["mcp", "--no-start"], sys.argv\n'
        'assert not os.path.exists(os.environ["DOCKER_HOST"].removeprefix("unix://"))\n'
        'assert os.environ["LOCALCLOUD_TELEMETRY"] == "false"\n'
        'TOOLS = [{"name": "localcloud_runtime_status", "inputSchema": {"type": "object"}}]\n'
        + ANSWERING_BRIDGE,
    )
    result = _check_handshake(bridge, "5")
    assert result.returncode == 0, result.stderr
    assert "MCP handshake answered after" in result.stdout


@pytest.mark.parametrize("body,expected", [
    ('print("No module named \'mcp.server.stdio\'", file=sys.stderr)\nsys.exit(1)\n',
     "exited with code 1 before answering initialize\nNo module named 'mcp.server.stdio'"),
    ('TOOLS = [{"name": "other", "inputSchema": {"type": "object"}}]\n' + ANSWERING_BRIDGE,
     "did not list localcloud_runtime_status in tools/list: ['other']"),
    ('TOOLS = [{"name": "localcloud_runtime_status"}]\n' + ANSWERING_BRIDGE,
     "answered tools/list outside the MCP schema"),
    ('TOOLS = []\n' + ANSWERING_BRIDGE.replace(
        '"capabilities": {}', '"capabilities": {"tasks": {"requests": {"tools": {"call": True}}}}'),
     "answered initialize outside the MCP schema"),
    ('print("not JSON-RPC", flush=True)\ntime.sleep(60)\n',
     "wrote a line that is not JSON-RPC to stdout: 'not JSON-RPC'"),
], ids=["exits", "no-status-tool", "tool-without-schema", "bad-capability", "stdout-noise"])
def test_mcp_handshake_check_rejects_a_broken_bridge(tmp_path, body, expected):
    result = _check_handshake(_bridge(tmp_path / "bridge", body), "5")
    assert result.returncode == 1
    assert expected in result.stderr
    assert result.stdout == ""


def test_mcp_handshake_check_stops_a_hung_bridge(tmp_path):
    pid_file = tmp_path / "pid"
    bridge = _bridge(
        tmp_path / "bridge",
        f"open({str(pid_file)!r}, 'w').write(str(os.getpid()))\ntime.sleep(60)\n",
    )
    result = _check_handshake(bridge, "2")
    assert result.returncode == 1
    assert "did not answer initialize within 2.0 seconds" in result.stderr
    with pytest.raises(ProcessLookupError):
        os.kill(int(pid_file.read_text()), 0)


def test_mcp_handshake_check_passes_for_the_source_bridge(tmp_path):
    bridge = _bridge(
        tmp_path / "localcloud",
        "from localcloud_cli.entrypoint import main\nraise SystemExit(main())\n",
    )
    result = _check_handshake(bridge, "20")
    assert result.returncode == 0, result.stderr

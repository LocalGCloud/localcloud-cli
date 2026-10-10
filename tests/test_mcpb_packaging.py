from __future__ import annotations

import importlib.util
import os
from pathlib import Path
import subprocess
import sys

import pytest


SCRIPT = Path(__file__).parents[1] / "scripts" / "build-mcpb.py"
HANDSHAKE_CHECK = Path(__file__).parents[1] / "scripts" / "check-mcp-handshake.py"
spec = importlib.util.spec_from_file_location("build_mcpb", SCRIPT)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
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


def test_mcp_handshake_check_accepts_a_bridge_that_answers_without_docker(tmp_path):
    bridge = _bridge(
        tmp_path / "bridge",
        'assert sys.argv[1:] == ["mcp", "--no-start"], sys.argv\n'
        'assert not os.path.exists(os.environ["DOCKER_HOST"].removeprefix("unix://"))\n'
        'assert os.environ["LOCALCLOUD_TELEMETRY"] == "false"\n'
        'TOOLS = [{"name": "localcloud_runtime_status"}]\n' + ANSWERING_BRIDGE,
    )
    result = _check_handshake(bridge, "5")
    assert result.returncode == 0, result.stderr
    assert "MCP handshake answered after" in result.stdout


@pytest.mark.parametrize("body,expected", [
    ('print("No module named \'mcp.server.stdio\'", file=sys.stderr)\nsys.exit(1)\n',
     "exited with code 1 before answering initialize\nNo module named 'mcp.server.stdio'"),
    ('TOOLS = [{"name": "other"}]\n' + ANSWERING_BRIDGE,
     "did not list localcloud_runtime_status in tools/list: ['other']"),
    ('print("not JSON-RPC", flush=True)\ntime.sleep(60)\n',
     "wrote a line that is not JSON-RPC to stdout: 'not JSON-RPC'"),
], ids=["exits", "no-status-tool", "stdout-noise"])
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

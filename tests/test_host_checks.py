from __future__ import annotations

import io
import json
import subprocess
from collections.abc import Sequence
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

from localcloud_cli import host_checks
from localcloud_cli.host_checks import (
    DockerHost,
    Fix,
    HostProbe,
    apply_fix,
    describe_host,
    docker_unavailable_error,
    inspect,
    localcloud_volumes,
    run_checks,
)

COLIMA_INFO = {"Name": "colima", "OperatingSystem": "Ubuntu 24.04.4 LTS"}
NOT_FOUND = "Error while fetching server API version: FileNotFoundError(2, 'No such file or directory')"


class Commands:
    """Fake command runner: maps argv prefixes to (returncode, stdout)."""

    def __init__(self, responses: dict[tuple[str, ...], tuple[int, str]] | None = None) -> None:
        self.responses = {
            ("sysctl", "-n", "hw.optional.arm64"): (0, "1\n"),
            ("arch", "-x86_64", "/usr/bin/true"): (0, ""),
            **(responses or {}),
        }

    def __call__(self, argv: Sequence[str], _timeout: float) -> Any:
        for prefix, (code, stdout) in self.responses.items():
            if tuple(argv[: len(prefix)]) == prefix:
                return subprocess.CompletedProcess(list(argv), code, stdout, "")
        return subprocess.CompletedProcess(list(argv), 1, "", "")


def mac_probe(
    tmp_path: Path,
    *,
    tools: Sequence[str] = (),
    apps: Sequence[str] = (),
    environ: dict[str, str] | None = None,
    commands: Any = None,
    system: str = "Darwin",
) -> HostProbe:
    home = tmp_path / "home"
    home.mkdir(parents=True, exist_ok=True)
    applications = tmp_path / "Applications"
    applications.mkdir(parents=True, exist_ok=True)
    for app in apps:
        (applications / app).mkdir()
    return HostProbe(
        environ=environ or {},
        home=home,
        system=system,
        run=commands or Commands(),
        which=lambda name: f"/opt/homebrew/bin/{name}" if name in tools else None,
        applications=(applications,),
    )


def write_colima(
    probe: HostProbe, vm_type: str, rosetta: bool, *, profile: str = "default", data_disk: bool = True
) -> None:
    profile_dir = probe.home / ".colima" / profile
    profile_dir.mkdir(parents=True, exist_ok=True)
    (profile_dir / "colima.yaml").write_text(
        f"cpu: 6\nmemory: 12\ndisk: 100\nvmType: {vm_type}\nrosetta: {str(rosetta).lower()}\n"
    )
    if data_disk:
        instance = "colima" if profile == "default" else f"colima-{profile}"
        disk = probe.home / ".colima" / "_lima" / "_disks" / instance
        disk.mkdir(parents=True, exist_ok=True)
        (disk / "datadisk").write_text("")


def engine(info: dict[str, Any]) -> Any:
    return SimpleNamespace(info=lambda: info)


def ids(findings: Sequence[host_checks.Finding]) -> list[str]:
    return [finding.id for finding in findings]


@pytest.mark.parametrize(("data_disk", "note"), [(True, "separate disk"), (False, "are deleted")])
def test_colima_in_qemu_mode_warns_and_recreates_on_vz(
    tmp_path: Path, data_disk: bool, note: str
) -> None:
    probe = mac_probe(tmp_path, tools=("colima",))
    write_colima(probe, "qemu", False, data_disk=data_disk)

    host, findings = inspect(engine(COLIMA_INFO), probe=probe)

    assert host.summary() == "Colima (QEMU)"
    assert ids(findings) == ["qemu_vm"]
    assert "LocalCloud prefers VZ with Rosetta" in findings[0].message
    fix = findings[0].fix
    assert fix is not None and fix.deletes_data is True
    assert fix.commands == (
        ("colima", "delete", "--force"),
        ("colima", "start", "--vm-type", "vz", "--vz-rosetta", "--cpu", "6", "--memory", "12", "--disk", "100"),
    )
    assert any(note in line for line in fix.notes)
    assert "colima delete --data --force" in fix.notes[-1]


def test_colima_profile_with_rosetta_off_restarts_with_rosetta(tmp_path: Path) -> None:
    probe = mac_probe(tmp_path, tools=("colima",))
    write_colima(probe, "vz", False, profile="dev")

    host, findings = inspect(engine({"Name": "colima-dev"}), probe=probe)

    assert host.summary() == "Colima profile 'dev' (VZ, Rosetta off)"
    assert ids(findings) == ["rosetta_off"]
    fix = findings[0].fix
    assert fix is not None and fix.deletes_data is False
    assert fix.commands == (
        ("colima", "stop", "-p", "dev"),
        ("colima", "start", "-p", "dev", "--vz-rosetta"),
    )


def test_vz_with_rosetta_is_clean(tmp_path: Path) -> None:
    probe = mac_probe(tmp_path, tools=("colima",))
    write_colima(probe, "vz", True)

    host, findings = inspect(engine(COLIMA_INFO), probe=probe)

    assert host.summary() == "Colima (VZ, Rosetta on)"
    assert findings == []


def test_missing_rosetta_runtime_is_installed_first(tmp_path: Path) -> None:
    commands = Commands({("arch", "-x86_64", "/usr/bin/true"): (86, "")})
    probe = mac_probe(tmp_path, tools=("colima",), commands=commands)
    write_colima(probe, "vz", False)

    _, findings = inspect(engine(COLIMA_INFO), probe=probe)

    assert findings[0].fix is not None
    assert findings[0].fix.commands[0] == ("softwareupdate", "--install-rosetta", "--agree-to-license")


def test_intel_mac_and_unknown_engines_get_no_vm_findings(tmp_path: Path) -> None:
    commands = Commands({("sysctl", "-n", "hw.optional.arm64"): (0, "0\n")})
    probe = mac_probe(tmp_path, tools=("colima",), commands=commands)
    write_colima(probe, "qemu", False)
    host, findings = inspect(engine(COLIMA_INFO), probe=probe)
    assert host.apple_silicon is False
    assert findings == []

    host, findings = inspect(engine({"Name": "buildbox"}), probe=mac_probe(tmp_path / "other"))
    assert host.summary() == "unknown"
    assert findings == []


@pytest.mark.parametrize(
    ("settings", "prefix"),
    [
        ({"virtualMachine": {"type": "qemu", "useRosetta": False}}, ""),
        ({"experimental": {"virtualMachine": {"type": "qemu", "useRosetta": False}}}, "experimental."),
    ],
)
def test_rancher_desktop_qemu_switch_uses_rdctl(
    tmp_path: Path, settings: dict[str, Any], prefix: str
) -> None:
    commands = Commands({("/opt/homebrew/bin/rdctl", "list-settings"): (0, json.dumps(settings))})
    probe = mac_probe(tmp_path, tools=("rdctl",), commands=commands)

    host, findings = inspect(engine({"Name": "lima-rancher-desktop"}), probe=probe)

    assert host.summary() == "Rancher Desktop (QEMU)"
    assert ids(findings) == ["qemu_vm"]
    fix = findings[0].fix
    assert fix is not None and fix.deletes_data is True
    assert fix.commands[-1] == (
        "/opt/homebrew/bin/rdctl",
        "set",
        f"--{prefix}virtual-machine.type=vz",
        f"--{prefix}virtual-machine.use-rosetta=true",
    )


def test_docker_desktop_findings_print_steps_only(tmp_path: Path) -> None:
    probe = mac_probe(tmp_path)
    settings = probe.home / "Library" / "Group Containers" / "group.com.docker" / "settings-store.json"
    settings.parent.mkdir(parents=True)
    info = {"Name": "docker-desktop", "OperatingSystem": "Docker Desktop"}

    settings.write_text(json.dumps({"UseVirtualizationFramework": True, "UseVirtualizationFrameworkRosetta": False}))
    host, findings = inspect(engine(info), probe=probe)
    assert host.summary() == "Docker Desktop (VZ, Rosetta off)"
    assert ids(findings) == ["rosetta_off"]
    assert findings[0].fix is None
    assert "Settings > General" in findings[0].steps[0]

    settings.write_text(json.dumps({"UseVirtualizationFramework": False}))
    _, findings = inspect(engine(info), probe=probe)
    assert ids(findings) == ["qemu_vm"]
    assert "4.44" in findings[0].steps[0]

    settings.write_text(json.dumps({"UseVirtualizationFramework": False, "UseLibkrun": True}))
    host, findings = inspect(engine(info), probe=probe)
    assert host.vm_type == "docker-vmm"
    assert findings == []


@pytest.mark.parametrize(
    ("tools", "packages"), [(("brew",), ("colima", "docker")), (("brew", "docker"), ("colima",))]
)
def test_not_installed_offers_colima_with_homebrew(
    tmp_path: Path, tools: tuple[str, ...], packages: tuple[str, ...]
) -> None:
    host, findings = inspect(error=NOT_FOUND, probe=mac_probe(tmp_path, tools=tools))

    assert host.reason == "not_installed"
    assert ids(findings) == ["docker_not_installed"]
    fix = findings[0].fix
    assert fix is not None
    assert fix.commands == (
        ("brew", "install", *packages),
        ("colima", "start", "--cpu", "4", "--memory", "8", "--vm-type", "vz", "--vz-rosetta"),
    )


@pytest.mark.parametrize(("system", "step"), [("Darwin", "brew.sh"), ("Linux", "docs.docker.com")])
def test_not_installed_without_homebrew_prints_steps(tmp_path: Path, system: str, step: str) -> None:
    _, findings = inspect(error=NOT_FOUND, probe=mac_probe(tmp_path, system=system))

    assert ids(findings) == ["docker_not_installed"]
    assert findings[0].fix is None
    assert step in findings[0].steps[0]


def test_not_running_starts_the_app_the_context_names(tmp_path: Path) -> None:
    probe = mac_probe(
        tmp_path, tools=("colima",), apps=("Docker.app",), environ={"DOCKER_CONTEXT": "colima-work"}
    )
    write_colima(probe, "vz", True, profile="work")

    host, findings = inspect(error=NOT_FOUND, probe=probe)

    assert host.summary() == "Colima profile 'work' (VZ, Rosetta on, not running)"
    assert ids(findings) == ["docker_not_running"]
    assert findings[0].fix is not None
    assert findings[0].fix.commands == (("colima", "start", "-p", "work"),)


def test_not_running_with_several_apps_lists_each_start(tmp_path: Path) -> None:
    probe = mac_probe(tmp_path, tools=("colima",), apps=("Docker.app",))

    _, findings = inspect(error=NOT_FOUND, probe=probe)

    assert findings[0].id == "docker_not_running"
    assert findings[0].fix is None
    assert findings[0].steps == ("Colima: colima start", "Docker Desktop: open -a Docker")


@pytest.mark.parametrize(
    ("environ", "cause", "reason"),
    [
        ({"DOCKER_HOST": "unix:///tmp/colima/docker.sock"}, NOT_FOUND, "unreachable"),
        ({"DOCKER_CONTEXT": "remote"}, NOT_FOUND, "unreachable"),
        ({}, "PermissionError(13, 'Permission denied')", "permission_denied"),
    ],
)
def test_unavailable_reasons(tmp_path: Path, environ: dict[str, str], cause: str, reason: str) -> None:
    host, findings = inspect(error=cause, probe=mac_probe(tmp_path, tools=("colima",), environ=environ))

    assert host.reason == reason
    assert ids(findings) == [f"docker_{reason}"]
    assert findings[0].fix is None
    assert findings[0].steps


def test_docker_unavailable_error_keeps_its_code_and_explains(tmp_path: Path) -> None:
    error = docker_unavailable_error(RuntimeError(NOT_FOUND), mac_probe(tmp_path, tools=("brew",)))

    assert error.code == "docker_unavailable"
    assert error.details["reason"] == "not_installed"
    assert error.details["cause"] == NOT_FOUND
    assert "lc doctor --fix" in error.message
    assert [item["id"] for item in error.details["setup_findings"]] == ["docker_not_installed"]

    probe = mac_probe(tmp_path / "remote", environ={"DOCKER_HOST": "tcp://build.internal:2376"})
    error = docker_unavailable_error(RuntimeError(NOT_FOUND), probe)
    assert error.message == (
        "Docker is not reachable at tcp://build.internal:2376. Check DOCKER_HOST, or select "
        "a running engine with 'docker context use <name>'."
    )


def test_broken_checks_and_commands_never_raise(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    def broken(_host: DockerHost, _probe: HostProbe) -> None:
        raise RuntimeError("boom")

    monkeypatch.setattr(host_checks, "CHECKS", (broken, *host_checks.CHECKS))
    probe = mac_probe(tmp_path, tools=("brew",))
    assert ids(run_checks(describe_host(probe, error=NOT_FOUND), probe)) == ["docker_not_installed"]

    def explode(_argv: Sequence[str], _timeout: float) -> Any:
        raise subprocess.TimeoutExpired("rdctl", 5)

    probe = mac_probe(tmp_path / "rd", tools=("rdctl",), commands=explode)
    host, findings = inspect(engine({"Name": "lima-rancher-desktop"}), probe=probe)
    assert (host.provider, host.vm_type, findings) == ("rancher-desktop", None, [])

    def info() -> dict[str, Any]:
        raise RuntimeError("timeout")

    host, findings = inspect(SimpleNamespace(info=info), probe=mac_probe(tmp_path / "slow"))
    assert host.reachable is True
    assert findings == []


def test_apply_fix_shows_each_command_and_stops_at_a_failure() -> None:
    fix = Fix("Recreate", (("colima", "delete", "--force"), ("colima", "start"), ("echo", "never")))
    ran: list[tuple[str, ...]] = []
    out = io.StringIO()

    def run(command: Sequence[str]) -> int:
        ran.append(tuple(command))
        return 1 if command[1] == "start" else 0

    assert apply_fix(fix, run=run, stream=out) == {
        "status": "failed",
        "command": "colima start",
        "exit_code": 1,
    }
    assert ran == [("colima", "delete", "--force"), ("colima", "start")]
    assert out.getvalue() == "$ colima delete --force\n$ colima start\n"

    def missing(_command: Sequence[str]) -> int:
        raise FileNotFoundError("brew")

    assert apply_fix(Fix("Install", (("brew", "install"),)), run=missing, stream=out)["exit_code"] == 127
    assert apply_fix(Fix("Start", (("colima", "start"),)), run=lambda _c: 0, stream=out) == {
        "status": "applied"
    }


def test_localcloud_volumes_names_managed_and_default_volumes(monkeypatch: pytest.MonkeyPatch) -> None:
    from localcloud_cli.docker_runtime import MANAGED_LABEL

    volumes = [
        SimpleNamespace(name="team-data", attrs={"Labels": {MANAGED_LABEL: "true"}}),
        SimpleNamespace(name="localcloud-data", attrs={"Labels": None}),
        SimpleNamespace(name="postgres", attrs={"Labels": {}}),
    ]
    client = SimpleNamespace(volumes=SimpleNamespace(list=lambda: volumes))
    monkeypatch.setattr(host_checks, "_docker_client", lambda: client)

    assert localcloud_volumes() == ["localcloud-data", "team-data"]

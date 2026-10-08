"""Checks of the host's Docker setup, and the fixes they offer.

Each check in ``CHECKS`` looks at a ``DockerHost`` and returns a ``Finding`` or
None. Checks only read the host and bound every command they run; fixes run
only through ``apply_fix``, after the caller has confirmed them.

The first checks cover a known failure: on Apple Silicon, a Docker VM running
under QEMU broke the BigQuery emulator, and VZ with Rosetta fixed it. Add new
checks to ``CHECKS`` as more setup-related failures are learned.
"""

from __future__ import annotations

import json
import os
import platform
import shlex
import shutil
import subprocess
import sys
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, TextIO

from .errors import HostError

COLIMA_CPU = 4
COLIMA_MEMORY_GIB = 8
_TIMEOUT = 5.0
PREFERENCE = (
    "LocalCloud prefers VZ with Rosetta: it is faster, and QEMU has known issues "
    "with LocalCloud (the BigQuery emulator can fail under it)."
)
_LABELS = {
    "colima": "Colima",
    "rancher-desktop": "Rancher Desktop",
    "docker-desktop": "Docker Desktop",
}
_VM_LABELS = {"vz": "VZ", "qemu": "QEMU", "docker-vmm": "Docker VMM"}
# Docker engine and context names that identify Docker Desktop.
_DOCKER_DESKTOP_NAMES = {"docker-desktop", "desktop-linux"}
_ROSETTA_INSTALL = ("softwareupdate", "--install-rosetta", "--agree-to-license")


def _run_command(argv: Sequence[str], timeout: float) -> Any:
    return subprocess.run(
        list(argv), capture_output=True, text=True, timeout=timeout, check=False
    )


class HostProbe:
    """Host access for the checks; tests pass their own inputs."""

    def __init__(
        self,
        *,
        environ: Mapping[str, str] | None = None,
        home: Path | None = None,
        system: str | None = None,
        run: Callable[[Sequence[str], float], Any] | None = None,
        which: Callable[[str], str | None] | None = None,
        applications: Sequence[Path] | None = None,
    ) -> None:
        self.environ = os.environ if environ is None else environ
        self.home = Path.home() if home is None else home
        self.system = platform.system() if system is None else system
        self._run = run or _run_command
        self.which = which or shutil.which
        self.applications = (
            (Path("/Applications"), self.home / "Applications")
            if applications is None
            else tuple(applications)
        )

    def run(self, argv: Sequence[str]) -> str | None:
        """The command's output, or None when it fails or cannot run."""
        try:
            result = self._run(list(argv), _TIMEOUT)
        except (OSError, subprocess.SubprocessError, ValueError):
            return None
        if result is None or result.returncode != 0:
            return None
        return str(result.stdout)

    def read_text(self, path: Path) -> str | None:
        try:
            return path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            return None

    def app_installed(self, name: str) -> bool:
        return any((base / name).exists() for base in self.applications)

    def apple_silicon(self) -> bool:
        # platform.machine() says x86_64 when Python itself runs under Rosetta,
        # so ask the kernel.
        if self.system != "Darwin":
            return False
        return (self.run(["sysctl", "-n", "hw.optional.arm64"]) or "").strip() == "1"

    def rosetta_installed(self) -> bool:
        return self.run(["arch", "-x86_64", "/usr/bin/true"]) is not None

    def docker_context(self) -> str | None:
        selected = str(self.environ.get("DOCKER_CONTEXT") or "").strip()
        if selected:
            return selected
        config = _load_json(self.read_text(self.home / ".docker" / "config.json")) or {}
        return str(config.get("currentContext") or "") or None


def default_probe() -> HostProbe:
    """The probe checks use when none is given; tests replace this."""
    return HostProbe()


@dataclass(frozen=True)
class Fix:
    summary: str
    commands: tuple[tuple[str, ...], ...]
    notes: tuple[str, ...] = ()
    # The fix can delete Docker images, containers and volumes, so it needs
    # typed approval.
    deletes_data: bool = False

    def to_dict(self) -> dict[str, Any]:
        return {
            "summary": self.summary,
            "commands": [shlex.join(command) for command in self.commands],
            "notes": list(self.notes),
            "deletes_data": self.deletes_data,
        }


@dataclass(frozen=True)
class Finding:
    id: str
    severity: str  # "error" or "warning"
    message: str
    fix: Fix | None = None
    steps: tuple[str, ...] = ()  # What to do by hand when there is no fix.

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "severity": self.severity,
            "message": self.message,
            "fix": self.fix.to_dict() if self.fix is not None else None,
            "steps": list(self.steps),
        }


@dataclass(frozen=True)
class DockerHost:
    provider: str | None = None
    profile: str | None = None  # Colima profile
    vm_type: str | None = None
    rosetta: bool | None = None
    apple_silicon: bool = False
    reachable: bool = True
    reason: str | None = None  # not_installed | not_running | unreachable | permission_denied
    installed: tuple[str, ...] = ()
    facts: Mapping[str, Any] = field(default_factory=dict)

    @property
    def label(self) -> str:
        label = _LABELS.get(self.provider or "", "unknown")
        if self.provider == "colima" and self.profile not in {None, "default"}:
            label = f"{label} profile '{self.profile}'"
        return label

    def summary(self) -> str:
        if self.provider is None:
            return "unknown"
        parts = [_VM_LABELS.get(self.vm_type, self.vm_type)] if self.vm_type else []
        if self.vm_type == "vz" and self.rosetta is not None:
            parts.append(f"Rosetta {'on' if self.rosetta else 'off'}")
        if not self.reachable:
            parts.append("not running" if self.reason == "not_running" else "unreachable")
        return f"{self.label} ({', '.join(parts)})" if parts else self.label


def describe_host(
    probe: HostProbe | None = None,
    *,
    info: Mapping[str, Any] | None = None,
    error: BaseException | str | None = None,
) -> DockerHost:
    """Which Docker app serves this host, and how its VM is set up."""
    probe = probe or default_probe()
    installed = _installed_apps(probe)
    docker_host = str(probe.environ.get("DOCKER_HOST") or "").strip()
    # DOCKER_HOST overrides the context, as it does for the docker CLI.
    context = None if docker_host else probe.docker_context()
    provider, profile = None, None
    if info:
        if "Docker Desktop" in str(info.get("OperatingSystem") or ""):
            provider = "docker-desktop"
        else:
            provider, profile = _provider(str(info.get("Name") or ""))
    if provider is None and context:
        provider, profile = _provider(context)
    # Nothing names the app, so start the only one installed.
    if (
        provider is None
        and error is not None
        and not docker_host
        and context in {None, "default"}
        and len(installed) == 1
    ):
        provider = installed[0]
    if provider == "colima":
        profile = profile or "default"

    reader = _SETTINGS_READERS.get(provider or "")
    vm_type, rosetta, facts = reader(probe, profile) if reader else (None, None, {})
    reason = None
    if error is not None:
        if "permission denied" in str(error).lower():
            reason = "permission_denied"
        elif provider is not None:
            reason = "not_running"
        elif docker_host or context not in {None, "default"}:
            reason = "unreachable"  # An endpoint no installed app serves.
        elif installed or (probe.system == "Linux" and probe.which("docker")):
            reason = "not_running"
        else:
            reason = "not_installed"
    return DockerHost(
        provider=provider,
        profile=profile,
        vm_type=vm_type,
        rosetta=rosetta,
        apple_silicon=probe.apple_silicon(),
        reachable=error is None,
        reason=reason,
        installed=installed,
        facts=facts,
    )


def _provider(name: str) -> tuple[str | None, str | None]:
    """The app (and Colima profile) behind a Docker engine or context name."""
    if name == "colima" or name.startswith("colima-"):
        return "colima", name.removeprefix("colima").removeprefix("-") or "default"
    if "rancher-desktop" in name:
        return "rancher-desktop", None
    if name in _DOCKER_DESKTOP_NAMES:
        return "docker-desktop", None
    return None, None


def _installed_apps(probe: HostProbe) -> tuple[str, ...]:
    if probe.system != "Darwin":
        return ()
    found: list[str] = []
    if probe.which("colima"):
        found.append("colima")
    if probe.app_installed("Rancher Desktop.app") or _rdctl(probe):
        found.append("rancher-desktop")
    if probe.app_installed("Docker.app"):
        found.append("docker-desktop")
    return tuple(found)


def _rdctl(probe: HostProbe) -> str | None:
    bundled = probe.home / ".rd" / "bin" / "rdctl"
    return probe.which("rdctl") or (str(bundled) if bundled.exists() else None)


def _load_json(text: str | None) -> dict[str, Any] | None:
    try:
        value = json.loads(text) if text else None
    except ValueError:
        return None
    return value if isinstance(value, dict) else None


def _lower(value: Any) -> str | None:
    return str(value or "").strip().lower() or None


def _bool(value: Any) -> bool | None:
    return value if isinstance(value, bool) else None


# Each reader returns the VM type, whether Rosetta is on, and facts its fix needs.
Settings = tuple[str | None, bool | None, dict[str, Any]]


def _colima_settings(probe: HostProbe, profile: str | None) -> Settings:
    import yaml

    profile = profile or "default"
    home = Path(probe.environ.get("COLIMA_HOME") or probe.home / ".colima")
    try:
        config = yaml.safe_load(probe.read_text(home / profile / "colima.yaml") or "")
    except yaml.YAMLError:
        config = None
    config = config if isinstance(config, dict) else {}
    instance = "colima" if profile == "default" else f"colima-{profile}"
    facts = {key: config.get(key) for key in ("cpu", "memory", "disk")}
    # Colima 0.9+ keeps Docker data on this disk, and `colima delete` keeps it
    # unless --data is passed.
    facts["data_disk"] = (home / "_lima" / "_disks" / instance / "datadisk").exists()
    return _lower(config.get("vmType")), _bool(config.get("rosetta")), facts


def _rancher_settings(probe: HostProbe, _profile: str | None) -> Settings:
    rdctl = _rdctl(probe)
    # rdctl answers only while Rancher Desktop runs, which is when the VM matters.
    settings = (_load_json(probe.run([rdctl, "list-settings"])) if rdctl else None) or {}
    vm, prefix = settings.get("virtualMachine"), ""
    if not isinstance(vm, dict) or "type" not in vm:
        # Older Rancher Desktop releases kept these settings under "experimental".
        legacy = settings.get("experimental")
        vm = legacy.get("virtualMachine") if isinstance(legacy, dict) else None
        vm, prefix = (vm if isinstance(vm, dict) else {}), "experimental."
    facts = {"rdctl": rdctl, "flag_prefix": prefix}
    return _lower(vm.get("type")), _bool(vm.get("useRosetta")), facts


def _docker_desktop_settings(probe: HostProbe, _profile: str | None) -> Settings:
    base = probe.home / "Library" / "Group Containers" / "group.com.docker"
    data = (
        _load_json(probe.read_text(base / "settings-store.json"))
        or _load_json(probe.read_text(base / "settings.json"))
        or {}
    )
    settings = {str(key).lower(): value for key, value in data.items()}
    framework = settings.get("usevirtualizationframework")
    vm_type = None
    if settings.get("uselibkrun") is True:
        vm_type = "docker-vmm"
    elif isinstance(framework, bool):
        vm_type = "vz" if framework else "qemu"
    rosetta = _bool(settings.get("usevirtualizationframeworkrosetta"))
    return vm_type, rosetta if vm_type == "vz" else None, {}


_SETTINGS_READERS: dict[str, Callable[[HostProbe, str | None], Settings]] = {
    "colima": _colima_settings,
    "rancher-desktop": _rancher_settings,
    "docker-desktop": _docker_desktop_settings,
}


def _profile_args(host: DockerHost) -> tuple[str, ...]:
    return ("-p", host.profile) if host.profile not in {None, "default"} else ()


def _rosetta_install(probe: HostProbe) -> tuple[tuple[str, ...], ...]:
    return () if probe.rosetta_installed() else (_ROSETTA_INSTALL,)


def _start_command(host: DockerHost) -> tuple[str, ...]:
    if host.provider == "colima":
        return ("colima", "start", *_profile_args(host))
    if host.provider == "rancher-desktop":
        rdctl = host.facts.get("rdctl")
        return (str(rdctl), "start") if rdctl else ("open", "-a", "Rancher Desktop")
    return ("open", "-a", "Docker")


def _install_colima(host: DockerHost, probe: HostProbe) -> Fix:
    start = ("colima", "start", "--cpu", str(COLIMA_CPU), "--memory", str(COLIMA_MEMORY_GIB))
    if host.apple_silicon:
        start += ("--vm-type", "vz", "--vz-rosetta")
    return Fix(
        summary="Install Colima with Homebrew and start it",
        commands=(
            ("brew", "install", "colima", *(() if probe.which("docker") else ("docker",))),
            *(_rosetta_install(probe) if host.apple_silicon else ()),
            start,
        ),
        notes=(
            f"Colima is a free, open-source Docker engine. It starts a VM with {COLIMA_CPU} "
            f"CPUs and {COLIMA_MEMORY_GIB} GiB of memory"
            + (", using VZ with Rosetta." if host.apple_silicon else "."),
        ),
    )


def _docker_available(host: DockerHost, probe: HostProbe) -> Finding | None:
    if host.reachable:
        return None
    reason = host.reason or "not_running"
    if reason == "permission_denied":
        return Finding(
            "docker_permission_denied",
            "error",
            "Permission denied on the Docker socket.",
            steps=(
                "On Linux, add your user to the docker group; on macOS, restart your Docker app",
            ),
        )
    if reason == "unreachable":
        endpoint = probe.environ.get("DOCKER_HOST") or f"context {probe.docker_context()}"
        return Finding(
            "docker_unreachable",
            "error",
            f"Docker is not reachable at {endpoint}.",
            steps=(
                "Check DOCKER_HOST, or select a running engine with 'docker context use <name>'",
            ),
        )
    if reason == "not_installed":
        if probe.system == "Darwin" and probe.which("brew"):
            return Finding(
                "docker_not_installed",
                "error",
                "Docker is not installed. Run 'lc doctor --fix' to install Colima, "
                "a free, lightweight Docker engine.",
                fix=_install_colima(host, probe),
            )
        return Finding(
            "docker_not_installed",
            "error",
            "Docker is not installed.",
            steps=(
                "Install Homebrew (https://brew.sh), then run 'lc doctor --fix' to install Colima"
                if probe.system == "Darwin"
                else "Install Docker Engine: https://docs.docker.com/engine/install/",
            ),
        )
    if host.provider is not None:
        command = _start_command(host)
        return Finding(
            "docker_not_running",
            "error",
            f"{host.label} is installed but not running. Start it with "
            f"'{shlex.join(command)}' or run 'lc doctor --fix'.",
            fix=Fix(summary=f"Start {host.label}", commands=(command,)),
        )
    starts = tuple(
        f"{_LABELS[name]}: {shlex.join(_start_command(DockerHost(provider=name)))}"
        for name in host.installed
    )
    return Finding(
        "docker_not_running",
        "error",
        "Docker is installed but not running.",
        steps=starts or ("Start your Docker engine",),
    )


def _colima_fix(host: DockerHost, probe: HostProbe, switch: bool) -> Fix:
    args = _profile_args(host)
    if not switch:
        return Fix(
            summary=f"Turn on Rosetta in {host.label}",
            commands=(
                *_rosetta_install(probe),
                ("colima", "stop", *args),
                ("colima", "start", *args, "--vz-rosetta"),
            ),
            notes=(
                (
                    f"{host.label} restarts its VM, so running containers stop; images, "
                    "containers and volumes are kept."
                ),
            ),
        )
    start = ["colima", "start", *args, "--vm-type", "vz", "--vz-rosetta"]
    for key in ("cpu", "memory", "disk"):
        value = host.facts.get(key)
        if type(value) is int and value > 0:
            start += [f"--{key}", str(value)]
    empty = shlex.join(("colima", "delete", *args, "--data", "--force"))
    return Fix(
        summary=f"Recreate {host.label} on VZ with Rosetta",
        commands=(*_rosetta_install(probe), ("colima", "delete", *args, "--force"), tuple(start)),
        notes=(
            (
                "Colima can't change an existing VM's type, so this deletes and recreates it. "
                "Running containers stop; CPU, memory and disk are kept, and other colima.yaml "
                "settings return to their defaults."
            ),
            (
                "Colima keeps Docker data on a separate disk and should reuse it, but that is "
                "not verified for a VM created with QEMU, so treat its images, containers and "
                "volumes as at risk."
            )
            if host.facts.get("data_disk")
            else (
                "This Colima VM keeps Docker data inside it, so its images, containers and "
                "volumes are deleted."
            ),
            (
                f"If Colima can't start with the old data disk, recreate it empty: {empty}, "
                f"then {shlex.join(start)}"
            ),
        ),
        deletes_data=True,
    )


def _rancher_fix(host: DockerHost, probe: HostProbe, switch: bool) -> Fix:
    prefix = host.facts.get("flag_prefix", "")
    flags = (f"--{prefix}virtual-machine.type=vz",) if switch else ()
    flags += (f"--{prefix}virtual-machine.use-rosetta=true",)
    return Fix(
        summary=(
            "Switch Rancher Desktop to VZ with Rosetta"
            if switch
            else "Turn on Rosetta in Rancher Desktop"
        ),
        commands=(*_rosetta_install(probe), (str(host.facts.get("rdctl")), "set", *flags)),
        notes=(
            "Rancher Desktop restarts its VM, so running containers stop. "
            + (
                "Whether it keeps images and volumes when its VM type changes is not "
                "verified, so treat them as at risk."
                if switch
                else "Images, containers and volumes are kept."
            ),
        ),
        deletes_data=switch,
    )


_DOCKER_DESKTOP_STEPS = {
    True: "Update Docker Desktop to 4.44 or later, which moves QEMU users to Apple "
    "Virtualization, or choose 'Apple Virtualization framework' in Settings > General > "
    "Virtual Machine Options, then Apply & restart",
    False: "In Docker Desktop, open Settings > General, turn on 'Use Rosetta for "
    "x86_64/amd64 emulation on Apple Silicon', then Apply & restart",
}


def _vm_finding(
    finding_id: str, message: str, host: DockerHost, probe: HostProbe, *, switch: bool
) -> Finding:
    """A warning whose fix moves the VM to VZ with Rosetta (switch) or turns Rosetta on."""
    message = f"{message} {PREFERENCE}"
    if host.provider == "colima":
        return Finding(finding_id, "warning", message, fix=_colima_fix(host, probe, switch))
    if host.provider == "rancher-desktop":
        return Finding(finding_id, "warning", message, fix=_rancher_fix(host, probe, switch))
    # Docker Desktop has no supported command for this.
    return Finding(finding_id, "warning", message, steps=(_DOCKER_DESKTOP_STEPS[switch],))


def _vm_type(host: DockerHost, probe: HostProbe) -> Finding | None:
    if not host.apple_silicon or host.vm_type != "qemu":
        return None
    return _vm_finding(
        "qemu_vm", f"{host.label} runs a QEMU virtual machine.", host, probe, switch=True
    )


def _rosetta(host: DockerHost, probe: HostProbe) -> Finding | None:
    if not host.apple_silicon or host.vm_type != "vz" or host.rosetta is not False:
        return None
    return _vm_finding(
        "rosetta_off",
        f"{host.label} has Rosetta turned off, so amd64 images run under QEMU emulation.",
        host,
        probe,
        switch=False,
    )


CHECKS: tuple[Callable[[DockerHost, HostProbe], Finding | None], ...] = (
    _docker_available,
    _vm_type,
    _rosetta,
)


def run_checks(host: DockerHost, probe: HostProbe | None = None) -> list[Finding]:
    probe = probe or default_probe()
    findings: list[Finding] = []
    for check in CHECKS:
        try:
            finding = check(host, probe)
        except Exception:
            continue  # A broken check must never change what the command reports.
        if finding is not None:
            findings.append(finding)
    return findings


def _docker_client() -> Any:
    import docker

    try:
        return docker.from_env(use_context=True, timeout=int(_TIMEOUT))
    except TypeError:
        return docker.from_env(timeout=int(_TIMEOUT))


def inspect(
    client: Any | None = None,
    *,
    error: BaseException | str | None = None,
    probe: HostProbe | None = None,
    connect: bool = True,
) -> tuple[DockerHost, list[Finding]]:
    """Describe the Docker host and run every check. Never raises.

    Without a client or an error, it connects to Docker unless ``connect`` is false.
    """
    probe = probe or default_probe()
    if error is None and client is None and connect:
        try:
            client = _docker_client()
        except Exception as exc:
            error = exc
    info = None
    if error is None and client is not None:
        try:
            info = client.info()
        except Exception:
            pass  # Docker answered the connection, so it still counts as reachable.
    try:
        info = info if isinstance(info, Mapping) else None
        host = describe_host(probe, info=info, error=error)
    except Exception:
        host = DockerHost(reachable=error is None)
    return host, run_checks(host, probe)


def docker_unavailable_error(
    error: BaseException, probe: HostProbe | None = None
) -> HostError:
    """``docker_unavailable``, with the reason and what to do about it."""
    probe = probe or default_probe()
    host, findings = inspect(error=error, probe=probe)
    message = "Docker is unavailable; start Docker Desktop, Colima, or the selected Docker context"
    finding = next((item for item in findings if item.id.startswith("docker_")), None)
    if finding is not None:
        message = finding.message
        if finding.fix is None and finding.steps:
            message = f"{message} {finding.steps[0]}."
    return HostError(
        "docker_unavailable",
        message,
        {
            "cause": str(error),
            "docker_host": probe.environ.get("DOCKER_HOST"),
            "reason": host.reason or "unreachable",
            "setup_findings": [item.to_dict() for item in findings],
        },
    )


def _run_streaming(command: Sequence[str]) -> int:
    # Output goes to stderr so stdout keeps only the command's result.
    return subprocess.run(
        list(command), stdout=sys.stderr, stderr=sys.stderr, check=False
    ).returncode


def apply_fix(
    fix: Fix,
    *,
    run: Callable[[Sequence[str]], int] | None = None,
    stream: TextIO | None = None,
) -> dict[str, Any]:
    """Run a confirmed fix's commands in order, stopping at the first failure."""
    run = run or _run_streaming
    out = stream or sys.stderr
    for command in fix.commands:
        print(f"$ {shlex.join(command)}", file=out, flush=True)
        try:
            code = run(command)
        except OSError as error:
            print(f"Could not run {command[0]}: {error}", file=out, flush=True)
            code = 127
        if code != 0:
            return {"status": "failed", "command": shlex.join(command), "exit_code": code}
    return {"status": "applied"}


def localcloud_volumes() -> list[str]:
    """LocalCloud data volumes on the current engine, named before deleting its data."""
    from .docker_runtime import MANAGED_LABEL, VOLUME_NAME_LABEL

    try:
        volumes = _docker_client().volumes.list()
        return sorted(
            {
                volume.name
                for volume in volumes
                if volume.name.startswith("localcloud")
                or {MANAGED_LABEL, VOLUME_NAME_LABEL} & set((volume.attrs or {}).get("Labels") or {})
            }
        )
    except Exception:
        return []

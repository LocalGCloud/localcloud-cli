from __future__ import annotations

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))
THIS_FILE = Path(__file__).resolve()
RETIRED_LOCALCLOUD_PORTS = (
    *range(24_080, 24_096),
    *range(5_365, 5_380),
    24_443,
    24_481,
    24_482,
    24_489,
    25_083,
    29_088,
    9_001,
)

RETIRED_REGEX = re.compile(
    r"(?<!\d)(" + "|".join(map(str, RETIRED_LOCALCLOUD_PORTS)) + r")(?!\d)"
)


def test_retired_ports_do_not_reappear_in_active_cli_authorities() -> None:
    files = [ROOT / "README.md", ROOT / "docs/cli-reference.md"]
    for directory in (ROOT / "src/localcloud_cli", ROOT / "tests", ROOT / "scripts"):
        files.extend(path for path in directory.rglob("*") if path.is_file())

    violations: list[str] = []
    for path in files:
        if path.resolve() == THIS_FILE:
            continue
        if "__pycache__" in path.parts or path.suffix not in {
            ".md",
            ".py",
            ".toml",
            ".yaml",
            ".yml",
        }:
            continue
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if "RETIRED_LOCALCLOUD_PORTS" in line or "53[67]" in line or "2408" in line:
                continue
            match = RETIRED_REGEX.search(line)
            if match is not None:
                violations.append(
                    f"{path.relative_to(ROOT)}:{number}: retired LocalCloud port {match.group(1)}"
                )

    assert violations == []


def test_retired_ports_do_not_reappear_in_localcloud_core() -> None:
    localcloud_dir = ROOT.parent / "localcloud"
    if not localcloud_dir.is_dir():
        return

    files_to_check: list[Path] = [
        localcloud_dir / "localcloud.defaults.yaml",
        localcloud_dir / "start.sh",
        localcloud_dir / "build.sh",
        localcloud_dir / "seed.yaml",
        localcloud_dir / "localcloud-console/dev.js",
        localcloud_dir / "localcloud-console/src/config/ports.js",
    ]
    for d in (localcloud_dir / "docker", localcloud_dir / "scripts"):
        if d.exists():
            files_to_check.extend(p for p in d.rglob("*") if p.is_file())

    violations: list[str] = []
    for path in files_to_check:
        if "__pycache__" in path.parts:
            continue
        if path.suffix not in {
            ".md",
            ".py",
            ".sh",
            ".yaml",
            ".yml",
            ".json",
            ".conf",
            ".js",
        } and not path.name.startswith("Dockerfile"):
            continue
        if "memorystore-retirement-ledger" in path.name:
            continue

        try:
            content = path.read_text(encoding="utf-8")
        except Exception:
            continue

        for number, line in enumerate(content.splitlines(), 1):
            if "RETIRED_LOCALCLOUD_PORTS" in line or "53[67]" in line or "2408" in line:
                continue
            match = RETIRED_REGEX.search(line)
            if match is not None:
                violations.append(
                    f"{path.relative_to(localcloud_dir)}:{number}: retired LocalCloud port {match.group(1)}"
                )

    assert violations == []


def test_retired_ports_do_not_reappear_in_subproject_dependencies() -> None:
    deps_dir = ROOT.parent / "local_cloud_dependencies"
    if not deps_dir.is_dir():
        return

    violations: list[str] = []

    # 1. BigQuery emulator subprojects
    for bq_sub in ("bigquery-emulator-on-duckdb", "bq-lcbq-light", "bq-lcbq-main"):
        sub_root = deps_dir / bq_sub
        if not sub_root.is_dir():
            continue
        for rel in (
            "Dockerfile",
            "src/bigquery_emulator/settings.py",
            "src/bigquery_emulator/main.py",
            "src/bigquery_emulator/engine/continuous.py",
        ):
            target = sub_root / rel
            if not target.is_file():
                continue
            for number, line in enumerate(target.read_text(encoding="utf-8").splitlines(), 1):
                match = RETIRED_REGEX.search(line)
                if match is not None:
                    violations.append(
                        f"{target.relative_to(deps_dir)}:{number}: retired port {match.group(1)}"
                    )

    # 2. Bigtable emulator
    bigtable_main = deps_dir / "little_bigtable/little_bigtable.go"
    if bigtable_main.is_file():
        for number, line in enumerate(bigtable_main.read_text(encoding="utf-8").splitlines(), 1):
            match = RETIRED_REGEX.search(line)
            if match is not None:
                violations.append(
                    f"{bigtable_main.relative_to(deps_dir)}:{number}: retired port {match.group(1)}"
                )

    assert violations == []


def test_canonical_ports_match_between_localcloud_and_cli() -> None:
    docker_runtime_text = (ROOT / "src/localcloud_cli/docker_runtime.py").read_text(encoding="utf-8")
    assert 'GATEWAY_PORT = "5380"' in docker_runtime_text
    assert "_BASE_TCP_PORTS = tuple(range(5380, 5406))" in docker_runtime_text

    v1_yaml = (ROOT / "src/localcloud_cli/defaults/localcloud.v1.yaml").read_text(encoding="utf-8")
    assert "port: 5380" in v1_yaml

    localcloud_dir = ROOT.parent / "localcloud"
    if not localcloud_dir.is_dir():
        return

    defaults_file = localcloud_dir / "localcloud.defaults.yaml"
    text = defaults_file.read_text(encoding="utf-8")
    # Canonical gateway port in localcloud.defaults.yaml must be 5380
    assert "port: 5380" in text

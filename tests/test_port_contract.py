from __future__ import annotations

from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
RETIRED_LOCALCLOUD_PORTS = (
    *range(24_080, 24_096),
    24_443,
    24_481,
    24_482,
    24_489,
    25_083,
    29_088,
    9_001,
)


def test_retired_ports_do_not_reappear_in_active_cli_authorities() -> None:
    retired = re.compile(
        r"(?<!\d)(" + "|".join(map(str, RETIRED_LOCALCLOUD_PORTS)) + r")(?!\d)"
    )
    files = [ROOT / "README.md", ROOT / "docs/cli-reference.md"]
    for directory in (ROOT / "src/localcloud_cli", ROOT / "tests", ROOT / "scripts"):
        files.extend(path for path in directory.rglob("*") if path.is_file())

    violations: list[str] = []
    for path in files:
        if "__pycache__" in path.parts or path.suffix not in {
            ".md",
            ".py",
            ".toml",
            ".yaml",
            ".yml",
        }:
            continue
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            match = retired.search(line)
            if match is not None:
                violations.append(
                    f"{path.relative_to(ROOT)}:{number}: retired LocalCloud port {match.group(1)}"
                )

    assert violations == []

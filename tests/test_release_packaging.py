from __future__ import annotations

import subprocess
import sys
from pathlib import Path


ASSETS = (
    "localcloud-darwin-arm64.tar.gz",
    "localcloud-darwin-amd64.tar.gz",
    "localcloud-linux-arm64.tar.gz",
    "localcloud-linux-amd64.tar.gz",
)


def test_rendered_homebrew_formula_installs_and_tests_lc_alias(tmp_path: Path) -> None:
    checksums = tmp_path / "SHA256SUMS"
    checksums.write_text(
        "".join(f"{'0' * 64}  {asset}\n" for asset in ASSETS),
        encoding="utf-8",
    )
    formula_path = tmp_path / "localcloud.rb"
    project_root = Path(__file__).resolve().parents[1]

    subprocess.run(
        [
            sys.executable,
            "scripts/render-homebrew-formula.py",
            "--version",
            "0.1.0",
            "--checksums",
            str(checksums),
            "--output",
            str(formula_path),
        ],
        cwd=project_root,
        check=True,
    )

    formula = formula_path.read_text(encoding="utf-8")
    assert 'bin.install_symlink bin/"localcloud" => "lc"' in formula
    assert "lc is an alias for localcloud; both commands behave identically." in formula
    assert 'canonical_version = shell_output("#{bin}/localcloud --version")' in formula
    assert (
        'assert_equal canonical_version, shell_output("#{bin}/lc --version")'
        in formula
    )

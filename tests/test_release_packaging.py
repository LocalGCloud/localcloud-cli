from __future__ import annotations

import importlib.util
import subprocess
import sys
import tomllib
from importlib import metadata
from pathlib import Path
from typing import Any

import yaml


PROJECT_ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = PROJECT_ROOT / ".github" / "workflows" / "cli-release.yml"
LICENSE_CHECK = PROJECT_ROOT / "scripts" / "check-third-party-licenses.py"
ASSETS = (
    "localcloud-darwin-arm64.tar.gz",
    "localcloud-darwin-amd64.tar.gz",
    "localcloud-linux-arm64.tar.gz",
    "localcloud-linux-amd64.tar.gz",
)
NOTICES_HEADER = (
    "Package | Version | License | Source\n"
    "------- | ------- | ------- | ------\n"
)


def _license_check() -> Any:
    spec = importlib.util.spec_from_file_location("check_third_party_licenses", LICENSE_CHECK)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _installed(site_packages: Path, name: str, *files: str) -> metadata.Distribution:
    info = site_packages / f"{name}-1.dist-info"
    for file in files:
        (info / file).parent.mkdir(parents=True, exist_ok=True)
        (info / file).write_text(f"{file}\n", encoding="utf-8")
    (info / "RECORD").write_text(
        "".join(f"{info.name}/{file},,\n" for file in files), encoding="utf-8"
    )
    return metadata.Distribution.at(info)


def _workflow_steps(job: str) -> list[dict[str, Any]]:
    workflow = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
    return workflow["jobs"][job]["steps"]


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
    assert 'libexec.install "localcloud", "localcloud-runtime"' in formula
    assert 'bin.write_exec_script libexec/"localcloud"' in formula
    assert 'bin.install_symlink bin/"localcloud" => "lc"' in formula
    assert "lc is an alias for localcloud; both commands behave identically." in formula
    assert 'canonical_version = shell_output("#{bin}/localcloud --version")' in formula
    assert (
        "/^localcloud #{Regexp.escape(version.to_s)} "
        r"\(commit [0-9a-f]{12}, released \d{4}-\d{2}-\d{2}\)\n$/"
        in formula
    )
    assert (
        'assert_equal canonical_version, shell_output("#{bin}/lc --version")'
        in formula
    )


def test_every_locked_runtime_distribution_has_a_license_file_to_ship() -> None:
    check = _license_check()
    with (PROJECT_ROOT / "third-party-licenses.toml").open("rb") as catalog_file:
        runtime = tomllib.load(catalog_file)["runtime"]

    components = check.notice_components(PROJECT_ROOT / "THIRD_PARTY_NOTICES")

    assert "pyyaml" in runtime and set(runtime) <= set(components)
    assert [name for name in runtime if not check.license_files(name)] == []


def test_license_check_ships_license_files_and_no_other_metadata(
    tmp_path: Path, monkeypatch: Any
) -> None:
    check = _license_check()
    site_packages = tmp_path / "site-packages"
    installed = {
        "modern": _installed(
            site_packages,
            "modern",
            "METADATA",
            "licenses/LICENSE",
            "licenses/vendored/NOTICE",
            "sboms/sbom.json",
        ),
        "legacy": _installed(
            site_packages, "legacy", "METADATA", "COPYING.txt", "entry_points.txt"
        ),
    }
    monkeypatch.setattr(check.metadata, "distribution", installed.__getitem__)
    monkeypatch.setattr(check.sysconfig, "get_path", lambda name: str(tmp_path / name))
    (tmp_path / "stdlib").mkdir()
    (tmp_path / "stdlib" / "LICENSE.txt").write_text("license\n", encoding="utf-8")
    notices = tmp_path / "THIRD_PARTY_NOTICES"
    notices.write_text(
        "Bundled runtime\n---------------\n"
        + NOTICES_HEADER
        + "CPython | 3.11.x | PSF-2.0 | https://www.python.org/\n"
        "modern | 1 | MIT | https://example.invalid/modern\n"
        "\nBuild components incorporated into the executable\n"
        + NOTICES_HEADER
        + "legacy | 1 | GPL-2.0-or-later | https://example.invalid/legacy\n",
        encoding="utf-8",
    )

    assert check.license_datas(notices) == [
        (str(tmp_path / "stdlib" / "LICENSE.txt"), "third_party_licenses/CPython"),
        (
            str(site_packages / "modern-1.dist-info" / "licenses" / "LICENSE"),
            "third_party_licenses/modern",
        ),
        (
            str(site_packages / "modern-1.dist-info" / "licenses" / "vendored" / "NOTICE"),
            "third_party_licenses/modern/vendored",
        ),
        (
            str(site_packages / "legacy-1.dist-info" / "COPYING.txt"),
            "third_party_licenses/legacy",
        ),
    ]


def test_license_check_fails_for_rows_without_a_license_file(
    tmp_path: Path, monkeypatch: Any, capsys: Any
) -> None:
    check = _license_check()
    installed = {"bare": _installed(tmp_path / "site-packages", "bare", "METADATA")}

    def distribution(name: str) -> metadata.Distribution:
        if name not in installed:
            raise metadata.PackageNotFoundError(name)
        return installed[name]

    monkeypatch.setattr(check.metadata, "distribution", distribution)
    monkeypatch.setattr(check.sysconfig, "get_path", lambda name: str(tmp_path / name))
    notices = tmp_path / "THIRD_PARTY_NOTICES"
    notices.write_text(
        NOTICES_HEADER
        + "CPython | 3.11.x | PSF-2.0 | https://www.python.org/\n"
        "bare | 1 | MIT | https://example.invalid/bare\n"
        "absent | 1 | MIT | https://example.invalid/absent\n",
        encoding="utf-8",
    )
    monkeypatch.setattr(sys, "argv", [str(LICENSE_CHECK), "--notices", str(notices)])

    assert check.main() == 1
    assert capsys.readouterr().err == (
        "error: no license file to ship for: CPython, bare, absent\n"
    )

    notices.write_text(NOTICES_HEADER, encoding="utf-8")
    assert check.main() == 1
    assert capsys.readouterr().err == f"error: {notices} lists no components\n"


def test_release_workflow_checks_license_files_with_the_notices() -> None:
    verify = next(
        step
        for step in _workflow_steps("validate-release")
        if step.get("name") == "Verify generated notices"
    )

    assert "scripts/generate-third-party-notices.py" in verify["run"]
    assert (
        "uv run --frozen --extra release python scripts/check-third-party-licenses.py"
        in verify["run"]
    )


def test_release_workflow_checks_the_mcp_handshake_of_each_frozen_binary() -> None:
    steps = _workflow_steps("build-native")
    names = [step.get("name") for step in steps]
    handshake = steps[names.index("Smoke frozen executable") + 1]

    assert handshake["name"] == "Verify frozen MCP handshake"
    assert handshake["run"].split() == [
        *"uv run --frozen python scripts/check-mcp-handshake.py".split(),
        "dist/localcloud",
        "--timeout",
        "30",
    ]
    # A binary that hangs outside the script's own timeout still fails the job.
    assert handshake["timeout-minutes"] <= 5

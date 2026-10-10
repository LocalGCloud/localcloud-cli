from __future__ import annotations

import json
import runpy
import sys
import sysconfig
import types
from importlib import metadata
from pathlib import Path
from typing import Any

import pytest


PROJECT_ROOT = Path(__file__).resolve().parents[1]


@pytest.fixture(autouse=True)
def license_environment(monkeypatch: Any, tmp_path: Path) -> Path:
    """An interpreter and site-packages where every distribution has one license file."""
    root = tmp_path / "license-environment"
    (root / "stdlib").mkdir(parents=True)
    (root / "stdlib" / "LICENSE.txt").write_text("interpreter license\n", encoding="utf-8")

    def distribution(name: str) -> metadata.Distribution:
        info = root / "site-packages" / f"{name}-1.dist-info"
        (info / "licenses").mkdir(parents=True, exist_ok=True)
        (info / "licenses" / "LICENSE").write_text("license\n", encoding="utf-8")
        (info / "RECORD").write_text(
            f"{info.name}/licenses/LICENSE,,\n{info.name}/RECORD,,\n", encoding="utf-8"
        )
        return metadata.Distribution.at(info)

    stdlib_path = sysconfig.get_path
    monkeypatch.setattr(metadata, "distribution", distribution)
    monkeypatch.setattr(
        sysconfig,
        "get_path",
        lambda name, *args, **kwargs: str(root / "stdlib")
        if name == "stdlib"
        else stdlib_path(name, *args, **kwargs),
    )
    return root


def _notice_components() -> list[str]:
    notices = (PROJECT_ROOT / "THIRD_PARTY_NOTICES").read_text(encoding="utf-8")
    return [
        line.split(" | ")[0]
        for line in notices.splitlines()
        if line.count(" | ") == 3 and not line.startswith(("Package | ", "------- | "))
    ]


def test_spec_trims_build_only_payloads(
    monkeypatch: Any, tmp_path: Path, license_environment: Path
) -> None:
    calls: dict[str, Any] = {}
    release_metadata_path = tmp_path / "build" / "_release.json"
    collected_datas = [("mcp/source.py", "mcp")]
    collected_binaries = [("mcp/native.dylib", "mcp")]
    collected_hiddenimports = ["mcp.server.stdio"]
    localcloud_metadata = [("localcloud_cli-0.1.1.dist-info", ".")]

    def collect_all(package: str, **kwargs: Any) -> tuple[list[Any], list[Any], list[str]]:
        calls["collect_all"] = (package, kwargs)
        return collected_datas, collected_binaries, collected_hiddenimports

    def copy_metadata(distribution: str, **kwargs: Any) -> list[Any]:
        calls["copy_metadata"] = (distribution, kwargs)
        return list(localcloud_metadata)

    hooks = types.ModuleType("PyInstaller.utils.hooks")
    hooks.collect_all = collect_all  # type: ignore[attr-defined]
    hooks.copy_metadata = copy_metadata  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "PyInstaller", types.ModuleType("PyInstaller"))
    monkeypatch.setitem(sys.modules, "PyInstaller.utils", types.ModuleType("PyInstaller.utils"))
    monkeypatch.setitem(sys.modules, "PyInstaller.utils.hooks", hooks)
    monkeypatch.setenv("LOCALCLOUD_RELEASE_COMMIT", "0123456789ab")
    monkeypatch.setenv("LOCALCLOUD_RELEASE_DATE", "2026-08-31")

    class AnalysisResult:
        pure: list[Any] = []
        scripts: list[Any] = []
        binaries: list[Any] = []
        datas: list[Any] = []

    def analysis(*args: Any, **kwargs: Any) -> AnalysisResult:
        calls["analysis"] = (args, kwargs)
        return AnalysisResult()

    runpy.run_path(
        str(PROJECT_ROOT / "localcloud.spec"),
        init_globals={
            "SPECPATH": str(PROJECT_ROOT),
            "workpath": str(release_metadata_path.parent),
            "Analysis": analysis,
            "PYZ": lambda *args, **kwargs: object(),
            "EXE": lambda *args, **kwargs: object(),
            "COLLECT": lambda *args, **kwargs: object(),
        },
    )

    package, collect_kwargs = calls["collect_all"]
    assert package == "mcp"
    module_filter = collect_kwargs["filter_submodules"]
    accepted_modules = (
        "mcp",
        "mcp.server",
        "mcp.server.stdio",
        "mcp.server.stdio.transport",
        "mcp.shared",
        "mcp.shared.message",
        "mcp.shared.message.session",
        "mcp.types",
        "mcp.types.utilities",
    )
    rejected_modules = (
        "mcp.client",
        "mcp.server.auth",
        "mcp.serverx",
        "mcp.shared.auth",
        "mcp.sharedx",
        "mcp.type",
        "other",
    )
    assert all(module_filter(name) for name in accepted_modules)
    assert not any(module_filter(name) for name in rejected_modules)

    assert calls["copy_metadata"] == ("localcloud-cli", {"recursive": False})

    _, analysis_kwargs = calls["analysis"]
    assert analysis_kwargs["binaries"] == []
    defaults = PROJECT_ROOT / "src" / "localcloud_cli" / "defaults"
    own_datas = localcloud_metadata + [
        (str(defaults / "localcloud.v1.yaml"), "localcloud_cli/defaults"),
        (str(defaults / "localcloud.v1.json"), "localcloud_cli/defaults"),
        (str(release_metadata_path), "localcloud_cli"),
    ]
    assert analysis_kwargs["datas"][: len(own_datas)] == own_datas
    # Third-party distributions contribute their license files, not their metadata.
    components = _notice_components()
    assert {"CPython", "pyyaml", "pyinstaller"} <= set(components)
    site_packages = license_environment / "site-packages"
    assert analysis_kwargs["datas"][len(own_datas) :] == [
        (
            str(license_environment / "stdlib" / "LICENSE.txt"),
            "third_party_licenses/CPython",
        )
        if component == "CPython"
        else (
            str(site_packages / f"{component}-1.dist-info" / "licenses" / "LICENSE"),
            f"third_party_licenses/{component}",
        )
        for component in components
    ]
    # The frozen bundle carries every packaged default, as the wheel does.
    assert {
        source
        for source, target in analysis_kwargs["datas"]
        if target == "localcloud_cli/defaults"
    } == {str(path) for path in defaults.iterdir() if path.is_file()}
    assert json.loads(release_metadata_path.read_text(encoding="utf-8")) == {
        "commit": "0123456789ab",
        "release_date": "2026-08-31",
    }
    assert analysis_kwargs["hiddenimports"] == collected_hiddenimports
    assert analysis_kwargs["excludes"] == [
        "setuptools",
        "distutils",
        "_distutils_hack",
    ]


def test_spec_autodiscovers_git_metadata_when_env_vars_missing(
    monkeypatch: Any, tmp_path: Path
) -> None:
    release_metadata_path = tmp_path / "build" / "_release.json"
    hooks = types.ModuleType("PyInstaller.utils.hooks")
    hooks.collect_all = lambda *a, **kw: ([], [], [])  # type: ignore[attr-defined]
    hooks.copy_metadata = lambda *a, **kw: []  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "PyInstaller", types.ModuleType("PyInstaller"))
    monkeypatch.setitem(sys.modules, "PyInstaller.utils", types.ModuleType("PyInstaller.utils"))
    monkeypatch.setitem(sys.modules, "PyInstaller.utils.hooks", hooks)
    monkeypatch.delenv("LOCALCLOUD_RELEASE_COMMIT", raising=False)
    monkeypatch.delenv("LOCALCLOUD_RELEASE_DATE", raising=False)

    class AnalysisResult:
        pure: list[Any] = []
        scripts: list[Any] = []
        binaries: list[Any] = []
        datas: list[Any] = []

    runpy.run_path(
        str(PROJECT_ROOT / "localcloud.spec"),
        init_globals={
            "SPECPATH": str(PROJECT_ROOT),
            "workpath": str(release_metadata_path.parent),
            "Analysis": lambda *a, **kw: AnalysisResult(),
            "PYZ": lambda *args, **kwargs: object(),
            "EXE": lambda *args, **kwargs: object(),
            "COLLECT": lambda *args, **kwargs: object(),
        },
    )

    data = json.loads(release_metadata_path.read_text(encoding="utf-8"))
    import re
    assert re.fullmatch(r"[0-9a-f]{12}", data["commit"]) is not None
    assert re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", data["release_date"]) is not None


def test_spec_falls_back_to_empty_when_git_fails(
    monkeypatch: Any, tmp_path: Path
) -> None:
    import subprocess
    release_metadata_path = tmp_path / "build" / "_release.json"
    hooks = types.ModuleType("PyInstaller.utils.hooks")
    hooks.collect_all = lambda *a, **kw: ([], [], [])  # type: ignore[attr-defined]
    hooks.copy_metadata = lambda *a, **kw: []  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "PyInstaller", types.ModuleType("PyInstaller"))
    monkeypatch.setitem(sys.modules, "PyInstaller.utils", types.ModuleType("PyInstaller.utils"))
    monkeypatch.setitem(sys.modules, "PyInstaller.utils.hooks", hooks)
    monkeypatch.delenv("LOCALCLOUD_RELEASE_COMMIT", raising=False)
    monkeypatch.delenv("LOCALCLOUD_RELEASE_DATE", raising=False)

    def failing_subprocess_run(*args: Any, **kwargs: Any) -> Any:
        raise FileNotFoundError("git not found")

    monkeypatch.setattr(subprocess, "run", failing_subprocess_run)

    class AnalysisResult:
        pure: list[Any] = []
        scripts: list[Any] = []
        binaries: list[Any] = []
        datas: list[Any] = []

    runpy.run_path(
        str(PROJECT_ROOT / "localcloud.spec"),
        init_globals={
            "SPECPATH": str(PROJECT_ROOT),
            "workpath": str(release_metadata_path.parent),
            "Analysis": lambda *a, **kw: AnalysisResult(),
            "PYZ": lambda *args, **kwargs: object(),
            "EXE": lambda *args, **kwargs: object(),
            "COLLECT": lambda *args, **kwargs: object(),
        },
    )

    data = json.loads(release_metadata_path.read_text(encoding="utf-8"))
    assert data == {}


def test_spec_refuses_a_notices_row_without_a_license_file(
    monkeypatch: Any, tmp_path: Path
) -> None:
    hooks = types.ModuleType("PyInstaller.utils.hooks")
    hooks.collect_all = lambda *a, **kw: ([], [], [])  # type: ignore[attr-defined]
    hooks.copy_metadata = lambda *a, **kw: []  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "PyInstaller", types.ModuleType("PyInstaller"))
    monkeypatch.setitem(sys.modules, "PyInstaller.utils", types.ModuleType("PyInstaller.utils"))
    monkeypatch.setitem(sys.modules, "PyInstaller.utils.hooks", hooks)
    with_license = metadata.distribution

    def distribution(name: str) -> metadata.Distribution:
        if name == "pyyaml":
            raise metadata.PackageNotFoundError(name)
        return with_license(name)

    monkeypatch.setattr(metadata, "distribution", distribution)
    analysed: list[Any] = []

    with pytest.raises(RuntimeError, match="no license file to ship for: pyyaml$"):
        runpy.run_path(
            str(PROJECT_ROOT / "localcloud.spec"),
            init_globals={
                "SPECPATH": str(PROJECT_ROOT),
                "workpath": str(tmp_path / "build"),
                "Analysis": lambda *a, **kw: analysed.append(kw),
                "PYZ": lambda *args, **kwargs: object(),
                "EXE": lambda *args, **kwargs: object(),
                "COLLECT": lambda *args, **kwargs: object(),
            },
        )

    assert analysed == []


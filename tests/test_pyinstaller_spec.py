from __future__ import annotations

import runpy
import sys
import types
from pathlib import Path
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[1]


def test_spec_trims_build_only_payloads(monkeypatch: Any) -> None:
    calls: dict[str, Any] = {}
    collected_datas = [("mcp/source.py", "mcp")]
    collected_binaries = [("mcp/native.dylib", "mcp")]
    collected_hiddenimports = ["mcp.server.stdio"]
    localcloud_metadata = [("localcloud_cli-0.1.1.dist-info", ".")]

    def collect_all(package: str, **kwargs: Any) -> tuple[list[Any], list[Any], list[str]]:
        calls["collect_all"] = (package, kwargs)
        return collected_datas, collected_binaries, collected_hiddenimports

    def copy_metadata(distribution: str, **kwargs: Any) -> list[Any]:
        calls["copy_metadata"] = (distribution, kwargs)
        return localcloud_metadata

    hooks = types.ModuleType("PyInstaller.utils.hooks")
    hooks.collect_all = collect_all  # type: ignore[attr-defined]
    hooks.copy_metadata = copy_metadata  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "PyInstaller", types.ModuleType("PyInstaller"))
    monkeypatch.setitem(sys.modules, "PyInstaller.utils", types.ModuleType("PyInstaller.utils"))
    monkeypatch.setitem(sys.modules, "PyInstaller.utils.hooks", hooks)

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
            "Analysis": analysis,
            "PYZ": lambda *args, **kwargs: object(),
            "EXE": lambda *args, **kwargs: object(),
            "COLLECT": lambda *args, **kwargs: object(),
        },
    )

    package, collect_kwargs = calls["collect_all"]
    assert package == "mcp"
    module_filter = collect_kwargs["filter_submodules"]
    assert module_filter("mcp.server.stdio")
    assert not module_filter("mcp.client")

    assert calls["copy_metadata"] == ("localcloud-cli", {"recursive": False})

    _, analysis_kwargs = calls["analysis"]
    assert analysis_kwargs["binaries"] == []
    assert analysis_kwargs["datas"] == localcloud_metadata
    assert analysis_kwargs["hiddenimports"] == collected_hiddenimports
    assert analysis_kwargs["excludes"] == [
        "setuptools",
        "distutils",
        "_distutils_hack",
    ]

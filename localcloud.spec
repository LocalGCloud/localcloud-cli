# -*- mode: python ; coding: utf-8 -*-

from pathlib import Path

from PyInstaller.utils.hooks import collect_all, copy_metadata


project_root = Path(SPECPATH).resolve()

def required_mcp_module(name):
    return (
        name == "mcp"
        or name == "mcp.server"
        or name.startswith("mcp.server.stdio")
        or name == "mcp.shared"
        or name.startswith("mcp.shared.message")
        or name.startswith("mcp.types")
    )


_mcp_datas, _mcp_binaries, mcp_hiddenimports = collect_all(
    "mcp",
    filter_submodules=required_mcp_module,
)
datas = copy_metadata("localcloud-cli", recursive=False)
datas += [
    (
        str(project_root / "src" / "localcloud_cli" / "defaults" / "localcloud.v1.yaml"),
        "localcloud_cli/defaults",
    )
]

analysis = Analysis(
    [str(project_root / "src" / "localcloud_cli" / "__main__.py")],
    pathex=[str(project_root / "src")],
    binaries=[],
    datas=datas,
    hiddenimports=mcp_hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=["setuptools", "distutils", "_distutils_hack"],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(analysis.pure)

executable = EXE(
    pyz,
    analysis.scripts,
    [],
    exclude_binaries=True,
    name="localcloud",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
bundle = COLLECT(
    executable,
    analysis.binaries,
    analysis.datas,
    strip=False,
    upx=True,
    upx_exclude=[],
    name="localcloud-runtime",
)

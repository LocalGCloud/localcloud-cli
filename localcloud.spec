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


mcp_datas, mcp_binaries, mcp_hiddenimports = collect_all(
    "mcp",
    filter_submodules=required_mcp_module,
)
datas = mcp_datas + copy_metadata("localcloud-cli", recursive=True)

analysis = Analysis(
    [str(project_root / "src" / "localcloud_cli" / "__main__.py")],
    pathex=[str(project_root / "src")],
    binaries=mcp_binaries,
    datas=datas,
    hiddenimports=mcp_hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(analysis.pure)

executable = EXE(
    pyz,
    analysis.scripts,
    analysis.binaries,
    analysis.datas,
    [],
    name="localcloud",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)

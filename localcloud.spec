# -*- mode: python ; coding: utf-8 -*-

from datetime import date
import json
import os
from pathlib import Path
import re

from PyInstaller.utils.hooks import collect_all, copy_metadata


project_root = Path(SPECPATH).resolve()


release_commit = os.environ.get("LOCALCLOUD_RELEASE_COMMIT")
release_date = os.environ.get("LOCALCLOUD_RELEASE_DATE")
if (release_commit is None) != (release_date is None):
    raise ValueError(
        "LOCALCLOUD_RELEASE_COMMIT and LOCALCLOUD_RELEASE_DATE must be set together"
    )

release_metadata = {}
if release_commit is not None and release_date is not None:
    if re.fullmatch(r"[0-9a-f]{12}", release_commit) is None:
        raise ValueError(
            "LOCALCLOUD_RELEASE_COMMIT must be a 12-character lowercase SHA"
        )
    try:
        parsed_release_date = date.fromisoformat(release_date)
    except ValueError as error:
        raise ValueError(
            "LOCALCLOUD_RELEASE_DATE must have the form YYYY-MM-DD"
        ) from error
    if parsed_release_date.isoformat() != release_date:
        raise ValueError("LOCALCLOUD_RELEASE_DATE must have the form YYYY-MM-DD")
    release_metadata = {
        "commit": release_commit,
        "release_date": release_date,
    }

release_metadata_path = Path(workpath) / "_release.json"
release_metadata_path.parent.mkdir(parents=True, exist_ok=True)
release_metadata_path.write_text(
    json.dumps(release_metadata, sort_keys=True) + "\n",
    encoding="utf-8",
)

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
    ),
    (str(release_metadata_path), "localcloud_cli"),
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

"""Export only the public LocalCloud plugin files, with reproducible ZIP metadata."""
from __future__ import annotations

import argparse
import hashlib
import json
import stat
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

FILES = (
    ".claude-plugin/plugin.json", ".mcp.json", "plugin.json", "mcp.json",
    "README.md", "LICENSE", "assets/icon.png",
    "scripts/launch-localcloud.sh", "skills/localcloud/SKILL.md",
)
LAUNCHER = "scripts/launch-localcloud.sh"
ROOT = Path(__file__).resolve().parents[1]


def build_package(plugin: Path, output: Path) -> dict:
    plugin = plugin.resolve()
    expected = set(FILES)
    entries = list(plugin.rglob("*"))
    if any(p.is_symlink() for p in entries):
        raise ValueError("Plugin symlinks are not permitted")
    actual = {p.relative_to(plugin).as_posix() for p in entries if p.is_file()}
    if actual != expected:
        raise ValueError(f"Plugin contents differ from allowlist: {sorted(actual ^ expected)}")
    payload = {}
    for name in FILES:
        path = plugin / name
        if not path.resolve().is_relative_to(plugin):
            raise ValueError(f"Plugin path escapes its root: {name}")
        payload[name] = path.read_bytes()
        if len(payload[name]) >= 5 * 1024 * 1024:
            raise ValueError(f"Plugin file exceeds directory limit: {name}")
    version = json.loads(payload["plugin.json"])["version"]
    if json.loads(payload[".claude-plugin/plugin.json"])["version"] != version:
        raise ValueError("Plugin manifest versions differ")
    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output, "w", compression=ZIP_DEFLATED) as archive:
        for name in sorted(FILES):
            info = ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            mode = 0o755 if name == LAUNCHER else 0o644
            info.external_attr = (stat.S_IFREG | mode) << 16
            info.compress_type = ZIP_DEFLATED
            archive.writestr(info, payload[name])
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix(output.suffix + ".sha256").write_text(
        f"{digest}  {output.name}\n", encoding="utf-8"
    )
    return {"version": version, "asset": output.name, "sha256": digest, "files": sorted(FILES)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "dist/localcloud-plugin.zip")
    args = parser.parse_args()
    print(json.dumps(build_package(ROOT / "plugins/localcloud", args.output), indent=2))


if __name__ == "__main__":
    main()

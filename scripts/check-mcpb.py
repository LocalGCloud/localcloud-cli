#!/usr/bin/env python3
"""Check the exact MCPB bytes before publishing or using a registry bundle.

Requires the same pinned MCPB CLI as build-mcpb.py for official schema validation.
Does not execute bundled code or start Docker.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import tempfile
import zipfile


PLATFORMS = ("darwin-arm64", "linux-arm64", "linux-amd64")
ROOT_FILES = {"manifest.json", "launcher.sh", "icon.png", "README.md", "LICENSE", "THIRD_PARTY_NOTICES"}


def check_bundle(archive: Path, version: str, sha256: str) -> dict[str, object]:
    if re.fullmatch(r"[0-9a-f]{64}", sha256) is None:
        raise ValueError("expected SHA-256 must be 64 lowercase hexadecimal characters")
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    if digest != sha256:
        raise ValueError("MCPB SHA-256 mismatch")
    with zipfile.ZipFile(archive) as bundle, tempfile.TemporaryDirectory(prefix="localcloud-mcpb-check-") as temp:
        members = bundle.infolist()
        names = [member.filename for member in members]
        if len(names) != len(set(names)):
            raise ValueError("duplicate MCPB member")
        for member in members:
            path = PurePosixPath(member.filename)
            mode = member.external_attr >> 16
            if (path.is_absolute() or ".." in path.parts or "\\" in member.filename
                    or member.filename != path.as_posix() or stat.S_ISLNK(mode)
                    or (stat.S_IFMT(mode) not in (0, stat.S_IFREG))):
                raise ValueError("unsafe MCPB member")
            parts = path.parts
            allowed = member.filename in ROOT_FILES
            if len(parts) >= 3 and parts[0] == "bin" and parts[1] in PLATFORMS:
                allowed = (len(parts) == 3 and parts[2] in {"localcloud", "LICENSE", "THIRD_PARTY_NOTICES"}
                           or parts[2:] == ("localcloud-runtime", "localcloud")
                           or len(parts) > 4 and parts[2:4] == ("localcloud-runtime", "_internal"))
            if (not allowed or any(part.startswith(".") for part in parts)
                    or path.suffix.lower() in {".go", ".java", ".class", ".jar", ".py", ".pyc",
                                              ".c", ".cpp", ".h", ".rs", ".key", ".log"}):
                raise ValueError("unexpected source or payload in MCPB")
            # Certificate trust stores are expected; private keys are never payload.
            if path.suffix.lower() == ".pem" and re.search(
                rb"-----BEGIN [^-]*PRIVATE KEY-----", bundle.read(member)
            ):
                raise ValueError("private key in MCPB")
        required = set(ROOT_FILES)
        for platform in PLATFORMS:
            prefix = f"bin/{platform}/"
            required.update(prefix + name for name in (
                "localcloud", "localcloud-runtime/localcloud", "LICENSE", "THIRD_PARTY_NOTICES"
            ))
        if not required <= set(names):
            raise ValueError("incomplete MCPB payload")
        for name in ["launcher.sh", *(f"bin/{p}/{n}" for p in PLATFORMS
                                       for n in ("localcloud", "localcloud-runtime/localcloud"))]:
            if not (bundle.getinfo(name).external_attr >> 16) & 0o111:
                raise ValueError("MCPB launcher is not executable")
        manifest = json.loads(bundle.read("manifest.json"))
        if not isinstance(manifest, dict) or manifest.get("version") != version:
            raise ValueError("MCPB manifest version mismatch")
        if manifest.get("server") != {
            "type": "binary", "entry_point": "launcher.sh",
            "mcp_config": {"command": "${__dirname}/launcher.sh", "args": []},
        }:
            raise ValueError("MCPB does not launch the bundled stdio bridge")
        # Reading every entry during extraction also checks its CRC.
        bundle.extractall(temp)
        subprocess.run(["npx", "--yes", "@anthropic-ai/mcpb@2.1.2", "validate",
                        str(Path(temp) / "manifest.json")], check=True)
    return {"asset": archive.name, "version": version, "sha256": digest,
            "bytes": archive.stat().st_size, "members": len(members),
            "expanded_bytes": sum(member.file_size for member in members)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--sha256", required=True, help="Expected published fileSha256 or release checksum")
    args = parser.parse_args()
    try:
        result = check_bundle(args.archive, args.version, args.sha256)
    except (ValueError, OSError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        parser.exit(1, f"MCPB validation failed: {error}\n")
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()

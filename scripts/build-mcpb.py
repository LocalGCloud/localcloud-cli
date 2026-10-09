#!/usr/bin/env python3
"""Bundle the four published CLI archives as one local MCP desktop extension."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
from urllib.request import urlopen


PLATFORMS = ("darwin-arm64", "darwin-amd64", "linux-arm64", "linux-amd64")
ROOT = Path(__file__).resolve().parents[1]
LAUNCHER = """#!/bin/sh
set -eu
case "$(uname -s)/$(uname -m)" in
  Darwin/arm64) platform=darwin-arm64 ;;
  Darwin/x86_64) platform=darwin-amd64 ;;
  Linux/aarch64|Linux/arm64) platform=linux-arm64 ;;
  Linux/x86_64) platform=linux-amd64 ;;
  *) echo 'LocalCloud MCP supports macOS and Linux on ARM64 or x86_64.' >&2; exit 1 ;;
esac
bundle_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
exec "$bundle_dir/bin/$platform/localcloud" mcp "$@"
"""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version", help="Published CLI version, for example 0.1.9")
    parser.add_argument("--output", type=Path, default=Path("dist/mcp"))
    args = parser.parse_args()
    version = args.version
    parts = version.split(".")
    if len(parts) != 3 or not all(part.isdigit() for part in parts):
        parser.error("version must be a published X.Y.Z CLI version")
    if tuple(map(int, parts)) < (0, 1, 9):
        parser.error("the MCP bundle requires CLI 0.1.9 or newer")
    args.output.mkdir(parents=True, exist_ok=True)
    output = args.output.resolve() / f"localcloud-mcp-{version}.mcpb"
    base = f"https://github.com/LocalGCloud/localcloud-cli/releases/download/v{version}/"
    with urlopen(base + "SHA256SUMS", timeout=60) as response:
        sums = dict((name, digest) for digest, name in
                    (line.split() for line in response.read().decode().splitlines() if line.strip()))
    with tempfile.TemporaryDirectory(prefix="localcloud-mcpb-") as temp:
        bundle = Path(temp) / "bundle"
        bundle.mkdir()
        for platform in PLATFORMS:
            name = f"localcloud-{platform}.tar.gz"
            archive = Path(temp) / name
            with urlopen(base + name, timeout=60) as response, archive.open("wb") as target:
                shutil.copyfileobj(response, target)
            if hashlib.sha256(archive.read_bytes()).hexdigest() != sums[name]:
                raise ValueError(f"Release checksum mismatch: {name}")
            destination = bundle / "bin" / platform
            destination.mkdir(parents=True)
            with tarfile.open(archive) as tar:
                tar.extractall(destination, filter="data")
            if not (destination / "localcloud").is_file():
                raise ValueError(f"Archive has no CLI launcher: {name}")
        (bundle / "launcher.sh").write_text(LAUNCHER)
        (bundle / "launcher.sh").chmod(0o755)
        manifest = {
            "manifest_version": "0.3", "name": "localcloud-mcp", "display_name": "LocalCloud MCP",
            "version": version,
            "description": "A free local cloud environment for AI agents to build, test, and debug Google Cloud applications.",
            "long_description": "Includes LocalCloud CLI " + version +
                ". Requires macOS 13+ or Linux glibc 2.35+, and running Docker. "
                "The MCP bridge starts or reuses your local cloud runtime. "
                "Use runtime 0.1.5 or newer for strict-client tool schema validation. "
                "Website: https://local.cloud/ Guide: https://local.cloud/docs/mcp/",
            "author": {"name": "LocalCloud", "url": "https://local.cloud/"},
            "homepage": "https://local.cloud/", "documentation": "https://local.cloud/docs/mcp/",
            "support": "https://github.com/LocalGCloud/localcloud-cli/issues",
            "repository": {"type": "git", "url": "https://github.com/LocalGCloud/localcloud-cli"},
            "icon": "icon.png",
            "server": {"type": "binary", "entry_point": "launcher.sh",
                       "mcp_config": {"command": "${__dirname}/launcher.sh", "args": []}},
            "compatibility": {"platforms": ["darwin", "linux"]},
            "tools_generated": True,
            "keywords": ["localcloud", "google-cloud", "coding-agents", "integration-testing"],
            "privacy_policies": ["https://local.cloud/docs/privacy/"],
        }
        (bundle / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        shutil.copy2(ROOT / "docs/assets/localcloud-mcp-icon.png", bundle / "icon.png")
        (bundle / "README.md").write_text(
            f"# LocalCloud MCP\n\nA free local cloud environment for AI coding agents.\n\n"
            f"This bundle includes LocalCloud CLI {version} for macOS 13+ and Linux glibc 2.35+ on ARM64/x86_64. "
            "Docker must be running. Your MCP client starts the bridge, which starts or reuses "
            "the LocalCloud runtime on your machine. Runtime 0.1.5+ is required for strict-client "
            "tool schema validation; CLI and runtime versions are independent.\n\n"
            "Discover services, obtain local SDK endpoints, inspect resources, and diagnose "
            "integration tests. Use test-owned resources and explicit cleanup. Runtime settings "
            "control management writes and destructive operations. Do not fall back to real GCP.\n\n"
            "Website: https://local.cloud/\n\nGuide: https://local.cloud/docs/mcp/\n\n"
            "Support: https://github.com/LocalGCloud/localcloud-cli/issues\n\n"
            "See the included LICENSE and THIRD_PARTY_NOTICES for the bundled CLI's terms.\n")
        for name in ("LICENSE", "THIRD_PARTY_NOTICES"):
            shutil.copy2(bundle / "bin" / "darwin-arm64" / name, bundle / name)
        subprocess.run(["npx", "--yes", "@anthropic-ai/mcpb@2.1.2", "pack", str(bundle), str(output)], check=True)
    print(json.dumps({"path": str(output), "sha256": hashlib.sha256(output.read_bytes()).hexdigest()}))


if __name__ == "__main__":
    main()

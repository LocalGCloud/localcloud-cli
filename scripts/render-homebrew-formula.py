#!/usr/bin/env python3
"""Render the official LocalCloud Homebrew formula from release checksums."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


ASSETS = {
    "darwin_arm64": "localcloud-darwin-arm64.tar.gz",
    "darwin_amd64": "localcloud-darwin-amd64.tar.gz",
    "linux_arm64": "localcloud-linux-arm64.tar.gz",
    "linux_amd64": "localcloud-linux-amd64.tar.gz",
}
VERSION_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
CHECKSUM_PATTERN = re.compile(r"^([0-9a-f]{64})[ \t]+\*?([^ \t]+)$")


def _checksums(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line_number, raw_line in enumerate(
        path.read_text(encoding="utf-8").splitlines(), start=1
    ):
        line = raw_line.strip()
        if not line:
            continue
        match = CHECKSUM_PATTERN.fullmatch(line)
        if match is None:
            raise ValueError(f"invalid checksum line {line_number}")
        digest, name = match.groups()
        if name in values:
            raise ValueError(f"duplicate checksum for {name!r}")
        values[name] = digest

    expected = set(ASSETS.values())
    missing = sorted(expected - values.keys())
    unexpected = sorted(values.keys() - expected)
    if missing or unexpected:
        details = []
        if missing:
            details.append("missing: " + ", ".join(missing))
        if unexpected:
            details.append("unexpected: " + ", ".join(unexpected))
        raise ValueError("invalid checksum asset set (" + "; ".join(details) + ")")
    return values


def render(version: str, checksums_path: Path) -> str:
    if VERSION_PATTERN.fullmatch(version) is None:
        raise ValueError("version must have the form X.Y.Z")
    checksums = _checksums(checksums_path)
    base = (
        "https://github.com/LocalGCloud/localcloud-cli/releases/download/"
        f"v{version}"
    )
    return f'''class Localcloud < Formula
  desc "Host CLI for the LocalCloud Google Cloud emulator"
  homepage "https://local.cloud"
  version "{version}"
  license :cannot_represent

  on_macos do
    depends_on macos: :ventura

    if Hardware::CPU.arm?
      url "{base}/{ASSETS['darwin_arm64']}"
      sha256 "{checksums[ASSETS['darwin_arm64']]}"
    else
      url "{base}/{ASSETS['darwin_amd64']}"
      sha256 "{checksums[ASSETS['darwin_amd64']]}"
    end
  end

  on_linux do
    if Hardware::CPU.arm?
      url "{base}/{ASSETS['linux_arm64']}"
      sha256 "{checksums[ASSETS['linux_arm64']]}"
    else
      url "{base}/{ASSETS['linux_amd64']}"
      sha256 "{checksums[ASSETS['linux_amd64']]}"
    end
  end

  def install
    libexec.install "localcloud", "localcloud-runtime"
    bin.write_exec_script libexec/"localcloud"
    bin.install_symlink bin/"localcloud" => "lc"
  end

  def caveats
    <<~EOS
      Docker Desktop, Colima, or Docker Engine must already be running.
      Linux binaries require glibc 2.35 or newer (Ubuntu 22.04 equivalent).

      lc is an alias for localcloud; both commands behave identically.

      Diagnose Docker and start LocalCloud:
        lc doctor
        lc start

      Then open http://localhost:24080.
    EOS
  end

  test do
    canonical_version = shell_output("#{{bin}}/localcloud --version")
    assert_equal "localcloud #{{version}}\\n", canonical_version
    assert_equal canonical_version, shell_output("#{{bin}}/lc --version")
    assert_match "LocalCloud coding-agent guide", shell_output("#{{bin}}/localcloud guide")
  end
end
'''


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--checksums", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        formula = render(args.version, args.checksums)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(formula, encoding="utf-8", newline="\n")
    except (OSError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

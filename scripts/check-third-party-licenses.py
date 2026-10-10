#!/usr/bin/env python3
"""Check that every third-party notices row has a license file to ship in the CLI bundle."""

from __future__ import annotations

import argparse
import re
import sys
import sysconfig
from importlib import metadata
from pathlib import Path, PurePosixPath


BUNDLE_DIRECTORY = "third_party_licenses"
INTERPRETER = "CPython"
_LICENSE_FILE = re.compile(r"(?i)(licen[cs]e|copying|notice)")


class MissingLicenseError(RuntimeError):
    pass


def notice_components(notices: Path) -> list[str]:
    """The component each row of the generated notices tables names."""
    components = []
    for line in notices.read_text(encoding="utf-8").splitlines():
        cells = line.split(" | ")
        if len(cells) == 4 and cells[0] not in {"Package", "-------"}:
            components.append(cells[0])
    return components


def license_files(component: str) -> list[tuple[Path, str]]:
    """A component's license and notice files, each with its directory below the component's.

    The interpreter's license comes from its standard library; a distribution's
    come from its installed metadata, without the rest of that metadata.
    """
    if component == INTERPRETER:
        interpreter_license = Path(sysconfig.get_path("stdlib")) / "LICENSE.txt"
        return [(interpreter_license, "")] if interpreter_license.is_file() else []
    try:
        distribution = metadata.distribution(component)
    except metadata.PackageNotFoundError:
        return []
    found = []
    for file in distribution.files or []:
        metadata_directory, *relative = file.parts
        if not metadata_directory.endswith(".dist-info") or not relative:
            continue
        if relative[0] == "licenses":
            relative = relative[1:]
        elif len(relative) != 1 or not _LICENSE_FILE.match(relative[0]):
            continue
        path = Path(file.locate())
        if relative and path.is_file():
            found.append((path, "/".join(relative[:-1])))
    return found


def license_datas(notices: Path) -> list[tuple[str, str]]:
    """PyInstaller datas that ship a license file for every notices row."""
    components = notice_components(notices)
    if not components:
        raise MissingLicenseError(f"{notices} lists no components")
    datas = []
    missing = []
    for component in components:
        files = license_files(component)
        if not files:
            missing.append(component)
        datas.extend(
            (str(path), str(PurePosixPath(BUNDLE_DIRECTORY, component, directory)))
            for path, directory in files
        )
    if missing:
        raise MissingLicenseError("no license file to ship for: " + ", ".join(missing))
    return datas


def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Verify that this environment has a license file to ship for every "
            "third-party notices row."
        )
    )
    parser.add_argument("--notices", type=Path, default=Path("THIRD_PARTY_NOTICES"))
    args = parser.parse_args()

    try:
        datas = license_datas(args.notices)
    except (OSError, MissingLicenseError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print(f"{len(datas)} license files cover every row of {args.notices}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

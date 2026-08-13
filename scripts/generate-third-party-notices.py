#!/usr/bin/env python3
"""Generate stable notices for the locked dependencies bundled by supported CLI builds."""

from __future__ import annotations

import argparse
import re
import sys
import tomllib
from pathlib import Path
from typing import Any

from packaging.markers import Marker, default_environment


ROOT_PACKAGE = "localcloud-cli"
SUPPORTED_TARGETS = (
    ("darwin", "Darwin", "arm64"),
    ("darwin", "Darwin", "x86_64"),
    ("linux", "Linux", "aarch64"),
    ("linux", "Linux", "x86_64"),
)


def _normalize(name: str) -> str:
    return re.sub(r"[-_.]+", "-", name).lower()


def _environments() -> tuple[dict[str, str], ...]:
    environments = []
    for sys_platform, platform_system, machine in SUPPORTED_TARGETS:
        environment = default_environment()
        environment.update(
            {
                "implementation_name": "cpython",
                "platform_machine": machine,
                "platform_python_implementation": "CPython",
                "platform_system": platform_system,
                "python_full_version": "3.11.0",
                "python_version": "3.11",
                "sys_platform": sys_platform,
            }
        )
        environments.append(environment)
    return tuple(environments)


def _applies(dependency: dict[str, Any], environment: dict[str, str]) -> bool:
    marker = dependency.get("marker")
    return marker is None or Marker(marker).evaluate(environment)


def _packages(lock: dict[str, Any]) -> dict[str, dict[str, Any]]:
    packages: dict[str, dict[str, Any]] = {}
    for package in lock.get("package", []):
        name = _normalize(package["name"])
        previous = packages.get(name)
        if previous is not None and previous.get("version") != package.get("version"):
            raise ValueError(f"multiple locked versions for {name!r} are unsupported")
        packages[name] = package
    return packages


def _catalog(
    path: Path,
) -> tuple[
    tuple[str, str, str, str],
    dict[str, tuple[str, str]],
    dict[str, tuple[str, str]],
]:
    with path.open("rb") as catalog_file:
        catalog = tomllib.load(catalog_file)

    interpreter_data = catalog.get("interpreter")
    if not isinstance(interpreter_data, dict):
        raise ValueError("license catalog is missing [interpreter]")
    interpreter = tuple(
        interpreter_data.get(field) for field in ("name", "version", "license", "source")
    )
    if not all(isinstance(value, str) and value.strip() for value in interpreter):
        raise ValueError("license catalog [interpreter] fields must be non-blank strings")

    def section(name: str) -> dict[str, tuple[str, str]]:
        raw = catalog.get(name)
        if not isinstance(raw, dict):
            raise ValueError(f"license catalog is missing [{name}]")
        entries: dict[str, tuple[str, str]] = {}
        for package_name, metadata in raw.items():
            if not isinstance(metadata, dict):
                raise ValueError(f"license catalog entry {package_name!r} must be a table")
            license_name = metadata.get("license")
            source = metadata.get("source")
            if not isinstance(license_name, str) or not license_name.strip():
                raise ValueError(
                    f"license catalog entry {package_name!r} has no license"
                )
            if not isinstance(source, str) or not source.strip():
                raise ValueError(f"license catalog entry {package_name!r} has no source")
            entries[_normalize(package_name)] = (license_name, source)
        return entries

    return interpreter, section("runtime"), section("build")


def _runtime_closure(
    packages: dict[str, dict[str, Any]], environment: dict[str, str]
) -> set[str]:
    try:
        pending = list(packages[ROOT_PACKAGE].get("dependencies", []))
    except KeyError as error:
        raise ValueError(f"{ROOT_PACKAGE!r} is missing from uv.lock") from error

    closure: set[str] = set()
    processed_extras: dict[str, set[str]] = {}
    while pending:
        dependency = pending.pop()
        if not _applies(dependency, environment):
            continue
        name = _normalize(dependency["name"])
        package = packages.get(name)
        if package is None:
            raise ValueError(f"runtime dependency {name!r} is missing from uv.lock")
        if name not in closure:
            closure.add(name)
            pending.extend(package.get("dependencies", []))

        requested_extras = {
            _normalize(extra) for extra in dependency.get("extra", [])
        }
        new_extras = requested_extras - processed_extras.setdefault(name, set())
        optional_dependencies = package.get("optional-dependencies", {})
        for extra in sorted(new_extras):
            extra_dependencies = optional_dependencies.get(extra)
            if extra_dependencies is None:
                raise ValueError(
                    f"dependency {name!r} requests unknown locked extra {extra!r}"
                )
            pending.extend(extra_dependencies)
        processed_extras[name].update(new_extras)
    return closure


def _release_components(packages: dict[str, dict[str, Any]]) -> set[str]:
    root = packages[ROOT_PACKAGE]
    return {
        _normalize(dependency["name"])
        for dependency in root.get("optional-dependencies", {}).get("release", [])
    }


def generate(lock_path: Path, catalog_path: Path) -> str:
    with lock_path.open("rb") as lock_file:
        packages = _packages(tomllib.load(lock_file))
    interpreter, runtime_catalog, build_catalog = _catalog(catalog_path)

    runtime = set().union(
        *(_runtime_closure(packages, environment) for environment in _environments())
    )
    unknown_runtime = sorted(runtime - runtime_catalog.keys())
    if unknown_runtime:
        raise ValueError(
            "unknown runtime dependency license: " + ", ".join(unknown_runtime)
        )

    release = _release_components(packages)
    unknown_build = sorted(release - build_catalog.keys())
    if unknown_build:
        raise ValueError("unknown release dependency license: " + ", ".join(unknown_build))

    lines = [
        "LOCALCLOUD THIRD-PARTY NOTICES",
        "",
        "LocalCloud is proprietary software governed by the accompanying LICENSE.",
        "The licenses below apply only to the identified third-party components and",
        "do not grant any open-source license to LocalCloud itself.",
        "",
        "This file is generated from uv.lock by",
        "scripts/generate-third-party-notices.py. Some dependencies are conditional",
        "across the supported macOS 13+ and glibc 2.35+ Linux archives.",
        "",
        "Bundled runtime",
        "---------------",
        "Package | Version | License | Source",
        "------- | ------- | ------- | ------",
    ]
    runtime_rows = [interpreter]
    runtime_rows.extend(
        (
            name,
            str(packages[name]["version"]),
            runtime_catalog[name][0],
            runtime_catalog[name][1],
        )
        for name in sorted(runtime)
    )
    lines.extend(" | ".join(row) for row in runtime_rows)

    lines.extend(
        [
            "",
            "Build components incorporated into the executable",
            "-------------------------------------------------",
            "Package | Version | License | Source",
            "------- | ------- | ------- | ------",
        ]
    )
    lines.extend(
        " | ".join(
            (
                name,
                str(packages[name]["version"]),
                build_catalog[name][0],
                build_catalog[name][1],
            )
        )
        for name in sorted(release)
    )
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lock", type=Path, default=Path("uv.lock"))
    parser.add_argument(
        "--catalog",
        type=Path,
        default=Path("third-party-licenses.toml"),
    )
    parser.add_argument("--output", type=Path, default=Path("THIRD_PARTY_NOTICES"))
    args = parser.parse_args()
    try:
        content = generate(args.lock, args.catalog)
    except (OSError, ValueError, tomllib.TOMLDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    args.output.write_text(content, encoding="utf-8", newline="\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

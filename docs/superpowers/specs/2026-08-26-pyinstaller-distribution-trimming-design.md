# PyInstaller Distribution Trimming Design

## Context

The native LocalCloud CLI uses PyInstaller one-folder packaging so commands can start without extracting a one-file executable on every invocation. A clean macOS arm64 release-equivalent build currently produces a 23,025,941-byte archive and a 48,588 KiB installed tree.

The bundle contains three avoidable payloads:

1. `collect_all("mcp")` returns MCP source files as data even though PyInstaller also embeds the same modules in its Python archive. The current bundle contains 122 duplicated MCP `.py` files.
2. `copy_metadata("localcloud-cli", recursive=True)` copies metadata for the complete dependency closure even though the CLI has a source-defined version and no runtime package-metadata lookup.
3. PyInstaller follows its setuptools hook and bundles `setuptools`, `distutils`, and their packaging support despite no LocalCloud runtime path using them.

A prototype containing only packaging changes reduced the macOS arm64 archive to 21,044,749 bytes and the installed tree to 44,804 KiB. The prototype passed the real release build, archive extraction, public CLI smoke commands, startup feedback, and the Docker status boundary.

## Goals

- Remove duplicated MCP source data from native release bundles.
- Copy only LocalCloud's own distribution metadata.
- Exclude build-only setuptools and distutils modules from runtime analysis.
- Preserve the existing one-folder layout, launcher behavior, release asset names, and supported platforms.
- Preserve MCP dependency loading, Docker integration, and prompt startup behavior.
- Add a regression test that makes the intended PyInstaller configuration explicit.

## Non-goals

- Do not replace the MCP SDK or change MCP protocol handling.
- Do not change direct runtime dependencies in `pyproject.toml`.
- Do not switch compression formats or add a platform-specific archive-size threshold.
- Do not change release environment isolation in `scripts/release.sh` or GitHub Actions.
- Do not change the launcher, Homebrew formula shape, or archive layout.

## Packaging Design

`localcloud.spec` will continue to call `collect_all("mcp", filter_submodules=required_mcp_module)` to obtain the already validated hidden-import list. Its returned data and binary collections will no longer be passed to `Analysis`.

The spec will make these changes:

- Build `datas` with `copy_metadata("localcloud-cli", recursive=False)`.
- Pass an empty `binaries` list to `Analysis`.
- Add `setuptools`, `distutils`, and `_distutils_hack` to `Analysis.excludes`.
- Preserve the current MCP hidden imports, `optimize=0`, one-folder `EXE` and `COLLECT` configuration, and all code-signing settings.

Discarding the MCP data and binary collections is intentional. The measured MCP collection contains only duplicated Python source and an empty `py.typed` marker. MCP imports remain embedded through normal module analysis and the filtered hidden-import list.

## Failure Handling

The build must fail normally if an excluded module becomes a required runtime import. No post-build deletion or missing-import suppression will be added. This keeps dependency errors visible during PyInstaller analysis and release smoke tests.

The regression test will describe the exclusion boundary so future runtime use of setuptools or distutils requires an explicit packaging decision rather than silently restoring the dependency.

## Testing and Verification

### Static regression coverage

Add a packaging test that verifies `localcloud.spec`:

- copies LocalCloud metadata non-recursively;
- does not pass MCP-collected data or binaries into `Analysis`;
- preserves the filtered MCP hidden imports;
- excludes `setuptools`, `distutils`, and `_distutils_hack`.

The test will not assert formatted source text more broadly than required for these packaging invariants.

### Release-path verification

Run the following with the release workflow's Python 3.11 environment:

1. Existing release and packaging tests.
2. `./scripts/release.sh --build-only`.
3. Package and extract the same launcher, runtime directory, license, and notices used by the release workflow.
4. Exercise the extracted public launcher with `--version`, `--help`, and `guide`.
5. Run the startup-feedback gate and require output within the existing two-second limit.
6. Exercise MCP dependency loading to the Docker boundary.
7. Run read-only `status` through the available Docker engine and verify behavior matches the baseline bundle.

### Size observation

Record the resulting macOS arm64 archive and installed sizes as verification evidence. Do not make exact byte counts a test requirement because Python, platform, and dependency builds can change binary sizes without representing a regression.

## Success Criteria

- The packaging regression test passes.
- Existing release tests pass.
- The real release build completes with Python 3.11.
- The extracted public CLI preserves version, help, guide, startup, MCP loading, and Docker status behavior.
- The release archive retains the expected top-level layout without symbolic or hard links.
- The macOS arm64 archive remains materially smaller than the measured 23.03 MB baseline, with the expected result near 21.04 MB.

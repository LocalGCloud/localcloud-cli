# PyInstaller Distribution Trimming Implementation Plan

## Objective

Implement the approved packaging-only trim from `docs/superpowers/specs/2026-08-26-pyinstaller-distribution-trimming-design.md`. Preserve the current one-folder launcher and release contract while removing duplicated MCP source data, recursive dependency metadata, and build-only setuptools modules.

## Files

- Update `localcloud.spec` with the validated PyInstaller collection boundaries.
- Add `tests/test_pyinstaller_spec.py` for focused packaging invariants.

Do not change runtime dependencies, release scripts, launchers, workflows, archive layout, compression, or platform support.

## Step 1: Add a failing packaging regression test

Create a focused test that executes `localcloud.spec` with lightweight PyInstaller constructor and hook stubs. Record the arguments passed to `collect_all`, `copy_metadata`, and `Analysis`, then assert:

- MCP collection retains `filter_submodules=required_mcp_module`;
- LocalCloud metadata uses `recursive=False`;
- `Analysis.binaries` is empty;
- `Analysis.datas` contains only LocalCloud metadata;
- `Analysis.hiddenimports` receives the filtered MCP hidden imports; and
- `Analysis.excludes` contains exactly `setuptools`, `distutils`, and `_distutils_hack`.

Run the new test first and confirm it fails against the current specification.

## Step 2: Apply the validated PyInstaller trim

Update `localcloud.spec` to:

- discard the data and binary return values from `collect_all` while retaining its hidden imports;
- use `copy_metadata("localcloud-cli", recursive=False)` as the complete data list;
- pass `binaries=[]` to `Analysis`; and
- exclude `setuptools`, `distutils`, and `_distutils_hack`.

Preserve `optimize=0`, the existing MCP filter, one-folder `EXE` and `COLLECT` configuration, UPX settings, and signing fields.

Run the focused test and existing packaging/release tests.

## Step 3: Verify the real release path

Using the release workflow's Python 3.11 environment:

1. Run `./scripts/release.sh --build-only`.
2. Stage the launcher, `localcloud-runtime`, license, and notices using the release workflow's archive layout.
3. Create and extract the macOS arm64 archive.
4. Verify the extracted launcher with `--version`, `--help`, and `guide`.
5. Run `scripts/check-startup-feedback.py` with the existing two-second limit.
6. Exercise MCP loading through a command that reaches the Docker boundary.
7. Run read-only `status` through the available Docker engine when available, or record Docker availability as an external constraint.
8. Verify the archive contains no symbolic or hard links.

Record compressed archive size and extracted installed size, comparing them with the 23,025,941-byte and 48,588-KiB baselines. Exact byte counts are evidence, not test thresholds.

## Step 4: Review and commit

Inspect the final diff for accidental changes, run `git diff --check`, and invoke the code-review workflow. Address material findings and rerun affected checks. Commit only the PyInstaller trim and its regression test while preserving unrelated working-tree and index changes.

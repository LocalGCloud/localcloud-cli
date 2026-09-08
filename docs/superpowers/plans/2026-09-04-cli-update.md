# CLI Update Implementation Plan

**Goal:** Add `localcloud update` and `lc update` using the existing installation channel.

**Architecture:** Handle updates before constructing the Docker controller. Detect script ownership from the running frozen executable and managed marker, or Homebrew ownership from the installed formula prefix. Replace the Python process with the installer or Homebrew so replacement never returns to a deleted bundle.

**Tech Stack:** Python standard library, POSIX shell, existing pytest and installer verification scripts.

**Spec:** User-approved design in this conversation, 2026-09-04.

## Constraints

- Preserve unrelated workspace edits; add no dependencies.
- Retain the existing script installation directory, including custom paths.
- Do not start Docker, modify shell configuration, or overwrite source/manual installations.
- Download the complete installer over HTTPS before running it; propagate failures and clean temporary files.
- Restore PyInstaller's library environment before external commands.
- Accept legacy and provenance-bearing CLI version output in the installer.

## Steps

- [x] Add `src/localcloud_cli/update.py`, register the command in `cli.py`, and document it in README and CLI reference.
- [x] Add focused `tests/test_update.py` checks for channel ownership, custom paths, environment restoration, CLI dispatch, and shell handoff success/failure.
- [x] Fix version extraction in the site's `public/install.sh`; extend `scripts/verify-installer.mjs` to upgrade to and reinstall a provenance-bearing fixture.
- [x] Run focused tests, the non-Docker CLI suite, installer verification, and diff checks. Record results and deployment ordering.

## Validation

- `.venv/bin/python -m pytest tests/test_update.py tests/test_cli.py -q`: 111 passed.
- `.venv/bin/python -m pytest -m 'not docker' -q`: 559 passed, 3 deselected.
- Site `node scripts/verify-installer.mjs`: passed using local release fixtures. Copied the changed public installer into ignored `dist/install.sh` for the verifier's asset equality check; no full site build was performed.
- Both initial parsing and same-version detection ignore commit/date metadata. The installer regression changes the installed commit/date while preserving its semantic version and verifies no replacement.
- Local PyInstaller 6.14.1 build: both command names handed off successfully to a fixture installer from a custom directory containing spaces and an apostrophe. The fixture removed the executing old bundle, proving the updater does not return to it. Unmanaged frozen installation refused with exit 2.
- Both repository diff checks and installer shell syntax passed.
- Release downloads and actual Homebrew upgrades were not exercised; the local frozen smoke used a fixture downloader/installer. No installed user CLI or Docker runtime was changed.

The site installer fix must be published before releasing the CLI command. Workspace changes are left uncommitted for review alongside the user's existing work.

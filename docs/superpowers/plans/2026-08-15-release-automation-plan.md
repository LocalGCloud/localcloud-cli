# Release Automation Implementation Plan

## Objective

Implement the approved POSIX release orchestrator from `docs/superpowers/specs/2026-08-15-release-automation-design.md`. Preserve the existing GitHub Actions matrix as the only all-platform builder. The local script must build only for its current host and must never edit tracked release inputs.

## Files

- Add `scripts/release.sh` for native builds and prepared-source releases.
- Add `tests/test_release_script.py` for the script's observable modes and release safety invariants.
- Update `RELEASING.md` after the script is proven so the automated path is primary and the existing commands remain available as recovery guidance.

Do not change `localcloud.spec`, `cli-release.yml`, version metadata, dependency metadata, or the Homebrew formula renderer. The workflow already contains the correct four-runner native build matrix and publication contract.

## Step 1: Lock the command-line contract

Add focused subprocess tests for behavior that does not require external tools:

- `--help` exits successfully and documents the default native build, `--build-only`, and `--release VERSION`;
- unknown options and extra arguments fail with usage;
- `--release` without a version fails; and
- versions outside exact `X.Y.Z` syntax fail before invoking `git`, `uv`, or `gh`.

Run the focused argument tests and confirm they fail because `scripts/release.sh` does not exist.

## Step 2: Implement common shell structure and native builds

Create an executable `scripts/release.sh` with:

- `#!/bin/sh` and `set -eu`;
- repository/workflow constants near the top;
- a usage function and mutually exclusive mode parser;
- a command prerequisite helper;
- stage messages; and
- a temporary-directory trap for notice generation.

Resolve the repository root from the script's own directory and change to it before running project commands. Native build mode must execute this observable sequence:

```sh
uv lock --check
uv run --frozen --extra release python scripts/generate-third-party-notices.py --output "$temporary_notice"
cmp THIRD_PARTY_NOTICES "$temporary_notice"
uv run --frozen --extra release python -m PyInstaller --clean --noconfirm localcloud.spec
./dist/localcloud --version
./dist/localcloud --help
./dist/localcloud guide
```

Help and guide output may be redirected after their successful exit status is observed. Do not infer or override the target architecture; PyInstaller must use the current host.

Extend the tests with a stub `uv` executable that records invocations, writes the requested temporary notice, and creates a runnable fake `dist/localcloud` during the PyInstaller call. Assert that no-argument and `--build-only` modes are equivalent, preserve tracked inputs, and execute the expected frozen build and smoke checks.

Run `sh -n scripts/release.sh` and the focused build-mode tests.

## Step 3: Lock release preflight safety

Use temporary Git repositories and PATH stubs to cover the safeguards before implementing remote mutation. The tests must demonstrate that `--release VERSION` refuses to tag, push, or dispatch when any of these conditions holds:

- the working tree has tracked, staged, or untracked changes;
- the current branch is not `main`;
- local `HEAD` differs from `origin/main`;
- committed `__version__` differs from `VERSION`;
- lockfile, notice, pytest, native build, or smoke validation fails;
- a local or remote tag resolves to a different commit; or
- a GitHub release already exists.

Use a local bare Git repository as `origin` so branch, fetch, tag, and push behavior remain real. Stub only `uv`, `gh`, and the built executable. Record all stub invocations and assert that external mutation is absent on every preflight failure.

## Step 4: Implement prepared-source release orchestration

Add the release flow in this order:

1. Validate `VERSION` and require `git`, `uv`, and `gh`.
2. Require `main` and a completely clean tree, including untracked files.
3. Authenticate with `gh auth status` for the source repository host.
4. Fetch `origin main --tags` and require `HEAD == origin/main`.
5. Read the committed package version and require it to equal `VERSION`.
6. Run `uv lock --check`, temporary notice comparison, the full frozen test suite, the native PyInstaller build, and frozen executable smoke checks.
7. Refuse to continue if `gh release view vVERSION` succeeds.
8. Create an annotated `vVERSION` tag only when absent. Reuse an existing tag only when local and remote peeled commits both equal `HEAD`.
9. Push `main` and the tag explicitly.
10. Dispatch `cli-release.yml --ref vVERSION`, require the returned Actions URL, extract its numeric run ID, and watch that ID with `--exit-status`.
11. Query the published release asset names and require the exact set from the design.
12. Dispatch `publish-formula.yml` with `version=VERSION`, capture its returned run ID, and watch it with `--exit-status`.
13. Print the published release URL and non-mutating/manual public-channel verification commands.

Keep shell functions narrow: native validation/build, release-state validation, workflow dispatch/watch, and release-asset verification. Do not introduce configuration files, generic command runners, logging frameworks, retries, rollback, or cross-compilation.

## Step 5: Verify successful and interrupted releases

Add a successful release test using the local bare remote and deterministic `gh` stub. Assert:

- the annotated tag resolves to the prepared commit locally and remotely;
- the source workflow is dispatched against `vVERSION`;
- the exact run ID returned for that dispatch is watched;
- the tap workflow is not dispatched before CLI workflow success and exact asset verification;
- the tap receives `version=VERSION`; and
- its exact returned run ID is watched.

Add failure-path assertions showing that a failed CLI run or invalid asset set prevents tap dispatch. Add a recovery case in which matching local and remote tags already exist but no release exists; the script must reuse the immutable tag and dispatch without rewriting it.

Run the entire `tests/test_release_script.py` file, then the full existing pytest suite.

## Step 6: Exercise the real native build

Run these checks against the real tools and project:

```sh
sh -n scripts/release.sh
./scripts/release.sh --build-only
./dist/localcloud --version
```

Confirm the current Apple Silicon host produces a working macOS ARM64 executable. Do not invoke `--release` during verification because it intentionally creates and pushes a public tag and dispatches publication workflows; its command sequencing and safety contract are covered in isolated repositories with stubbed GitHub commands.

## Step 7: Reconcile operator documentation

After the real build succeeds, update `RELEASING.md` to make the script the primary operator path:

```sh
./scripts/release.sh --build-only
./scripts/release.sh --release 0.1.1
```

State clearly that release mode requires the version change and notices to be committed on a clean, up-to-date `main`. Retain the underlying manual commands as troubleshooting/recovery reference rather than maintaining a second contradictory procedure. Preserve the separate Docker image prerequisite and website deployment ordering.

Run the release-script tests once more if documentation examples affect asserted help or commands. Invoke the repository code-review workflow, address material findings, and rerun every affected focused check.

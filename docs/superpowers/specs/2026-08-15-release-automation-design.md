# Release Automation Design

## Goal

Add a small POSIX shell entry point that makes the existing LocalCloud CLI release procedure repeatable without duplicating the platform build logic already encoded in GitHub Actions.

The script supports two workflows:

- build and smoke-test a binary for the current host; and
- publish a prepared version through the existing four-platform CLI release and Homebrew tap workflows.

Release mode must not edit tracked source files or create a release commit. The operator prepares and commits the version change before invoking it.

## Interface

Create `scripts/release.sh` with these invocations:

```sh
./scripts/release.sh
./scripts/release.sh --build-only
./scripts/release.sh --release 0.1.1
./scripts/release.sh --help
```

No arguments and `--build-only` are equivalent. `--release` requires exactly one stable semantic version in `X.Y.Z` form. Unknown flags, extra arguments, prerelease versions, and build metadata fail with usage and a nonzero status.

The script uses `#!/bin/sh` and POSIX syntax. Repository and workflow names are constants near the beginning of the file:

- source repository: `LocalGCloud/localcloud-cli`;
- tap repository: `LocalGCloud/homebrew-tap`;
- source remote and branch: `origin` and `main`;
- release workflow: `cli-release.yml`; and
- tap workflow: `publish-formula.yml`.

## Native build flow

Build mode changes to the repository root based on the script location, independent of the caller's working directory. It then:

1. requires `uv`;
2. runs `uv lock --check`;
3. generates third-party notices into a temporary file and compares them with committed `THIRD_PARTY_NOTICES` without rewriting the tracked file;
4. runs the frozen PyInstaller build from `localcloud.spec`; and
5. smoke-tests `dist/localcloud --version`, `--help`, and `guide`.

The resulting executable is native to the machine running the script. On the current Apple Silicon workstation this produces `dist/localcloud` for macOS ARM64. Build mode does not attempt cross-compilation and does not run the full pytest suite.

Normal PyInstaller output under `build/` and `dist/` is the only persistent filesystem output from this mode.

## Release preflight

Release mode requires `uv`, `git`, and `gh`, plus an authenticated GitHub CLI session. Before any tag or remote mutation, it verifies all of the following:

- the supplied version matches `X.Y.Z` exactly;
- the current branch is `main`;
- the working tree, index, and untracked-file set are clean;
- `git fetch origin main --tags` succeeds;
- local `HEAD` equals `origin/main`;
- `src/localcloud_cli/__init__.py` contains a matching committed `__version__`;
- `uv.lock` is current;
- generated third-party notices match the committed notice file;
- the complete pytest suite passes;
- the native one-file executable builds and passes its smoke checks; and
- no GitHub release already exists for `vVERSION`.

These checks establish that the current commit is the exact immutable source to publish. The script never rewrites the version, lockfile, notice file, or any other tracked source.

## Tag and CLI release flow

After preflight succeeds, the script handles `vVERSION` as follows:

- If the tag does not exist locally or remotely, create an annotated local tag and push `main` and the tag to `origin`.
- If the tag already exists, accept it only when both the local and remote tag resolve to the current `HEAD`. A missing local copy may be fetched. Any conflicting tag fails before workflow dispatch.

The script dispatches `cli-release.yml` in `LocalGCloud/localcloud-cli` with `--ref vVERSION`. It captures the workflow-run URL returned by `gh workflow run`, extracts the numeric run ID, prints the URL, and watches that exact run with `gh run watch --exit-status`. Failure or cancellation stops the script before Homebrew publication.

Once the workflow succeeds, the script verifies the GitHub release and its exact required asset set:

- four native archives for Darwin/Linux and ARM64/AMD64;
- `SHA256SUMS`;
- `localcloud.rb`;
- one Sigstore bundle for each native archive; and
- one Sigstore bundle for `SHA256SUMS`.

Missing or unexpected assets fail release verification.

## Homebrew publication

After CLI release verification, the script dispatches `publish-formula.yml` in `LocalGCloud/homebrew-tap` with `version=VERSION`. It captures and watches that exact run using the same URL-to-run-ID flow. Success means the tap publisher downloaded, validated, and committed the formula according to the tap repository's own workflow.

The script finishes by printing the CLI release URL and the existing public-channel verification commands from the runbook. It does not automatically install or replace the operator's Homebrew package.

Publishing the runtime Docker image remains a prerequisite managed in the private LocalCloud repository; `cli-release.yml` independently verifies the required Linux architectures and runtime ownership capability. Website deployment remains a post-release operation because this repository exposes no deployment command or workflow for it.

## Failure handling and recovery

The script uses `set -eu`, named stage messages, and direct command exit statuses. It reports the failed stage and leaves the underlying command output visible. Temporary notice output is removed with a trap.

All validation that can run safely occurs before tag creation. Once a tag is pushed, rerunning the same `--release VERSION` is safe only while no GitHub release exists: matching local and remote tags are reused, and a new workflow run may be dispatched. A conflicting tag or an already-published release is never overwritten.

The script does not delete tags, releases, workflow runs, build output, or Homebrew state. Recovery from a failed external workflow is explicit and preserves the immutable release source.

## Verification

Add focused pytest coverage around the public script behavior using temporary repositories and stub executables on `PATH`. The tests cover:

- default and `--build-only` argument handling;
- invalid release versions and unknown arguments;
- native-build command ordering and smoke checks;
- refusal to release from a dirty tree, wrong branch, stale branch, or mismatched source version;
- no tag, push, or workflow dispatch before all preflight checks pass;
- exact tag/ref and repository arguments for CLI and tap workflow dispatch;
- watching the exact returned workflow run IDs; and
- stopping before tap publication when the CLI workflow or asset verification fails.

Run the focused tests, the full existing test suite, `sh -n scripts/release.sh`, and the real `./scripts/release.sh --build-only` flow. The final smoke proof is executing the newly built `dist/localcloud` through the script's version, help, and guide checks.

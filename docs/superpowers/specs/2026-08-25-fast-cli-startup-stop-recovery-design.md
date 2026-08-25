# Fast CLI Startup and Reliable Stop Design

## Context

Two observed CLI failures share a release/runtime boundary but have separate root causes.

The released native CLI is a 25 MB PyInstaller one-file executable. PyInstaller must create a temporary `_MEI_*` directory and extract bundled support files before Python executes. `LifecycleReporter.start()` already runs before Docker initialization, but it cannot emit `Processing` until that extraction and Python startup have completed. Moving imports or starting the reporter earlier cannot remove this delay.

The failed `stop` command selected stale persisted active state. The host state identifies data volume `lc-pr-dbdc02d6-3fd-c0-data` and an absent container, while the running LocalCloud container mounts `localcloud-data`. `_command_config()` currently lets the stale active record choose the implicit data volume before Docker validates it, so `Controller.stop()` correctly finds no container for the wrong volume and reports that the runtime was not running.

## Goals

- Make the installed native CLI reach its first progress output promptly without requiring Python on the host.
- Preserve the existing four release asset names, checksums, signatures, command names, and supported platforms.
- Make implicit runtime selection ignore stale active state while preserving explicit operator choices.
- Keep exact-volume discovery, collision detection, immutable container identity checks, and ownership protections fail-closed.
- Update every release consumer atomically so no supported installer receives an unusable archive.

## Non-goals

- Do not add a custom self-extracting cache or another compiler toolchain.
- Do not stop arbitrary containers by image, name, or label when no selected data volume resolves.
- Do not weaken explicit config, `--data-volume`, current-directory config, or multi-runtime behavior.
- Do not change the GitHub release asset set or Homebrew tap security model.

## Native Bundle Architecture

`localcloud.spec` will use PyInstaller one-folder mode. The build output will be:

```text
dist/
├── localcloud                  # small POSIX launcher
└── localcloud-runtime/
    ├── localcloud              # PyInstaller bootloader executable
    └── _internal/              # interpreter and bundled dependencies
```

The launcher resolves its own directory and `exec`s `localcloud-runtime/localcloud` with the original arguments and exit behavior. It contains no extraction or persistent cache logic. The runtime dependencies are extracted once when the release archive is installed, so every command can enter Python and start the existing lifecycle reporter directly.

The local release build and GitHub Actions workflow will smoke `dist/localcloud`, not the nested bootloader directly. Each existing platform archive will contain:

```text
localcloud
localcloud-runtime/**
LICENSE
THIRD_PARTY_NOTICES
```

Archive names, `SHA256SUMS`, Sigstore bundles, and GitHub release verification remain unchanged. Packaging checks will validate required roots, reject unexpected top-level entries, and reject symbolic or hard links.

## Installation Consumers

### Homebrew

The generated formula will install `localcloud` and `localcloud-runtime` into the formula's `libexec`. Homebrew will create executable wrappers in `bin` for `localcloud` and the `lc` alias. Formula tests will continue to verify `--version`, alias equivalence, and the agent guide through the public `bin` commands.

### Website installer

`localcloud-site/public/install.sh` will treat the launcher and runtime directory as one managed installation.

For a new version it will:

1. Verify the archive checksum and safe tree shape before installation.
2. Execute the extracted launcher for the existing semantic-version check.
3. Stage the runtime under a version-owned hidden directory in the selected install directory.
4. Stage a generated launcher that targets that exact runtime directory.
5. Move the runtime into place before atomically replacing the public `localcloud` launcher.
6. Update the managed-install marker with the owned runtime directory.
7. Remove the previous marker-owned runtime only after the new launcher and marker are committed.

A failure before the launcher switch leaves the previous installation usable and removes only newly staged files. Uninstall removes the launcher, managed `lc` alias, marker, and only the runtime directory recorded by a valid marker. Unrelated files and unowned runtime directories remain untouched.

The installer fixture will model the new archive tree and cover clean install, same-version behavior, upgrade, rollback, alias ownership, and uninstall cleanup.

## Runtime Selection Repair

The current selection precedence remains:

1. explicit config and explicit CLI overrides;
2. current-directory `localcloud.yaml`;
3. a valid active runtime and its remembered config;
4. built-in defaults.

`Controller.remembered_config()` will use the existing `DEFAULTS_CONFIG_LABEL` sentinel when Docker resolves the selected runtime but that runtime used built-in defaults. `None` will mean that no runtime resolved for the preliminary selection.

When `_command_config()` has implicitly selected an active runtime and `remembered_config()` returns `None`, it will recompute the preliminary config with active state disabled for that invocation. It will then resolve the built-in/default volume and load any config remembered by that actual runtime. The stale state file is not broadly deleted because schema version 3 may contain other valid runtime entries; a later successful start can update last-active state normally.

Explicit config paths, current-directory config, and explicit `--data-volume` remain authoritative even when no matching container exists. Only an implicit active selection receives the stale-state fallback.

`Controller.stop()` and `DockerRuntime.stop()` retain their existing mutation path: exact named-volume discovery, single-container collision enforcement, immutable container ID revalidation, attached-runtime preservation, and managed ephemeral cleanup.

## Progress-Latency Gate

The native release smoke will launch `dist/localcloud status --data-volume localcloud-startup-smoke` with stderr captured and require the first `Processing` output within 2.0 seconds. It will terminate the smoke process immediately after observing that output, before the read-only status path completes.

The archive-layout check independently prevents a return to one-file packaging. The latency gate catches launcher, import, and reporter-start regressions and reports the measured delay on failure.

## Testing and Verification

### CLI repository

- Unit-test stale implicit active state falling back to `localcloud-data`.
- Unit-test that a valid non-default active runtime using built-in defaults remains selected.
- Retain explicit config, local config, and explicit data-volume precedence tests.
- Cover the exact missing-runtime versus built-in-default sentinel contract.
- Update native build-script, release-workflow, archive-layout, and generated-formula assertions.
- Build the actual native bundle and smoke `--version`, `--help`, and `guide` through the launcher.
- Exercise the prompt-latency gate against the built bundle.

### Website repository

- Update installer archive fixtures to include the launcher and runtime tree.
- Test clean install, idempotent same-version install, upgrade, failed upgrade rollback, managed alias behavior, and uninstall removal of only marker-owned files.
- Run the existing installer and documentation contract checks.

### Observable stop verification

After source tests pass, run the repaired CLI against the current stale active record and the running `localcloud-data` container. The command must select `localcloud-data`, report `stopped`, and leave the container in a non-running state without deleting persistent data. This is the final behavioral proof of the reported stop failure.

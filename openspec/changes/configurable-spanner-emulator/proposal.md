## Why

The Spanner emulator is currently built from a local fork (`spanner-emulator-build:latest`) that adds LevelDB persistence via `--data_dir`. Building this fork takes ~60 minutes on first run and requires a separate repository checkout. For users who don't need persistence — or who want to validate against the official Google emulator — there should be a way to use the standard `gcr.io/cloud-spanner-emulator/emulator` image without building the fork at all.

## What Changes

- Add a `SPANNER_EMULATOR_IMAGE` build arg / env var with two values: `local` (default, current fork) and `google` (upstream `gcr.io/cloud-spanner-emulator/emulator`)
- When `google`: Dockerfile copies both `gateway_main` and `emulator_main` from the upstream image, wrapper script runs the emulator without `--data_dir`, entrypoint logs a warning that persistence is unavailable
- When `local`: behavior is unchanged (fork binary with persistence)
- `build.sh` skips the fork image pre-check when `SPANNER_EMULATOR_IMAGE=google`
- `docker-compose.yml` passes the build arg through

## Capabilities

### New Capabilities
- `configurable-spanner-emulator`: Runtime and build-time selection between Google's standard Spanner emulator and the locally-built fork with persistence

### Modified Capabilities

_(none — no existing spec-level requirements change)_

## Impact

- **Dockerfile**: Conditional `COPY --from` based on build arg for Spanner binaries; conditional wrapper script
- **supervisord.conf**: Wrapper script logic changes (no `--data_dir` in google mode)
- **docker-entrypoint.sh**: Warning log when using google emulator about no persistence
- **build.sh**: Skip fork pre-check when `SPANNER_EMULATOR_IMAGE=google`
- **docker-compose.yml**: Pass `SPANNER_EMULATOR_IMAGE` as build arg
- **services.yaml**: No changes (persistence flag is runtime-informational only)

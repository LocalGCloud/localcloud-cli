## 1. Dockerfile — Conditional Spanner Binary Selection

- [x] 1.1 Add `ARG SPANNER_EMULATOR_IMAGE=local` build arg to the Dockerfile
- [x] 1.2 Add `ENV SPANNER_EMULATOR_IMAGE=${SPANNER_EMULATOR_IMAGE}` so the value is available at runtime (for wrapper script and entrypoint)
- [x] 1.3 Update Spanner binary COPY logic: when `google`, copy both `gateway_main` and `emulator_main` from the upstream stage (`spanner-emulator-upstream`); when `local`, keep current behavior (gateway from upstream, emulator from fork). Use a builder stage or conditional RUN to handle this.
- [x] 1.4 Update the wrapper script (`spanner-emulator-wrapper`): check `SPANNER_EMULATOR_IMAGE` env var — if `google`, exec emulator without `--data_dir`; if `local`, exec with `--data_dir=/var/lib/localcloud/spanner-data` (current behavior)
- [x] 1.5 Make the `FROM spanner-emulator-build:latest AS spanner-emulator-fork` stage conditional — it should not fail the build when `SPANNER_EMULATOR_IMAGE=google` and the fork image doesn't exist

## 2. docker-compose.yml — Pass Build Arg

- [x] 2.1 Add `SPANNER_EMULATOR_IMAGE` to the `build.args` section in `docker-compose.yml`, defaulting to `${SPANNER_EMULATOR_IMAGE:-local}`

## 3. docker-entrypoint.sh — Startup Warning

- [x] 3.1 Add a check near the top of `docker-entrypoint.sh`: if `SPANNER_EMULATOR_IMAGE=google` and Spanner is enabled, log the warning: `"WARNING: Using Google's standard Spanner emulator — persistence is NOT supported. Data will be lost on container restart."`

## 4. build.sh — Conditional Fork Check

- [x] 4.1 Update the `spanner-emulator-build:latest` pre-check in `build.sh` to skip when `SPANNER_EMULATOR_IMAGE=google`

## 5. Verification

- [x] 5.1 Test: run `SPANNER_EMULATOR_IMAGE=google ./build.sh` — build succeeds without `spanner-emulator-build:latest`, no fork check warning
- [x] 5.2 Test: run `docker compose up -d` with google image — Spanner emulator starts, logs persistence warning, basic Spanner operations work (create instance, create database, insert/query)
- [x] 5.3 Test: run default `./build.sh` (no env var) — existing behavior unchanged, fork image required, persistence works

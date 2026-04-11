## ADDED Requirements

### Requirement: Configurable Spanner emulator source via build arg
The Docker image SHALL accept a build arg `SPANNER_EMULATOR_IMAGE` with values `google` or `local` (default: `local`). When `google`, the image SHALL use binaries from `gcr.io/cloud-spanner-emulator/emulator:latest`. When `local`, the image SHALL use binaries from `spanner-emulator-build:latest` (the fork with persistence).

#### Scenario: Build with default (local) mode
- **WHEN** `docker compose build` is run without setting `SPANNER_EMULATOR_IMAGE`
- **THEN** the image SHALL contain the forked emulator binary with persistence support, identical to current behavior

#### Scenario: Build with google mode
- **WHEN** `docker compose build` is run with `SPANNER_EMULATOR_IMAGE=google`
- **THEN** the image SHALL contain the official Google emulator binary from `gcr.io/cloud-spanner-emulator/emulator:latest`
- **AND** the fork image (`spanner-emulator-build:latest`) SHALL NOT be required

### Requirement: Persistence disabled in google mode
When `SPANNER_EMULATOR_IMAGE=google`, the Spanner emulator SHALL run without the `--data_dir` flag. Spanner data SHALL be ephemeral (in-memory only).

#### Scenario: Google emulator runs without data_dir
- **WHEN** the container starts with `SPANNER_EMULATOR_IMAGE=google`
- **THEN** the Spanner emulator process SHALL NOT receive a `--data_dir` argument
- **AND** Spanner data SHALL NOT persist across container restarts

#### Scenario: Local emulator retains persistence
- **WHEN** the container starts with `SPANNER_EMULATOR_IMAGE=local`
- **THEN** the Spanner emulator process SHALL receive `--data_dir=/var/lib/localcloud/spanner-data`
- **AND** Spanner data SHALL persist across container restarts

### Requirement: Startup warning for google mode
When running in google mode, the entrypoint SHALL log a warning indicating that Spanner persistence is not available.

#### Scenario: Warning logged on startup
- **WHEN** the container starts with `SPANNER_EMULATOR_IMAGE=google`
- **AND** Spanner is enabled
- **THEN** the entrypoint SHALL log: `"WARNING: Using Google's standard Spanner emulator — persistence is NOT supported. Data will be lost on container restart."`

#### Scenario: No warning in local mode
- **WHEN** the container starts with `SPANNER_EMULATOR_IMAGE=local`
- **THEN** no persistence warning SHALL be logged for Spanner

### Requirement: Build script skips fork check in google mode
`build.sh` SHALL skip the `spanner-emulator-build:latest` image pre-check when `SPANNER_EMULATOR_IMAGE=google`.

#### Scenario: build.sh with google mode
- **WHEN** `SPANNER_EMULATOR_IMAGE=google ./build.sh` is run
- **THEN** the fork image check SHALL be skipped
- **AND** the build SHALL succeed without `spanner-emulator-build:latest` present

#### Scenario: build.sh with local mode (default)
- **WHEN** `./build.sh` is run without `SPANNER_EMULATOR_IMAGE` or with `SPANNER_EMULATOR_IMAGE=local`
- **THEN** the fork image check SHALL warn if `spanner-emulator-build:latest` is missing (existing behavior)

### Requirement: docker-compose passes build arg
`docker-compose.yml` SHALL pass `SPANNER_EMULATOR_IMAGE` as a build arg to the Dockerfile.

#### Scenario: Build arg passed through
- **WHEN** `SPANNER_EMULATOR_IMAGE=google docker compose build` is run
- **THEN** the Dockerfile SHALL receive `SPANNER_EMULATOR_IMAGE=google` as a build arg

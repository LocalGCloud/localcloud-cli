## Context

LocalCloud bundles a forked Spanner emulator (`spanner-emulator-build:latest`) built from a separate repository. The fork adds LevelDB-backed persistence via a `--data_dir` flag. The upstream gateway binary (`gateway_main`) is already pulled from `gcr.io/cloud-spanner-emulator/emulator:latest`.

Building the fork takes ~60 minutes on first run and requires cloning a separate repo. Users who don't need persistence — or who want to test against the official Google emulator — are blocked by this build requirement.

**Current binary layout:**
- `/usr/local/bin/spanner-gateway` ← from upstream (`gateway_main`)
- `/usr/local/bin/spanner-emulator-main` ← from fork (`emulator_main` with `--data_dir`)
- `/usr/local/bin/spanner-emulator-wrapper` ← bash script that injects `--data_dir`

## Goals / Non-Goals

**Goals:**
- Allow users to choose between Google's official Spanner emulator and the local fork via a single config knob (`SPANNER_EMULATOR_IMAGE`)
- Skip the fork build entirely when using Google's emulator
- Warn users at startup when persistence is unavailable (google mode)
- No behavioral change when using the default (`local`) mode

**Non-Goals:**
- Adding persistence to the Google emulator (that's the fork's purpose)
- Changing the Spanner emulator ports or API behavior
- Runtime switching between modes (requires image rebuild)

## Decisions

### D1: Build arg `SPANNER_EMULATOR_IMAGE` with values `local` | `google`

**Choice:** A Docker build arg that controls which Spanner emulator binaries get copied into the image.

**Rationale:** Build args are the standard Docker mechanism for build-time variants. This is cleaner than maintaining two Dockerfiles or using multi-stage conditional logic.

**Alternatives considered:**
- *Two separate Dockerfiles*: More duplication, harder to maintain
- *Runtime download of emulator*: Adds startup latency, network dependency, image size uncertainty

### D2: Conditional wrapper script based on env var at runtime

**Choice:** The wrapper script (`spanner-emulator-wrapper`) checks `SPANNER_EMULATOR_IMAGE` env var at runtime to decide whether to pass `--data_dir`. The Dockerfile sets this env var to match the build arg.

**Rationale:** The wrapper already exists. Adding a condition is minimal change. The env var is baked in at build time but readable at runtime for logging.

### D3: Single upstream `FROM` stage, conditional `COPY`

**Choice:** Keep the existing `FROM gcr.io/cloud-spanner-emulator/emulator:latest AS spanner-emulator-upstream` stage. When `SPANNER_EMULATOR_IMAGE=google`, copy `emulator_main` from upstream instead of fork. When `local`, use fork as today.

**Rationale:** Docker doesn't support conditional `FROM`, but we can use shell-in-RUN to conditionally copy or a two-stage approach where both images are pulled but only the relevant binary is kept. Since the fork stage will fail if the image doesn't exist, we guard it with a conditional `FROM` that only triggers for `local` mode.

**Implementation:** Use a script-based approach in a builder stage that copies the correct binary based on the build arg.

## Risks / Trade-offs

- **[Risk] Fork image missing when `SPANNER_EMULATOR_IMAGE=local`** → Mitigated by existing `build.sh` pre-check (unchanged behavior)
- **[Risk] Users forget they're in google mode and expect persistence** → Mitigated by startup warning log in entrypoint
- **[Trade-off] Build arg is build-time only** → Accepted; switching emulator mode is an infrastructure decision, not a runtime toggle

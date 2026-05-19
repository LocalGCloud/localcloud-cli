## Why

The license enforcement mechanism is currently gated by the `BUILD_MODE` file and `LOCALCLOUD_LICENSE_SERVER` env var. There's no simple, single toggle to disable all license checks at the container level. Developers who want to run LocalCloud without a license key (e.g., local dev, CI/CD, demos) have to either set `LOCALCLOUD_LICENSE_SERVER=none` or rely on bypass mode, which is inconsistently documented and checked across the Java and shell sides.

We need a single Dockerfile ARG (`ENFORCE_LICENSE`) that controls whether the container enforces license validation at all. This makes the behavior explicit, consistent, and documented.

## What Changes

- **Already implemented (shell side):** Dockerfile has `ARG ENFORCE_LICENSE=false`, writing the value to `/opt/localcloud/ENFORCE_LICENSE`. Entrypoint and `license-gate.sh` already read this file and skip all license checks when `false`.

- **Already implemented (Dockerfile):** `docker build --build-arg ENFORCE_LICENSE=true` bakes enforcement into the image. Default is `false` (no enforcement) for developer convenience.

- **Gap (Java side):** `LicenseManager.validate()` in `LocalCloudApplication.java:188` is called unconditionally. It does not check `ENFORCE_LICENSE`. When `ENFORCE_LICENSE=false`, the Java side should skip validation entirely instead of relying on bypass mode.

## Capabilities

### New
- `license-enforce-toggle`: Add a single `ENFORCE_LICENSE` build arg that disables all license checks when `false`. Already done on shell side; needs Java-side implementation for completeness.

### Modified
- `license-validation-flow`: `LicenseManager` should check `/opt/localcloud/ENFORCE_LICENSE` at construction time and skip validation in Java as well.

## Impact

- **Dockerfile**: Already done — `ARG ENFORCE_LICENSE=false` writes to `/opt/localcloud/ENFORCE_LICENSE`
- **Shell scripts**: Already done — `entrypoint.sh` and `license-gate.sh` respect the file
- **Java (new)**: `LicenseManager` needs to read `/opt/localcloud/ENFORCE_LICENSE` and always return a valid PRO-tier result when `false`
- **Documentation**: Add ENFORCE_LICENSE to DEVELOPER_GUIDE.md and update Dockerfile comments

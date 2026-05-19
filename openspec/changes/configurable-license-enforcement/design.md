## Overview

Single ARG that controls whether license validation runs at container startup. Already implemented on the shell side; needs Java-side `LicenseManager` support for completeness.

## Architecture

```
Docker build:
  docker build --build-arg ENFORCE_LICENSE=false .
    → Dockerfile writes "false" → /opt/localcloud/ENFORCE_LICENSE

Docker runtime:
  entrypoint.sh reads ENFORCE_LICENSE
    → if "false": skip license-gate.sh entirely, print bypass message
    → if "true": run license-gate.sh → LicenseGateMain → LicenseManager.validate()

  LocalCloudApplication.java reads ENFORCE_LICENSE (GAP — not yet)
    → if "false": skip LicenseManager.validate(), use PRO tier
    → if "true": run LicenseManager.validate(), exit 1 on failure
```

## Components

### 1. Dockerfile (done)
- Line 312-315: `ARG ENFORCE_LICENSE=false` + `RUN echo "${ENFORCE_LICENSE}" > /opt/localcloud/ENFORCE_LICENSE`

### 2. docker-entrypoint.sh (done)
- Line 398-402: Reads file, if "false" prints bypass message and skips license gate

### 3. license-gate.sh (done)
- Line 9, 16, 42: Reads file, if "false" exits 0 without validating

### 4. LicenseManager.java (GAP — needs fix)
- Constructor should read `/opt/localcloud/ENFORCE_LICENSE` (or env var `LOCALCLOUD_ENFORCE_LICENSE`)
- If `false`, `validate()` should immediately return `LicenseResult.valid(PRO, "dev@local.cloud", deviceId, Long.MAX_VALUE)` without any clock checks, cache checks, or validation
- `isProductionBuild()` and `bypassMode` remain orthogonal — `ENFORCE_LICENSE=false` overrides all of them

### 5. Documentation (needs update)
- DEVELOPER_GUIDE.md: Add ENFORCE_LICENSE build arg to quick start and customization sections
- Dockerfile: Comments already exist, add to `--help` style header

## File Paths

| Layer | File | Status |
|-------|------|--------|
| Build | `Dockerfile:314-315` | Done |
| Shell | `scripts/docker-entrypoint.sh:398-402` | Done |
| Shell | `scripts/license-gate.sh:9,16,42` | Done |
| Java | `localcloud-server/.../LicenseManager.java` | **Gap** |
| Docs | `docs/DEVELOPER_GUIDE.md` | **Gap** |
| Docs | `docs/licensing-security.md` | **Gap** |

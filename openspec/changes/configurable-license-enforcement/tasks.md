## Tasks

### Task 1: Add ENFORCE_LICENSE check to LicenseManager

`localcloud-server/src/main/java/com/localcloud/licensing/LicenseManager.java`

- [ ] Add a static method or constructor parameter to read `/opt/localcloud/ENFORCE_LICENSE`
- [ ] Store `enforceLicense` boolean alongside existing `bypassMode`
- [ ] In `validate()`: if `!enforceLicense`, return `LicenseResult.valid(PRO, "dev@local.cloud", deviceId, Long.MAX_VALUE)` immediately (skip clock check, cache, validation)
- [ ] Keep `bypassMode` and `isProductionBuild()` logic unchanged for when enforcement IS enabled

### Task 2: Update DEVELOPER_GUIDE.md

`docs/DEVELOPER_GUIDE.md`

- [ ] Add a "License Enforcement" section explaining `ENFORCE_LICENSE` build arg
- [ ] Show both examples: `--build-arg ENFORCE_LICENSE=false` (no check) and default (enabled for production builds)
- [ ] Update the "Custom Build" or similar section

### Task 3: Update licensing-security.md

`docs/licensing-security.md`

- [ ] Add "Configurable Enforcement" section after the existing phases
- [ ] Document the `ENFORCE_LICENSE` flow: Dockerfile → file → entrypoint → LicenseManager
- [ ] Update the container startup diagram to show the `ENFORCE_LICENSE=false` bypass path

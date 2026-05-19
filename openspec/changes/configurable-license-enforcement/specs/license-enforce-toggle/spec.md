## NEW Requirements

### Requirement: ENFORCE_LICENSE build arg controls enforcement at container level

The Dockerfile SHALL accept an `ENFORCE_LICENSE` build argument. When set to `false`, all license checks are bypassed at every layer (shell scripts, Java gate, Java server). When set to `true`, full license validation is enforced.

#### Scenario: ENFORCE_LICENSE=false builds skip all checks
- **GIVEN** the Docker image is built with `--build-arg ENFORCE_LICENSE=false`
- **WHEN** the container starts with no `LOCALCLOUD_API_KEY` and no `LOCALCLOUD_LICENSE_SERVER`
- **THEN** the entrypoint SHALL print "Enforcement disabled — bypassing all license checks"
- **AND** `license-gate.sh` SHALL exit 0 without validating
- **AND** `LocalCloudApplication` SHALL treat the license as valid PRO tier

#### Scenario: ENFORCE_LICENSE=true builds enforce validation
- **GIVEN** the Docker image is built with `--build-arg ENFORCE_LICENSE=true`
- **WHEN** the container starts
- **THEN** the entrypoint SHALL run `license-gate.sh`
- **AND** `LocalCloudApplication` SHALL call `LicenseManager.validate()`
- **AND** the container SHALL exit 1 if validation fails

#### Scenario: Default build (ENFORCE_LICENSE=false) runs in bypass mode
- **GIVEN** the Docker image is built with default args (no `--build-arg`)
- **WHEN** the container starts
- **THEN** the entrypoint SHALL skip license checks
- **AND** `LocalCloudApplication` SHALL treat the license as valid PRO tier

### Requirement: Java side respects ENFORCE_LICENSE

`LicenseManager.validate()` SHALL check `/opt/localcloud/ENFORCE_LICENSE` before running any validation logic. If the file contains `"false"`, it SHALL immediately return a valid PRO-tier result without clock checks, cache reads, or key validation.

#### Scenario: Java skip validation when enforcement disabled
- **GIVEN** `/opt/localcloud/ENFORCE_LICENSE` contains `"false"`
- **WHEN** `LicenseManager.validate()` is called
- **THEN** it SHALL return `LicenseResult.valid(PRO, ...)` immediately
- **AND** SHALL NOT read the license cache
- **AND** SHALL NOT check the clock
- **AND** SHALL NOT validate any key

## ADDED Requirements

### Requirement: GCP credential source configuration
The system SHALL support three credential source modes via the `LOCALCLOUD_GCP_CREDENTIAL_SOURCE` environment variable: `adc` (Application Default Credentials), `service-account` (SA key file), and `none` (default, fully isolated).

#### Scenario: Default mode is none
- **WHEN** `LOCALCLOUD_GCP_CREDENTIAL_SOURCE` is not set or set to `none`
- **THEN** no GCP credentials are loaded and all services operate in local-only mode

#### Scenario: ADC mode
- **WHEN** `LOCALCLOUD_GCP_CREDENTIAL_SOURCE=adc`
- **THEN** the system reads credentials from `/credentials/adc/application_default_credentials.json`

#### Scenario: Service account mode
- **WHEN** `LOCALCLOUD_GCP_CREDENTIAL_SOURCE=service-account`
- **THEN** the system reads credentials from `/credentials/sa-key.json`

#### Scenario: Credential file missing
- **WHEN** the configured credential source file does not exist
- **THEN** the system logs a warning at startup and falls back to `none` mode

### Requirement: Credential detection API endpoint
The system SHALL expose `GET /_localcloud/credentials` that returns the current credential status.

#### Scenario: Credentials active
- **WHEN** ADC credentials are loaded and valid
- **THEN** the endpoint returns `{ "source": "adc", "valid": true, "identity": "user@example.com", "project": "my-dev-project" }`

#### Scenario: No credentials
- **WHEN** credential source is `none`
- **THEN** the endpoint returns `{ "source": "none", "valid": false, "identity": null, "project": null }`

#### Scenario: Invalid credentials
- **WHEN** the credential file exists but is malformed or expired
- **THEN** the endpoint returns `{ "source": "adc", "valid": false, "identity": null, "project": null, "error": "Invalid credential file" }`

### Requirement: Credential injection into spawned containers
When the ContainerManager spawns a Compute, Cloud Run, or GKE container, and valid credentials are available, it SHALL inject the credential file and environment variable into the container.

#### Scenario: Container with credentials
- **WHEN** a Compute instance is created and credentials are available
- **THEN** the container SHALL have `/credentials/gcp.json` bind-mounted (read-only) and `GOOGLE_APPLICATION_CREDENTIALS=/credentials/gcp.json` set in its environment

#### Scenario: Container without credentials
- **WHEN** a Compute instance is created and credential source is `none`
- **THEN** the container SHALL NOT have any credential file mounted or `GOOGLE_APPLICATION_CREDENTIALS` set

#### Scenario: GKE cluster with credentials
- **WHEN** a GKE cluster is created via k3d and credentials are available
- **THEN** the credential file SHALL be mounted into the k3d cluster nodes so pods can access it

### Requirement: Docker-compose credential volume mounts
The `docker-compose.yml` SHALL include optional, commented-out volume mounts for GCP credential files that users can uncomment to enable credential bridging.

#### Scenario: ADC mount configuration
- **WHEN** a user wants to use ADC credentials
- **THEN** they uncomment the `~/.config/gcloud:/credentials/adc:ro` volume mount and set `LOCALCLOUD_GCP_CREDENTIAL_SOURCE=adc`

#### Scenario: SA key mount configuration
- **WHEN** a user wants to use a service account key
- **THEN** they set `LOCALCLOUD_GCP_SA_KEY=/path/to/key.json` and `LOCALCLOUD_GCP_CREDENTIAL_SOURCE=service-account`

### Requirement: Credential status in console Settings
The Settings page SHALL display a "GCP Credentials" section showing the credential source, validation status, and authenticated identity.

#### Scenario: Credentials configured
- **WHEN** credentials are active and valid
- **THEN** the Settings page shows a green status with the authenticated email and project

#### Scenario: No credentials
- **WHEN** credential source is `none`
- **THEN** the Settings page shows an informational message explaining how to configure credentials with the docker-compose volume mount instructions

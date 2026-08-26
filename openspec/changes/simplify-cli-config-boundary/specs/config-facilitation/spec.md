## Purpose

Defines the CLI as an independent host/container facilitator that passes the shared configuration to LocalCloud without reproducing server resolution semantics.

## ADDED Requirements

### Requirement: Section-scoped configuration ownership
The CLI SHALL semantically interpret configuration required for host/container management while treating server, service, and infrastructure configuration as LocalCloud-owned content.

#### Scenario: Host settings are supplied
- **WHEN** `localcloud.yaml` contains supported `host` settings
- **THEN** the CLI validates and applies those settings when managing Docker resources

#### Scenario: Java-owned nested field is unknown to the CLI
- **WHEN** a compatible image supports a `server`, `services`, or `infrastructure` field unknown to the installed CLI
- **THEN** the CLI passes the original file unchanged without rejecting the field solely because it does not recognize its semantics

#### Scenario: CLI inspects Java-owned raw values
- **WHEN** the CLI displays raw configuration for diagnostics or management UX
- **THEN** it does not present those values as the merged or effective LocalCloud runtime configuration

### Requirement: Exact configuration pass-through
The CLI SHALL mount the selected public configuration file read-only and identify its canonical container path without rewriting or materializing Java-owned values.

#### Scenario: Selected file is mounted
- **WHEN** a user selects a configuration file
- **THEN** the exact file is mounted read-only and `LOCALCLOUD_CONFIG` points to its canonical container destination

#### Scenario: No file is selected
- **WHEN** discovery finds no user configuration
- **THEN** the CLI adds neither a configuration mount nor `LOCALCLOUD_CONFIG`

#### Scenario: Server values contain secrets
- **WHEN** the selected file contains passwords, keys, or tokens in Java-owned sections
- **THEN** the CLI does not copy those values into Docker labels, runtime hashes, generated environment variables, or diagnostic output

### Requirement: Capability-based image compatibility
The CLI SHALL use the public configuration version and image capability metadata to prevent launching an image that cannot consume the selected contract.

#### Scenario: Image supports the configuration schema
- **WHEN** the selected image advertises support for the public configuration version
- **THEN** the CLI may create or restart the container without needing the image's packaged defaults

#### Scenario: Image lacks schema capability
- **WHEN** a selected configuration is present and the image does not advertise compatible schema support
- **THEN** the CLI fails before container creation with actionable compatibility guidance

### Requirement: Server-sourced effective runtime state
The CLI SHALL obtain effective service and server-owned runtime state from LocalCloud after startup rather than predicting it by merging the public file.

#### Scenario: Startup succeeds
- **WHEN** LocalCloud becomes ready
- **THEN** CLI status and management output use LocalCloud-reported effective service/runtime state

#### Scenario: Effective state is unavailable
- **WHEN** LocalCloud is absent or not ready
- **THEN** the CLI reports effective server state as unavailable rather than presenting raw YAML as resolved state

### Requirement: Independent CLI operation
The CLI SHALL remain installable and usable without a local Java installation, LocalCloud source checkout, or pre-creation execution of the LocalCloud image.

#### Scenario: CLI manages a compatible remote image reference
- **WHEN** the user has Python, Docker access, and a compatible LocalCloud image reference
- **THEN** the CLI can discover host configuration and create the container without any source-repository dependency

### Requirement: Host-focused runtime identity
The CLI SHALL derive managed-container identity from CLI-owned host settings and configuration path/presence without hashing Java-owned configuration content.

#### Scenario: Server configuration changes at the same path
- **WHEN** Java-owned values change in place without changing CLI-owned host settings
- **THEN** runtime identity remains stable and the new values apply on container restart without recreation

#### Scenario: Host configuration changes
- **WHEN** a CLI-owned setting requiring container recreation changes
- **THEN** the CLI detects the identity difference and replaces the managed container according to existing lifecycle rules

## ADDED Requirements

### Requirement: Firestore documents persist across Docker restarts
The system SHALL persist all Firestore documents (collections, subcollections, fields) across Docker container restarts. When the container is restarted with the same Docker volume, all previously created documents MUST be available without re-seeding.

#### Scenario: Documents survive restart
- **WHEN** a developer creates Firestore documents, restarts the Docker container, and queries the same collection
- **THEN** all previously created documents are returned with their original field values

#### Scenario: Subcollections survive restart
- **WHEN** a developer creates documents with subcollections, restarts the container
- **THEN** both parent documents and subcollection documents are preserved

#### Scenario: No persistence without volume
- **WHEN** the container is started without a Docker volume mount
- **THEN** the emulator operates in ephemeral mode (data lost on restart)

### Requirement: Firestore export/import cycle
The system SHALL periodically export Firestore state to the Docker volume and import it on startup. The export format SHALL be compatible with the Firestore emulator's `--seed_from_export` flag.

#### Scenario: Automatic export on data change
- **WHEN** documents are created or modified in Firestore
- **THEN** the state is exported to the persistent volume within 30 seconds

#### Scenario: Automatic import on startup
- **WHEN** the container starts and a Firestore export exists on the volume
- **THEN** the Firestore emulator loads the exported state before accepting requests

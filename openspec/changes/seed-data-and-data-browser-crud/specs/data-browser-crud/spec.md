## ADDED Requirements

### Requirement: Create records from Data Browser
The system SHALL allow users to create new records for any browsable database service directly from the Data Browser UI. Creation forms SHALL be dynamically generated based on the service's schema (table columns for Spanner/BigQuery, key-value for GCS/Memorystore, document structure for Firestore). All mutations MUST go through the emulator's own API (REST or gRPC), not direct database writes.

#### Scenario: Create a new Spanner row
- **WHEN** user is viewing a Spanner table and clicks "Add Row", fills in column values, and submits
- **THEN** the system creates a session, commits a mutation via the Spanner REST API, and the new row appears in the table view

#### Scenario: Create a new GCS object
- **WHEN** user is viewing a GCS bucket and clicks "Upload Object", provides a key name and content
- **THEN** the system uploads the object via the GCS REST API and the object appears in the bucket listing

#### Scenario: Create a new BigQuery row
- **WHEN** user is viewing a BigQuery table and clicks "Add Row", fills in field values
- **THEN** the system inserts the row via the BigQuery insertAll API and the row appears in the table data

#### Scenario: Create a new Memorystore key
- **WHEN** user clicks "Add Key", selects a data type (string/hash/list/set/sorted_set), provides key name and value
- **THEN** the system creates the key via the Redis RESP protocol and the key appears in the listing

#### Scenario: Create a new Firestore document
- **WHEN** user is viewing a Firestore collection and clicks "Add Document", provides a document ID and field values
- **THEN** the system creates the document via the Firestore REST API and the document appears in the collection

#### Scenario: Create a new Secret Manager secret
- **WHEN** user clicks "Add Secret", provides a secret name and initial version data
- **THEN** the system creates the secret and version via the Secret Manager gRPC API and the secret appears in the listing

### Requirement: Update records from Data Browser
The system SHALL allow users to edit existing records for database services that support updates. The edit interface SHALL show current values and allow inline modification.

#### Scenario: Update a Spanner row
- **WHEN** user clicks "Edit" on a Spanner row, modifies column values, and saves
- **THEN** the system commits an update mutation via the Spanner REST API and the updated values are reflected

#### Scenario: Update a GCS object
- **WHEN** user clicks "Edit" on a GCS object and modifies the content
- **THEN** the system uploads a new version of the object via the GCS REST API

#### Scenario: Update a Memorystore value
- **WHEN** user clicks "Edit" on a Memorystore key and changes the value
- **THEN** the system updates the value via Redis SET/HSET command and the new value is reflected

#### Scenario: Update a Firestore document
- **WHEN** user clicks "Edit" on a Firestore document and modifies fields
- **THEN** the system updates the document via the Firestore REST API

### Requirement: Delete records from Data Browser
The system SHALL allow users to delete records from any browsable database service. Delete actions MUST require user confirmation before execution.

#### Scenario: Delete a Spanner row
- **WHEN** user clicks "Delete" on a Spanner row and confirms the action
- **THEN** the system commits a delete mutation via the Spanner REST API and the row is removed from the view

#### Scenario: Delete a GCS object
- **WHEN** user clicks "Delete" on a GCS object and confirms
- **THEN** the system deletes the object via the GCS REST API and the object is removed from the listing

#### Scenario: Delete a BigQuery row
- **WHEN** user clicks "Delete" on a BigQuery row and confirms
- **THEN** the system executes a DELETE DML statement via the BigQuery jobs API

#### Scenario: Delete a Memorystore key
- **WHEN** user clicks "Delete" on a Memorystore key and confirms
- **THEN** the system deletes the key via Redis DEL command and the key is removed from the listing

#### Scenario: Delete a Firestore document
- **WHEN** user clicks "Delete" on a Firestore document and confirms
- **THEN** the system deletes the document via the Firestore REST API

#### Scenario: Delete a Secret Manager secret
- **WHEN** user clicks "Delete" on a secret and confirms
- **THEN** the system deletes the secret via the Secret Manager gRPC API

#### Scenario: Delete confirmation
- **WHEN** user clicks "Delete" on any record
- **THEN** a confirmation dialog appears showing what will be deleted, and the record is only removed after explicit confirmation

### Requirement: Browse Firestore data
The system SHALL support full data browsing for Firestore, including listing collections, viewing documents, and navigating nested sub-collections. Firestore MUST be promoted from "connection only" to a fully browsable service.

#### Scenario: List Firestore collections
- **WHEN** user selects Firestore in the Data Browser
- **THEN** the system displays all root-level collections with document counts

#### Scenario: View Firestore documents
- **WHEN** user clicks on a Firestore collection
- **THEN** the system displays documents with their fields, values, and types

#### Scenario: Navigate sub-collections
- **WHEN** user views a Firestore document that has sub-collections
- **THEN** the system displays sub-collection names and allows drill-down navigation

### Requirement: Browse Bigtable data
The system SHALL support data browsing for Bigtable, including listing tables, viewing column families, and reading rows. Bigtable MUST be promoted from "connection only" to a fully browsable service.

#### Scenario: List Bigtable tables
- **WHEN** user selects Bigtable in the Data Browser
- **THEN** the system displays all tables in the instance with their column families

#### Scenario: View Bigtable rows
- **WHEN** user clicks on a Bigtable table
- **THEN** the system displays rows with row keys, column families, qualifiers, and cell values (LIMIT 50)

### Requirement: Expanded seed data for all database services
The seed.yaml file SHALL contain realistic mockup data for all database services with 10-20 rows per table. Seed data MUST cover Firestore (collections with documents) and Bigtable (tables with row data) which currently have no seed data. Spanner seed data SHALL include interleaved tables and multiple databases to validate persistence.

#### Scenario: Firestore seed data loaded
- **WHEN** the seed file is loaded
- **THEN** Firestore contains at least 2 collections with 5+ documents each, including nested fields

#### Scenario: Bigtable seed data loaded
- **WHEN** the seed file is loaded
- **THEN** Bigtable contains at least 1 table with 10+ rows across multiple column families

#### Scenario: Spanner expanded seed data
- **WHEN** the seed file is loaded
- **THEN** Spanner contains at least 2 databases with 3+ tables each, including interleaved parent-child tables, with 10+ rows per table

#### Scenario: All database services seeded
- **WHEN** the seed file is loaded
- **THEN** GCS, Pub/Sub, BigQuery, Spanner, Firestore, Bigtable, Secret Manager, and Memorystore all contain meaningful mockup data

### Requirement: Spanner persistence validation
The system SHALL verify that Spanner data persists across Docker container restarts when using the forked emulator with `--data_dir` configured.

#### Scenario: Spanner data survives restart
- **WHEN** seed data is loaded into Spanner, the Docker container is restarted, and the Data Browser queries Spanner
- **THEN** all previously seeded instances, databases, schemas, and row data are present without re-seeding

## ADDED Requirements

### Requirement: Remote GCS browse
When GCS routing mode is "remote", the browse endpoint SHALL fetch real buckets and objects from the remote GCP project via the Cloud Storage JSON API instead of querying local PostgreSQL.

#### Scenario: List remote buckets
- **WHEN** `GET /_localcloud/browse/gcs` is called and GCS routing is "remote" with remote_project="my-project"
- **THEN** the response SHALL contain buckets from the real GCP project, formatted identically to local browse responses

#### Scenario: List remote objects in bucket
- **WHEN** `GET /_localcloud/browse/gcs/buckets/{name}/objects` is called in remote mode
- **THEN** the response SHALL contain objects from the real GCS bucket with name, size, contentType, and updated fields

#### Scenario: Pagination for large buckets
- **WHEN** a remote bucket contains more than 100 objects
- **THEN** the response SHALL include a `nextPageToken` field and the UI SHALL show a "Load more" control

### Requirement: Remote BigQuery browse
When BigQuery routing mode is "remote", the browse endpoint SHALL fetch real datasets and tables from the remote GCP project via the BigQuery REST API.

#### Scenario: List remote datasets
- **WHEN** `GET /_localcloud/browse/bigquery` is called and BigQuery routing is "remote"
- **THEN** the response SHALL contain datasets from the real GCP project

#### Scenario: List remote tables in dataset
- **WHEN** `GET /_localcloud/browse/bigquery/datasets/{id}/tables` is called in remote mode
- **THEN** the response SHALL contain tables with name, type, row count, and size metadata

#### Scenario: Preview remote table rows
- **WHEN** `GET /_localcloud/browse/bigquery/datasets/{id}/tables/{name}/preview` is called in remote mode
- **THEN** the response SHALL contain the first 100 rows from the real BigQuery table

### Requirement: Remote BigQuery query execution
When BigQuery routing mode is "remote", the query endpoint SHALL execute SQL against the remote BigQuery project.

#### Scenario: Run query on remote BigQuery
- **WHEN** a user runs a SQL query with BigQuery in remote mode
- **THEN** the query SHALL be executed against the remote GCP project and results SHALL be returned in the standard query response format

#### Scenario: Query cost warning
- **WHEN** a user submits a query in remote mode
- **THEN** the system SHALL first execute a dry-run to estimate bytes processed, and the UI SHALL display a warning with the estimated cost before executing

### Requirement: Remote data visual indicator
The data explorer SHALL show a "Cloud" badge and distinct color scheme when displaying remote GCP data to clearly distinguish it from local emulated data.

#### Scenario: Remote data badge
- **WHEN** the data explorer shows data from a remote GCP service
- **THEN** a "Cloud" badge SHALL appear next to the service name and the data table header SHALL use a blue accent color

### Requirement: GCP API response transformation
Remote GCP API responses SHALL be transformed to match the local browse response format so the console data explorer renders them identically.

#### Scenario: GCS bucket list format matches
- **WHEN** the GCS Storage API returns a `storage.buckets.list` response
- **THEN** the transformer SHALL convert it to the same `{ buckets: [{ name, location, storageClass, created }] }` format as local browse

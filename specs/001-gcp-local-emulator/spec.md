# Feature Specification: LocalCloud - GCP Local Emulator

**Feature Branch**: `001-gcp-local-emulator`
**Created**: 2026-03-09
**Status**: Draft
**Input**: User description: "Implement LocalStack for Google Cloud - a local emulation platform that simulates Google Cloud Platform services on a developer's laptop for development, testing, and cost reduction."

## Clarifications

### Session 2026-03-09

- Q: How should service URLs be exposed from the Docker container? → A: Hybrid approach - single gateway port for REST services + dedicated ports for gRPC services (Firestore, Spanner, Bigtable, Pub/Sub). Matches real GCP SDK behavior.
- Q: Should the platform include a web-based management dashboard? → A: Yes, a lightweight web dashboard for service status monitoring, request logs, and basic read-only data browsing (e.g., list buckets/objects, view topics). No data editing through the UI.
- Q: How should SDK environment variables be configured? → A: Platform auto-generates a shell script on startup (`eval $(localcloud env)`) that sets all `*_EMULATOR_HOST` variables. Also provides a Docker Compose snippet for multi-container application setups.
- Q: How should data seeding and reset work for testing workflows? → A: Declarative seed files (YAML/JSON) define initial state for each service, loaded on startup or via CLI. A `reset` command restores all services to the seeded state.
- Q: Should Cloud Logging and Cloud Monitoring APIs be supported to maintain production parity? → A: Yes, sink mode. Accept all Logging/Monitoring API calls, store locally, and display in the web dashboard. No alerting or complex query features.

## Constraints

- **Packaging**: The entire platform MUST be packaged as a single Docker container that developers run locally.
- **State Persistence**: State persistence is always on by default. All service data MUST survive container restarts via Docker volume mounts.
- **Language Preference**: Java is the preferred language for the abstraction layer and service emulators, with deep integration with Google Cloud Java SDKs. Python is the secondary language. Go is excluded for now.
- **Production Parity**: The developer experience MUST ensure that application code can migrate to production GCP with only configuration changes (endpoint URLs, credentials). No code changes required.
- **Networking**: The Docker container uses local networking to expose service URLs. REST services are accessible via a single gateway port with path-based routing. gRPC services (Firestore, Spanner, Bigtable, Pub/Sub) expose dedicated ports.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Start Local GCP Environment (Priority: P1)

A developer wants to start a local GCP emulation environment with a single command so they can begin developing against GCP services without cloud access, credentials, or incurring costs.

**Why this priority**: This is the foundational capability. Without a simple startup mechanism, no other feature is usable. It delivers immediate value by eliminating cloud dependency for local development.

**Independent Test**: Can be fully tested by running a single start command and verifying that the emulation platform is running and accepting connections. Delivers the value of a running local GCP environment.

**Acceptance Scenarios**:

1. **Given** a developer has the tool installed, **When** they run the start command, **Then** the emulation platform starts within 60 seconds and reports which services are available.
2. **Given** the platform is starting, **When** a service fails to initialize, **Then** the platform logs the failure, starts remaining services, and reports partial availability.
3. **Given** the platform is running, **When** the developer runs a status command, **Then** they see a list of all active services with their ports and health status.
4. **Given** the platform is running, **When** the developer runs a stop command, **Then** all services shut down cleanly and release their ports.

---

### User Story 2 - Use Cloud Storage Locally (Priority: P1)

A developer wants to use Google Cloud Storage (GCS) locally to upload, download, list, and manage objects and buckets without connecting to real GCP, so they can develop and test storage-dependent features offline.

**Why this priority**: Cloud Storage is the most universally used GCP service. Nearly every application interacts with GCS for file uploads, static assets, backups, or data pipelines. No official emulator exists from Google.

**Independent Test**: Can be fully tested by creating a bucket, uploading a file, listing objects, downloading the file, and verifying content matches. Delivers the value of local storage development without cloud costs.

**Acceptance Scenarios**:

1. **Given** the platform is running, **When** a developer uses the Google Cloud client library with the endpoint overridden to localhost, **Then** they can create buckets, upload objects, list objects, and download objects.
2. **Given** a bucket exists locally, **When** the developer lists objects with a prefix filter, **Then** only matching objects are returned.
3. **Given** an object exists, **When** the developer deletes it, **Then** subsequent retrieval attempts return a 404 error consistent with the real GCS API response format.
4. **Given** the platform restarts, **When** the container is restarted with the same volume mount, **Then** previously stored objects are still available.

---

### User Story 3 - Use Pub/Sub Locally (Priority: P1)

A developer wants to publish and subscribe to messages using Google Cloud Pub/Sub locally to test event-driven architectures without cloud infrastructure.

**Why this priority**: Pub/Sub is the backbone of event-driven GCP architectures. Integrating it into a unified platform reduces setup complexity and enables cross-service event wiring (e.g., GCS notifications triggering Pub/Sub messages).

**Independent Test**: Can be fully tested by creating a topic, creating a subscription, publishing a message, and pulling the message. Delivers the value of local event-driven development.

**Acceptance Scenarios**:

1. **Given** the platform is running, **When** a developer creates a topic and subscription via the client library, **Then** published messages are delivered to subscribers.
2. **Given** a subscription exists, **When** a message is published to its topic, **Then** the subscriber receives the message within 2 seconds.
3. **Given** a message is received, **When** the subscriber acknowledges it, **Then** the message is not redelivered.
4. **Given** a message is received, **When** the subscriber does not acknowledge within the deadline, **Then** the message is redelivered.

---

### User Story 4 - Use Firestore Locally (Priority: P1)

A developer wants to read and write documents in Firestore locally to develop and test document-based data models without cloud connectivity.

**Why this priority**: Firestore is one of the most popular GCP databases for web and mobile applications. Including it in the unified platform ensures a complete local development experience for the most common GCP stack.

**Independent Test**: Can be fully tested by creating a collection, adding documents, querying documents, and verifying CRUD operations work correctly.

**Acceptance Scenarios**:

1. **Given** the platform is running, **When** a developer creates a document via the Firestore client library, **Then** the document is stored and retrievable by its path.
2. **Given** documents exist in a collection, **When** the developer queries with filters (equality, range, ordering), **Then** correct results are returned.
3. **Given** a document exists, **When** the developer updates specific fields, **Then** only those fields change and others are preserved.
4. **Given** multiple operations, **When** the developer uses a batch write, **Then** all operations succeed or fail atomically.

---

### User Story 5 - Use BigQuery Locally (Priority: P2)

A developer wants to run SQL queries against BigQuery datasets locally to develop and test data analytics workflows without cloud costs or data transfer.

**Why this priority**: BigQuery is GCP's flagship analytics service with no official emulator. It is critical for data engineering workflows, but its complexity makes it a P2 behind the more broadly-used services.

**Independent Test**: Can be fully tested by creating a dataset, creating a table, loading data, and running SQL queries. Delivers the value of local analytics development.

**Acceptance Scenarios**:

1. **Given** the platform is running, **When** a developer creates a dataset and table via the BigQuery client library, **Then** the schema is stored and the table accepts data inserts.
2. **Given** a table contains data, **When** the developer runs a SQL SELECT query, **Then** correct results are returned with proper data types.
3. **Given** multiple tables exist, **When** the developer runs a JOIN query, **Then** results correctly combine data from both tables.
4. **Given** a large query, **When** it completes, **Then** the response format matches the BigQuery API response structure (job-based query model).

---

### User Story 6 - Use Secret Manager Locally (Priority: P2)

A developer wants to store and retrieve secrets locally using the Secret Manager API so their application can use the same secret-retrieval code in local and cloud environments.

**Why this priority**: Secret Manager is used by nearly every production GCP application but has no official emulator. Developers currently resort to `.env` files which diverge from production code paths.

**Independent Test**: Can be fully tested by creating a secret, adding a version, and accessing the secret value.

**Acceptance Scenarios**:

1. **Given** the platform is running, **When** a developer creates a secret and adds a version, **Then** the secret value is retrievable via the Secret Manager client library.
2. **Given** a secret has multiple versions, **When** the developer accesses the "latest" version, **Then** the most recently added version is returned.
3. **Given** a secret version exists, **When** the developer disables it, **Then** subsequent access attempts return an error.

---

### User Story 7 - Use Cloud Tasks Locally (Priority: P2)

A developer wants to enqueue and process Cloud Tasks locally to test asynchronous task processing workflows.

**Why this priority**: Cloud Tasks is widely used for deferred processing and has no official emulator. Testing task-based workflows currently requires deploying to the cloud.

**Independent Test**: Can be fully tested by creating a queue, enqueuing a task with an HTTP target, and verifying the target endpoint receives the request.

**Acceptance Scenarios**:

1. **Given** the platform is running and a queue is created, **When** a developer creates a task with an HTTP target, **Then** the platform dispatches an HTTP request to the target URL.
2. **Given** a task is dispatched, **When** the target returns a non-2xx status, **Then** the task is retried according to the queue's retry configuration.
3. **Given** a queue is paused, **When** tasks are added, **Then** they are held until the queue is resumed.

---

### User Story 8 - Use Cloud Functions / Cloud Run Locally (Priority: P2)

A developer wants to run Cloud Functions and Cloud Run services locally with proper trigger wiring so event-driven functions (e.g., Pub/Sub triggers, GCS triggers) execute automatically.

**Why this priority**: While Functions Framework handles the runtime, there is no unified trigger wiring. The value is in connecting emulated services (e.g., a GCS upload triggers a function via Pub/Sub).

**Independent Test**: Can be fully tested by deploying a function locally, triggering it via a Pub/Sub message, and verifying it executes.

**Acceptance Scenarios**:

1. **Given** a function is deployed locally, **When** a Pub/Sub message is published to its trigger topic, **Then** the function is invoked with the message payload.
2. **Given** a Cloud Run service is deployed locally, **When** an HTTP request is sent to its URL, **Then** the service processes the request and returns a response.
3. **Given** a function has a GCS trigger, **When** a file is uploaded to the trigger bucket, **Then** the function is invoked with the event metadata.

---

### User Story 9 - SDK & CLI Compatibility (Priority: P1)

A developer wants to use existing Google Cloud client libraries and the `gcloud` CLI with minimal configuration changes so they can switch between local and cloud environments seamlessly.

**Why this priority**: Compatibility with official SDKs is essential for the platform to be useful. If developers must rewrite code or use custom clients, adoption will be minimal.

**Independent Test**: Can be fully tested by configuring the endpoint override and running standard SDK operations against the local platform.

**Acceptance Scenarios**:

1. **Given** the platform is running, **When** a developer sets the appropriate environment variable (e.g., `STORAGE_EMULATOR_HOST`), **Then** the Google Cloud client library routes requests to the local platform.
2. **Given** the platform is running, **When** a developer uses a provided CLI wrapper (e.g., `localcloud`), **Then** standard `gcloud`-style commands work against the local platform.
3. **Given** a developer's application uses Google Cloud client libraries, **When** they switch from local to cloud by removing the environment variable, **Then** no code changes are required.

---

### User Story 10 - Spanner Emulation (Priority: P3)

A developer wants to use Cloud Spanner locally to test globally-distributed database schemas and queries without provisioning expensive Spanner instances.

**Why this priority**: Spanner is critical for some applications but has an existing official emulator. Value comes from unified management and cross-service integration.

**Independent Test**: Can be fully tested by creating an instance, database, and table, then running DML queries.

**Acceptance Scenarios**:

1. **Given** the platform is running, **When** a developer creates a Spanner instance and database, **Then** DDL and DML operations work via the client library.
2. **Given** a table with data, **When** the developer runs a parameterized query, **Then** correct results are returned.

---

### User Story 11 - IAM & Authentication Simulation (Priority: P3)

A developer wants basic IAM simulation so their application's permission-checking code works locally without real GCP credentials.

**Why this priority**: Full IAM emulation is complex. Basic support (accept any credentials, optionally enforce simple role checks) is enough for most development workflows.

**Independent Test**: Can be fully tested by configuring a service account locally and verifying that API calls are accepted.

**Acceptance Scenarios**:

1. **Given** the platform is running, **When** a developer makes API calls without credentials, **Then** the platform accepts them by default (permissive mode).
2. **Given** strict mode is enabled, **When** a developer configures local IAM policies, **Then** API calls are authorized or rejected based on the configured policies.

---

### Edge Cases

- What happens when a developer starts the platform while another instance is already running on the same ports?
- How does the system handle requests for GCP services that are not yet emulated?
- What happens when the local disk runs out of space during data storage operations?
- How does the platform handle concurrent requests to the same resource from multiple processes?
- What happens when a developer upgrades the platform while persistent data exists from an older version?
- How does the system respond to malformed API requests that don't match the GCP API specification?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide a single-command startup that launches all configured service emulators.
- **FR-002**: System MUST expose a unified API gateway that routes requests to the appropriate service emulator based on the GCP API path and service headers.
- **FR-003**: System MUST support Google Cloud client library endpoint override via standard environment variables (`*_EMULATOR_HOST`, `GOOGLE_CLOUD_PROJECT`, etc.).
- **FR-004**: System MUST emulate Cloud Storage (GCS) with support for bucket CRUD, object CRUD, listing with prefix/delimiter filtering, and signed URL generation.
- **FR-005**: System MUST emulate Pub/Sub with support for topic and subscription management, message publishing, pull and push delivery, and message acknowledgment.
- **FR-006**: System MUST emulate Firestore with support for document CRUD, collection queries with filters and ordering, batch writes, and real-time listeners.
- **FR-007**: System MUST emulate BigQuery with support for dataset and table management, data loading, and SQL query execution (standard SQL subset).
- **FR-008**: System MUST emulate Secret Manager with support for secret creation, version management, and secret value retrieval.
- **FR-009**: System MUST emulate Cloud Tasks with support for queue management, task creation with HTTP targets, and automatic task dispatch with retry logic.
- **FR-010**: System MUST support both REST and gRPC protocols for services that use them (e.g., Firestore uses gRPC, GCS uses REST).
- **FR-011**: System MUST return API responses that match the structure, status codes, and error formats of the real GCP APIs.
- **FR-012**: System MUST persist all service state by default so that data survives container restarts via Docker volume mounts. A reset command MUST be available to clear all persisted state.
- **FR-013**: System MUST provide a CLI tool for managing the platform (start, stop, status, reset, configure services).
- **FR-014**: System MUST support configurable service selection so developers can start only the services they need.
- **FR-015**: System MUST support Cloud Functions and Cloud Run service emulation with event trigger wiring to other emulated services.
- **FR-016**: System MUST provide a health check endpoint that reports the status of all running service emulators.
- **FR-017**: System MUST support Spanner emulation with DDL, DML, and parameterized queries.
- **FR-018**: System MUST support basic IAM simulation with a permissive mode (accept all requests) and an optional strict mode (enforce configured policies).
- **FR-019**: System MUST provide a CLI wrapper (e.g., `localcloud`) that proxies standard cloud commands to the local platform.
- **FR-020**: System MUST support Bigtable emulation with table management and row-level read/write operations.
- **FR-021**: System MUST log all API requests and responses for debugging purposes, with configurable verbosity.
- **FR-022**: System MUST handle GCP project/resource hierarchy (projects, datasets, instances) in a manner consistent with GCP's resource naming conventions.
- **FR-023**: System MUST provide a lightweight web dashboard accessible via a dedicated port that displays service status, recent API request logs, and read-only data browsing for each emulated service (e.g., list buckets/objects, view topics/subscriptions, browse Firestore documents).
- **FR-024**: System MUST auto-generate a shell-compatible environment export script on startup (usable via `eval $(localcloud env)`) that sets all required `*_EMULATOR_HOST` and `GOOGLE_CLOUD_PROJECT` variables for the running services.
- **FR-025**: System MUST provide a Docker Compose configuration snippet that developers can include in their own `docker-compose.yml` to auto-configure sibling application containers with the correct emulator endpoints.
- **FR-026**: System MUST support declarative seed files (YAML/JSON format) that define initial state for each service (e.g., buckets, topics, datasets, secrets). Seed data MUST be loadable on startup or via a CLI command.
- **FR-027**: System MUST provide a `reset` command that clears all current service state and restores it to the defined seed state. If no seed file exists, reset clears all data entirely.
- **FR-028**: System MUST accept Cloud Logging API calls and store log entries locally, viewable in the web dashboard. No log-based metrics or alerting.
- **FR-029**: System MUST accept Cloud Monitoring API calls and store custom metrics locally, viewable in the web dashboard. No alerting policies or complex metric queries.

### Key Entities

- **Project**: A GCP project context (e.g., `my-project`). All resources are scoped to a project. A developer can configure one or more local projects.
- **Service Emulator**: An individual service implementation (e.g., GCS, Pub/Sub, BigQuery). Each emulator manages its own state and exposes the corresponding GCP API surface.
- **API Gateway**: The unified entry point that receives all API requests, identifies the target service, and routes accordingly. Handles both REST and gRPC protocols.
- **Resource**: Any GCP resource created within an emulator (buckets, topics, datasets, secrets, etc.). Resources follow GCP naming conventions and are scoped to a project.
- **Configuration**: Platform settings including which services to start, port assignments, persistence mode, and IAM mode.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Developers can start the full local emulation environment in under 60 seconds on a standard development laptop.
- **SC-002**: Applications using Google Cloud client libraries require only an endpoint configuration change (environment variable) to switch between local and cloud environments, with zero code changes.
- **SC-003**: The platform emulates at least 10 GCP services (Cloud Storage, Pub/Sub, Firestore, BigQuery, Secret Manager, Cloud Tasks, Spanner, Bigtable, Cloud Logging, Cloud Monitoring) with sufficient fidelity for development and integration testing.
- **SC-004**: API responses from emulated services match the structure and error format of real GCP APIs with at least 95% compatibility for common operations.
- **SC-005**: Organizations adopting this tool reduce their cloud development environment costs by at least 60% by eliminating the need for always-on cloud resources during development.
- **SC-006**: A developer new to the project can install and run their first successful API call against the local platform within 10 minutes.
- **SC-007**: The platform runs reliably on macOS, Linux, and Windows development machines with no more than 2 GB of memory usage for 8 concurrent service emulators.
- **SC-008**: Cross-service event wiring works end-to-end (e.g., a GCS upload triggers a Pub/Sub notification which invokes a Cloud Function) with events delivered within 5 seconds.
- **SC-009**: The platform supports at least 100 concurrent API requests without request failures or significant latency degradation (response times under 500ms for standard operations).

## Assumptions

- Developers are using official Google Cloud client libraries (Python, Java, Go, Node.js, etc.) which support endpoint override via environment variables or client configuration.
- The platform runs as a single Docker container with all services packaged together. State is persisted via Docker volume mounts by default.
- Database emulations (BigQuery, Spanner, Bigtable) will use an underlying general-purpose database engine to provide SQL capabilities without implementing storage engines from scratch.
- The abstraction layer and service emulators are implemented primarily in Java (with Python as secondary). Go is not used.
- Full GCP API parity is not a goal. The platform targets the most commonly used API operations (estimated 80% of developer use cases) rather than edge-case features.
- IAM emulation in permissive mode (default) means the platform accepts any credentials or no credentials. Strict mode is an opt-in advanced feature.
- The platform is designed for development and testing only, not for production use or performance benchmarking.
- Networking services (Load Balancer, VPC, Cloud DNS) are out of scope for the initial version as they have limited applicability in local development.
- The project follows a modular architecture: service providers behind a unified API gateway, with SDK-compatible API surfaces.

<!--
  Sync Impact Report
  ===================
  Version change: 0.0.0 (template) -> 1.0.0 (initial ratification)
  Modified principles: N/A (all new)
  Added sections:
    - Core Principles: 5 principles (I-V)
    - Technical Constraints
    - Development Workflow
    - Governance
  Removed sections: All template placeholders replaced
  Templates requiring updates:
    - .specify/templates/plan-template.md: Constitution Check section ✅ compatible
    - .specify/templates/spec-template.md: ✅ compatible (no changes needed)
    - .specify/templates/tasks-template.md: ✅ compatible (no changes needed)
  Follow-up TODOs: None
-->

# LocalCloud Constitution

## Core Principles

### I. Google-Cloud-in-a-Box

Every emulated GCP service MUST behave identically to its real
counterpart from the SDK client perspective. Application code MUST
work against LocalCloud with only endpoint configuration changes
(environment variables or client settings). Zero application code
changes between local and production.

- Official Google Cloud client libraries MUST be the primary
  integration interface.
- Standard `*_EMULATOR_HOST` environment variables and endpoint
  override patterns MUST be supported for all emulated services.
- API response structures, status codes, and error formats MUST
  match the real GCP API for supported operations.
- Resource naming conventions (projects, locations, resource paths)
  MUST follow GCP patterns.

### II. Single Docker Deployment

The entire platform MUST be deployable as a single Docker container.
No external infrastructure beyond Docker MUST be required for a
developer to start using LocalCloud.

- `docker run` or `localcloud start` MUST be sufficient to launch
  all configured services.
- All dependencies (PostgreSQL, emulator processes, gateway) MUST
  be packaged inside the container.
- State MUST persist across container restarts via Docker volume
  mounts by default.
- The platform MUST start within 60 seconds on a standard
  development laptop and consume no more than 2 GB of memory.

### III. Core Functionality First

Each emulated service MUST implement the core operations that cover
the most common developer use cases. Feature richness is best-effort;
comprehensive GCP API parity is explicitly a non-goal.

- Every emulated service MUST support CRUD operations and the
  primary workflow its real counterpart enables (e.g., Pub/Sub MUST
  support publish/subscribe, BigQuery MUST support SQL queries).
- Target coverage is approximately 80% of common developer
  operations per service.
- Advanced features (e.g., CMEK encryption, IAM policy bindings on
  individual resources, partitioned tables) MAY be deferred or
  omitted if they do not affect the core development workflow.
- When in doubt, prioritize the operations that appear in Google's
  official quickstart guides and getting-started tutorials.

### IV. Transparent Limitations

Every emulated service MUST explicitly document what operations are
NOT supported and SHOULD provide workarounds or migration guidance
for unsupported features. Developers MUST never encounter silent
failures or incorrect behavior without explanation.

- Each service MUST maintain a "Not Supported (v1)" section in
  `contracts/emulated-services.md` listing excluded operations.
- Requests to unsupported API endpoints MUST return a clear error
  response indicating the operation is not emulated, not a generic
  500 or hang.
- Where possible, the error response SHOULD suggest a workaround
  (e.g., "Partitioned tables not supported; use standard tables
  for local development").
- Release notes and README MUST surface limitation summaries so
  developers know before they start what is and is not available.

### V. Lightweight Console

The web console MUST provide a lightweight management interface
inspired by Google Cloud Console. It is a monitoring and debugging
tool, not a full administration platform.

- The console MUST display service status, health, and request logs.
- The console MUST support read-only data browsing for each
  emulated service (list buckets, view topics, browse documents).
- The console SHOULD support basic service management actions
  (start, stop, reset, seed).
- The console MUST NOT replicate the full complexity of Google
  Cloud Console. Focus on what helps local development workflows.

## Technical Constraints

- **Languages**: Java 21 LTS (primary, API gateway and facade
  emulators), Python 3.11+ (CLI tooling, seed processing),
  Solid.js (web console frontend), Armeria (console backend).
  Go is excluded.
- **Persistence**: PostgreSQL (managed by supervisord inside the
  Docker container) for structured data. Filesystem for blob
  storage. ConcurrentHashMap for transient in-memory data.
- **API Gateway**: Armeria (gRPC + REST on unified ports). REST
  services share the gateway port (8080) via path-based routing.
  gRPC services that require dedicated ports (Firestore, Pub/Sub,
  Spanner, Bigtable) get their own ports (9010-9040).
- **External Emulators**: GCS (fake-gcs-server), Pub/Sub, Firestore,
  BigQuery, Spanner, and Bigtable run as external emulator processes
  managed by supervisord. The Java gateway provides facade services
  for Secret Manager, Cloud Tasks, Logging, Monitoring, GKE,
  Compute Engine, and Cloud Run.
- **Packaging**: Single Docker container based on
  `eclipse-temurin:21-jre-jammy`. CLI is pip-installable and manages
  the container from the host.

## Development Workflow

- **Spec-Driven Development**: All features MUST begin with a
  specification (`/speckit.specify`), progress through planning
  (`/speckit.plan`), task generation (`/speckit.tasks`), and
  analysis (`/speckit.analyze`) before implementation.
- **Constitution Gate**: Every plan MUST pass a constitution check
  before Phase 0 research and again after Phase 1 design. Violations
  MUST be justified in the Complexity Tracking table or resolved.
- **Incremental Delivery**: User stories MUST be independently
  implementable and testable. MVP-first: deliver the highest
  priority story before expanding scope.
- **Spec-Code Alignment**: Tasks marked complete in `tasks.md` MUST
  have corresponding implemented code. Spec artifacts (spec.md,
  plan.md, tasks.md) MUST be updated when implementation diverges
  from the original design.

## Governance

This constitution is the highest-authority document for LocalCloud
development decisions. All implementation choices, spec reviews, and
code reviews MUST verify compliance with these principles.

- **Amendments**: Changes to principles require updating this file,
  incrementing the version, and propagating changes to dependent
  templates and existing specs via `/speckit.constitution`.
- **Versioning**: MAJOR for principle removals/redefinitions, MINOR
  for new principles or material expansions, PATCH for wording
  clarifications.
- **Compliance**: Any deviation from a MUST principle requires
  explicit justification in the plan's Complexity Tracking table.
  Unjustified deviations MUST be resolved before implementation
  proceeds.

**Version**: 1.0.0 | **Ratified**: 2026-03-26 | **Last Amended**: 2026-03-26

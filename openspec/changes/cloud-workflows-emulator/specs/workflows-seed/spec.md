## ADDED Requirements

### Requirement: Seed format

The seed YAML file MUST support a `workflows` key nested under `services`. The value MUST be an object containing a `workflows` array. Each element of the array MUST support the following fields: `name` (required, string — the workflow ID), `location` (optional string, defaults to `"us-central1"`), and `source` (required, inline YAML string containing the workflow definition). No other top-level keys are required for a valid workflow seed entry.

#### Scenario: Minimal valid seed entry with default location

WHEN the seed YAML contains:
```yaml
services:
  workflows:
    workflows:
      - name: my-workflow
        source: |
          main:
            steps:
              - returnStep:
                  return: "hello"
```
THEN the workflow `my-workflow` SHALL be deployed to location `us-central1`
AND its source contents SHALL match the provided inline YAML string

#### Scenario: Seed entry with explicit location

WHEN a seed entry specifies `location: us-east1`
THEN the workflow MUST be associated with location `us-east1` after seeding

#### Scenario: Seed format is parallel to other services

WHEN a seed YAML contains both a `gcs` section and a `workflows` section under `services`
THEN both sections MUST be processed independently during seed loading
AND neither section's processing MUST block or affect the other

---

### Requirement: Auto-deploy

Seed workflows MUST be deployed automatically after the gateway is confirmed healthy, using the existing auto-seed mechanism in `docker-entrypoint.sh`. The auto-seed MUST follow the same trigger point used by other services (e.g., Secret Manager, GCS). Workflow seed deployment MUST occur within the same seed pass as other services and MUST NOT require a separate startup phase.

#### Scenario: Workflows are seeded on container startup

WHEN the Docker container starts with a `seed.yaml` containing a `workflows` section
AND the gateway health check passes
THEN the seed mechanism SHALL deploy all listed workflows before the container is considered ready

#### Scenario: Seed is triggered at the same point as other services

WHEN the auto-seed mechanism runs after gateway health
THEN workflow seeding SHALL occur in the same execution pass as GCS, Pub/Sub, and Secret Manager seeding

---

### Requirement: Seed implementation

Workflow seed loading MUST use direct PostgreSQL inserts to populate the `workflows` table, following the same pattern as Secret Manager seeding. The seed MUST NOT invoke the gRPC CreateWorkflow API. The implementation MUST set `revision_id`, `source_contents`, `state`, `create_time`, and `update_time` columns directly via SQL.

#### Scenario: Seeded workflow is present in PostgreSQL without gRPC call

WHEN the seed mechanism processes a workflow entry
THEN a row MUST exist in the `workflows` PostgreSQL table with matching `name` and `source_contents`
AND no gRPC CreateWorkflow call SHALL be recorded in the emulator request log

#### Scenario: Seeded workflow has valid initial revision_id

WHEN a workflow is inserted via seed
THEN its `revision_id` column MUST be set to a non-empty string (e.g., a UUID or integer string)
AND its `state` column MUST be set to `ACTIVE`

---

### Requirement: Idempotent

Re-seeding with a workflow `name` that already exists in the database MUST update the `source_contents` and increment the `revision_id` rather than failing with a duplicate key error. The `update_time` MUST be refreshed. The `create_time` MUST NOT be changed on update. This behavior MUST be implemented using an SQL UPSERT (INSERT ... ON CONFLICT DO UPDATE).

#### Scenario: Re-seeding updates source and increments revision

WHEN the seed is applied a second time with the same workflow `name` but different `source` content
THEN the `source_contents` column SHALL be updated to the new source
AND the `revision_id` SHALL be incremented or replaced with a new unique value
AND no error or exception SHALL be raised

#### Scenario: create_time is preserved on upsert

WHEN a workflow is re-seeded
THEN the `create_time` value in the database MUST remain unchanged from the original insert

#### Scenario: update_time is refreshed on upsert

WHEN a workflow is re-seeded
THEN the `update_time` value MUST be updated to the current timestamp

---

### Requirement: Integration with existing seed.yaml format

The `workflows` section in `seed.yaml` MUST be treated as a peer of existing top-level service sections (`gcs`, `pubsub`, `bigquery`, `secretmanager`, etc.) under the `services` key. The seed parser MUST handle presence or absence of the `workflows` section gracefully — its absence MUST NOT cause an error or warning.

#### Scenario: seed.yaml with no workflows section is valid

WHEN the `seed.yaml` does not contain a `workflows` key under `services`
THEN the seed loading process MUST complete without error
AND no workflows MUST be inserted or modified

#### Scenario: workflows section coexists with other service sections

WHEN `seed.yaml` contains `gcs`, `pubsub`, and `workflows` sections
THEN all three sections MUST be processed
AND each section's data MUST appear in its respective storage (PostgreSQL tables or GCS buckets)

---

### Requirement: Seed validation

The seed loader MUST validate the `source` field of each workflow entry as parseable YAML before attempting a database insert. If the `source` value cannot be parsed as valid YAML, the loader MUST log a warning message identifying the workflow name and the parse error, and MUST skip that workflow entry. The remainder of the seed MUST continue processing normally. The overall seed operation MUST NOT fail or exit due to a single invalid workflow source.

#### Scenario: Invalid YAML source logs warning and skips workflow

WHEN a seed entry contains a `source` field with invalid YAML (e.g., unmatched indentation or invalid syntax)
THEN the seed loader SHALL log a warning containing the workflow name and a description of the parse error
AND that workflow MUST NOT be inserted into the database
AND any remaining valid workflow entries in the seed MUST still be processed and inserted

#### Scenario: Valid entries after an invalid entry are still seeded

WHEN the `workflows` array contains three entries where the second has invalid YAML source
THEN the first and third entries MUST be inserted successfully
AND the second entry MUST be skipped with a warning log

#### Scenario: Entire seed does not fail due to one invalid workflow

WHEN one workflow seed entry has an invalid `source`
THEN the seed operation MUST return a success status overall
AND other service sections (e.g., `gcs`, `pubsub`) in the same seed MUST not be affected

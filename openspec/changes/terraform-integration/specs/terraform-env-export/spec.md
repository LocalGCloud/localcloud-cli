## ADDED Requirements

### Requirement: Terraform env format outputs GOOGLE_*_CUSTOM_ENDPOINT variables
The `GET /_localcloud/env?format=terraform` endpoint SHALL return shell export statements for all `GOOGLE_*_CUSTOM_ENDPOINT` variables pointing at LocalCloud service ports.

#### Scenario: Terraform env output
- **WHEN** `GET /_localcloud/env?format=terraform` is called
- **THEN** the response SHALL contain `export GOOGLE_STORAGE_CUSTOM_ENDPOINT=http://localhost:4443` and similar lines for all enabled services, plus `export GOOGLE_APPLICATION_CREDENTIALS=/dev/null`

#### Scenario: Only enabled services included
- **WHEN** `format=terraform` is requested and Spanner is disabled
- **THEN** `GOOGLE_SPANNER_CUSTOM_ENDPOINT` SHALL NOT appear in the output

### Requirement: Terraform env format works with eval
The output SHALL be directly sourceable via `eval $(curl -s http://localhost:8080/_localcloud/env?format=terraform)`.

#### Scenario: Shell eval
- **WHEN** a user runs `eval $(curl -s http://localhost:8080/_localcloud/env?format=terraform)`
- **THEN** all `GOOGLE_*_CUSTOM_ENDPOINT` variables SHALL be set in the current shell

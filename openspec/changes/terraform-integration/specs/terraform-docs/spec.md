## ADDED Requirements

### Requirement: Example Terraform configuration shipped
The project SHALL include example Terraform files demonstrating LocalCloud usage in a `terraform/examples/` directory.

#### Scenario: Basic infrastructure example
- **WHEN** a user copies the example Terraform config
- **THEN** it SHALL contain resources for storage bucket, Pub/Sub topic, BigQuery dataset, and a comment block explaining the LocalCloud setup

### Requirement: CI/CD pipeline examples
The project SHALL include example pipeline configurations for GitHub Actions and GitLab CI that use LocalCloud for Terraform testing.

#### Scenario: GitHub Actions example
- **WHEN** a user copies the GitHub Actions workflow
- **THEN** it SHALL start a LocalCloud container, source the Terraform env vars, run `terraform plan` and `terraform apply`, and verify resources were created

### Requirement: Compatibility matrix documented
The README or docs SHALL include a table showing which `google_*` Terraform resources are supported, partially supported, or unsupported.

#### Scenario: User checks compatibility
- **WHEN** a user looks for Terraform support in the documentation
- **THEN** they SHALL find a clear table with resource name, status (supported/partial/unsupported), and notes

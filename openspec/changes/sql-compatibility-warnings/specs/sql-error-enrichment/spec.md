## ADDED Requirements

### Requirement: DuckDB errors translated to user-friendly messages
When a BigQuery query fails with a DuckDB error, the system SHALL translate known error patterns into actionable messages with alternatives.

#### Scenario: Unknown function error enriched
- **WHEN** a BigQuery query fails with "Catalog Error: Scalar Function with name approx_count_distinct does not exist"
- **THEN** the error response SHALL contain "Function APPROX_COUNT_DISTINCT is not supported by the BigQuery emulator (DuckDB). Use COUNT(DISTINCT ...) instead."

#### Scenario: Unsupported type error enriched
- **WHEN** a BigQuery query fails with a GEOGRAPHY-related error
- **THEN** the error response SHALL mention "GEOGRAPHY type is not supported by the BigQuery emulator"

#### Scenario: Unknown errors passed through unchanged
- **WHEN** a query fails with an error that doesn't match any known pattern
- **THEN** the original error message SHALL be returned as-is

### Requirement: Spanner errors translated similarly
When a Spanner query fails with a known emulator limitation, the system SHALL translate the error.

#### Scenario: Spanner unsupported feature
- **WHEN** a Spanner query fails due to an unsupported feature
- **THEN** the error SHALL indicate the feature is not supported by the Spanner emulator

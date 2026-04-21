## ADDED Requirements

### Requirement: Compatibility data file for BigQuery emulator
The system SHALL include a curated data file listing unsupported BigQuery SQL features with alternatives, covering at minimum: functions, clauses, types, and operators.

#### Scenario: BigQuery compatibility data structure
- **WHEN** the compatibility data is loaded for BigQuery
- **THEN** it SHALL contain entries for unsupported functions (e.g., APPROX_COUNT_DISTINCT, SAFE_DIVIDE, SAFE_CAST), unsupported types (e.g., GEOGRAPHY, BIGNUMERIC, INTERVAL), and unsupported clauses (e.g., TABLESAMPLE, PIVOT, UNPIVOT, QUALIFY, FOR SYSTEM_TIME AS OF)

### Requirement: Compatibility data file for Spanner emulator
The system SHALL include a curated data file listing unsupported Spanner SQL features.

#### Scenario: Spanner compatibility data structure
- **WHEN** the compatibility data is loaded for Spanner
- **THEN** it SHALL contain entries for unsupported features (e.g., MERGE, TABLESAMPLE) with alternatives where available

### Requirement: Each entry includes an alternative when available
Each unsupported feature entry SHALL include a human-readable alternative suggestion when one exists.

#### Scenario: Entry with alternative
- **WHEN** the compatibility data contains APPROX_COUNT_DISTINCT
- **THEN** the entry SHALL include the alternative "Use COUNT(DISTINCT ...)"

#### Scenario: Entry without alternative
- **WHEN** the compatibility data contains GEOGRAPHY
- **THEN** the entry SHALL indicate "Not supported" without a specific alternative

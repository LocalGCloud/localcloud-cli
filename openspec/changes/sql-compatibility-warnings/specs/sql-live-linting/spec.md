## ADDED Requirements

### Requirement: Unsupported SQL highlighted with yellow warning underline
The SQL editor SHALL underline unsupported keywords, functions, and clauses with a yellow warning indicator as the user types.

#### Scenario: Unsupported function detected
- **WHEN** a user types `SELECT APPROX_COUNT_DISTINCT(col) FROM table` in the BigQuery editor
- **THEN** `APPROX_COUNT_DISTINCT` SHALL be underlined in yellow with a warning tooltip

#### Scenario: Supported syntax not flagged
- **WHEN** a user types `SELECT COUNT(DISTINCT col) FROM table` in the BigQuery editor
- **THEN** no warnings SHALL appear

### Requirement: Warning tooltip shows alternative
Each warning underline SHALL display a tooltip on hover containing the unsupported feature name and a suggested alternative when available.

#### Scenario: Tooltip with alternative
- **WHEN** a user hovers over the underlined `APPROX_COUNT_DISTINCT`
- **THEN** the tooltip SHALL show "APPROX_COUNT_DISTINCT is not supported by the BigQuery emulator. Use COUNT(DISTINCT ...) instead."

#### Scenario: Tooltip without alternative
- **WHEN** a user hovers over an underlined `GEOGRAPHY` type
- **THEN** the tooltip SHALL show "GEOGRAPHY type is not supported by the BigQuery emulator (DuckDB)."

### Requirement: Linting is per-dialect
The linter SHALL use the correct compatibility map based on the active editor dialect (bigquery, spanner, postgresql).

#### Scenario: BigQuery-specific warning
- **WHEN** a user is in the BigQuery SQL editor and types `SAFE_DIVIDE(a, b)`
- **THEN** `SAFE_DIVIDE` SHALL be underlined (not supported by DuckDB)

#### Scenario: PostgreSQL has no warnings
- **WHEN** a user is in the Secret Manager or Bigtable SQL editor (PostgreSQL dialect)
- **THEN** no compatibility warnings SHALL appear (PostgreSQL queries run against real PostgreSQL)

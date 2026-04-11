## ADDED Requirements

### Requirement: BigQuery SQL query console
The Data Browser SHALL include an interactive SQL editor for BigQuery. Developers can type arbitrary SQL queries, execute them against the BigQuery emulator, and see results in a formatted table. The editor SHALL support query history and syntax highlighting.

#### Scenario: Run a SELECT query
- **WHEN** developer types `SELECT name, amount FROM app_analytics.orders WHERE status = 'completed'` and clicks Execute
- **THEN** the results are displayed in a table with column headers and formatted values

#### Scenario: Query error feedback
- **WHEN** developer runs a query with a syntax error
- **THEN** the error message from BigQuery is displayed below the editor with the error position highlighted

### Requirement: Spanner SQL query console
The Data Browser SHALL include an interactive SQL editor for Spanner. Developers select an instance and database, type SQL (GoogleSQL or PostgreSQL dialect), execute, and see results. The editor SHALL show the database's DDL schema for reference.

#### Scenario: Run a parameterized query
- **WHEN** developer selects `local-instance/users_db` and runs `SELECT * FROM Persons WHERE WorksFor = 'PayPal'`
- **THEN** the results show matching rows with all columns

#### Scenario: View schema alongside query
- **WHEN** developer opens the Spanner query console for a database
- **THEN** a sidebar shows all table schemas (columns, types, keys) extracted from DDL

### Requirement: Memorystore Redis CLI
The Data Browser SHALL include an interactive Redis command input for Memorystore. Developers type Redis commands and see responses formatted as they would appear in `redis-cli`.

#### Scenario: Run Redis commands
- **WHEN** developer types `HGETALL user:1` and presses Enter
- **THEN** the response shows all hash fields and values in the standard Redis output format

#### Scenario: Command history
- **WHEN** developer presses Up arrow in the Redis CLI input
- **THEN** the previous command is recalled (like a real terminal)

### Requirement: Pub/Sub message publisher with templates
The Data Browser SHALL include a message publish form for Pub/Sub topics. Developers select a topic, type message data (with JSON validation), add optional attributes as key-value pairs, and publish. The form SHALL offer message templates based on the topic name.

#### Scenario: Publish a JSON message
- **WHEN** developer selects `user-events` topic, types `{"event":"user.login","userId":"1"}`, and clicks Publish
- **THEN** the message is published to the topic and appears in the message browser for associated subscriptions

#### Scenario: Template suggestion
- **WHEN** developer selects `order-events` topic
- **THEN** the form suggests templates like "order.created", "order.shipped" with pre-filled JSON structures

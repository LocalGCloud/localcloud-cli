# Workflow Import Connector Spec

## Overview

The workflow import connector is a backend service that connects to a remote workflow source via its REST API to discover workflows, templates, deployed services, and proxy URLs. Connection configuration (URL and username) is stored in PostgreSQL. The connector exposes admin REST endpoints used by the import UI and the URL rewriter.

## ADDED Requirements

### Requirement: Connect to Remote Source API

The system SHALL accept a remote source base URL and username as connection configuration. The system SHALL store the connection configuration in PostgreSQL. The system SHALL validate the connection by calling a remote API endpoint and return a success or failure response.

#### Scenario: User provides valid source URL and username

WHEN a POST request is sent to `/_localcloud/workflow/connect` with a JSON body containing `url` and `username`
THEN the system SHALL store the URL and username in the `workflow_config` table
AND the system SHALL call `GET {url}/api/list` to validate reachability
AND the system SHALL return HTTP 200 with `{"connected": true, "envCount": N}` where N is the number of discovered environments

#### Scenario: Remote source server is unreachable

WHEN a POST request is sent to `/_localcloud/workflow/connect` with a URL that cannot be reached
THEN the system SHALL return HTTP 422 with an error body containing `{"error": "Cannot connect to remote source at {url}: {reason}"}`
AND the system SHALL NOT persist the connection config

#### Scenario: Missing required fields

WHEN a POST request is sent to `/_localcloud/workflow/connect` with a missing `url` or `username` field
THEN the system SHALL return HTTP 400 with `{"error": "url and username are required"}`

---

### Requirement: List Workflows from Remote Source

The system SHALL retrieve the list of workflows available to the configured user from the remote source API. The system SHALL indicate which workflows are already imported into LocalCloud.

#### Scenario: Retrieve workflow list for connected user

WHEN a GET request is sent to `/_localcloud/workflow/workflows`
AND a remote source connection is configured
THEN the system SHALL call `GET {sourceUrl}/api/workflows/list?user={username}`
AND the system SHALL return HTTP 200 with a JSON array of workflow objects, each containing `name`, `stepCount`, and `alreadyImported` (boolean)

#### Scenario: No connection configured

WHEN a GET request is sent to `/_localcloud/workflow/workflows`
AND no remote source connection config is stored
THEN the system SHALL return HTTP 409 with `{"error": "No remote source connection configured. Call POST /_localcloud/workflow/connect first."}`

#### Scenario: Remote API returns an error

WHEN a GET request is sent to `/_localcloud/workflow/workflows`
AND the remote source API responds with a non-2xx status
THEN the system SHALL return HTTP 502 with `{"error": "Remote API error: {status} {body}"}`

---

### Requirement: Fetch Workflow Source YAML

The system SHALL retrieve the YAML source of a specific workflow from the remote source API.

#### Scenario: Fetch source for an existing workflow

WHEN `RemoteSourceClient.getWorkflowSource(username, workflowName)` is called
THEN the system SHALL call `GET {sourceUrl}/api/workflows/source?user={username}&workflow={workflowName}`
AND the system SHALL return the raw YAML string from the `source` field of the response body

#### Scenario: Workflow not found in remote source

WHEN `RemoteSourceClient.getWorkflowSource(username, workflowName)` is called for a workflow name that does not exist
AND the remote source returns HTTP 404
THEN the system SHALL throw a `RemoteApiException` with message `"Workflow not found: {workflowName}"`

---

### Requirement: Discover Deployed Services and Proxy URLs

The system SHALL discover the services deployed in remote source environments and extract their proxy URLs for use in preset auto-population.

#### Scenario: Retrieve deployed services for all environments

WHEN a GET request is sent to `/_localcloud/workflow/services`
THEN the system SHALL call `GET {sourceUrl}/api/list` to list all environments
AND the system SHALL call `GET {sourceUrl}/api/status/{envId}` for the environment owned by the configured username
AND the system SHALL return HTTP 200 with a JSON array of service objects, each containing `name`, `proxyUrl`, and `envId`

#### Scenario: No environments owned by configured user

WHEN a GET request is sent to `/_localcloud/workflow/services`
AND the remote source API returns no environments with owner matching the configured username
THEN the system SHALL return HTTP 200 with an empty array `[]`

#### Scenario: Status endpoint returns service endpoints

WHEN `RemoteSourceClient.getServiceEndpoints(envId)` is called
THEN the system SHALL call `GET {sourceUrl}/api/status/{envId}`
AND the system SHALL extract the `services` array from the response
AND the system SHALL return a list of `{name, proxyUrl}` objects where `proxyUrl` is the full proxy base URL including host and `/proxy/{env}/{service}` path

---

### Requirement: Store Connection Configuration

The system SHALL persist the remote source connection configuration so it survives server restarts.

#### Scenario: Connection config is persisted to PostgreSQL

WHEN a successful `POST /_localcloud/workflow/connect` request is processed
THEN the system SHALL upsert a row in `workflow_config` with keys `source_url` and `source_username`
AND subsequent calls to `GET /_localcloud/workflow/workflows` SHALL use the persisted URL and username without requiring reconnection

#### Scenario: Retrieve current connection status

WHEN a GET request is sent to `/_localcloud/workflow/connect`
THEN the system SHALL return HTTP 200 with `{"connected": true, "url": "...", "username": "..."}` if a config is stored
OR return HTTP 200 with `{"connected": false}` if no config is stored

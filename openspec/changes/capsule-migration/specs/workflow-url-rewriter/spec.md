# Workflow URL Rewriter Spec

## Overview

The workflow URL rewriter scans imported workflow YAML for hardcoded remote proxy URLs, extracts the service name and path suffix from each URL, and replaces the URL with a `${SERVICE_NAME_URL}/path` environment variable pattern. It also generates the corresponding env var entries (one per preset) for each discovered service.

## ADDED Requirements

### Requirement: Detect Remote Proxy URLs in YAML

The system SHALL detect all occurrences of remote proxy URLs in a workflow YAML string. A remote proxy URL matches the pattern `http://{host}/proxy/{env}/{service}/{path}` where `{host}` is any IP or hostname, `{env}` is the source environment name, `{service}` is the deployed service name, and `{path}` is the remaining path (may be empty).

#### Scenario: Detect proxy URL in a step's url field

WHEN a workflow YAML contains `url: http://10.179.131.124/proxy/jay-env/payment-service/api/charge`
THEN `WorkflowUrlRewriter.detect(yaml)` SHALL return a match containing:
  - `fullUrl`: `http://10.179.131.124/proxy/jay-env/payment-service/api/charge`
  - `envName`: `jay-env`
  - `serviceName`: `payment-service`
  - `pathSuffix`: `/api/charge`
  - `proxyBase`: `http://10.179.131.124/proxy/jay-env/payment-service`

#### Scenario: Detect multiple proxy URLs across different services

WHEN a workflow YAML contains proxy URLs for both `payment-service` and `order-service`
THEN `WorkflowUrlRewriter.detect(yaml)` SHALL return one match entry per unique service
AND each match SHALL correctly identify its `serviceName` and `pathSuffix`

#### Scenario: No proxy URLs present

WHEN a workflow YAML contains no strings matching the pattern `/proxy/{env}/{service}/`
THEN `WorkflowUrlRewriter.detect(yaml)` SHALL return an empty list

#### Scenario: URL inside a string expression

WHEN a workflow YAML contains a proxy URL inside a quoted string expression such as `args: ["http://10.x.x.x/proxy/env/svc/path"]`
THEN the rewriter SHALL detect and rewrite the URL within the expression

---

### Requirement: Replace Proxy URLs with `${VAR}` Pattern

The system SHALL replace each detected remote proxy URL with the `${SERVICE_NAME_URL}` variable pattern followed by the original path suffix. The variable name SHALL be derived from the service name using uppercase and underscores.

#### Scenario: Replace single proxy URL

WHEN a workflow YAML contains `url: http://10.179.131.124/proxy/jay-env/payment-service/api/charge`
THEN `WorkflowUrlRewriter.rewrite(yaml)` SHALL return a YAML string where the URL is replaced with `${PAYMENT_SERVICE_URL}/api/charge`

#### Scenario: Derive variable name from service name

WHEN the service name is `payment-service`
THEN the generated variable name SHALL be `PAYMENT_SERVICE_URL`

WHEN the service name is `order-processing-v2`
THEN the generated variable name SHALL be `ORDER_PROCESSING_V2_URL`

WHEN the service name is `notify`
THEN the generated variable name SHALL be `NOTIFY_URL`

#### Scenario: Replace all occurrences of the same service URL

WHEN a proxy URL for `payment-service` appears multiple times in the YAML (different paths)
THEN all occurrences SHALL be rewritten to use `${PAYMENT_SERVICE_URL}` with their respective path suffixes
AND only one env var entry SHALL be generated for `PAYMENT_SERVICE_URL`

#### Scenario: Replace URLs for multiple distinct services

WHEN a workflow YAML contains proxy URLs for `payment-service` and `order-service`
THEN `${PAYMENT_SERVICE_URL}` SHALL replace all payment-service proxy URLs
AND `${ORDER_SERVICE_URL}` SHALL replace all order-service proxy URLs

#### Scenario: Proxy URL with no path suffix

WHEN a proxy URL ends immediately after the service name with no trailing path (e.g., `http://10.x.x.x/proxy/env/service`)
THEN the rewritten value SHALL be `${SERVICE_NAME_URL}` with no trailing slash or path

---

### Requirement: Generate Env Var Entries from Discovered Service Names

The system SHALL generate env var table entries for every service discovered during URL rewriting. Entries SHALL be generated for all three standard presets: `local`, `remote`, and `production`.

#### Scenario: Generate three preset rows for each discovered service

WHEN `WorkflowUrlRewriter.rewrite(yaml)` processes a YAML containing proxy URLs for `payment-service` with proxy base `http://10.179.131.124/proxy/jay-env/payment-service`
THEN the system SHALL return env var entries including:
  - `{varName: "PAYMENT_SERVICE_URL", preset: "remote", varValue: "http://10.179.131.124/proxy/jay-env/payment-service"}`
  - `{varName: "PAYMENT_SERVICE_URL", preset: "local", varValue: ""}`
  - `{varName: "PAYMENT_SERVICE_URL", preset: "production", varValue: ""}`

#### Scenario: Remote preset value is auto-populated from proxy base

WHEN a service proxy base URL is discovered during rewriting
THEN the `remote` preset value for that service's env var SHALL be set to the full proxy base URL (host + `/proxy/{env}/{service}` — no trailing slash)

#### Scenario: Local and Production preset values are left empty on import

WHEN env var entries are generated during workflow import
THEN the `local` and `production` preset values SHALL be empty strings
AND the user SHALL be expected to fill them in via the env vars UI after import

---

### Requirement: Handle Edge Cases

The system SHALL correctly handle URL patterns embedded in YAML expressions, multi-line strings, and nested structures.

#### Scenario: URL inside a YAML block scalar

WHEN a proxy URL appears inside a YAML literal block scalar (`|`) or folded block scalar (`>`)
THEN the rewriter SHALL detect and replace the URL within the block scalar content

#### Scenario: Same service appears with different environments in the same YAML

WHEN a workflow YAML contains URLs for `payment-service` under two different source environment names (e.g., `/proxy/env-a/payment-service/` and `/proxy/env-b/payment-service/`)
THEN both URLs SHALL be rewritten to the same `${PAYMENT_SERVICE_URL}` variable
AND the `remote` preset value SHALL use the URL from the first detected occurrence

#### Scenario: Non-proxy URL is not rewritten

WHEN a workflow YAML contains `url: https://api.stripe.com/v1/charges`
THEN the rewriter SHALL NOT modify this URL
AND it SHALL NOT generate any env var entry for it

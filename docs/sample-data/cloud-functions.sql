-- Cloud Functions (2nd Gen): Emulator Schema + Sample Data
-- Features: JSONB build/service configs, event triggers, BYTEA proto,
--           state management, runtime/entry point tracking

CREATE TABLE IF NOT EXISTS cloud_functions (
    project_id     VARCHAR(255) NOT NULL,
    location_id    VARCHAR(255) NOT NULL,
    function_id    VARCHAR(255) NOT NULL,
    runtime        VARCHAR(128) DEFAULT '',
    entry_point    VARCHAR(255) DEFAULT '',
    max_instance_count INT DEFAULT 10,
    min_instance_count INT DEFAULT 0,
    available_cpu    VARCHAR(20) DEFAULT '1',
    available_memory  VARCHAR(20) DEFAULT '512Mi',
    timeout_seconds   INT DEFAULT 60,
    ingress_settings  VARCHAR(32) DEFAULT 'ALLOW_ALL',
    vpc_connector    VARCHAR(255),
    service_account  VARCHAR(512),
    build_config    JSONB DEFAULT '{}',
    service_config  JSONB DEFAULT '{}',
    event_trigger   JSONB DEFAULT '{}',
    state           VARCHAR(32) DEFAULT 'ACTIVE',
    url             VARCHAR(1024),
    labels          JSONB DEFAULT '{}',
    function_proto  BYTEA NOT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, location_id, function_id)
);

INSERT INTO cloud_functions (project_id, location_id, function_id, runtime, entry_point, max_instance_count, min_instance_count, available_cpu, available_memory, timeout_seconds, service_account, build_config, service_config, event_trigger, state, url, labels, created_at, updated_at) VALUES
    -- HTTP function: user registration webhook
    ('local-project', 'us-central1', 'user-registration-webhook',
     'nodejs20', 'handleRegistration', 10, 0, '1', '256Mi', 30,
     'functions-sa@local-project.iam.gserviceaccount.com',
     '{"runtime":"nodejs20","entryPoint":"handleRegistration","source":"gs://function-sources/user-registration/v1.2.0","buildpack":"google.nodejs","environmentVariables":{"NODE_ENV":"production"}}',
     '{"maxInstanceCount":10,"minInstanceCount":0,"availableCpu":"1","availableMemory":"256Mi","ingressSettings":"ALLOW_ALL","allTrafficOnLatestRevision":true,"serviceAccountEmail":"functions-sa@local-project.iam.gserviceaccount.com"}',
     '{}',
     'ACTIVE', 'https://us-central1-local-project.cloudfunctions.net/user-registration-webhook',
     '{"team":"backend","trigger":"http","language":"node"}', '2024-02-15T10:00:00Z', '2025-05-18T09:00:00Z'),

    -- Event-driven: Pub/Sub image processor
    ('local-project', 'us-central1', 'image-processor',
     'python311', 'process_image', 20, 1, '2', '1024Mi', 300,
     'functions-sa@local-project.iam.gserviceaccount.com',
     '{"runtime":"python311","entryPoint":"process_image","source":"gs://function-sources/image-processor/v3.0.1","buildpack":"google.python","environmentVariables":{"BUCKET":"uploads","MAX_SIZE_MB":"10"}}',
     '{"maxInstanceCount":20,"minInstanceCount":1,"availableCpu":"2","availableMemory":"1024Mi","timeoutSeconds":300,"ingressSettings":"ALLOW_INTERNAL_ONLY","serviceAccountEmail":"functions-sa@local-project.iam.gserviceaccount.com"}',
     '{"trigger":"pubsub","topic":"projects/local-project/topics/image-uploads","eventType":"google.cloud.pubsub.topic.v1.messagePublished","retryPolicy":"RETRY_ON_FAILURE"}',
     'ACTIVE', NULL,
     '{"team":"backend","trigger":"pubsub","language":"python"}', '2024-03-01T14:00:00Z', '2025-05-19T11:00:00Z'),

    -- Event-driven: audit log sink
    ('local-project', 'europe-west1', 'audit-log-sink',
     'go121', 'processAuditLog', 5, 0, '1', '512Mi', 120,
     'audit-sa@local-project.iam.gserviceaccount.com',
     '{"runtime":"go121","entryPoint":"processAuditLog","source":"gs://function-sources/audit-log-sink/v1.0.0","buildpack":"google.go"}',
     '{"maxInstanceCount":5,"minInstanceCount":0,"availableCpu":"1","availableMemory":"512Mi","timeoutSeconds":120,"ingressSettings":"ALLOW_INTERNAL_ONLY","serviceAccountEmail":"audit-sa@local-project.iam.gserviceaccount.com"}',
     '{"trigger":"audit_log","eventType":"google.cloud.audit.log.v1.written","serviceName":"allServices"}',
     'ACTIVE', NULL,
     '{"team":"security","trigger":"audit_log","language":"go"}', '2024-06-01T09:00:00Z', '2025-05-17T14:00:00Z'),

    -- HTTP function: Slack command handler
    ('local-project', 'us-central1', 'slack-command-handler',
     'nodejs20', 'handleSlackCommand', 3, 0, '1', '128Mi', 10,
     'functions-sa@local-project.iam.gserviceaccount.com',
     '{"runtime":"nodejs20","entryPoint":"handleSlackCommand","source":"gs://function-sources/slack-handler/v2.1.0","environmentVariables":{"SLACK_SIGNING_SECRET":"***"}}',
     '{"maxInstanceCount":3,"minInstanceCount":0,"availableCpu":"1","availableMemory":"128Mi","ingressSettings":"ALLOW_ALL","timeoutSeconds":10}',
     '{}',
     'ACTIVE', 'https://us-central1-local-project.cloudfunctions.net/slack-command-handler',
     '{"team":"backend","trigger":"http","language":"node","integration":"slack"}', '2024-04-10T11:00:00Z', '2025-05-15T10:00:00Z'),

    -- Storage event: thumbnail generator
    ('local-project', 'us-central1', 'thumbnail-generator',
     'python311', 'generate_thumbnail', 30, 2, '2', '2048Mi', 540,
     'functions-sa@local-project.iam.gserviceaccount.com',
     '{"runtime":"python311","entryPoint":"generate_thumbnail","source":"gs://function-sources/thumbnail-gen/v4.0.0","buildpack":"google.python","environmentVariables":{"THUMBNAIL_SIZES":"[150,300,600]","QUALITY":"85"}}',
     '{"maxInstanceCount":30,"minInstanceCount":2,"availableCpu":"2","availableMemory":"2048Mi","timeoutSeconds":540,"vpcConnector":"projects/local-project/locations/us-central1/connectors/data-vpc","serviceAccountEmail":"functions-sa@local-project.iam.gserviceaccount.com"}',
     '{"trigger":"eventarc","eventType":"google.cloud.storage.object.v1.finalized","eventFilters":{"bucket":"user-uploads"},"retryPolicy":"RETRY_ON_FAILURE"}',
     'ACTIVE', NULL,
     '{"team":"backend","trigger":"storage","language":"python"}', '2024-05-01T08:00:00Z', '2025-05-20T08:00:00Z'),

    -- HTTP: webhook receiver for third-party
    ('local-project', 'us-central1', 'webhook-receiver',
     'java21', 'com.example.WebhookHandler', 5, 0, '1', '512Mi', 30,
     'functions-sa@local-project.iam.gserviceaccount.com',
     '{"runtime":"java21","entryPoint":"com.example.WebhookHandler","source":"gs://function-sources/webhook-receiver/v1.0.0","buildpack":"google.java"}',
     '{"maxInstanceCount":5,"minInstanceCount":0,"availableCpu":"1","availableMemory":"512Mi","ingressSettings":"ALLOW_ALL","timeoutSeconds":30}',
     '{}',
     'ACTIVE', 'https://us-central1-local-project.cloudfunctions.net/webhook-receiver',
     '{"team":"platform","trigger":"http","language":"java"}', '2024-07-15T13:00:00Z', '2025-05-16T12:00:00Z'),

    -- Disabled: old function
    ('local-project', 'us-central1', 'legacy-cron-function',
     'nodejs18', 'cronTask', 0, 0, '1', '256Mi', 60,
     'functions-sa@local-project.iam.gserviceaccount.com',
     '{"runtime":"nodejs18","entryPoint":"cronTask","source":"gs://function-sources/legacy-cron/v0.1.0"}',
     '{"maxInstanceCount":1,"minInstanceCount":0,"availableCpu":"1","availableMemory":"256Mi"}',
     '{}',
     'INACTIVE', NULL,
     '{"team":"eng","trigger":"http","temporary":"true"}', '2024-01-01T00:00:00Z', '2025-01-01T00:00:00Z'),

    ('demo-project', 'us-central1', 'demo-hello',
     'nodejs20', 'helloWorld', 1, 0, '1', '128Mi', 10,
     NULL,
     '{"runtime":"nodejs20","entryPoint":"helloWorld","source":"gs://demo-functions/hello/v1.0.0"}',
     '{"maxInstanceCount":1,"availableCpu":"1","availableMemory":"128Mi","ingressSettings":"ALLOW_ALL"}',
     '{}',
     'ACTIVE', 'https://us-central1-demo-project.cloudfunctions.net/demo-hello',
     '{"env":"demo"}', '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z');

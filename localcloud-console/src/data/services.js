export const SERVICE_META = {
    gcs:           { label: 'Cloud Storage',    description: 'Object storage for companies of all sizes. Store any amount of data and retrieve it as often as you like.' },
    pubsub:        { label: 'Pub/Sub',          description: 'Global messaging and event ingestion. Reliable, many-to-many, asynchronous messaging between services.' },
    firestore:     { label: 'Firestore',        description: 'Flexible, scalable NoSQL cloud database for mobile, web, and server development.', tag: 'Coming up' },
    bigquery:      { label: 'BigQuery',         description: 'Serverless, highly scalable, and cost-effective multicloud data warehouse for analytics.' },
    secretmanager: { label: 'Secret Manager',   description: 'Store API keys, passwords, and certificates. Supports create, list, version management (add, enable, disable, destroy), and secret value access.' },
    cloudtasks:    { label: 'Cloud Tasks',      description: 'Manage the execution of large numbers of distributed tasks, callbacks, and webhooks.' },
    spanner:       { label: 'Spanner',          description: 'Fully managed relational database with unlimited scale, strong consistency, and up to 99.999% availability.' },
    bigtable:      { label: 'Bigtable',         description: 'A fully managed, scalable NoSQL database service for large analytical and operational workloads.' },
    logging:       { label: 'Cloud Logging',    description: 'Real-time log management and analysis. Store, search, analyze, and alert on log data.' },
    monitoring:    { label: 'Cloud Monitoring', description: 'Full-stack monitoring for cloud applications. Metrics, uptime checks, dashboards, alert policies, and notification channels.' },
    gke:           { label: 'GKE',              description: 'Secured and managed Kubernetes service with four-way auto-scaling and multi-cluster support.' },
    compute:       { label: 'Compute Engine',   description: 'Virtual machines running in Google\'s data center. Scalable, high-performance VMs.' },
    cloudrun:      { label: 'Cloud Run',        description: 'Fully managed compute platform for deploying and scaling containerized applications quickly and securely.' },
    memorystore:   { label: 'Memorystore',      description: 'Fully managed in-memory data store service. Supports AUTH (password) and persistence (RDB/AOF) configuration.' },
    workflows:     { label: 'Cloud Workflows',  description: 'Orchestrate and automate Google Cloud and HTTP-based API services with serverless workflows.' },
    vertexai:      { label: 'Vertex AI',        description: 'Local Gemini-style generative AI endpoints with deterministic stub responses and optional backend wiring.', tag: 'Coming up' },
    kms:           { label: 'Cloud KMS',         description: 'Local key rings, crypto keys, key versions. Supports symmetric and asymmetric (RSA/EC) algorithms for development.' },
    cloudsql:      { label: 'Cloud SQL',         description: 'Cloud SQL Admin API control plane for PostgreSQL and MySQL-compatible development workflows.' },
    alloydb:       { label: 'AlloyDB',          description: 'PostgreSQL-compatible managed database emulation with cluster/instance/backup CRUD and pgvector support.' },
    cloudscheduler: { label: 'Cloud Scheduler',  description: 'Cron job scheduling service. Create, pause, resume, and delete jobs with HTTP, Pub/Sub, or App Engine targets.' },
    cloudfunctions: { label: 'Cloud Functions',   description: 'Lightweight serverless compute. Supports Pub/Sub, Cloud Storage Eventarc, and Audit Log triggers backed by Cloud Run emulator.' },
    dataproc:      { label: 'Dataproc',          description: 'Managed Apache Spark and Hadoop service emulation with local spark-submit execution for Spark, PySpark, and SparkSQL jobs.' },
    cloudiam:      { label: 'Cloud IAM',         description: 'Identity and Access Management with role validation against known GCP roles. Permissive mode available via --permissive-iam flag.' },
    cloudbilling:  { label: 'Cloud Billing',     description: 'Billing account management and budget CRUD. Create, list, and delete budgets with threshold rules.' },
    serviceusage:  { label: 'Service Usage',     description: 'Service enablement and consumer quota metric stubs for Terraform compatibility.' },
};

export const SQL_SERVICES = [
    { id: 'pubsub', label: 'Pub/Sub', dialect: 'postgresql', dialectLabel: 'Pub/Sub SQL', icon: 'pubsub',
      placeholder: "" },
    { id: 'gcs', label: 'Cloud Storage', dialect: 'bigquery', dialectLabel: 'BigQuery SQL', icon: 'gcs',
      placeholder: "" },
    { id: 'bigquery', label: 'BigQuery', dialect: 'bigquery', dialectLabel: 'BigQuery SQL', icon: 'bigquery',
      placeholder: "SELECT * FROM `dataset.table` LIMIT 10" },
    { id: 'spanner', label: 'Spanner', dialect: 'googlesql', dialectLabel: 'GoogleSQL', icon: 'spanner',
      placeholder: "SELECT * FROM my_table LIMIT 10" },
    { id: 'cloudtasks', label: 'Cloud Tasks', dialect: 'postgresql', dialectLabel: 'PostgreSQL', icon: 'cloudtasks',
      placeholder: "" },
    { id: 'logging', label: 'Logging', dialect: 'postgresql', dialectLabel: 'PostgreSQL', icon: 'logging',
      placeholder: "" },
    { id: 'monitoring', label: 'Monitoring', dialect: 'postgresql', dialectLabel: 'PostgreSQL', icon: 'monitoring',
      placeholder: "" },
    { id: 'bigtable', label: 'Bigtable', dialect: 'postgresql', dialectLabel: 'Bigtable SQL', icon: 'bigtable',
      placeholder: "" },
    { id: 'compute', label: 'Compute Engine', dialect: 'postgresql', dialectLabel: 'PostgreSQL', icon: 'compute',
      placeholder: "" },
    { id: 'cloudrun', label: 'Cloud Run', dialect: 'postgresql', dialectLabel: 'PostgreSQL', icon: 'cloudrun',
      placeholder: "" },
    { id: 'gke', label: 'GKE', dialect: 'postgresql', dialectLabel: 'PostgreSQL', icon: 'gke',
      placeholder: "" },
    { id: 'memorystore', label: 'Memorystore', dialect: 'postgresql', dialectLabel: 'PostgreSQL', icon: 'memorystore',
      placeholder: "SELECT db_number, key_name, data_type, value, ttl_expires_at\nFROM redis_data\nORDER BY db_number, key_name\nLIMIT 50" },
    { id: 'workflows', label: 'Cloud Workflows', dialect: 'postgresql', dialectLabel: 'PostgreSQL', icon: 'workflows',
      placeholder: "SELECT workflow_id, state, revision_id, updated_at\nFROM workflows\nWHERE state = 'ACTIVE'" },
    { id: 'vertexai', label: 'Vertex AI', dialect: 'postgresql', dialectLabel: 'PostgreSQL', icon: 'vertexai',
      placeholder: "SELECT model_id, method, prompt_tokens, response_tokens, created_at\nFROM vertexai_requests\nORDER BY created_at DESC\nLIMIT 20" },
    { id: 'kms', label: 'Cloud KMS', dialect: 'postgresql', dialectLabel: 'PostgreSQL', icon: 'kms',
      placeholder: "SELECT key_ring_id, crypto_key_id, primary_version, created_at\nFROM kms_crypto_keys\nORDER BY created_at DESC" },
    { id: 'cloudsql', label: 'Cloud SQL', dialect: 'postgresql', dialectLabel: 'PostgreSQL', icon: 'cloudsql',
      placeholder: "SELECT * FROM myinstance.mydb.mytable LIMIT 10" },
    { id: 'alloydb', label: 'AlloyDB', dialect: 'postgresql', dialectLabel: 'PostgreSQL', icon: 'alloydb',
      placeholder: "SELECT cluster_id, database_name, created_at\nFROM alloydb_clusters\nORDER BY created_at DESC" },
    { id: 'cloudscheduler', label: 'Cloud Scheduler', dialect: 'postgresql', dialectLabel: 'PostgreSQL', icon: 'cloudscheduler',
      placeholder: "SELECT job_id, schedule, time_zone, state, next_execution_time\nFROM scheduler_jobs\nORDER BY job_id" },
    { id: 'cloudfunctions', label: 'Cloud Functions', dialect: 'postgresql', dialectLabel: 'PostgreSQL', icon: 'cloudfunctions',
      placeholder: "SELECT function_id, runtime, entry_point, state\nFROM cloud_functions\nORDER BY function_id" },
    { id: 'dataproc', label: 'Dataproc', dialect: 'postgresql', dialectLabel: 'PostgreSQL', icon: 'dataproc',
      placeholder: "SELECT cluster_name, region, created_at\nFROM dataproc_clusters\nORDER BY created_at DESC" },
    { id: 'cloudiam', label: 'Cloud IAM', dialect: 'postgresql', dialectLabel: 'PostgreSQL', icon: 'cloudiam',
      placeholder: "SELECT resource_type, resource_id\nFROM iam_policies\nORDER BY resource_type" },
];

export const SQL_RESULT_PAGE_SIZE = 50;

export const SERVICE_SCHEMAS = {
    bigquery: { tables: [] },
    spanner: { tables: [] },
    workflows: { tables: [
        { name: 'workflows', columns: [{ name: 'workflow_id', type: 'TEXT' }, { name: 'project_id', type: 'TEXT' }, { name: 'location_id', type: 'TEXT' }, { name: 'source_contents', type: 'TEXT' }, { name: 'state', type: 'TEXT' }, { name: 'revision_id', type: 'INT' }, { name: 'labels', type: 'JSONB' }, { name: 'created_at', type: 'TIMESTAMP' }, { name: 'updated_at', type: 'TIMESTAMP' }] },
        { name: 'workflow_executions', columns: [{ name: 'execution_id', type: 'TEXT' }, { name: 'workflow_id', type: 'TEXT' }, { name: 'state', type: 'TEXT' }, { name: 'argument', type: 'JSONB' }, { name: 'result', type: 'JSONB' }, { name: 'error', type: 'JSONB' }, { name: 'start_time', type: 'TIMESTAMP' }, { name: 'end_time', type: 'TIMESTAMP' }] }
    ]},
    vertexai: { tables: [
        { name: 'vertexai_requests', columns: [{ name: 'project_id', type: 'TEXT' }, { name: 'location_id', type: 'TEXT' }, { name: 'model_id', type: 'TEXT' }, { name: 'method', type: 'TEXT' }, { name: 'prompt_tokens', type: 'INT' }, { name: 'response_tokens', type: 'INT' }, { name: 'created_at', type: 'TIMESTAMP' }] }
    ]},
    kms: { tables: [
        { name: 'kms_key_rings', columns: [{ name: 'project_id', type: 'TEXT' }, { name: 'location_id', type: 'TEXT' }, { name: 'key_ring_id', type: 'TEXT' }, { name: 'created_at', type: 'TIMESTAMP' }] },
        { name: 'kms_crypto_keys', columns: [{ name: 'project_id', type: 'TEXT' }, { name: 'location_id', type: 'TEXT' }, { name: 'key_ring_id', type: 'TEXT' }, { name: 'crypto_key_id', type: 'TEXT' }, { name: 'purpose', type: 'TEXT' }, { name: 'algorithm', type: 'TEXT' }, { name: 'primary_version', type: 'INT' }] }
    ]},
    memorystore: { tables: [
        { name: 'memorystore_instances', columns: [{ name: 'project_id', type: 'TEXT' }, { name: 'instance_id', type: 'TEXT' }, { name: 'display_name', type: 'TEXT' }, { name: 'tier', type: 'TEXT' }, { name: 'engine', type: 'TEXT' }, { name: 'redis_version', type: 'TEXT' }, { name: 'port', type: 'INT' }, { name: 'memory_size_gb', type: 'INT' }, { name: 'state', type: 'TEXT' }, { name: 'host', type: 'TEXT' }, { name: 'auth_enabled', type: 'BOOLEAN' }, { name: 'persistence_mode', type: 'TEXT' }, { name: 'created_at', type: 'TIMESTAMP' }] }
    ]},
    cloudrun: { tables: [
        { name: 'cloudrun_services', columns: [{ name: 'project_id', type: 'TEXT' }, { name: 'location', type: 'TEXT' }, { name: 'service_id', type: 'TEXT' }, { name: 'container_image', type: 'TEXT' }, { name: 'container_port', type: 'INT' }, { name: 'revision_count', type: 'INT' }, { name: 'traffic_json', type: 'JSONB' }, { name: 'created_at', type: 'TIMESTAMP' }] },
        { name: 'cloudrun_revisions', columns: [{ name: 'project_id', type: 'TEXT' }, { name: 'location', type: 'TEXT' }, { name: 'service_id', type: 'TEXT' }, { name: 'revision_id', type: 'TEXT' }, { name: 'created_at', type: 'TIMESTAMP' }] }
    ]},
    cloudtasks: { tables: [
        { name: 'task_queues', columns: [{ name: 'project_id', type: 'TEXT' }, { name: 'location_id', type: 'TEXT' }, { name: 'queue_id', type: 'TEXT' }, { name: 'state', type: 'TEXT' }, { name: 'max_dispatches_per_second', type: 'DOUBLE' }, { name: 'max_concurrent_dispatches', type: 'INT' }, { name: 'max_attempts', type: 'INT' }, { name: 'created_at', type: 'TIMESTAMP' }] },
        { name: 'cloud_tasks', columns: [{ name: 'task_id', type: 'TEXT' }, { name: 'queue_name', type: 'TEXT' }, { name: 'http_method', type: 'TEXT' }, { name: 'url', type: 'TEXT' }, { name: 'state', type: 'TEXT' }, { name: 'dispatch_count', type: 'INT' }, { name: 'created_at', type: 'TIMESTAMP' }] }
    ]},
    cloudscheduler: { tables: [
        { name: 'scheduler_jobs', columns: [{ name: 'project_id', type: 'TEXT' }, { name: 'location_id', type: 'TEXT' }, { name: 'job_id', type: 'TEXT' }, { name: 'schedule', type: 'TEXT' }, { name: 'time_zone', type: 'TEXT' }, { name: 'state', type: 'TEXT' }, { name: 'job_proto', type: 'JSONB' }, { name: 'next_execution_time', type: 'TIMESTAMP' }] },
        { name: 'scheduler_executions', columns: [{ name: 'job_name', type: 'TEXT' }, { name: 'status', type: 'TEXT' }, { name: 'executed_at', type: 'TIMESTAMP' }, { name: 'output', type: 'TEXT' }] }
    ]},
    cloudfunctions: { tables: [
        { name: 'cloud_functions', columns: [{ name: 'project_id', type: 'TEXT' }, { name: 'location_id', type: 'TEXT' }, { name: 'function_id', type: 'TEXT' }, { name: 'runtime', type: 'TEXT' }, { name: 'entry_point', type: 'TEXT' }, { name: 'trigger_event_type', type: 'TEXT' }, { name: 'state', type: 'TEXT' }, { name: 'build_config', type: 'JSONB' }, { name: 'service_config', type: 'JSONB' }, { name: 'event_trigger', type: 'JSONB' }, { name: 'created_at', type: 'TIMESTAMP' }, { name: 'updated_at', type: 'TIMESTAMP' }] }
    ]},
    alloydb: { tables: [
        { name: 'alloydb_clusters', columns: [{ name: 'project_id', type: 'TEXT' }, { name: 'location_id', type: 'TEXT' }, { name: 'cluster_id', type: 'TEXT' }, { name: 'database_name', type: 'TEXT' }, { name: 'metadata', type: 'JSONB' }, { name: 'cluster_proto', type: 'BYTEA' }, { name: 'created_at', type: 'TIMESTAMP' }] },
        { name: 'alloydb_instances', columns: [{ name: 'project_id', type: 'TEXT' }, { name: 'location_id', type: 'TEXT' }, { name: 'cluster_id', type: 'TEXT' }, { name: 'instance_id', type: 'TEXT' }, { name: 'metadata', type: 'JSONB' }, { name: 'instance_proto', type: 'BYTEA' }, { name: 'created_at', type: 'TIMESTAMP' }] },
        { name: 'alloydb_backups', columns: [{ name: 'project_id', type: 'TEXT' }, { name: 'location_id', type: 'TEXT' }, { name: 'backup_id', type: 'TEXT' }, { name: 'cluster_name', type: 'TEXT' }, { name: 'metadata', type: 'JSONB' }, { name: 'backup_proto', type: 'BYTEA' }, { name: 'created_at', type: 'TIMESTAMP' }] },
        { name: 'alloydb_users', columns: [{ name: 'project_id', type: 'TEXT' }, { name: 'location_id', type: 'TEXT' }, { name: 'cluster_id', type: 'TEXT' }, { name: 'user_id', type: 'TEXT' }, { name: 'metadata', type: 'JSONB' }, { name: 'user_proto', type: 'BYTEA' }, { name: 'created_at', type: 'TIMESTAMP' }] }
    ]},
    dataproc: { tables: [
        { name: 'dataproc_clusters', columns: [{ name: 'project_id', type: 'TEXT' }, { name: 'region', type: 'TEXT' }, { name: 'cluster_name', type: 'TEXT' }, { name: 'metadata', type: 'JSONB' }, { name: 'cluster_proto', type: 'BYTEA' }, { name: 'created_at', type: 'TIMESTAMP' }] },
        { name: 'dataproc_jobs', columns: [{ name: 'project_id', type: 'TEXT' }, { name: 'region', type: 'TEXT' }, { name: 'job_id', type: 'TEXT' }, { name: 'cluster_name', type: 'TEXT' }, { name: 'status', type: 'TEXT' }, { name: 'driver_output_path', type: 'TEXT' }, { name: 'job_proto', type: 'BYTEA' }, { name: 'created_at', type: 'TIMESTAMP' }, { name: 'updated_at', type: 'TIMESTAMP' }] }
    ]},
    cloudiam: { tables: [
        { name: 'iam_policies', columns: [{ name: 'resource_type', type: 'TEXT' }, { name: 'resource_id', type: 'TEXT' }, { name: 'policy', type: 'JSONB' }, { name: 'policy_proto', type: 'BYTEA' }, { name: 'updated_at', type: 'TIMESTAMP' }] }
    ]},
    secretmanager: { tables: [
        { name: 'secrets', columns: [{ name: 'project_id', type: 'TEXT' }, { name: 'secret_id', type: 'TEXT' }, { name: 'labels', type: 'JSONB' }, { name: 'replication', type: 'JSONB' }, { name: 'expire_at', type: 'TIMESTAMP' }, { name: 'rotation_period', type: 'BIGINT' }, { name: 'created_at', type: 'TIMESTAMP' }] },
        { name: 'secret_versions', columns: [{ name: 'project_id', type: 'TEXT' }, { name: 'secret_id', type: 'TEXT' }, { name: 'version_number', type: 'INT' }, { name: 'payload', type: 'BYTEA' }, { name: 'state', type: 'TEXT' }, { name: 'created_at', type: 'TIMESTAMP' }] },
        { name: 'secret_version_aliases', columns: [{ name: 'project_id', type: 'TEXT' }, { name: 'secret_id', type: 'TEXT' }, { name: 'version_number', type: 'INT' }, { name: 'alias', type: 'TEXT' }, { name: 'create_time', type: 'TIMESTAMP' }] }
    ]},
    logging: { tables: [
        { name: 'log_entries', columns: [{ name: 'project_id', type: 'TEXT' }, { name: 'log_name', type: 'TEXT' }, { name: 'resource_type', type: 'TEXT' }, { name: 'severity', type: 'TEXT' }, { name: 'text_payload', type: 'TEXT' }, { name: 'json_payload', type: 'TEXT' }, { name: 'timestamp', type: 'BIGINT' }, { name: 'insert_id', type: 'TEXT' }] },
        { name: 'log_exclusion_filters', columns: [{ name: 'project_id', type: 'TEXT' }, { name: 'name', type: 'TEXT' }, { name: 'filter', type: 'TEXT' }, { name: 'description', type: 'TEXT' }, { name: 'disabled', type: 'BOOLEAN' }, { name: 'create_time', type: 'TIMESTAMP' }] }
    ]},
    monitoring: { tables: [
        { name: 'alert_policies', columns: [{ name: 'project_id', type: 'TEXT' }, { name: 'name', type: 'TEXT' }, { name: 'display_name', type: 'TEXT' }, { name: 'conditions_json', type: 'JSONB' }, { name: 'combiner', type: 'TEXT' }, { name: 'enabled', type: 'BOOLEAN' }, { name: 'create_time', type: 'TIMESTAMP' }] },
        { name: 'notification_channels', columns: [{ name: 'project_id', type: 'TEXT' }, { name: 'name', type: 'TEXT' }, { name: 'type', type: 'TEXT' }, { name: 'display_name', type: 'TEXT' }, { name: 'labels_json', type: 'JSONB' }, { name: 'enabled', type: 'BOOLEAN' }, { name: 'create_time', type: 'TIMESTAMP' }] }
    ]},
    gke: { tables: [
        { name: 'gke_node_pools', columns: [{ name: 'project_id', type: 'TEXT' }, { name: 'cluster_id', type: 'BIGINT' }, { name: 'name', type: 'TEXT' }, { name: 'config_json', type: 'JSONB' }, { name: 'initial_node_count', type: 'INT' }, { name: 'locations_json', type: 'JSONB' }, { name: 'status', type: 'TEXT' }, { name: 'create_time', type: 'TIMESTAMP' }] }
    ]},
    cloudbilling: { tables: [
        { name: 'billing_budgets', columns: [{ name: 'billing_account', type: 'TEXT' }, { name: 'budget_id', type: 'TEXT' }, { name: 'display_name', type: 'TEXT' }, { name: 'amount_json', type: 'JSONB' }, { name: 'threshold_rules_json', type: 'JSONB' }, { name: 'create_time', type: 'TIMESTAMP' }, { name: 'update_time', type: 'TIMESTAMP' }] }
    ]},
    pubsub: { tables: [
        { name: 'pubsub_subscriptions', columns: [{ name: 'project_id', type: 'TEXT' }, { name: 'subscription_id', type: 'TEXT' }, { name: 'topic_project_id', type: 'TEXT' }, { name: 'topic_id', type: 'TEXT' }, { name: 'ack_deadline_seconds', type: 'INT' }, { name: 'push_endpoint', type: 'TEXT' }, { name: 'labels', type: 'JSONB' }, { name: 'max_delivery_attempts', type: 'INT' }, { name: 'dead_letter_topic', type: 'TEXT' }] }
    ]},
};

export const TABS = Object.keys(SERVICE_META).filter(id => !['workflows'].includes(id)).map(id => ({
    id,
    label: SERVICE_META[id].label
}));

export const SERVICE_INFO = {
    gke: {
        name: 'GKE', port: 24080, envVar: 'GKE_EMULATOR_HOST', envValue: 'localhost:24080',
        protocol: 'gRPC', description: 'Use GKE SDK or kubectl to manage clusters.',
    },
    compute: {
        name: 'Compute Engine', port: 24080, envVar: 'COMPUTE_EMULATOR_HOST', envValue: 'localhost:24080',
        protocol: 'REST', description: 'Use the Compute Engine SDK to manage instances.',
    },
    cloudrun: {
        name: 'Cloud Run', port: 24080, envVar: 'CLOUD_RUN_EMULATOR_HOST', envValue: 'localhost:24080',
        protocol: 'gRPC', description: 'Use the Cloud Run SDK to manage services.',
    },
};

import { createSignal, createEffect, onCleanup, Show, For } from 'solid-js';
import { api } from '../api.js';
import DataBrowser from './DataBrowser.jsx';
import CodeEditor, { toCodeMirrorSchema } from '../components/CodeEditor.jsx';
import Workflows from './Workflows.jsx';
import { RemoteSyncPanel } from '../components/RemoteSyncPanel.jsx';
import { IconDatabase, IconTable, IconColumn, IconChevron } from '../components/TreeIcons.jsx';

// ─── Service Metadata (icon, title, description) ─────────────────────
const SERVICE_META = {
    gcs:           { label: 'Cloud Storage',    description: 'Object storage for companies of all sizes. Store any amount of data and retrieve it as often as you like.' },
    pubsub:        { label: 'Pub/Sub',          description: 'Global messaging and event ingestion. Reliable, many-to-many, asynchronous messaging between services.' },
    firestore:     { label: 'Firestore',        description: 'Flexible, scalable NoSQL cloud database for mobile, web, and server development.' },
    bigquery:      { label: 'BigQuery',         description: 'Serverless, highly scalable, and cost-effective multicloud data warehouse for analytics.' },
    secretmanager: { label: 'Secret Manager',   description: 'Store API keys, passwords, certificates, and other sensitive data securely.' },
    cloudtasks:    { label: 'Cloud Tasks',      description: 'Manage the execution of large numbers of distributed tasks, callbacks, and webhooks.' },
    spanner:       { label: 'Spanner',          description: 'Fully managed relational database with unlimited scale, strong consistency, and up to 99.999% availability.' },
    bigtable:      { label: 'Bigtable',         description: 'A fully managed, scalable NoSQL database service for large analytical and operational workloads.' },
    logging:       { label: 'Cloud Logging',    description: 'Real-time log management and analysis. Store, search, analyze, and alert on log data.' },
    monitoring:    { label: 'Cloud Monitoring', description: 'Full-stack monitoring for cloud applications. Metrics, uptime checks, dashboards, and alerting.' },
    gke:           { label: 'GKE',              description: 'Secured and managed Kubernetes service with four-way auto-scaling and multi-cluster support.' },
    compute:       { label: 'Compute Engine',   description: 'Virtual machines running in Google\'s data center. Scalable, high-performance VMs.' },
    cloudrun:      { label: 'Cloud Run',        description: 'Fully managed compute platform for deploying and scaling containerized applications quickly and securely.' },
    memorystore:   { label: 'Memorystore',      description: 'Fully managed in-memory data store service for Redis and Memcached.' },
    workflows:     { label: 'Cloud Workflows',  description: 'Orchestrate and automate Google Cloud and HTTP-based API services with serverless workflows.' },
};

// ─── SQL-Capable Services ──────────────────────────────────────────────
const SQL_SERVICES = [
    { id: 'pubsub', label: 'Pub/Sub', dialect: 'postgresql', dialectLabel: 'Pub/Sub SQL', icon: 'pubsub',
      placeholder: "SELECT * FROM user-events LIMIT 10" },
    { id: 'gcs', label: 'Cloud Storage', dialect: 'bigquery', dialectLabel: 'BigQuery SQL', icon: 'gcs',
      placeholder: "-- Click a file in the explorer to generate a query" },
    { id: 'bigquery', label: 'BigQuery', dialect: 'bigquery', dialectLabel: 'BigQuery SQL', icon: 'bigquery',
      placeholder: "SELECT * FROM `dataset.table` LIMIT 10" },
    { id: 'spanner', label: 'Spanner', dialect: 'googlesql', dialectLabel: 'GoogleSQL', icon: 'spanner',
      placeholder: "SELECT * FROM my_table LIMIT 10" },
    { id: 'secretmanager', label: 'Secret Manager', dialect: 'postgresql', dialectLabel: 'PostgreSQL', icon: 'secretmanager',
      placeholder: "SELECT secret_id, labels, created_at FROM secrets" },
    { id: 'cloudtasks', label: 'Cloud Tasks', dialect: 'postgresql', dialectLabel: 'PostgreSQL', icon: 'cloudtasks',
      placeholder: "SELECT queue_id, state, max_attempts FROM task_queues" },
    { id: 'logging', label: 'Logging', dialect: 'postgresql', dialectLabel: 'PostgreSQL', icon: 'logging',
      placeholder: "SELECT log_name, severity, text_payload, timestamp\nFROM log_entries\nORDER BY timestamp DESC\nLIMIT 100" },
    { id: 'monitoring', label: 'Monitoring', dialect: 'postgresql', dialectLabel: 'PostgreSQL', icon: 'monitoring',
      placeholder: "SELECT metric_type, resource_type, points\nFROM time_series\nLIMIT 100" },
    { id: 'bigtable', label: 'Bigtable', dialect: 'postgresql', dialectLabel: 'PostgreSQL', icon: 'bigtable',
      placeholder: "SELECT instance_id, table_name, row_key, cells\nFROM bigtable_data\nWHERE project_id = 'local-project'\nLIMIT 50" },
    { id: 'compute', label: 'Compute Engine', dialect: 'postgresql', dialectLabel: 'PostgreSQL', icon: 'compute',
      placeholder: "SELECT name, zone, machine_type, status\nFROM compute_instances" },
    { id: 'cloudrun', label: 'Cloud Run', dialect: 'postgresql', dialectLabel: 'PostgreSQL', icon: 'cloudrun',
      placeholder: "SELECT service_name, region, status, url\nFROM cloudrun_services" },
    { id: 'gke', label: 'GKE', dialect: 'postgresql', dialectLabel: 'PostgreSQL', icon: 'gke',
      placeholder: "SELECT cluster_name, location, node_count, status\nFROM gke_clusters" },
    { id: 'memorystore', label: 'Memorystore', dialect: 'postgresql', dialectLabel: 'PostgreSQL', icon: 'memorystore',
      placeholder: "SELECT key_name, data_type, value\nFROM redis_data\nLIMIT 200" },
    { id: 'workflows', label: 'Cloud Workflows', dialect: 'postgresql', dialectLabel: 'PostgreSQL', icon: 'workflows',
      placeholder: "SELECT workflow_id, state, revision_id, updated_at\nFROM workflows\nWHERE state = 'ACTIVE'" },
];

// ─── Static Schema Fallbacks ───────────────────────────────────────────
const SERVICE_SCHEMAS = {
    bigquery: { tables: [] },
    spanner: { tables: [] },
    secretmanager: { tables: [
        { name: 'secrets', columns: [{ name: 'secret_id', type: 'TEXT' }, { name: 'labels', type: 'JSONB' }, { name: 'created_at', type: 'TIMESTAMP' }, { name: 'project_id', type: 'TEXT' }] },
        { name: 'secret_versions', columns: [{ name: 'secret_id', type: 'TEXT' }, { name: 'version_id', type: 'TEXT' }, { name: 'payload', type: 'TEXT' }, { name: 'state', type: 'TEXT' }, { name: 'created_at', type: 'TIMESTAMP' }] }
    ]},
    cloudtasks: { tables: [
        { name: 'task_queues', columns: [{ name: 'queue_id', type: 'TEXT' }, { name: 'location_id', type: 'TEXT' }, { name: 'state', type: 'TEXT' }, { name: 'max_attempts', type: 'INT' }, { name: 'created_at', type: 'TIMESTAMP' }, { name: 'project_id', type: 'TEXT' }] },
        { name: 'task_contents', columns: [{ name: 'queue_id', type: 'TEXT' }, { name: 'task_name', type: 'TEXT' }, { name: 'http_url', type: 'TEXT' }, { name: 'http_method', type: 'TEXT' }, { name: 'body', type: 'TEXT' }, { name: 'created_at', type: 'TIMESTAMP' }] }
    ]},
    logging: { tables: [{ name: 'log_entries', columns: [{ name: 'id', type: 'SERIAL' }, { name: 'log_name', type: 'TEXT' }, { name: 'severity', type: 'TEXT' }, { name: 'text_payload', type: 'TEXT' }, { name: 'json_payload', type: 'JSONB' }, { name: 'timestamp', type: 'TIMESTAMP' }, { name: 'project_id', type: 'TEXT' }] }] },
    monitoring: { tables: [{ name: 'time_series', columns: [{ name: 'id', type: 'SERIAL' }, { name: 'metric_type', type: 'TEXT' }, { name: 'metric_labels', type: 'JSONB' }, { name: 'resource_type', type: 'TEXT' }, { name: 'resource_labels', type: 'JSONB' }, { name: 'points', type: 'JSONB' }, { name: 'project_name', type: 'TEXT' }] }] },
    bigtable: { tables: [{ name: 'bigtable_data', columns: [{ name: 'instance_id', type: 'TEXT' }, { name: 'table_name', type: 'TEXT' }, { name: 'row_key', type: 'TEXT' }, { name: 'cells', type: 'JSONB' }, { name: 'project_id', type: 'TEXT' }] }] },
    compute: { tables: [{ name: 'compute_instances', columns: [{ name: 'instance_id', type: 'TEXT' }, { name: 'name', type: 'TEXT' }, { name: 'zone', type: 'TEXT' }, { name: 'machine_type', type: 'TEXT' }, { name: 'status', type: 'TEXT' }, { name: 'network_ip', type: 'TEXT' }, { name: 'project_id', type: 'TEXT' }] }] },
    cloudrun: { tables: [
        { name: 'cloudrun_services', columns: [{ name: 'service_name', type: 'TEXT' }, { name: 'region', type: 'TEXT' }, { name: 'image', type: 'TEXT' }, { name: 'port', type: 'INT' }, { name: 'env_vars', type: 'JSONB' }, { name: 'status', type: 'TEXT' }, { name: 'url', type: 'TEXT' }, { name: 'project_id', type: 'TEXT' }] },
        { name: 'cloudrun_revisions', columns: [{ name: 'revision_name', type: 'TEXT' }, { name: 'service_name', type: 'TEXT' }, { name: 'image', type: 'TEXT' }, { name: 'created_at', type: 'TIMESTAMP' }, { name: 'traffic_percent', type: 'INT' }] }
    ]},
    gke: { tables: [{ name: 'gke_clusters', columns: [{ name: 'cluster_name', type: 'TEXT' }, { name: 'location', type: 'TEXT' }, { name: 'node_count', type: 'INT' }, { name: 'machine_type', type: 'TEXT' }, { name: 'k8s_version', type: 'TEXT' }, { name: 'status', type: 'TEXT' }, { name: 'project_id', type: 'TEXT' }] }] },
    memorystore: { tables: [{ name: 'redis_data', columns: [{ name: 'key_name', type: 'TEXT' }, { name: 'data_type', type: 'TEXT' }, { name: 'value', type: 'JSONB' }, { name: 'db_number', type: 'INT' }, { name: 'ttl_expires_at', type: 'TIMESTAMP' }, { name: 'project_id', type: 'TEXT' }] }] },
    workflows: { tables: [
        { name: 'workflows', columns: [{ name: 'workflow_id', type: 'TEXT' }, { name: 'project_id', type: 'TEXT' }, { name: 'location_id', type: 'TEXT' }, { name: 'source_contents', type: 'TEXT' }, { name: 'state', type: 'TEXT' }, { name: 'revision_id', type: 'INT' }, { name: 'labels', type: 'JSONB' }, { name: 'created_at', type: 'TIMESTAMP' }, { name: 'updated_at', type: 'TIMESTAMP' }] },
        { name: 'workflow_executions', columns: [{ name: 'execution_id', type: 'TEXT' }, { name: 'workflow_id', type: 'TEXT' }, { name: 'state', type: 'TEXT' }, { name: 'argument', type: 'JSONB' }, { name: 'result', type: 'JSONB' }, { name: 'error', type: 'JSONB' }, { name: 'start_time', type: 'TIMESTAMP' }, { name: 'end_time', type: 'TIMESTAMP' }] }
    ]}
};

// ─── Helpers ───────────────────────────────────────────────────────────
function formatDuration(ms) {
    if (ms < 1) return '<1ms';
    if (ms < 1000) return ms + 'ms';
    return (ms / 1000).toFixed(2) + 's';
}

function truncateCell(val, max = 120) {
    if (val == null) return 'NULL';
    const s = typeof val === 'object' ? JSON.stringify(val) : String(val);
    return s.length > max ? s.slice(0, max) + '...' : s;
}

// ─── Services without SQL support ────────────────────────────────────────
const NON_SQL_SERVICES = new Set(['firestore']);

// ─── Non-SQL service descriptions for the "no SQL" placeholder ───────────
const NON_SQL_INFO = {
    pubsub:    { label: 'Pub/Sub',        hint: 'Pub/Sub uses topic-based messaging. Use the Data Explorer tab to browse topics and subscriptions.' },
    firestore: { label: 'Firestore',      hint: 'Firestore uses document-based NoSQL storage. Use the Data Explorer tab to browse collections and documents.' },
};

// ─── GCS File Query Helpers ──────────────────────────────────────────────
const QUERYABLE_EXTENSIONS = new Set(['.parquet', '.csv', '.json', '.jsonl', '.ndjson']);

function getFileExtension(name) {
    const dot = name.lastIndexOf('.');
    return dot >= 0 ? name.substring(dot).toLowerCase() : '';
}

function isQueryableFile(name) {
    return QUERYABLE_EXTENSIONS.has(getFileExtension(name));
}

function getReaderFunction(name) {
    const ext = getFileExtension(name);
    switch (ext) {
        case '.parquet': return 'read_parquet';
        case '.csv': return 'read_csv';
        case '.json': case '.jsonl': case '.ndjson': return 'read_json';
        default: return null;
    }
}

function generateFileQuery(bucket, objectName) {
    const reader = getReaderFunction(objectName);
    // Use local filesystem path inside the container (DuckDB reads directly)
    // Escape single quotes for SQL safety
    const filePath = `/var/lib/localcloud/gcs-data/${bucket}/${objectName}`.replace(/'/g, "''");
    if (reader === 'read_csv') return `SELECT * FROM read_csv('${filePath}', auto_detect=true, header=true) LIMIT 100`;
    if (reader === 'read_json') return `SELECT * FROM read_json('${filePath}', auto_detect=true) LIMIT 100`;
    return `SELECT * FROM read_parquet('${filePath}') LIMIT 100`;
}

function formatFileSize(bytes) {
    if (bytes == null) return '';
    const n = Number(bytes);
    if (n === 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(n) / Math.log(1024));
    return `${(n / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0)} ${units[i]}`;
}

function formatBadge(name) {
    const ext = getFileExtension(name).replace('.', '').toUpperCase();
    return ext === 'JSONL' || ext === 'NDJSON' ? 'JSON' : ext;
}

// ─── SQL Editor Component (Full Workspace) ─────────────────────────────
function SQLEditor(props) {
    // Service is driven by the parent — no internal service selector
    const service = () => props.serviceId || SQL_SERVICES[0].id;
    const isSQLSupported = () => !NON_SQL_SERVICES.has(service());

    const [sqlText, setSqlText] = createSignal('');
    const [running, setRunning] = createSignal(false);
    const [result, setResult] = createSignal(null);
    const [error, setError] = createSignal(null);
    const [history, setHistory] = createSignal([]);
    const [showHistory, setShowHistory] = createSignal(false);
    const [dynamicSchema, setDynamicSchema] = createSignal(null);
    const [expanded, setExpanded] = createSignal({});
    const [schemaSearch, setSchemaSearch] = createSignal('');
    const [schemaLoading, setSchemaLoading] = createSignal(false);

    // Resizable editor pane
    const [editorHeight, setEditorHeight] = createSignal(300);
    let resizing = false;
    let resizeStartY = 0;
    let resizeStartH = 0;

    const startResize = (e) => {
        e.preventDefault();
        resizing = true;
        resizeStartY = e.clientY;
        resizeStartH = editorHeight();
        const onMove = (ev) => {
            if (!resizing) return;
            const delta = ev.clientY - resizeStartY;
            const newH = Math.max(80, Math.min(resizeStartH + delta, window.innerHeight - 200));
            setEditorHeight(newH);
        };
        const onUp = () => {
            resizing = false;
            document.removeEventListener('mousemove', onMove);
            document.removeEventListener('mouseup', onUp);
            document.body.style.cursor = '';
            document.body.style.userSelect = '';
        };
        document.addEventListener('mousemove', onMove);
        document.addEventListener('mouseup', onUp);
        document.body.style.cursor = 'row-resize';
        document.body.style.userSelect = 'none';
    };

    // GCS file explorer state
    const isGcsMode = () => service() === 'gcs';
    const [gcsBuckets, setGcsBuckets] = createSignal([]);
    const [gcsFiles, setGcsFiles] = createSignal({}); // { bucketName: [{ name, size, ... }] }
    const [gcsFileSchemas, setGcsFileSchemas] = createSignal({}); // { "bucket/obj": [{ name, type }] }
    const [gcsSchemaLoading, setGcsSchemaLoading] = createSignal({});

    // Spanner instance/database selection signals
    // Note: Spanner instance/database selection is independent from the Data Explorer tab.
    // Changing the selection in one tab does not affect the other.
    const [spannerInstances, setSpannerInstances] = createSignal([]);
    const [spannerDatabases, setSpannerDatabases] = createSignal([]);
    const [selectedInstance, setSelectedInstance] = createSignal('');
    const [selectedDatabase, setSelectedDatabase] = createSignal('');

    // Load GCS buckets and files when in GCS mode
    createEffect(() => {
        if (isGcsMode()) loadGcsFiles();
    });

    // Spanner: auto-select instance from schema response
    createEffect(() => {
        const svc = service();
        if (svc === 'spanner') {
            const schema = dynamicSchema();
            if (schema?.selectedInstance && !selectedInstance()) setSelectedInstance(schema.selectedInstance);
            if (schema?.databases?.length > 0 && !selectedDatabase()) setSelectedDatabase(schema.databases[0]);
        }
    });

    async function loadGcsFiles() {
        setSchemaLoading(true);
        try {
            const data = await api.browse('gcs');
            const buckets = data.buckets || [];
            setGcsBuckets(buckets);
            // Load objects for all buckets in parallel
            const files = {};
            const results = await Promise.allSettled(
                buckets.map(b => api.browse('gcs', b.name).then(objs => ({ name: b.name, objects: objs.objects || [] })))
            );
            for (const r of results) {
                if (r.status === 'fulfilled') files[r.value.name] = r.value.objects;
            }
            setGcsFiles(files);
            // Auto-expand first bucket
            if (buckets.length > 0) setExpanded(prev => ({ ...prev, ['bucket:' + buckets[0].name]: true }));
        } catch (e) { console.error('Failed to load GCS files:', e); }
        setSchemaLoading(false);
    }

    async function loadFileSchema(bucket, objectName) {
        const key = bucket + '/' + objectName;
        setGcsSchemaLoading(prev => ({ ...prev, [key]: true }));
        try {
            const data = await api.gcsFileSchema(bucket, objectName);
            setGcsFileSchemas(prev => ({ ...prev, [key]: data.columns || [] }));
        } catch (e) {
            setGcsFileSchemas(prev => ({ ...prev, [key]: { error: e.message } }));
        }
        setGcsSchemaLoading(prev => ({ ...prev, [key]: false }));
    }

    function handleFileClick(bucket, objectName) {
        const query = generateFileQuery(bucket, objectName);
        setSqlText(query);
        setIsPlaceholder(false);
        setResult(null);
        setError(null);
    }

    function handleFileExpand(bucket, objectName) {
        const key = bucket + '/' + objectName;
        const fileKey = 'file:' + key;
        toggle(fileKey);
        // Load schema on first expand
        if (!gcsFileSchemas()[key] && !gcsSchemaLoading()[key]) {
            loadFileSchema(bucket, objectName);
        }
    }

    // Reset editor state when service changes
    createEffect((prev) => {
        const svc = service();
        if (prev && prev !== svc) {
            setSqlText('');
            setIsPlaceholder(true);
            setResult(null);
            setError(null);
            setExpanded({});
            setDynamicSchema(null);
            setSelectedInstance('');
            setSelectedDatabase('');
        }
        return svc;
    });

    // Load schema from API for SQL-capable services (not GCS — it uses file loading)
    createEffect(() => {
        const svc = service();
        // Note: For Spanner, instance/database may be empty on first load.
        // The backend auto-resolves to the first available instance/database.
        if (!NON_SQL_SERVICES.has(svc) && svc !== 'gcs') loadDynamicSchema(svc);
    });

    async function loadDynamicSchema(svc) {
        setSchemaLoading(true);
        let data = null;
        try {
            // Spanner: always fetch all instances for schema tree (no instance/database filter)
            // Instance/database selection only affects query execution, not schema browsing
            const schemaParams = undefined;
            data = await api.schema(svc, schemaParams);
            if (data && data.tables) setDynamicSchema(data);
            else setDynamicSchema(null);
            // Populate Spanner instance/database signals from schema response
            if (svc === 'spanner' && data) {
                if (data.instances) setSpannerInstances(data.instances);
                if (data.databases) setSpannerDatabases(data.databases);
            }
        } catch { setDynamicSchema(null); }
        setSchemaLoading(false);
        // Auto-expand nodes
        const info = SQL_SERVICES.find(s => s.id === svc);
        if (svc === 'spanner' && data && data.instances) {
            const exp = {};
            // Expand all instances and their databases
            for (const inst of (data.instances || [])) exp['inst:' + inst] = true;
            // Group tables by instance to find database names per instance
            for (const t of (data.tables || [])) {
                if (t.instance && t.database) exp['db:' + t.instance + '/' + t.database] = true;
            }
            setExpanded(prev => ({ ...prev, ...exp }));
        } else {
            const dbName = info?.dialect === 'bigquery' ? null : 'public';
            if (dbName) setExpanded(prev => ({ ...prev, ['db:' + dbName]: true }));
        }
    }

    const currentServiceInfo = () => SQL_SERVICES.find(s => s.id === service());
    const currentSchema = () => {
        const ds = dynamicSchema();
        if (ds && ds.tables && ds.tables.length > 0) return ds;
        return SERVICE_SCHEMAS[service()] || { tables: [] };
    };
    const cmSchema = () => toCodeMirrorSchema(currentSchema()?.tables);

    // Build hierarchical tree: database → tables → columns
    // For Spanner: instance → database → tables → columns
    const schemaTree = () => {
        const schema = currentSchema();
        const tables = schema?.tables || [];
        const q = schemaSearch().toLowerCase().trim();
        const svcInfo = currentServiceInfo();
        const isBigQuery = svcInfo?.dialect === 'bigquery';
        const isSpanner = svcInfo?.id === 'spanner';

        // Group tables by database/dataset
        const groups = {};
        for (const t of tables) {
            let dbName, tableName;
            if (isBigQuery && t.name.includes('.')) {
                const parts = t.name.split('.');
                dbName = parts[0];
                tableName = parts.slice(1).join('.');
            } else if (isSpanner && t.name.includes('.')) {
                // Spanner: tables are prefixed as "database.TableName"
                const parts = t.name.split('.');
                dbName = parts[0];
                tableName = parts.slice(1).join('.');
            } else {
                dbName = 'public';
                tableName = t.name;
            }
            // Apply search filter
            if (q) {
                const matchTable = tableName.toLowerCase().includes(q);
                const matchCols = (t.columns || []).some(c => (c.name || c).toLowerCase().includes(q));
                if (!matchTable && !matchCols) continue;
            }
            // For Spanner: group key is "instance/database" to separate same-named dbs across instances
            const groupKey = isSpanner && t.instance ? t.instance + '/' + dbName : dbName;
            if (!groups[groupKey]) groups[groupKey] = [];
            groups[groupKey].push({ ...t, shortName: tableName, _instance: t.instance, _database: dbName });
        }
        return groups;
    };

    // For Spanner: build instance → databases grouping from schemaTree
    const spannerInstanceTree = () => {
        const tree = schemaTree();
        const instanceGroups = {};
        for (const [groupKey, tables] of Object.entries(tree)) {
            const inst = tables[0]?._instance || currentSchema()?.selectedInstance || 'default';
            const dbName = tables[0]?._database || groupKey;
            if (!instanceGroups[inst]) instanceGroups[inst] = {};
            instanceGroups[inst][dbName] = tables;
        }
        return instanceGroups;
    };

    function toggle(key) { setExpanded(prev => ({ ...prev, [key]: !prev[key] })); }

    async function runQuery() {
        const query = sqlText().trim();
        if (!query || running()) return;
        setRunning(true); setError(null); setResult(null);
        const startTime = performance.now();
        try {
            // GCS file queries route through BigQuery emulator
            const queryService = service() === 'gcs' ? 'bigquery' : service();
            const params = {};
            if (queryService === 'spanner') {
                const schema = currentSchema();
                params.instance = schema?.selectedInstance || selectedInstance() || '';
                // Infer database from the SQL — look for table references in schema
                // Default to first database in schema
                const databases = schema?.databases || [];
                params.database = selectedDatabase() || (databases.length > 0 ? databases[0] : '');
            }
            const data = await api.query(queryService, query, params);
            const elapsed = Math.round(performance.now() - startTime);
            if (data.error) { setError(data.error); }
            else {
                setResult({ columns: data.columns || [], rows: data.rows || [], rowCount: data.row_count ?? (data.rows || []).length, executionTime: data.execution_time_ms || elapsed });
                // Refresh schema tree after DDL statements (CREATE, DROP, ALTER)
                const trimmedUpper = query.trim().toUpperCase();
                if (trimmedUpper.startsWith('CREATE ') || trimmedUpper.startsWith('DROP ') || trimmedUpper.startsWith('ALTER ')) {
                    loadDynamicSchema(queryService);
                }
            }
            setHistory(prev => [{ sql: query, service: service(), timestamp: new Date(), rowCount: data.row_count ?? (data.rows || []).length, executionTime: data.execution_time_ms || Math.round(performance.now() - startTime), error: data.error || null }, ...prev.slice(0, 49)]);
        } catch (err) {
            setError(err.message || 'Query failed');
            setHistory(prev => [{ sql: query, service: service(), timestamp: new Date(), rowCount: 0, executionTime: Math.round(performance.now() - startTime), error: err.message }, ...prev.slice(0, 49)]);
        } finally { setRunning(false); }
    }

    function loadHistoryItem(item) { setSqlText(item.sql); setShowHistory(false); }
    function clearEditor() { setSqlText(''); setIsPlaceholder(true); setResult(null); setError(null); }

    // Tree icons imported from ../components/TreeIcons.jsx

    // Track whether current sqlText is still auto-generated placeholder
    const [isPlaceholder, setIsPlaceholder] = createSignal(true);

    // For PostgreSQL-backed services, filter schema to show only service-relevant tables
    const serviceSchema = () => {
        const svc = currentServiceInfo();
        const ds = dynamicSchema();
        if (!svc || !ds || !ds.tables) return ds;
        // BigQuery and Spanner return their own schema — no filtering needed
        if (svc.dialect === 'bigquery' || svc.dialect === 'googlesql') return ds;
        // For PostgreSQL-backed services, use the static schema to filter
        const staticTables = SERVICE_SCHEMAS[service()]?.tables;
        if (staticTables && staticTables.length > 0) {
            const allowedNames = new Set(staticTables.map(t => t.name));
            const filtered = ds.tables.filter(t => allowedNames.has(t.name));
            if (filtered.length > 0) return { ...ds, tables: filtered };
        }
        return ds;
    };

    // Dynamic placeholder: use first real table from service-filtered schema
    const dynamicPlaceholder = () => {
        const svc = currentServiceInfo();
        if (!svc) return '';
        const ds = dynamicSchema();
        if (ds && ds.tables && ds.tables.length > 0) {
            const tbl = ds.tables[0];
            return `SELECT * FROM ${svc.dialect === 'bigquery' ? '`' + tbl.name + '`' : tbl.name} LIMIT 10`;
        }
        return svc.placeholder;
    };

    // Set placeholder from dynamic schema when it loads (overwrite if still auto-generated)
    createEffect(() => {
        const placeholder = dynamicPlaceholder();
        if (placeholder && isPlaceholder()) setSqlText(placeholder);
    });

    // Wrap onChange to track user edits
    const handleSqlChange = (val) => {
        setIsPlaceholder(false);
        setSqlText(val);
    };

    return (
        <>
        {/* ── Non-SQL fallback ── */}
        <Show when={!isSQLSupported()}>
            <div class="sql-workspace sql-not-supported">
                <div class="sql-not-supported-content">
                    <img src={`/icons/${service()}.svg`} alt="" width="48" height="48" style={{ opacity: 0.25 }} />
                    <h3 class="sql-not-supported-title">SQL Editor not available</h3>
                    <p class="sql-not-supported-hint">{(NON_SQL_INFO[service()] || { hint: 'SQL queries are not available for this service.' }).hint}</p>
                </div>
            </div>
        </Show>

        {/* ── SQL workspace ── */}
        <Show when={isSQLSupported()}>
        <div class="sql-workspace">
            {/* ── Left: Resource Explorer ── */}
            <div class="sql-explorer">
                <div class="sql-explorer-header">
                    <div class="sql-explorer-title">
                        <img src={`/icons/${currentServiceInfo()?.icon || service()}.svg`} alt="" width="14" height="14" />
                        <span>{currentServiceInfo()?.label || 'Explorer'}</span>
                    </div>
                    <div class="sql-explorer-search">
                        <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor" style={{ opacity: 0.35, "flex-shrink": 0 }}>
                            <path d="M15.5 14h-.79l-.28-.27A6.471 6.471 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"/>
                        </svg>
                        <input
                            type="text"
                            placeholder="Filter resources..."
                            value={schemaSearch()}
                            onInput={(e) => setSchemaSearch(e.currentTarget.value)}
                        />
                    </div>
                </div>
                <div class="sql-explorer-tree">
                    <Show when={schemaLoading()}>
                        <div class="sql-explorer-loading">
                            <div class="loading-spinner" style={{ width: '14px', height: '14px', "border-width": '1.5px' }} />
                            <span>{isGcsMode() ? 'Loading files...' : 'Loading schema...'}</span>
                        </div>
                    </Show>

                    {/* ── GCS File Explorer Tree ── */}
                    <Show when={isGcsMode() && !schemaLoading()}>
                        <Show when={gcsBuckets().length === 0}>
                            <div class="sql-explorer-empty">
                                <svg width="32" height="32" viewBox="0 0 24 24" fill="currentColor" style={{ opacity: 0.12 }}>
                                    <path d="M20 6H12L10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2z"/>
                                </svg>
                                <span>No buckets found</span>
                            </div>
                        </Show>
                        <For each={gcsBuckets()}>
                            {(bucket) => {
                                const bucketKey = 'bucket:' + bucket.name;
                                const allObjects = () => gcsFiles()[bucket.name] || [];
                                const queryableFiles = () => {
                                    const q = schemaSearch().toLowerCase().trim();
                                    return allObjects().filter(o => isQueryableFile(o.name) && (!q || o.name.toLowerCase().includes(q)));
                                };
                                const otherCount = () => allObjects().length - allObjects().filter(o => isQueryableFile(o.name)).length;
                                return (
                                    <div class="tree-group">
                                        <div class="tree-row tree-row-db" onClick={() => toggle(bucketKey)} role="button" tabIndex={0}>
                                            <IconChevron open={expanded()[bucketKey]} />
                                            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" class="tree-icon tree-icon-db">
                                                <path d="M20 6H12L10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2z" stroke="currentColor" strokeWidth="1.5" fill="currentColor" fillOpacity="0.12"/>
                                            </svg>
                                            <span class="tree-name">{bucket.name}</span>
                                            <span class="tree-badge tree-badge-db">{queryableFiles().length} file{queryableFiles().length !== 1 ? 's' : ''}</span>
                                        </div>
                                        <Show when={expanded()[bucketKey]}>
                                            <div class="tree-children">
                                                <For each={queryableFiles()}>
                                                    {(file) => {
                                                        const fileKey = 'file:' + bucket.name + '/' + file.name;
                                                        const schemaKey = bucket.name + '/' + file.name;
                                                        const cols = () => gcsFileSchemas()[schemaKey];
                                                        const isLoading = () => gcsSchemaLoading()[schemaKey];
                                                        return (
                                                            <div class="tree-group">
                                                                <div class="tree-row tree-row-tbl" role="button" tabIndex={0}
                                                                    onClick={() => handleFileClick(bucket.name, file.name)}>
                                                                    <span style={{ cursor: 'pointer', display: 'inline-flex' }}
                                                                        onClick={(e) => { e.stopPropagation(); handleFileExpand(bucket.name, file.name); }}>
                                                                        <IconChevron open={expanded()[fileKey]} />
                                                                    </span>
                                                                    <IconTable />
                                                                    <span class="tree-name">{file.name}</span>
                                                                    <span class="tree-badge" style={{ "font-size": "9px", "letter-spacing": "0.5px" }}>{formatBadge(file.name)}</span>
                                                                    <Show when={file.size}>
                                                                        <span class="tree-col-type" style={{ "margin-left": "auto" }}>{formatFileSize(file.size)}</span>
                                                                    </Show>
                                                                </div>
                                                                <Show when={expanded()[fileKey]}>
                                                                    <div class="tree-children">
                                                                        <Show when={isLoading()}>
                                                                            <div class="sql-explorer-loading" style={{ padding: '4px 0 4px 24px' }}>
                                                                                <div class="loading-spinner" style={{ width: '12px', height: '12px', "border-width": '1.5px' }} />
                                                                                <span>Detecting schema...</span>
                                                                            </div>
                                                                        </Show>
                                                                        <Show when={!isLoading() && cols() && !cols().error}>
                                                                            <For each={cols()}>
                                                                                {(col) => (
                                                                                    <div class="tree-row tree-row-col" title={`${col.name} (${col.type})`}>
                                                                                        <IconColumn />
                                                                                        <span class="tree-col-name">{col.name}</span>
                                                                                        <span class="tree-col-type">{col.type}</span>
                                                                                    </div>
                                                                                )}
                                                                            </For>
                                                                        </Show>
                                                                        <Show when={!isLoading() && cols()?.error}>
                                                                            <div class="tree-row tree-row-col" style={{ color: 'var(--error)', "font-size": "11px" }}>
                                                                                <span>Schema detection failed</span>
                                                                            </div>
                                                                        </Show>
                                                                    </div>
                                                                </Show>
                                                            </div>
                                                        );
                                                    }}
                                                </For>
                                                <Show when={otherCount() > 0}>
                                                    <div class="tree-row tree-row-col" style={{ opacity: 0.4, "font-style": "italic" }}>
                                                        <span>+{otherCount()} non-queryable file{otherCount() !== 1 ? 's' : ''}</span>
                                                    </div>
                                                </Show>
                                            </div>
                                        </Show>
                                    </div>
                                );
                            }}
                        </For>
                    </Show>

                    {/* ── Standard Schema Tree (non-GCS) ── */}
                    <Show when={!isGcsMode() && !schemaLoading() && Object.keys(schemaTree()).length === 0}>
                        <div class="sql-explorer-empty">
                            <svg width="32" height="32" viewBox="0 0 24 24" fill="currentColor" style={{ opacity: 0.12 }}>
                                <ellipse cx="12" cy="5.5" rx="9" ry="3.5"/>
                                <path d="M3 5.5v13c0 1.93 4.03 3.5 9 3.5s9-1.57 9-3.5v-13"/>
                            </svg>
                            <span>No resources found</span>
                        </div>
                    </Show>
                    <Show when={!isGcsMode()}>
                    {/* Spanner: instance → database → table hierarchy */}
                    <Show when={service() === 'spanner' && currentSchema()?.instances?.length > 0}>
                        <For each={Object.entries(spannerInstanceTree())}>
                            {([instName, databases]) => {
                                const instKey = 'inst:' + instName;
                                const dbCount = Object.keys(databases).length;
                                return (
                                    <div class="tree-group">
                                        <div class="tree-row tree-row-db" onClick={() => toggle(instKey)} role="button" tabIndex={0}>
                                            <IconChevron open={expanded()[instKey] !== false} />
                                            <svg width="14" height="14" viewBox="0 0 24 24" fill="var(--primary)" style={{"flex-shrink":"0"}}><path d="M19 15v4H5v-4h14m1-2H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1v-6c0-.55-.45-1-1-1zM7 18.5c-.82 0-1.5-.67-1.5-1.5s.68-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zM19 3v4H5V3h14m1-2H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1V2c0-.55-.45-1-1-1zM7 6.5c-.82 0-1.5-.67-1.5-1.5S6.19 3.5 7 3.5s1.5.67 1.5 1.5S7.83 6.5 7 6.5zM19 9v4H5V9h14m1-2H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1v-6c0-.55-.45-1-1-1zM7 12.5c-.82 0-1.5-.67-1.5-1.5s.68-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5z"/></svg>
                                            <span class="tree-name" style={{"font-weight":"600"}}>{instName}</span>
                                            <span class="tree-badge tree-badge-db">{dbCount} db{dbCount !== 1 ? 's' : ''}</span>
                                        </div>
                                        <Show when={expanded()[instKey] !== false}>
                                            <div class="tree-children">
                                                <For each={Object.entries(databases)}>
                                                    {([dbName, tables]) => {
                                                        const dbKey = 'db:' + instName + '/' + dbName;
                                                        const tableCount = tables.length;
                                                        return (
                                                            <div class="tree-group">
                                                                <div class="tree-row tree-row-db"
                                                                    onClick={() => { toggle(dbKey); setSelectedInstance(instName); setSelectedDatabase(dbName); }}
                                                                    role="button" tabIndex={0}>
                                                                    <IconChevron open={expanded()[dbKey]} />
                                                                    <IconDatabase />
                                                                    <span class="tree-name">{dbName}</span>
                                                                    <span class="tree-badge tree-badge-db">{tableCount} table{tableCount !== 1 ? 's' : ''}</span>
                                                                </div>
                                                                <Show when={expanded()[dbKey]}>
                                                                    <div class="tree-children">
                                                                        <For each={tables}>
                                                                            {(table) => {
                                                                                const tblKey = 'tbl:' + instName + '/' + table.name;
                                                                                const colCount = (table.columns || []).length;
                                                                                return (
                                                                                    <div class="tree-group">
                                                                                        <div class="tree-row tree-row-tbl"
                                                                                            onClick={() => { toggle(tblKey); setSelectedInstance(instName); setSelectedDatabase(dbName); }}
                                                                                            role="button" tabIndex={0}>
                                                                                            <IconChevron open={expanded()[tblKey]} />
                                                                                            <IconTable />
                                                                                            <span class="tree-name">{table.shortName}</span>
                                                                                            <span class="tree-badge">{colCount}</span>
                                                                                        </div>
                                                                                        <Show when={expanded()[tblKey]}>
                                                                                            <div class="tree-children">
                                                                                                <For each={table.columns || []}>
                                                                                                    {(col) => (
                                                                                                        <div class="tree-row tree-row-col" title={`${col.name || col} (${col.type || ''})`}>
                                                                                                            <IconColumn />
                                                                                                            <span class="tree-col-name">{col.name || col}</span>
                                                                                                            <Show when={col.type}>
                                                                                                                <span class="tree-col-type">{col.type}</span>
                                                                                                            </Show>
                                                                                                        </div>
                                                                                                    )}
                                                                                                </For>
                                                                                            </div>
                                                                                        </Show>
                                                                                    </div>
                                                                                );
                                                                            }}
                                                                        </For>
                                                                    </div>
                                                                </Show>
                                                            </div>
                                                        );
                                                    }}
                                                </For>
                                            </div>
                                        </Show>
                                    </div>
                                );
                            }}
                        </For>
                    </Show>
                    {/* Non-Spanner services: flat database grouping */}
                    <Show when={service() !== 'spanner'}>
                    <For each={Object.entries(schemaTree())}>
                        {([dbName, tables]) => {
                            const dbKey = 'db:' + dbName;
                            const tableCount = tables.length;
                            return (
                                <div class="tree-group">
                                    {/* ── Database / Dataset node ── */}
                                    <div
                                        class="tree-row tree-row-db"
                                        onClick={() => toggle(dbKey)}
                                        role="button" tabIndex={0}
                                    >
                                        <IconChevron open={expanded()[dbKey]} />
                                        <IconDatabase />
                                        <span class="tree-name">{dbName}</span>
                                        <span class="tree-badge tree-badge-db">{tableCount} table{tableCount !== 1 ? 's' : ''}</span>
                                    </div>
                                    <Show when={expanded()[dbKey]}>
                                        <div class="tree-children">
                                            <For each={tables}>
                                                {(table) => {
                                                    const tblKey = 'tbl:' + table.name;
                                                    const colCount = (table.columns || []).length;
                                                    return (
                                                        <div class="tree-group">
                                                            {/* ── Table node ── */}
                                                            <div
                                                                class="tree-row tree-row-tbl"
                                                                onClick={() => toggle(tblKey)}
                                                                role="button" tabIndex={0}
                                                            >
                                                                <IconChevron open={expanded()[tblKey]} />
                                                                <IconTable />
                                                                <span class="tree-name">{table.shortName}</span>
                                                                <span class="tree-badge">{colCount}</span>
                                                            </div>
                                                            <Show when={expanded()[tblKey]}>
                                                                <div class="tree-children">
                                                                    <For each={table.columns || []}>
                                                                        {(col) => (
                                                                            <div class="tree-row tree-row-col" title={`${col.name || col} (${col.type || ''})`}>
                                                                                <IconColumn />
                                                                                <span class="tree-col-name">{col.name || col}</span>
                                                                                <Show when={col.type}>
                                                                                    <span class="tree-col-type">{col.type}</span>
                                                                                </Show>
                                                                            </div>
                                                                        )}
                                                                    </For>
                                                                </div>
                                                            </Show>
                                                        </div>
                                                    );
                                                }}
                                            </For>
                                        </div>
                                    </Show>
                                </div>
                            );
                        }}
                    </For>
                    </Show>
                    </Show>
                </div>
            </div>

            {/* ── Right: Toolbar + Editor + Results ── */}
            <div class="sql-main-panel">
                {/* Consolidated Toolbar */}
                <div class="sql-toolbar-unified">
                    <div class="sql-toolbar-left">
                        <span class="sql-dialect-badge">{currentServiceInfo()?.dialectLabel}</span>
                    </div>
                    <div class="sql-toolbar-center">
                        <button class="btn btn-primary sql-run-btn" onClick={runQuery} disabled={running() || !sqlText().trim()}>
                            <Show when={running()} fallback={
                                <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>
                            }>
                                <div class="loading-spinner" style={{ width: '14px', height: '14px', "border-width": '2px' }} />
                            </Show>
                            {running() ? 'Running...' : 'Run'}
                        </button>
                        <button class="btn btn-secondary" onClick={clearEditor} title="Clear editor and results">Clear</button>
                        <div class="sql-shortcut-hint">
                            <kbd>{navigator.platform?.includes('Mac') ? '\u2318' : 'Ctrl'}</kbd><span>+</span><kbd>Enter</kbd>
                        </div>
                    </div>
                    <div class="sql-toolbar-right">
                        <button class="btn btn-icon sql-toolbar-btn" title="Query history" onClick={() => setShowHistory(!showHistory())}>
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
                                <path d="M13 3a9 9 0 0 0-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42A8.954 8.954 0 0 0 13 21a9 9 0 0 0 0-18zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z"/>
                            </svg>
                        </button>
                    </div>
                </div>

                {/* Editor Pane — resizable via drag handle */}
                <div class="sql-editor-pane" style={{ flex: `0 0 ${editorHeight()}px` }}>
                    <CodeEditor
                        value={sqlText()}
                        onChange={handleSqlChange}
                        dialect={currentServiceInfo()?.dialect || 'postgresql'}
                        schema={cmSchema()}
                        placeholder="Enter SQL query..."
                        onRun={runQuery}
                        class="sql-editor-fill"
                    />
                </div>

                {/* Resize Handle */}
                <div
                    class="sql-resize-handle"
                    onMouseDown={startResize}
                    title="Drag to resize editor"
                />

                {/* Status Bar */}
                <div class="sql-status-bar">
                    <Show when={running()}>
                        <div class="loading-spinner" style={{ width: '12px', height: '12px', "border-width": '1.5px' }} />
                        <span>Running query...</span>
                    </Show>
                    <Show when={!running() && error()}>
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="var(--error)">
                            <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z"/>
                        </svg>
                        <span class="sql-status-error">{error()}</span>
                    </Show>
                    <Show when={!running() && !error() && result()}>
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="var(--success)">
                            <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/>
                        </svg>
                        <span class="sql-status-ok">Query complete</span>
                        <span class="sql-status-meta">{result().rowCount} row{result().rowCount !== 1 ? 's' : ''} &middot; {formatDuration(result().executionTime)}</span>
                    </Show>
                    <Show when={!running() && !error() && !result()}>
                        <span class="sql-status-idle">Run a query to see results</span>
                    </Show>
                </div>

                {/* Results Pane (flex: 1, fills bottom half) */}
                <div class="sql-results-pane">
                    <Show when={result() && result().rows.length > 0}>
                        <div class="sql-results-table-wrap">
                            <table class="sql-results-table">
                                <thead>
                                    <tr>
                                        <th class="sql-results-rownum">#</th>
                                        <For each={result().columns}>{(col) => <th>{col}</th>}</For>
                                    </tr>
                                </thead>
                                <tbody>
                                    <For each={result().rows}>
                                        {(row, idx) => (
                                            <tr>
                                                <td class="sql-results-rownum">{idx() + 1}</td>
                                                <For each={row}>
                                                    {(cell) => (
                                                        <td title={cell != null ? String(typeof cell === 'object' ? JSON.stringify(cell) : cell) : 'NULL'}>
                                                            {cell == null ? <span class="sql-null">NULL</span> : truncateCell(cell)}
                                                        </td>
                                                    )}
                                                </For>
                                            </tr>
                                        )}
                                    </For>
                                </tbody>
                            </table>
                        </div>
                    </Show>
                    <Show when={result() && result().rows.length === 0 && !error()}>
                        <div class="sql-results-placeholder">Query returned no rows.</div>
                    </Show>
                    <Show when={!result() && !error() && !running()}>
                        <div class="sql-results-placeholder">
                            <svg width="40" height="40" viewBox="0 0 24 24" fill="currentColor" style={{ opacity: 0.15 }}>
                                <path d="M3 14h4v-4H3v4zm0 5h4v-4H3v4zM3 9h4V5H3v4zm5 5h13v-4H8v4zm0 5h13v-4H8v4zM8 5v4h13V5H8z"/>
                            </svg>
                            <span>Results will appear here</span>
                        </div>
                    </Show>
                </div>
            </div>

            {/* History Overlay */}
            <Show when={showHistory()}>
                <div class="sql-history-overlay" onClick={() => setShowHistory(false)}>
                    <div class="sql-history-panel" onClick={(e) => e.stopPropagation()}>
                        <div class="sql-history-header">
                            <span class="sql-history-title">Query History</span>
                            <button class="btn btn-icon" onClick={() => setShowHistory(false)}>
                                <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
                                    <path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/>
                                </svg>
                            </button>
                        </div>
                        <Show when={history().length === 0}><div class="sql-history-empty">No queries yet.</div></Show>
                        <div class="sql-history-list">
                            <For each={history()}>
                                {(item) => (
                                    <button class="sql-history-item" onClick={() => loadHistoryItem(item)}>
                                        <div class="sql-history-item-top">
                                            <span class="sql-history-service">{SQL_SERVICES.find(s => s.id === item.service)?.label || item.service}</span>
                                            <span class="sql-history-time">{item.timestamp.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</span>
                                        </div>
                                        <div class="sql-history-sql">{item.sql.length > 80 ? item.sql.slice(0, 80) + '...' : item.sql}</div>
                                        <div class="sql-history-item-meta">
                                            <Show when={item.error}><span class="sql-history-error">Error</span></Show>
                                            <Show when={!item.error}>
                                                <span>{item.rowCount} row{item.rowCount !== 1 ? 's' : ''}</span>
                                                <span class="sql-results-sep">&middot;</span>
                                                <span>{formatDuration(item.executionTime)}</span>
                                            </Show>
                                        </div>
                                    </button>
                                )}
                            </For>
                        </div>
                    </div>
                </div>
            </Show>
        </div>
        </Show>
        </>
    );
}

// ─── Main Component ────────────────────────────────────────────────────
export default function ServiceExplorer(props) {
    const [mode, setMode] = createSignal('explorer');
    const [refreshTrigger, setRefreshTrigger] = createSignal(0);
    const [resetTrigger, setResetTrigger] = createSignal(0);

    const activeService = () => props.selectedService?.() || 'gcs';
    const meta = () => SERVICE_META[activeService()] || { label: activeService(), description: '' };

    const handleRefresh = () => setRefreshTrigger(prev => prev + 1);
    const handleReset = () => {
        if (confirm(`Reset all ${activeService()} data? This cannot be undone.`)) {
            setResetTrigger(prev => prev + 1);
        }
    };

    const isWorkflows = () => activeService() === 'workflows';

    return (
        <div class="se-root">
            {/* Service Header — icon, title, description */}
            <div class="se-service-header">
                <img
                    src={`/icons/${activeService()}.svg`}
                    alt=""
                    class="se-service-icon"
                />
                <div class="se-service-info">
                    <h1 class="se-service-title">{meta().label}</h1>
                    <p class="se-service-desc">{meta().description}</p>
                </div>
            </div>

            {/* Workflows gets its own dedicated UI — function child forces remount on toggle */}
            <Show when={isWorkflows()} keyed>
                {(_) => <Workflows activeProject={props.activeProject} />}
            </Show>

            {/* Standard services get SQL Editor + Data Explorer */}
            <Show when={!isWorkflows()}>
                {/* Mode Toggle + Action Buttons */}
                <div class="se-mode-bar">
                    <div class="se-mode-tabs">
                        <button
                            class={`se-mode-tab ${mode() === 'editor' ? 'active' : ''}`}
                            onClick={() => setMode('editor')}
                        >
                            <svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor">
                                <path d="M9.4 16.6L4.8 12l4.6-4.6L8 6l-6 6 6 6 1.4-1.4zm5.2 0l4.6-4.6-4.6-4.6L16 6l6 6-6 6-1.4-1.4z"/>
                            </svg>
                            SQL Editor
                        </button>
                        <button
                            class={`se-mode-tab ${mode() === 'explorer' ? 'active' : ''}`}
                            onClick={() => setMode('explorer')}
                        >
                            <svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor">
                                <path d="M20 6H12L10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm0 12H4V8h16v10z"/>
                            </svg>
                            Data Explorer
                        </button>
                        <button
                            class={`se-mode-tab ${mode() === 'sync' ? 'active' : ''}`}
                            onClick={() => setMode('sync')}
                        >
                            <svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor">
                                <path d="M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74C4.46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z"/>
                            </svg>
                            Remote Sync
                            <span style={{
                                "font-size": "9px",
                                "font-weight": "700",
                                "letter-spacing": "0.5px",
                                padding: "1px 5px",
                                "border-radius": "3px",
                                background: "var(--accent, #4285f4)",
                                color: "#fff",
                                "margin-left": "6px",
                                "vertical-align": "middle",
                                "line-height": "1"
                            }}>BETA</span>
                        </button>
                    </div>
                    <div class="se-mode-actions">
                        <button class="btn btn-secondary" onClick={handleRefresh} style={{ height: "30px", "font-size": "11px", padding: "0 12px" }}>
                            Refresh
                        </button>
                        <button class="btn btn-danger" onClick={handleReset} style={{ height: "30px", "font-size": "11px", padding: "0 12px" }}>
                            Reset
                        </button>
                    </div>
                </div>

                <div style={{ display: mode() === 'editor' ? 'flex' : 'none', flex: '1', "min-height": '0', "flex-direction": 'column' }}>
                    <SQLEditor serviceId={activeService()} />
                </div>

                <div style={{ display: mode() === 'explorer' ? 'flex' : 'none', flex: '1', "min-height": '0', "flex-direction": 'column' }}>
                    <DataBrowser
                        selectedService={props.selectedService}
                        onTabChange={props.onTabChange}
                        activeProject={props.activeProject}
                        refreshTrigger={refreshTrigger}
                        resetTrigger={resetTrigger}
                    />
                </div>

                <div style={{ display: mode() === 'sync' ? 'flex' : 'none', flex: '1', "min-height": '0', "flex-direction": 'column' }}>
                    <RemoteSyncPanel serviceId={activeService()} activeProject={props.activeProject} />
                </div>
            </Show>
        </div>
    );
}

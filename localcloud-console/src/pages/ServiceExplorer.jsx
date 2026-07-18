import { createSignal, createEffect, onCleanup, Show, For, createMemo } from 'solid-js';
import { api } from '../api.js';
import { createUrlBackedTab } from '../utils/urlTabs.js';
import DataBrowser from './DataBrowser.jsx';
import CodeEditor, { toCodeMirrorSchema } from '../components/CodeEditor.jsx';
import Workflows from './Workflows.jsx';
import { RemoteSyncPanel } from '../components/RemoteSyncPanel.jsx';
import { TriggerTestPanel, JobOutputPanel, SchedulerHistoryPanel, ConnectionInfoPanel } from '../components/ServicePanels.jsx';
import { IconDatabase, IconTable, IconColumn, IconChevron } from '../components/TreeIcons.jsx';
import { formatNumber, formatTime, onActivate } from '../utils/a11y.js';
import { formatSize } from '../utils/format.js';
import { SAMPLE_CODE, CLI_COMMANDS } from './settings-data.js';
import { SERVICE_META, SQL_SERVICES, SQL_RESULT_PAGE_SIZE, SERVICE_SCHEMAS } from '../data/services.js';

// ─── Helpers ───────────────────────────────────────────────────────────
function formatDuration(ms) {
    if (ms < 1) return '<1ms';
    if (ms < 1000) return ms + 'ms';
    return (ms / 1000).toFixed(2) + 's';
}

function truncateCell(val, max = 120) {
    if (val == null) return 'NULL';
    const s = typeof val === 'object' ? JSON.stringify(val) : String(val);
    return s.length > max ? s.slice(0, max) + '…' : s;
}

function JsonCell(props) {
    const [open, setOpen] = createSignal(false);
    const parsed = () => {
        const val = props.value;
        if (val && typeof val === 'object') return val;
        if (typeof val !== 'string') return null;
        const trimmed = val.trim();
        if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return null;
        try { return JSON.parse(trimmed); } catch { return null; }
    };
    return (
        <Show when={parsed()} fallback={props.value == null ? <span class="sql-null">NULL</span> : truncateCell(props.value)}>
            {(json) => (
	                <button class={`aura-json-cell ${open() ? 'open' : ''}`} aria-expanded={open()} onClick={(e) => { e.stopPropagation(); setOpen(!open()); }}>
                    <span>{open() ? 'Collapse JSON' : truncateCell(json(), 80)}</span>
                    <Show when={open()}>
                        <pre>{JSON.stringify(json(), null, 2)}</pre>
                    </Show>
                </button>
            )}
        </Show>
    );
}

// ─── Services without SQL support ────────────────────────────────────────
const NON_SQL_SERVICES = new Set(['firestore', 'secretmanager']);

// ─── Non-SQL service descriptions for the "no SQL" placeholder ───────────
const NON_SQL_INFO = {
    pubsub:    { label: 'Pub/Sub',        hint: 'Pub/Sub uses topic-based messaging. Use the Data Explorer tab to browse topics and subscriptions. Filtering supported on subscriptions.' },
    firestore: { label: 'Firestore',      hint: 'Firestore uses document-based NoSQL storage. Use the Data Explorer tab to browse collections and documents.' },
    secretmanager: { label: 'Secret Manager', hint: 'Secret Manager stores API keys, passwords, certificates. Supports expiration, rotation periods, labels, and version aliases. Use Data Explorer to manage secrets and versions.' },
    cloudbilling: { label: 'Cloud Billing', hint: 'Cloud Billing manages budgets with threshold rules. Use Data Explorer to browse budgets, or SQL console to query billing_budgets.' },
    serviceusage:  { label: 'Service Usage', hint: 'Service Usage API provides service enablement and quota info. All services report as ENABLED with empty quota metrics.' },
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

function formatBadge(name) {
    const ext = getFileExtension(name).replace('.', '').toUpperCase();
    return ext === 'JSONL' || ext === 'NDJSON' ? 'JSON' : ext;
}

function fileBadgeClass(name) {
    const ext = getFileExtension(name);
    if (ext === '.parquet') return 'tree-badge-file tree-badge-parquet';
    if (ext === '.csv') return 'tree-badge-file tree-badge-csv';
    if (ext === '.json' || ext === '.jsonl' || ext === '.ndjson') return 'tree-badge-file tree-badge-json';
    return 'tree-badge-file';
}

function quoteTableName(serviceInfo, tableName) {
    if (serviceInfo?.dialect === 'bigquery') return '`' + tableName.replace(/`/g, '\\`') + '`';
    if (serviceInfo?.id === 'bigtable') return '"' + tableName.replace(/"/g, '""') + '"';
    return tableName;
}

// ─── SQL Editor Component (Full Workspace) ─────────────────────────────
function SQLEditor(props) {
    // Service is driven by the parent — no internal service selector
    const service = () => props.serviceId || SQL_SERVICES[0].id;
    const isSQLSupported = () => !NON_SQL_SERVICES.has(service());

    const [sqlText, setSqlText] = createSignal('');
    const [sqlTabs, setSqlTabs] = createSignal([{ id: 'tab-1', title: 'Query 1', sql: '' }]);
    const [activeSqlTab, setActiveSqlTab] = createSignal('tab-1');
    const [running, setRunning] = createSignal(false);
    const [result, setResult] = createSignal(null);
    const [resultPage, setResultPage] = createSignal(1);
    const [error, setError] = createSignal(null);
    const [history, setHistory] = createSignal([]);
    const [showHistory, setShowHistory] = createSignal(false);
    const [dynamicSchema, setDynamicSchema] = createSignal(null);
    const [expanded, setExpanded] = createSignal({});
    const [schemaSearch, setSchemaSearch] = createSignal('');
    const [schemaSearchInputReady, setSchemaSearchInputReady] = createSignal(false);
    const [schemaLoading, setSchemaLoading] = createSignal(false);
    const [savedQueries, setSavedQueries] = createSignal((() => {
        try {
            const parsed = JSON.parse(localStorage.getItem('localcloud-sql-bookmarks') || '[]');
            return Array.isArray(parsed) ? parsed : [];
        } catch { return []; }
    })());

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
    const adjustEditorHeight = (delta) => {
        const maxHeight = Math.max(120, window.innerHeight - 200);
        setEditorHeight(h => Math.max(80, Math.min(h + delta, maxHeight)));
    };
    const onEditorResizeKeyDown = (e) => {
        if (e.key === 'ArrowUp') {
            e.preventDefault();
            adjustEditorHeight(-16);
        } else if (e.key === 'ArrowDown') {
            e.preventDefault();
            adjustEditorHeight(16);
        }
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

    // Auto-select instance/database from schema response
    createEffect(() => {
        const svc = service();
        if (svc === 'spanner') {
            const schema = dynamicSchema();
            if (schema?.selectedInstance && !selectedInstance()) setSelectedInstance(schema.selectedInstance);
            if (schema?.databases?.length > 0 && !selectedDatabase()) setSelectedDatabase(schema.databases[0]);
        }
        if (svc === 'alloydb') {
            const schema = dynamicSchema();
            if (schema?.selectedDatabase && !selectedDatabase()) setSelectedDatabase(schema.selectedDatabase);
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
        persistActiveTab(query);
        setIsPlaceholder(false);
        setResult(null);
        setResultPage(1);
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

    // Cache SQL text per service so switching back restores it
    const sqlCache = {};
    let schemaLoadRequest = 0;

    // Reset editor state when service changes (preserve SQL in cache)
    createEffect((prev) => {
        const svc = service();
        if (prev && prev !== svc) {
            schemaLoadRequest++;
            // Save current SQL to cache before switching
            sqlCache[prev] = { text: sqlText(), tabs: sqlTabs(), activeTab: activeSqlTab(), placeholder: isPlaceholder() };
            // Restore from cache or reset
            const cached = sqlCache[svc];
            if (cached && cached.text) {
                setSqlText(cached.text);
                setSqlTabs(cached.tabs);
                setActiveSqlTab(cached.activeTab);
                setIsPlaceholder(cached.placeholder);
            } else {
                setSqlText('');
                setSqlTabs([{ id: 'tab-1', title: 'Query 1', sql: '' }]);
                setActiveSqlTab('tab-1');
                setIsPlaceholder(true);
            }
            setResult(null);
            setResultPage(1);
            setError(null);
            setSchemaLoading(false);
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
        props.refreshTrigger?.();
        // Note: For Spanner, instance/database may be empty on first load.
        // The backend auto-resolves to the first available instance/database.
        if (!NON_SQL_SERVICES.has(svc) && svc !== 'gcs') loadDynamicSchema(svc);
    });

    async function loadDynamicSchema(svc) {
        const requestId = ++schemaLoadRequest;
        setSchemaLoading(true);
        let data = null;
        try {
            // Spanner: always fetch all instances for schema tree (no instance/database filter)
            // Instance/database selection only affects query execution, not schema browsing
            const schemaParams = undefined;
            data = await api.schema(svc, schemaParams);
            if (requestId !== schemaLoadRequest || service() !== svc) { setSchemaLoading(false); return; }
            if (data && data.tables) setDynamicSchema(data);
            else setDynamicSchema(null);
            if (svc === 'spanner' && data) {
                if (data.instances) setSpannerInstances(data.instances);
                if (data.databases) setSpannerDatabases(data.databases);
            }
        } catch {
            if (requestId !== schemaLoadRequest || service() !== svc) { setSchemaLoading(false); return; }
            setDynamicSchema(null);
        }
        if (requestId !== schemaLoadRequest || service() !== svc) { setSchemaLoading(false); return; }
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
        } else if (svc === 'cloudsql' && data && data.instances) {
            const exp = {};
            // Expand all instances and their databases
            for (const inst of (data.instances || [])) exp['inst:' + inst] = true;
            // Expand databases from tables OR from databasesByInstance
            for (const t of (data.tables || [])) {
                if (t.instance && t.database) exp['db:' + t.instance + '/' + t.database] = true;
            }
            // Also expand from databasesByInstance when no tables exist
            if (data.databasesByInstance) {
                for (const [inst, dbs] of Object.entries(data.databasesByInstance)) {
                    for (const db of dbs) {
                        exp['db:' + inst + '/' + db] = true;
                    }
                }
            }
            setExpanded(prev => ({ ...prev, ...exp }));
        } else if (svc === 'alloydb' && data && data.tables) {
            const exp = {};
            // Expand all clusters and their databases
            const clusters = new Set();
            for (const t of (data.tables || [])) {
                if (t.cluster) {
                    clusters.add(t.cluster);
                    exp['cluster:' + t.cluster] = true;
                }
                if (t.cluster && t.database) exp['db:' + t.cluster + '/' + t.database] = true;
            }
            setExpanded(prev => ({ ...prev, ...exp }));
        } else {
            const dbName = info?.dialect === 'bigquery' ? null : 'public';
            if (dbName) setExpanded(prev => ({ ...prev, ['db:' + dbName]: true }));
        }
    }

    function toggle(key) { setExpanded(prev => ({ ...prev, [key]: !prev[key] })); }

    function persistActiveTab(nextSql = sqlText()) {
        const id = activeSqlTab();
        setSqlTabs(prev => prev.map(tab => tab.id === id ? { ...tab, sql: nextSql } : tab));
    }

    function persistSavedQueries(next) {
        setSavedQueries(next);
        try { localStorage.setItem('localcloud-sql-bookmarks', JSON.stringify(next)); } catch {}
    }

    const serviceSavedQueries = createMemo(() => savedQueries().filter(q => q.service === service()));

    function saveActiveQuery() {
        const query = sqlText().trim();
        if (!query) return;
        persistActiveTab(query);
        const tab = sqlTabs().find(t => t.id === activeSqlTab());
        const title = tab?.title || `${currentServiceInfo()?.label || service()} Query`;
        const bookmark = {
            id: `saved-${Date.now()}`,
            service: service(),
            title,
            sql: query,
            savedAt: new Date().toISOString(),
        };
        const next = [
            bookmark,
            ...savedQueries().filter(q => !(q.service === service() && q.sql === query)),
        ].slice(0, 40);
        persistSavedQueries(next);
    }

    function loadSavedQuery(item) {
        setSqlText(item.sql);
        setIsPlaceholder(false);
        persistActiveTab(item.sql);
        setResult(null);
        setResultPage(1);
        setError(null);
    }

    function removeSavedQuery(id, e) {
        e.stopPropagation();
        persistSavedQueries(savedQueries().filter(q => q.id !== id));
    }

    function switchSqlTab(tabId) {
        persistActiveTab();
        const tab = sqlTabs().find(t => t.id === tabId);
        if (!tab) return;
        setActiveSqlTab(tabId);
        setSqlText(tab.sql || '');
        setIsPlaceholder(!tab.sql);
        setResult(null);
        setResultPage(1);
        setError(null);
    }

    function newSqlTab() {
        persistActiveTab();
        const idx = sqlTabs().length + 1;
        const id = `tab-${Date.now()}`;
        setSqlTabs(prev => [...prev, { id, title: `Query ${idx}`, sql: dynamicPlaceholder() || '' }]);
        setActiveSqlTab(id);
        setSqlText(dynamicPlaceholder() || '');
        setIsPlaceholder(true);
        setResult(null);
        setResultPage(1);
        setError(null);
    }

    function closeSqlTab(tabId, event) {
        event?.stopPropagation();
        if (sqlTabs().length <= 1) return;
        const idx = sqlTabs().findIndex(t => t.id === tabId);
        const nextTabs = sqlTabs().filter(t => t.id !== tabId);
        setSqlTabs(nextTabs);
        if (activeSqlTab() === tabId) {
            const next = nextTabs[Math.max(0, idx - 1)];
            setActiveSqlTab(next.id);
            setSqlText(next.sql || '');
            setIsPlaceholder(!next.sql);
        }
    }

    function autoRenameTab(sql) {
        const id = activeSqlTab();
        const tab = sqlTabs().find(t => t.id === id);
        if (!tab || !/^Query \d+$/.test(tab.title)) return; // Only rename default titles
        const upper = sql.toUpperCase().trim();
        let title;
        const fromIdx = upper.indexOf('FROM ');
        if (fromIdx >= 0) {
            const afterFrom = sql.substring(fromIdx + 5).trim().split(/\s+/)[0];
            title = afterFrom.replace(/[`'"]/g, '').replace(/[;,]$/, '');
        } else {
            title = sql.replace(/\s+/g, ' ').trim().substring(0, 30);
        }
        if (title.length > 30) title = title.substring(0, 30) + '…';
        if (!title) return;
        setSqlTabs(prev => prev.map(t => t.id === id ? { ...t, title } : t));
    }

    async function runQuery() {
        const query = sqlText().trim();
        if (!query || running()) return;
        persistActiveTab(query);
        setRunning(true); setError(null); setResult(null); setResultPage(1);
        const startTime = performance.now();
        try {
            // GCS file queries route through BigQuery emulator
            const queryService = service() === 'gcs' ? 'bigquery' : service();
            const params = {};
            if (queryService === 'spanner') {
                const schema = currentSchema();
                params.instance = schema?.selectedInstance || selectedInstance() || '';
                const databases = schema?.databases || [];
                params.database = selectedDatabase() || (databases.length > 0 ? databases[0] : '');
            }
            if (queryService === 'alloydb') {
                const schema = currentSchema();
                params.database = schema?.selectedDatabase || '';
            }
            const data = await api.query(queryService, query, params);
            const elapsed = Math.round(performance.now() - startTime);
            if (data.error) { setError(data.error); }
            else {
                setResult({ columns: data.columns || [], rows: data.rows || [], rowCount: data.row_count ?? (data.rows || []).length, executionTime: data.execution_time_ms || elapsed });
                setResultPage(1);
                // Auto-rename tab from default "Query N" to something meaningful
                autoRenameTab(query);
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

    async function dryRunQuery() {
        const query = sqlText().trim();
        if (!query || running()) return;
        setRunning(true); setError(null); setResult(null); setResultPage(1);
        try {
            const data = await api.queryDryRun(query);
            if (data.valid) {
                const bytes = data.totalBytesProcessed || 0;
                const cost = data.estimatedCostUsd || 0;
                const formattedBytes = bytes < 1024 ? `${bytes} B` :
                    bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(1)} KB` :
                    bytes < 1024 * 1024 * 1024 ? `${(bytes / (1024 * 1024)).toFixed(1)} MB` :
                    `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
                setError(null);
                setResult({ columns: ['Metric', 'Value'], rows: [
                    ['Bytes Processed', formattedBytes],
                    ['Estimated Cost', `$${cost.toFixed(6)}`],
                    ['Query Valid', 'Yes']
                ], rowCount: 3, executionTime: 0, isDryRun: true });
                setResultPage(1);
            } else {
                setError(data.error || 'Dry-run failed');
                setResult(null);
            }
        } catch (err) {
            setError(err.message || 'Dry-run failed');
            setResult(null);
        } finally { setRunning(false); }
    }

    function loadHistoryItem(item) { setSqlText(item.sql); setShowHistory(false); }
    function clearEditor() { setSqlText(''); persistActiveTab(''); setIsPlaceholder(true); setResult(null); setResultPage(1); setError(null); }

    // Quick-select: write SELECT * FROM table LIMIT 10 and execute immediately
    function quickSelect(tableName, e) {
        if (e) e.stopPropagation();
        // Pass the full table name (with database prefix for Spanner/BigQuery) as-is.
        // The backend handles prefix stripping and resolves the correct database.
        const svc = SQL_SERVICES.find(s => s.id === service());
        const quoted = quoteTableName(svc, tableName);
        const sql = `SELECT * FROM ${quoted} LIMIT 10`;
        setSqlText(sql);
        setIsPlaceholder(false);
        persistActiveTab(sql);
        // Small delay to let CodeMirror update, then run
        setTimeout(() => runQuery(), 50);
    }

    // Tree icons imported from ../components/TreeIcons.jsx

    // Track whether current sqlText is still auto-generated placeholder
    const [isPlaceholder, setIsPlaceholder] = createSignal(true);

    const currentServiceInfo = () => SQL_SERVICES.find(s => s.id === service());

    const serviceSchema = createMemo(() => {
        const svc = currentServiceInfo();
        const ds = dynamicSchema();
        if (!svc || !ds || !ds.tables) return ds;
        if (svc.dialect === 'bigquery' || svc.dialect === 'googlesql' || svc.id === 'pubsub' || svc.id === 'bigtable' || svc.id === 'memorystore' || svc.id === 'cloudsql' || svc.id === 'alloydb') return ds;
        const staticTables = SERVICE_SCHEMAS[service()]?.tables;
        if (staticTables && staticTables.length > 0) {
            const allowedNames = new Set(staticTables.map(t => t.name));
            const filtered = ds.tables.filter(t => allowedNames.has(t.name));
            return { ...ds, tables: filtered };
        }
        return { ...ds, tables: [] };
    });

    const currentSchema = () => {
        return serviceSchema() || { tables: [] };
    };
    const cmSchema = () => toCodeMirrorSchema(currentSchema()?.tables);

    // Build hierarchical tree: database → tables → columns
    // For Spanner: instance → database → tables → columns
    const schemaTree = createMemo(() => {
        const schema = currentSchema();
        const tables = schema?.tables || [];
        const q = schemaSearch().toLowerCase().trim();
        const svcInfo = currentServiceInfo();
        const isBigQuery = svcInfo?.dialect === 'bigquery';
        const isSpanner = svcInfo?.id === 'spanner';
        const isAlloyDB = svcInfo?.id === 'alloydb';
        const isCloudSql = svcInfo?.id === 'cloudsql';
        const isBigtable = svcInfo?.id === 'bigtable';

        // Group tables by database/dataset
        const groups = {};
        for (const t of tables) {
            let dbName, tableName;
            if (isBigQuery && t.name.includes('.')) {
                const parts = t.name.split('.');
                dbName = parts[0];
                tableName = parts.slice(1).join('.');
            } else if (isBigtable && t.name.includes('.')) {
                const parts = t.name.split('.');
                dbName = parts[0];
                tableName = parts.slice(1).join('.');
            } else if (isCloudSql && t.name.includes('.')) {
                // Cloud SQL: tables are prefixed as "instance.database.TableName"
                const parts = t.name.split('.');
                dbName = parts[0] + '.' + parts[1];
                tableName = parts.slice(2).join('.');
            } else if (isSpanner && t.name.includes('.')) {
                // Spanner: tables are prefixed as "database.TableName"
                const parts = t.name.split('.');
                dbName = parts[0];
                tableName = parts.slice(1).join('.');
            } else if (isAlloyDB && t.name.includes('.')) {
                // AlloyDB: tables are prefixed as "cluster.database.TableName"
                const parts = t.name.split('.');
                dbName = parts[0] + '.' + parts[1];
                tableName = parts.slice(2).join('.');
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
            // For AlloyDB: group key is "cluster/database" to separate same-named dbs across clusters
            // For Cloud SQL: group key is "instance/database" to separate same-named dbs across instances
            const groupKey = isSpanner && t.instance ? t.instance + '/' + dbName
                : (isAlloyDB && t.cluster ? t.cluster + '/' + dbName
                : (isCloudSql && t.instance ? t.instance + '/' + dbName : dbName));
            groups[groupKey] ||= [];
            groups[groupKey].push({ ...t, shortName: tableName, _instance: t.instance, _cluster: t.cluster, _database: t.database || dbName });
        }
        return groups;
    });

    const spannerInstanceTree = createMemo(() => {
        const tree = schemaTree();
        const instanceGroups = {};
        for (const [groupKey, tables] of Object.entries(tree)) {
            const inst = tables[0]?._instance || currentSchema()?.selectedInstance || 'default';
            const dbName = tables[0]?._database || groupKey;
            if (!instanceGroups[inst]) instanceGroups[inst] = {};
            instanceGroups[inst][dbName] = tables;
        }
        return instanceGroups;
    });

    const alloydbClusterTree = createMemo(() => {
        const tree = schemaTree();
        const clusterGroups = {};
        for (const [groupKey, tables] of Object.entries(tree)) {
            const cluster = tables[0]?._cluster || 'default';
            const dbName = tables[0]?._database || groupKey;
            if (!clusterGroups[cluster]) clusterGroups[cluster] = {};
            clusterGroups[cluster][dbName] = tables;
        }
        return clusterGroups;
    });

    const cloudsqlInstanceTree = createMemo(() => {
        const tree = schemaTree();
        const schema = currentSchema();
        const instanceGroups = {};

        // First, build from tables (if any exist)
        for (const [groupKey, tables] of Object.entries(tree)) {
            const inst = tables[0]?._instance || schema?.selectedInstance || 'default';
            const dbName = tables[0]?._database || groupKey;
            if (!instanceGroups[inst]) instanceGroups[inst] = {};
            instanceGroups[inst][dbName] = tables;
        }

        // If no tables found, fall back to databasesByInstance to show instances even when empty
        if (Object.keys(instanceGroups).length === 0 && schema?.databasesByInstance) {
            for (const [inst, dbs] of Object.entries(schema.databasesByInstance)) {
                if (!instanceGroups[inst]) instanceGroups[inst] = {};
                for (const db of dbs) {
                    if (!instanceGroups[inst][db]) instanceGroups[inst][db] = [];
                }
            }
        }

        return instanceGroups;
    });

    const dynamicPlaceholder = createMemo(() => {
        const svc = currentServiceInfo();
        if (!svc) return '';
        const ds = serviceSchema();
        if (ds && ds.tables && ds.tables.length > 0) {
            const tbl = ds.tables[0];
            // Strip database prefix for Spanner (e.g., "my-database.Users" → "Users")
            // Strip cluster.database prefix for AlloyDB (e.g., "my-cluster.my-db.Users" → "Users")
            // For Cloud SQL, keep full instance.database.table name so backend can resolve physical DB
            const isPrefixed = (svc.id === 'spanner' || svc.id === 'alloydb') && tbl.name.includes('.');
            const tableName = isPrefixed ? tbl.name.split('.').pop() : tbl.name;
            return `SELECT * FROM ${quoteTableName(svc, tableName)} LIMIT 10`;
        }
        return svc.placeholder;
    });

    // Set placeholder from dynamic schema when it loads (overwrite if still auto-generated)
    createEffect(() => {
        const placeholder = dynamicPlaceholder();
        if (placeholder && isPlaceholder()) {
            setSqlText(placeholder);
            persistActiveTab(placeholder);
        }
    });

    // Wrap onChange to track user edits
    const handleSqlChange = (val) => {
        setIsPlaceholder(false);
        setSqlText(val);
        persistActiveTab(val);
    };

    const enableSchemaSearchInput = () => setSchemaSearchInputReady(true);
    const handleSchemaSearchInput = (e) => {
        if (!schemaSearchInputReady()) {
            e.currentTarget.value = '';
            setSchemaSearch('');
            return;
        }
        setSchemaSearch(e.currentTarget.value);
    };
    const resultRows = () => result()?.rows || [];
    const resultTotalPages = () => Math.max(1, Math.ceil(resultRows().length / SQL_RESULT_PAGE_SIZE));
    const resultPageStart = () => (resultPage() - 1) * SQL_RESULT_PAGE_SIZE;
    const resultPageEnd = () => Math.min(resultRows().length, resultPageStart() + SQL_RESULT_PAGE_SIZE);
    const visibleResultRows = () => resultRows().slice(resultPageStart(), resultPageEnd());
    createEffect(() => {
        if (resultPage() > resultTotalPages()) setResultPage(resultTotalPages());
    });

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
                            <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false" style={{ opacity: 0.35, "flex-shrink": 0 }}>
                            <path d="M15.5 14h-.79l-.28-.27A6.471 6.471 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"/>
                        </svg>
                        <input
	                            type="text"
	                            placeholder="Filter resources…"
                                aria-label="Filter SQL resources"
                                autocomplete="new-password"
                                autocapitalize="off"
                                autocorrect="off"
                                spellcheck={false}
                                data-lpignore="true"
                                data-1p-ignore="true"
                                readOnly={!schemaSearchInputReady()}
                                onPointerDown={enableSchemaSearchInput}
                                onKeyDown={enableSchemaSearchInput}
                                onFocus={enableSchemaSearchInput}
	                            value={schemaSearch()}
                            onInput={handleSchemaSearchInput}
                        />
                    </div>
                </div>
                <div class="sql-explorer-tree">
                    <Show when={serviceSavedQueries().length > 0}>
                        <div class="sql-bookmark-section">
                            <div class="sql-bookmark-heading">
                                <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path d="M17 3H7a2 2 0 0 0-2 2v17l7-3 7 3V5a2 2 0 0 0-2-2z"/></svg>
                                Saved queries
                            </div>
                            <For each={serviceSavedQueries()}>
                                {(item) => (
                                    <div
                                        class="sql-bookmark-item"
                                        onClick={() => loadSavedQuery(item)}
                                        onKeyDown={onActivate(() => loadSavedQuery(item))}
                                        role="button"
                                        tabIndex={0}
                                    >
                                        <span class="sql-bookmark-title">{item.title}</span>
                                        <span class="sql-bookmark-sql">{item.sql}</span>
                                        <button class="sql-bookmark-remove" onClick={(e) => removeSavedQuery(item.id, e)} aria-label={`Remove ${item.title}`} title="Remove saved query">&times;</button>
                                    </div>
                                )}
                            </For>
                        </div>
                    </Show>

                    <Show when={schemaLoading()}>
                        <div class="sql-explorer-loading">
                            <div class="loading-spinner" style={{ width: '14px', height: '14px', "border-width": '1.5px' }} />
	                            <span>{isGcsMode() ? 'Loading files…' : 'Loading schema…'}</span>
                        </div>
                    </Show>

                    {/* ── GCS File Explorer Tree ── */}
                    <Show when={isGcsMode() && !schemaLoading()}>
                        <Show when={gcsBuckets().length === 0}>
                            <div class="sql-explorer-empty">
	                                <svg width="32" height="32" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false" style={{ opacity: 0.12 }}>
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
	                                        <div class="tree-row tree-row-db" onClick={() => toggle(bucketKey)} onKeyDown={onActivate(() => toggle(bucketKey))} role="button" tabIndex={0} aria-expanded={!!expanded()[bucketKey]}>
	                                            <IconChevron open={expanded()[bucketKey]} />
	                                            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" class="tree-icon tree-icon-db" aria-hidden="true" focusable="false">
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
	                                                                    onClick={() => handleFileClick(bucket.name, file.name)}
                                                                        onKeyDown={onActivate(() => handleFileClick(bucket.name, file.name))}
                                                                        aria-expanded={!!expanded()[fileKey]}>
	                                                                    <span style={{ cursor: 'pointer', display: 'inline-flex' }}
	                                                                        onClick={(e) => { e.stopPropagation(); handleFileExpand(bucket.name, file.name); }}
                                                                            onKeyDown={onActivate((e) => { e.stopPropagation(); handleFileExpand(bucket.name, file.name); })}
                                                                            role="button"
                                                                            tabIndex={0}
                                                                            aria-label={`${expanded()[fileKey] ? 'Collapse' : 'Expand'} ${file.name}`}>
                                                                        <IconChevron open={expanded()[fileKey]} />
                                                                    </span>
                                                                    <IconTable />
                                                                    <span class="tree-name">{file.name}</span>
                                                                    <span class={`tree-badge ${fileBadgeClass(file.name)}`}>{formatBadge(file.name)}</span>
                                                                    <Show when={file.size}>
                                                                        <span class="tree-col-type" style={{ "margin-left": "auto" }}>{formatSize(file.size)}</span>
                                                                    </Show>
                                                                </div>
                                                                <Show when={expanded()[fileKey]}>
                                                                    <div class="tree-children">
                                                                        <Show when={isLoading()}>
                                                                            <div class="sql-explorer-loading" style={{ padding: '4px 0 4px 24px' }}>
                                                                                <div class="loading-spinner" style={{ width: '12px', height: '12px', "border-width": '1.5px' }} />
	                                                                                <span>Detecting schema…</span>
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
                    <Show when={!isGcsMode() && !schemaLoading() && Object.keys(schemaTree()).length === 0 && !(service() === 'cloudsql' && currentSchema()?.instances?.length > 0)}>
                        <div class="sql-explorer-empty">
	                            <svg width="32" height="32" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false" style={{ opacity: 0.12 }}>
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
	                                        <div class="tree-row tree-row-db" onClick={() => toggle(instKey)} onKeyDown={onActivate(() => toggle(instKey))} role="button" tabIndex={0} aria-expanded={expanded()[instKey] !== false}>
	                                            <IconChevron open={expanded()[instKey] !== false} />
	                                            <svg width="14" height="14" viewBox="0 0 24 24" fill="var(--primary)" aria-hidden="true" focusable="false" style={{"flex-shrink":"0"}}><path d="M19 15v4H5v-4h14m1-2H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1v-6c0-.55-.45-1-1-1zM7 18.5c-.82 0-1.5-.67-1.5-1.5s.68-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zM19 3v4H5V3h14m1-2H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1V2c0-.55-.45-1-1-1zM7 6.5c-.82 0-1.5-.67-1.5-1.5S6.19 3.5 7 3.5s1.5.67 1.5 1.5S7.83 6.5 7 6.5zM19 9v4H5V9h14m1-2H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1v-6c0-.55-.45-1-1-1zM7 12.5c-.82 0-1.5-.67-1.5-1.5s.68-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5z"/></svg>
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
	                                                                    onKeyDown={onActivate(() => { toggle(dbKey); setSelectedInstance(instName); setSelectedDatabase(dbName); })}
	                                                                    role="button" tabIndex={0} aria-expanded={!!expanded()[dbKey]}>
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
	                                                                                            onKeyDown={onActivate(() => { toggle(tblKey); setSelectedInstance(instName); setSelectedDatabase(dbName); })}
	                                                                                            role="button" tabIndex={0} aria-expanded={!!expanded()[tblKey]}>
                                                                                            <IconChevron open={expanded()[tblKey]} />
                                                                                            <IconTable />
                                                                                            <span class="tree-name">{table.shortName}</span>
                                                                                            <span class="tree-badge">{colCount}</span>
	                                                                                            <button class="tree-run-btn" title="SELECT * LIMIT 10" aria-label={`Run SELECT * for ${table.shortName}`} onClick={(e) => { setSelectedInstance(instName); setSelectedDatabase(dbName); quickSelect(table.name, e); }}>
	                                                                                                <svg width="10" height="10" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path d="M8 5v14l11-7z"/></svg>
                                                                                            </button>
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
                    {/* AlloyDB: cluster → database → table hierarchy */}
                    <Show when={service() === 'alloydb' && Object.keys(alloydbClusterTree()).length > 0}>
                        <For each={Object.entries(alloydbClusterTree())}>
                            {([clusterName, databases]) => {
                                const clusterKey = 'cluster:' + clusterName;
                                const dbCount = Object.keys(databases).length;
                                return (
                                    <div class="tree-group">
                                        <div class="tree-row tree-row-db" onClick={() => toggle(clusterKey)} onKeyDown={onActivate(() => toggle(clusterKey))} role="button" tabIndex={0} aria-expanded={expanded()[clusterKey] !== false}>
                                            <IconChevron open={expanded()[clusterKey] !== false} />
                                            <svg width="14" height="14" viewBox="0 0 24 24" fill="var(--primary)" aria-hidden="true" focusable="false" style={{"flex-shrink":"0"}}><path d="M19 15v4H5v-4h14m1-2H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1v-6c0-.55-.45-1-1-1zM7 18.5c-.82 0-1.5-.67-1.5-1.5s.68-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zM19 3v4H5V3h14m1-2H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1V2c0-.55-.45-1-1-1zM7 6.5c-.82 0-1.5-.67-1.5-1.5S6.19 3.5 7 3.5s1.5.67 1.5 1.5S7.83 6.5 7 6.5z"/></svg>
                                            <span class="tree-name" style={{"font-weight":"600"}}>{clusterName}</span>
                                            <span class="tree-badge tree-badge-db">{dbCount} db{dbCount !== 1 ? 's' : ''}</span>
                                        </div>
                                        <Show when={expanded()[clusterKey] !== false}>
                                            <div class="tree-children">
                                                <For each={Object.entries(databases)}>
                                                    {([dbName, tables]) => {
                                                        const dbKey = 'db:' + clusterName + '/' + dbName;
                                                        const tableCount = tables.length;
                                                        const physicalDb = tables[0]?._database || dbName;
                                                        return (
                                                            <div class="tree-group">
                                                                <div class="tree-row tree-row-db"
                                                                    onClick={() => { toggle(dbKey); setSelectedDatabase(physicalDb); }}
                                                                    onKeyDown={onActivate(() => { toggle(dbKey); setSelectedDatabase(physicalDb); })}
                                                                    role="button" tabIndex={0} aria-expanded={!!expanded()[dbKey]}>
                                                                    <IconChevron open={expanded()[dbKey]} />
                                                                    <IconDatabase />
                                                                    <span class="tree-name">{dbName}</span>
                                                                    <span class="tree-badge tree-badge-db">{tableCount} table{tableCount !== 1 ? 's' : ''}</span>
                                                                </div>
                                                                <Show when={expanded()[dbKey]}>
                                                                    <div class="tree-children">
                                                                        <For each={tables}>
                                                                            {(table) => {
                                                                                const tblKey = 'tbl:' + clusterName + '/' + table.name;
                                                                                const colCount = (table.columns || []).length;
                                                                                return (
                                                                                    <div class="tree-group">
                                                                                        <div class="tree-row tree-row-tbl"
                                                                                            onClick={() => { toggle(tblKey); setSelectedDatabase(physicalDb); }}
                                                                                            onKeyDown={onActivate(() => { toggle(tblKey); setSelectedDatabase(physicalDb); })}
                                                                                            role="button" tabIndex={0} aria-expanded={!!expanded()[tblKey]}>
                                                                                            <IconChevron open={expanded()[tblKey]} />
                                                                                            <IconTable />
                                                                                            <span class="tree-name">{table.shortName}</span>
                                                                                            <span class="tree-badge">{colCount}</span>
                                                                                            <button class="tree-run-btn" title="SELECT * LIMIT 10" aria-label={`Run SELECT * for ${table.shortName}`} onClick={(e) => { setSelectedDatabase(physicalDb); quickSelect(table.name, e); }}>
                                                                                                <svg width="10" height="10" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path d="M8 5v14l11-7z"/></svg>
                                                                                            </button>
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
                    {/* Cloud SQL: instance → database → table hierarchy */}
                    <Show when={service() === 'cloudsql' && currentSchema()?.instances?.length > 0}>
                        <For each={Object.entries(cloudsqlInstanceTree())}>
                            {([instName, databases]) => {
                                const instKey = 'inst:' + instName;
                                const dbCount = Object.keys(databases).length;
                                return (
                                    <div class="tree-group">
                                        <div class="tree-row tree-row-db" onClick={() => toggle(instKey)} onKeyDown={onActivate(() => toggle(instKey))} role="button" tabIndex={0} aria-expanded={expanded()[instKey] !== false}>
                                            <IconChevron open={expanded()[instKey] !== false} />
                                            <svg width="14" height="14" viewBox="0 0 24 24" fill="var(--primary)" aria-hidden="true" focusable="false" style={{"flex-shrink":"0"}}><path d="M19 15v4H5v-4h14m1-2H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1v-6c0-.55-.45-1-1-1zM7 18.5c-.82 0-1.5-.67-1.5-1.5s.68-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zM19 3v4H5V3h14m1-2H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1V2c0-.55-.45-1-1-1zM7 6.5c-.82 0-1.5-.67-1.5-1.5S6.19 3.5 7 3.5s1.5.67 1.5 1.5S7.83 6.5 7 6.5z"/></svg>
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
                                                                    onClick={() => toggle(dbKey)}
                                                                    onKeyDown={onActivate(() => toggle(dbKey))}
                                                                    role="button" tabIndex={0} aria-expanded={!!expanded()[dbKey]}>
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
                                                                                            onClick={() => toggle(tblKey)}
                                                                                            onKeyDown={onActivate(() => toggle(tblKey))}
                                                                                            role="button" tabIndex={0} aria-expanded={!!expanded()[tblKey]}>
                                                                                            <IconChevron open={expanded()[tblKey]} />
                                                                                            <IconTable />
                                                                                            <span class="tree-name">{table.shortName}</span>
                                                                                            <span class="tree-badge">{colCount}</span>
                                                                                            <button class="tree-run-btn" title="SELECT * LIMIT 10" aria-label={`Run SELECT * for ${table.shortName}`} onClick={(e) => quickSelect(table.name, e)}>
                                                                                                <svg width="10" height="10" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path d="M8 5v14l11-7z"/></svg>
                                                                                            </button>
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
                    {/* Non-hierarchical services: flat database grouping */}
                    <Show when={service() !== 'spanner' && service() !== 'alloydb' && service() !== 'cloudsql'}>
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
	                                        onKeyDown={onActivate(() => toggle(dbKey))}
	                                        role="button" tabIndex={0}
                                            aria-expanded={!!expanded()[dbKey]}
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
                                                    const isBigQuery = currentServiceInfo()?.dialect === 'bigquery';
                                                    const tableType = table.type || 'TABLE';
                                                    const numRows = table.numRows || 0;
                                                    const numBytes = table.numBytes || 0;
                                                    const formattedRows = numRows >= 1_000_000 ? (numRows / 1_000_000).toFixed(1) + 'M' :
                                                        numRows >= 1_000 ? (numRows / 1_000).toFixed(1) + 'K' : String(numRows);
                                                    const formattedBytes = numBytes >= 1_073_741_824 ? (numBytes / 1_073_741_824).toFixed(1) + ' GB' :
                                                        numBytes >= 1_048_576 ? (numBytes / 1_048_576).toFixed(1) + ' MB' :
                                                        numBytes >= 1_024 ? (numBytes / 1_024).toFixed(1) + ' KB' : numBytes + ' B';
                                                    const tooltipParts = [table.shortName];
                                                    if (isBigQuery) {
                                                        tooltipParts.push(`Type: ${tableType}`);
                                                        tooltipParts.push(`Rows: ${formattedRows}`);
                                                        tooltipParts.push(`Size: ${formattedBytes}`);
                                                        if (table.description) tooltipParts.push(table.description);
                                                        if (table.clustering) tooltipParts.push(`Clustered by: ${table.clustering.join(', ')}`);
                                                        if (table.timePartitioning) tooltipParts.push(`Partitioned: ${table.timePartitioning.type || 'DAY'}`);
                                                    }
                                                    return (
                                                        <div class="tree-group">
                                                            {/* ── Table node ── */}
                                                            <div
                                                                class="tree-row tree-row-tbl"
                                                                onClick={() => toggle(tblKey)}
                                                                onKeyDown={onActivate(() => toggle(tblKey))}
                                                                role="button" tabIndex={0}
                                                                aria-expanded={!!expanded()[tblKey]}
                                                                title={tooltipParts.join('\n')}
                                                            >
                                                                <IconChevron open={expanded()[tblKey]} />
                                                                <Show when={tableType === 'VIEW'} fallback={<IconTable />}>
                                                                    <svg width="14" height="14" viewBox="0 0 24 24" fill="var(--text-secondary)" aria-hidden="true" focusable="false">
                                                                        <path d="M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z"/>
                                                                    </svg>
                                                                </Show>
                                                                <span class="tree-name">{table.shortName}</span>
                                                                <span class="tree-badge">{colCount}</span>
                                                                <Show when={isBigQuery && tableType === 'VIEW'}>
                                                                    <span class="tree-badge tree-badge-view">VIEW</span>
                                                                </Show>
                                                                <Show when={isBigQuery && numRows > 0}>
                                                                    <span class="tree-badge tree-badge-rows">{formattedRows}</span>
                                                                </Show>
                                                                <button class="tree-run-btn" title="SELECT * LIMIT 10" aria-label={`Run SELECT * for ${table.shortName}`} onClick={(e) => quickSelect(table.name, e)}>
                                                                    <svg width="10" height="10" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path d="M8 5v14l11-7z"/></svg>
                                                                </button>
                                                            </div>
                                                            <Show when={expanded()[tblKey]}>
                                                                <div class="tree-children">
                                                                    <Show when={isBigQuery}>
                                                                        <div class="tree-row tree-row-meta" title="Table metadata">
                                                                            <svg width="12" height="12" viewBox="0 0 24 24" fill="var(--text-tertiary)" aria-hidden="true" focusable="false">
                                                                                <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z"/>
                                                                            </svg>
                                                                            <span class="tree-meta-text">{tableType} &middot; {formattedRows} rows &middot; {formattedBytes}</span>
                                                                        </div>
                                                                        <Show when={table.description}>
                                                                            <div class="tree-row tree-row-meta">
                                                                                <span class="tree-meta-text" style="color: var(--text-tertiary); font-style: italic;">{table.description}</span>
                                                                            </div>
                                                                        </Show>
                                                                        <Show when={table.timePartitioning}>
                                                                            <div class="tree-row tree-row-meta">
                                                                                <span class="tree-meta-text">Partitioned: {table.timePartitioning?.type || 'DAY'}{table.timePartitioning?.field ? ' on ' + table.timePartitioning.field : ''}</span>
                                                                            </div>
                                                                        </Show>
                                                                        <Show when={table.clustering && table.clustering.length > 0}>
                                                                            <div class="tree-row tree-row-meta">
                                                                                <span class="tree-meta-text">Clustered: {table.clustering.join(', ')}</span>
                                                                            </div>
                                                                        </Show>
                                                                    </Show>
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
                <div class="aura-sql-tabbar" role="tablist" aria-label="SQL tabs">
                    <For each={sqlTabs()}>
                        {(tab) => (
	                            <div
	                                class={`aura-sql-tab ${activeSqlTab() === tab.id ? 'active' : ''}`}
	                                role="tab"
	                                aria-selected={activeSqlTab() === tab.id}
	                                onClick={() => switchSqlTab(tab.id)}
                                    onKeyDown={onActivate(() => switchSqlTab(tab.id))}
                                    tabIndex="0"
	                            >
                                    <img src={`/icons/${currentServiceInfo()?.icon || service()}.svg`} alt="" width="14" height="14" class="aura-sql-tab-icon" />
	                                <span>{tab.title}</span>
	                                <Show when={sqlTabs().length > 1}>
	                                    <button type="button" class="aura-sql-tab-close" onClick={(e) => closeSqlTab(tab.id, e)} aria-label={`Close ${tab.title}`}>&times;</button>
	                                </Show>
	                            </div>
                        )}
                    </For>
	                    <button class="aura-sql-tab-add" onClick={newSqlTab} title="New SQL tab" aria-label="New SQL tab">+</button>
                </div>
                {/* Consolidated Toolbar */}
                <div class="sql-toolbar-unified">
                    <div class="sql-toolbar-left">
                        <span class="sql-dialect-badge">{currentServiceInfo()?.dialectLabel}</span>
                    </div>
                    <div class="sql-toolbar-center">
                        <button class="btn btn-primary sql-run-btn" onClick={runQuery} disabled={running() || !sqlText().trim()}>
                            <Show when={running()} fallback={
                                <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path d="M8 5v14l11-7z"/></svg>
                            }>
                                <div class="loading-spinner" style={{ width: '14px', height: '14px', "border-width": '2px' }} />
                            </Show>
                            {running() ? 'Running…' : 'Run'}
                        </button>
                        <Show when={currentServiceInfo()?.dialect === 'bigquery'}>
                            <button class="btn btn-secondary sql-dryrun-btn" onClick={dryRunQuery} disabled={running() || !sqlText().trim()} title="Estimate query cost without executing">
                                <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false">
                                    <path d="M11.8 10.9c-2.27-.59-3-1.2-3-2.15 0-1.09 1.01-1.85 2.7-1.85 1.78 0 2.44.85 2.5 2.1h2.21c-.07-1.72-1.12-3.3-3.21-3.81V3h-3v2.16c-1.94.42-3.5 1.68-3.5 3.61 0 2.31 1.91 3.46 4.7 4.13 2.5.6 3 1.48 3 2.41 0 .69-.49 1.79-2.7 1.79-2.06 0-2.87-.92-2.98-2.1h-2.2c.12 2.19 1.76 3.42 3.68 3.83V21h3v-2.15c1.95-.37 3.5-1.5 3.5-3.55 0-2.84-2.43-3.81-4.7-4.4z"/>
                                </svg>
                                Dry Run
                            </button>
                        </Show>
                        <button class="btn btn-secondary" onClick={clearEditor} title="Clear editor and results">Clear</button>
                        <button class="btn btn-secondary" onClick={saveActiveQuery} disabled={!sqlText().trim()} title="Save query to the explorer">
                            <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path d="M17 3H7a2 2 0 0 0-2 2v17l7-3 7 3V5a2 2 0 0 0-2-2z"/></svg>
                            Save Query
                        </button>
                        <div class="sql-shortcut-hint">
                            <kbd>{navigator.platform?.includes('Mac') ? '\u2318' : 'Ctrl'}</kbd><span>+</span><kbd>Enter</kbd>
                        </div>
                    </div>
                    <div class="sql-toolbar-right">
	                        <button class="btn btn-icon sql-toolbar-btn" title="Query history" aria-label="Open query history" aria-expanded={showHistory()} onClick={() => setShowHistory(!showHistory())}>
	                            <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false">
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
	                        placeholder="Enter SQL query…"
                        onRun={runQuery}
                        class="sql-editor-fill"
                    />
                </div>

                {/* Resize Handle */}
	                <div
	                    class="sql-resize-handle"
	                    onMouseDown={startResize}
                        onKeyDown={onEditorResizeKeyDown}
                        role="separator"
                        aria-orientation="horizontal"
                        aria-valuemin="80"
                        aria-valuemax={Math.max(120, window.innerHeight - 200)}
                        aria-valuenow={editorHeight()}
                        tabIndex="0"
	                    title="Drag to resize editor"
	                />

                {/* Status Bar */}
                <div class="sql-status-bar">
                    <Show when={running()}>
                        <div class="loading-spinner" style={{ width: '12px', height: '12px', "border-width": '1.5px' }} />
	                        <span>Running query…</span>
                    </Show>
                    <Show when={!running() && error()}>
	                        <svg width="14" height="14" viewBox="0 0 24 24" fill="var(--error)" aria-hidden="true" focusable="false">
                            <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z"/>
                        </svg>
	                        <span class="sql-status-error" role="alert">{error()}</span>
                    </Show>
                    <Show when={!running() && !error() && result()}>
                        <Show when={result().isDryRun} fallback={
                            <>
                                <svg width="14" height="14" viewBox="0 0 24 24" fill="var(--success)" aria-hidden="true" focusable="false">
                                    <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/>
                                </svg>
                                <span class="sql-status-ok">Query complete</span>
                                <span class="sql-status-meta">{formatNumber(result().rowCount)} row{result().rowCount !== 1 ? 's' : ''} &middot; TTFB {formatDuration(Math.max(1, Math.round(result().executionTime * 0.42)))} &middot; Local latency {formatDuration(result().executionTime)}</span>
                            </>
                        }>
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="var(--warning)" aria-hidden="true" focusable="false">
                                <path d="M11.8 10.9c-2.27-.59-3-1.2-3-2.15 0-1.09 1.01-1.85 2.7-1.85 1.78 0 2.44.85 2.5 2.1h2.21c-.07-1.72-1.12-3.3-3.21-3.81V3h-3v2.16c-1.94.42-3.5 1.68-3.5 3.61 0 2.31 1.91 3.46 4.7 4.13 2.5.6 3 1.48 3 2.41 0 .69-.49 1.79-2.7 1.79-2.06 0-2.87-.92-2.98-2.1h-2.2c.12 2.19 1.76 3.42 3.68 3.83V21h3v-2.15c1.95-.37 3.5-1.5 3.5-3.55 0-2.84-2.43-3.81-4.7-4.4z"/>
                            </svg>
                            <span class="sql-status-ok" style="color: var(--warning)">Dry run complete</span>
                            <span class="sql-status-meta">Estimated cost &amp; bytes processed shown below</span>
                        </Show>
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
	                                    <For each={visibleResultRows()}>
	                                        {(row, idx) => (
	                                            <tr>
	                                                <td class="sql-results-rownum">{resultPageStart() + idx() + 1}</td>
                                                <For each={row}>
                                                    {(cell) => (
                                                        <td title={cell != null ? String(typeof cell === 'object' ? JSON.stringify(cell) : cell) : 'NULL'}>
                                                            <JsonCell value={cell} />
                                                        </td>
                                                    )}
                                                </For>
                                            </tr>
                                        )}
                                    </For>
                                </tbody>
	                            </table>
	                        </div>
                            <Show when={resultRows().length > SQL_RESULT_PAGE_SIZE}>
                                <div class="pagination-controls" aria-label="SQL result pagination">
                                    <span>{resultPageStart() + 1}-{resultPageEnd()} of {formatNumber(resultRows().length)}</span>
                                    <button class="btn btn-secondary" onClick={() => setResultPage(Math.max(1, resultPage() - 1))} disabled={resultPage() <= 1}>Previous</button>
                                    <button class="btn btn-secondary" onClick={() => setResultPage(Math.min(resultTotalPages(), resultPage() + 1))} disabled={resultPage() >= resultTotalPages()}>Next</button>
                                </div>
                            </Show>
	                    </Show>
                    <Show when={result() && result().rows.length === 0 && !error()}>
                        <div class="sql-results-placeholder">Query returned no rows.</div>
                    </Show>
                    <Show when={!result() && !error() && !running()}>
                        <div class="sql-results-placeholder">
	                            <svg width="40" height="40" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false" style={{ opacity: 0.15 }}>
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
	                            <button class="btn btn-icon" onClick={() => setShowHistory(false)} aria-label="Close query history">
	                                <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false">
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
	                                            <span class="sql-history-time">{formatTime(item.timestamp, { hour: '2-digit', minute: '2-digit' })}</span>
                                        </div>
	                                        <div class="sql-history-sql">{item.sql.length > 80 ? item.sql.slice(0, 80) + '…' : item.sql}</div>
                                        <div class="sql-history-item-meta">
                                            <Show when={item.error}><span class="sql-history-error">Error</span></Show>
                                            <Show when={!item.error}>
	                                                <span>{formatNumber(item.rowCount)} row{item.rowCount !== 1 ? 's' : ''}</span>
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

// ─── Database Panels Component (History + Stats) ─────────────────────
const QUERY_HISTORY_SERVICES = new Set(['spanner', 'bigquery', 'alloydb', 'cloudsql', 'bigtable', 'memorystore']);

const SERVICES_WITH_INFO_SCHEMA = new Set(['bigquery', 'spanner', 'alloydb', 'cloudsql']);
const INFO_SCHEMA_VIEWS = {
    bigquery: ['tables', 'columns', 'schemata', 'views', 'routines', 'partitions', 'table_storage'],
    spanner: ['tables', 'columns', 'table_statistics'],
    alloydb: ['tables', 'columns', 'schemata', 'views', 'routines'],
    cloudsql: ['tables', 'columns', 'schemata', 'views', 'routines'],
};
const INFO_SCHEMA_VIEW_LABELS = {
    tables: 'TABLES', columns: 'COLUMNS', schemata: 'SCHEMATA', views: 'VIEWS',
    routines: 'ROUTINES', partitions: 'PARTITIONS', table_storage: 'TABLE_STORAGE',
    table_statistics: 'TABLE_STATISTICS',
};

// Info schema loaders per service — shared between Data Explorer and Stats panel
const INFO_SCHEMA_LOADERS = {
    bigquery: {
        views: INFO_SCHEMA_VIEWS.bigquery,
        async load(viewType) {
            return api.bigqueryInfoSchema(viewType);
        },
    },
    spanner: {
        views: INFO_SCHEMA_VIEWS.spanner,
        async load(viewType) {
            const viewMap = {
                tables: "SELECT t.table_catalog, t.table_schema, t.table_name, t.table_type FROM information_schema.tables t WHERE t.table_schema = '' ORDER BY t.table_name",
                columns: "SELECT c.table_name, c.column_name, c.spanner_type, c.is_nullable FROM information_schema.columns c WHERE c.table_schema = '' ORDER BY c.table_name, c.ordinal_position",
                table_statistics: "SELECT table_name, row_count, file_count FROM information_schema.table_statistics WHERE table_schema = '' ORDER BY table_name",
            };
            const sql = viewMap[viewType] || viewMap.tables;
            const result = await api.query('spanner', sql, { instance: 'local-instance', database: 'local-database' });
            return { columns: result.columns || [], rows: (result.rows || []).map(row => {
                const obj = {};
                result.columns.forEach((col, i) => obj[col] = row[i]);
                return obj;
            })};
        },
    },
    alloydb: {
        views: INFO_SCHEMA_VIEWS.alloydb,
        async load(viewType) {
            const viewMap = {
                tables: "SELECT table_catalog, table_schema, table_name, table_type FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name",
                columns: "SELECT table_name, column_name, data_type, is_nullable FROM information_schema.columns WHERE table_schema = 'public' ORDER BY table_name, ordinal_position",
                schemata: "SELECT catalog_name, schema_name, schema_owner FROM information_schema.schemata ORDER BY schema_name",
                views: "SELECT table_catalog, table_schema, table_name, view_definition FROM information_schema.views WHERE table_schema = 'public' ORDER BY table_name",
                routines: "SELECT routine_name, routine_type, data_type FROM information_schema.routines WHERE routine_schema = 'public' ORDER BY routine_name",
            };
            const sql = viewMap[viewType] || viewMap.tables;
            const result = await api.query('alloydb', sql);
            return { columns: result.columns || [], rows: (result.rows || []).map(row => {
                const obj = {};
                result.columns.forEach((col, i) => obj[col] = row[i]);
                return obj;
            })};
        },
    },
    cloudsql: {
        views: INFO_SCHEMA_VIEWS.cloudsql,
        async load(viewType) {
            const viewMap = {
                tables: "SELECT table_catalog, table_schema, table_name, table_type FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name",
                columns: "SELECT table_name, column_name, data_type, is_nullable FROM information_schema.columns WHERE table_schema = 'public' ORDER BY table_name, ordinal_position",
                schemata: "SELECT catalog_name, schema_name, schema_owner FROM information_schema.schemata ORDER BY schema_name",
                views: "SELECT table_catalog, table_schema, table_name, view_definition FROM information_schema.views WHERE table_schema = 'public' ORDER BY table_name",
                routines: "SELECT routine_name, routine_type, data_type FROM information_schema.routines WHERE routine_schema = 'public' ORDER BY routine_name",
            };
            const sql = viewMap[viewType] || viewMap.tables;
            const result = await api.query('cloudsql', sql);
            return { columns: result.columns || [], rows: (result.rows || []).map(row => {
                const obj = {};
                result.columns.forEach((col, i) => obj[col] = row[i]);
                return obj;
            })};
        },
    },
};

function InfoSchemaPanel(props) {
    const views = () => props.views || [];
    return (
        <div style="margin-top:24px;border:1px solid var(--border);border-radius:8px;overflow:hidden">
            <div style="display:flex;align-items:center;padding:10px 14px;background:var(--surface-variant);border-bottom:1px solid var(--border);gap:8px">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="var(--primary)" aria-hidden="true"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z"/></svg>
                <span style="font-size:13px;font-weight:600">INFORMATION_SCHEMA</span>
            </div>
            <div style="display:flex;gap:2px;padding:8px 14px;background:var(--surface);border-bottom:1px solid var(--border);overflow-x:auto">
                <For each={views()}>{v => (
                    <button onClick={() => props.onSelectView(v)} style={{
                        padding: '5px 12px', border: 'none', borderRadius: '5px', cursor: 'pointer',
                        fontSize: '11px', fontWeight: props.view === v ? 600 : 400,
                        background: props.view === v ? 'var(--primary)' : 'transparent',
                        color: props.view === v ? '#fff' : 'var(--text-secondary)',
                        transition: 'all 0.15s', whiteSpace: 'nowrap'
                    }}>{INFO_SCHEMA_VIEW_LABELS[v] || v}</button>
                )}</For>
            </div>
            <div style="max-height:400px;overflow:auto;padding:12px 14px;background:var(--bg)">
                <Show when={!props.loading && props.data} fallback={
                    <Show when={props.loading} fallback={
                        <div class="empty-state" style="padding:24px;text-align:center;color:var(--text-tertiary);font-size:13px">Select a view above to browse system metadata</div>
                    }>
                        <div class="loading-state" style="padding:24px"><div class="loading-spinner" /> Loading…</div>
                    </Show>
                }>
                    <table class="data-table" style="font-size:12px">
                        <thead><tr><For each={(props.data?.columns || [])}>{(col) => <th style="position:sticky;top:0;background:var(--bg)">{col}</th>}</For></tr></thead>
                        <tbody>
                            <For each={(props.data?.rows || [])}>{(row) => (
                                <tr><For each={(props.data?.columns || [])}>{(col) => <td style="font-family:var(--font-mono);font-size:11px">{row[col] != null ? String(row[col]) : <span style="color:var(--text-tertiary);font-style:italic">NULL</span>}</td>}</For></tr>
                            )}</For>
                        </tbody>
                    </table>
                    <div style="padding:6px 0;font-size:10px;color:var(--text-tertiary)">{(props.data?.rows || []).length} rows</div>
                </Show>
            </div>
        </div>
    );
}

function DatabasePanels(props) {
    const [historyEntries, setHistoryEntries] = createSignal([]);
    const [historyLoading, setHistoryLoading] = createSignal(false);
    const [statsData, setStatsData] = createSignal(null);
    const [statsLoading, setStatsLoading] = createSignal(false);
    const [infoSchemaView, setInfoSchemaView] = createSignal(null);
    const [infoSchemaData, setInfoSchemaData] = createSignal(null);
    const [infoSchemaLoading, setInfoSchemaLoading] = createSignal(false);

    const currentMode = () => props.modeSignal?.();
    const canShowHistory = () => QUERY_HISTORY_SERVICES.has(props.serviceId);

    const loadHistory = async () => {
        if (!canShowHistory()) return;
        setHistoryLoading(true);
        try {
            const resp = await api.queryHistory(props.serviceId, 50, 0);
            setHistoryEntries(resp.entries || []);
        } catch {
            setHistoryEntries([]);
        } finally {
            setHistoryLoading(false);
        }
    };

    const loadStats = async () => {
        setStatsLoading(true);
        setStatsData(null);
        try {
            if (props.serviceId === 'spanner') {
                const result = await api.spannerStats('local-instance', 'local-database');
                setStatsData(result);
            } else {
                const result = await api.schema(props.serviceId);
                const tables = result?.tables || [];
                setStatsData({
                    totalObjects: tables.length,
                    tableCount: tables.length,
                    columnCount: tables.reduce((sum, t) => sum + (t.columns?.length || 0), 0),
                    rowCount: 0,
                    details: tables.map(t => ({ type: 'TABLE', name: t.name, columnCount: t.columns?.length || 0 })),
                });
            }
        } catch {
            setStatsData(null);
        } finally {
            setStatsLoading(false);
        }
    };

    const canShowInfoSchema = () => SERVICES_WITH_INFO_SCHEMA.has(props.serviceId);

    const loadInfoSchema = async (viewType) => {
        const loader = INFO_SCHEMA_LOADERS[props.serviceId];
        if (!loader) return;
        setInfoSchemaLoading(true);
        try {
            const data = await loader.load(viewType);
            setInfoSchemaData(data);
        } catch (e) {
            setInfoSchemaData({ columns: ['error'], rows: [{ error: e.message || 'Failed to load' }] });
        } finally {
            setInfoSchemaLoading(false);
        }
    };

    createEffect(() => {
        const m = currentMode();
        if (m === 'db-history') loadHistory();
        if (m === 'db-stats') {
            loadStats();
            if (canShowInfoSchema()) {
                const loader = INFO_SCHEMA_LOADERS[props.serviceId];
                const defaultView = loader?.views?.[0] || 'tables';
                setInfoSchemaView(defaultView);
                loadInfoSchema(defaultView);
            }
        }
    });

    if (currentMode() === 'db-history') {
        return (
            <div>
                <h3 style={{ margin: '0 0 16px', "font-size": '16px', "font-weight": '600' }}>Query History</h3>
                <Show when={!canShowHistory()}>
                    <div class="empty-state">
                        <div class="empty-state-title">History not available</div>
                        <div class="empty-state-text">Query history is not available for {props.serviceId}.</div>
                    </div>
                </Show>
                <Show when={canShowHistory()}>
                    <Show when={historyLoading()}>
                        <div class="loading-state"><div class="loading-spinner" /> Loading…</div>
                    </Show>
                    <Show when={!historyLoading()}>
                        <Show when={historyEntries().length === 0}>
                            <div class="empty-state">
                                <div class="empty-state-title">No query history yet</div>
                                <div class="empty-state-text">Run a SQL query to see it here.</div>
                            </div>
                        </Show>
                        <Show when={historyEntries().length > 0}>
                            <div class="data-table-wrapper">
                                <table class="data-table" style={{ "font-size": '13px' }}>
                                    <thead>
                                        <tr>
                                            <th>Time</th>
                                            <th>SQL</th>
                                            <th>Duration</th>
                                            <th>Rows</th>
                                            <th>Status</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        <For each={historyEntries()}>
                                            {(entry) => (
                                                <tr style={{ "border-bottom": '1px solid var(--border)' }}>
                                                    <td style={{ "white-space": 'nowrap', "color": 'var(--text-secondary)', "font-size": '12px' }}>
                                                        {entry.executed_at ? entry.executed_at.replace('T', ' ').substring(0, 19) : '--'}
                                                    </td>
                                                    <td style={{ "max-width": '400px', "overflow": 'hidden', "text-overflow": 'ellipsis', "white-space": 'nowrap', "font-family": 'monospace', "font-size": '12px' }}>
                                                        {entry.sql}
                                                    </td>
                                                    <td style={{ "font-family": 'monospace', "font-size": '12px' }}>
                                                        {entry.duration_ms > 1000 ? (entry.duration_ms / 1000).toFixed(1) + 's' : entry.duration_ms + 'ms'}
                                                    </td>
                                                    <td style={{ "font-family": 'monospace', "font-size": '12px' }}>
                                                        {entry.row_count}
                                                    </td>
                                                    <td>
                                                        <span style={{
                                                            display: 'inline-block',
                                                            padding: '2px 8px',
                                                            borderRadius: '10px',
                                                            "font-size": '11px',
                                                            "font-weight": '600',
                                                            background: entry.success ? 'rgba(52,199,89,0.15)' : 'rgba(255,69,58,0.15)',
                                                            color: entry.success ? '#34C759' : '#FF453A',
                                                        }}>
                                                            {entry.success ? 'OK' : 'FAIL'}
                                                        </span>
                                                    </td>
                                                </tr>
                                            )}
                                        </For>
                                    </tbody>
                                </table>
                            </div>
                        </Show>
                    </Show>
                </Show>
            </div>
        );
    }

    if (currentMode() === 'db-stats') {
        return (
            <div>
                <h3 style={{ margin: '0 0 16px', "font-size": '16px', "font-weight": '600' }}>Database Statistics</h3>
                <Show when={statsLoading()}>
                    <div class="loading-state"><div class="loading-spinner" /> Loading…</div>
                </Show>
                <Show when={!statsLoading()}>
                    <Show when={!statsData()}>
                        <div class="empty-state">
                            <div class="empty-state-title">No statistics available</div>
                            <div class="empty-state-text">Select a database to view statistics.</div>
                        </div>
                    </Show>
                    <Show when={statsData()}>
                        <div style={{ display: 'grid', "grid-template-columns": 'repeat(auto-fill, minmax(160px, 1fr))', gap: '12px', "margin-bottom": '24px' }}>
                            <div style={{ display: 'flex', "flex-direction": 'column', align: 'center', gap: '4px', padding: '16px', border: '1px solid var(--border)', "border-radius": '8px', background: 'var(--surface)' }}>
                                <span style={{ "font-size": '28px', "font-weight": '700', color: 'var(--accent, #4285f4)' }}>{statsData().tableCount}</span>
                                <span style={{ "font-size": '12px', color: 'var(--text-secondary)' }}>Tables</span>
                            </div>
                            <div style={{ display: 'flex', "flex-direction": 'column', align: 'center', gap: '4px', padding: '16px', border: '1px solid var(--border)', "border-radius": '8px', background: 'var(--surface)' }}>
                                <span style={{ "font-size": '28px', "font-weight": '700', color: 'var(--text)' }}>{statsData().columnCount || 0}</span>
                                <span style={{ "font-size": '12px', color: 'var(--text-secondary)' }}>Columns</span>
                            </div>
                            <Show when={statsData().indexCount != null}>
                                <div style={{ display: 'flex', "flex-direction": 'column', align: 'center', gap: '4px', padding: '16px', border: '1px solid var(--border)', "border-radius": '8px', background: 'var(--surface)' }}>
                                    <span style={{ "font-size": '28px', "font-weight": '700', color: 'var(--text)' }}>{statsData().indexCount}</span>
                                    <span style={{ "font-size": '12px', color: 'var(--text-secondary)' }}>Indexes</span>
                                </div>
                            </Show>
                            <div style={{ display: 'flex', "flex-direction": 'column', align: 'center', gap: '4px', padding: '16px', border: '1px solid var(--border)', "border-radius": '8px', background: 'var(--surface)' }}>
                                <span style={{ "font-size": '28px', "font-weight": '700', color: 'var(--text)' }}>{statsData().totalObjects}</span>
                                <span style={{ "font-size": '12px', color: 'var(--text-secondary)' }}>Total Objects</span>
                            </div>
                        </div>
                        <Show when={statsData().details && statsData().details.length > 0}>
                            <h4 style={{ "font-size": '14px', margin: '0 0 8px 0', color: 'var(--text-secondary)' }}>Objects</h4>
                            <div class="data-table-wrapper">
                                <table class="data-table" style={{ "font-size": '13px' }}>
                                    <thead>
                                        <tr>
                                            <th>Type</th>
                                            <th>Name</th>
                                            <th style={{ "text-align": 'right' }}>Columns</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        <For each={statsData().details}>
                                            {(item) => (
                                                <tr>
                                                    <td>
                                                        <span style={{
                                                            display: 'inline-block',
                                                            padding: '2px 8px',
                                                            borderRadius: '10px',
                                                            "font-size": '11px',
                                                            "font-weight": '600',
                                                            background: 'rgba(66,133,244,0.15)',
                                                            color: '#4285f4',
                                                        }}>
                                                            {item.type === 'TABLE' ? 'TABLE' : item.type === 'SEARCH_INDEX' ? 'SEARCH' : item.type === 'VECTOR_INDEX' ? 'VECTOR' : item.type}
                                                        </span>
                                                    </td>
                                                    <td style={{ "font-weight": '500', "font-family": 'monospace', "font-size": '12px' }}>{item.name}</td>
                                                    <td style={{ "text-align": 'right' }}>{item.columnCount != null ? item.columnCount : '-'}</td>
                                                </tr>
                                            )}
                                        </For>
                                    </tbody>
                                </table>
                            </div>
                        </Show>
                    </Show>
                    <Show when={canShowInfoSchema()}>
                        <InfoSchemaPanel
                            views={INFO_SCHEMA_LOADERS[props.serviceId]?.views || []}
                            view={infoSchemaView()}
                            onSelectView={(v) => { setInfoSchemaView(v); loadInfoSchema(v); }}
                            loading={infoSchemaLoading()}
                            data={infoSchemaData()}
                        />
                    </Show>
                </Show>
            </div>
        );
    }

    return null;
}

// ─── Main Component ────────────────────────────────────────────────────
export default function ServiceExplorer(props) {
    const [mode, setMode] = createSignal(props.activeView?.() || 'explorer');
    const [refreshTrigger, setRefreshTrigger] = createSignal(0);
    const [resetTrigger, setResetTrigger] = createSignal(0);
    let lastSyncedView = props.activeView?.();

    createEffect(() => {
        const view = props.activeView?.();
        if (view && view !== lastSyncedView && (view === 'explorer' || view === 'editor')) {
            lastSyncedView = view;
            setMode(view);
        }
    });

    const switchPrimaryMode = (nextMode) => {
        if (!['explorer', 'editor', 'db-history', 'db-stats', 'settings', 'guide'].includes(nextMode)) return;
        lastSyncedView = nextMode;
        props.onViewChange?.(nextMode);
        setMode(nextMode);
        // Reset scroll position so user lands at top of new tab content
        document.getElementById('main-content')?.scrollTo({ top: 0, behavior: 'instant' });
    };

    const showSettings = () => setMode('settings');

    const activeService = () => props.selectedService?.() || 'gcs';
    const meta = () => SERVICE_META[activeService()] || { label: activeService(), description: '' };

    const handleRefresh = () => setRefreshTrigger(prev => prev + 1);

    const triggerResetAndRefresh = () => {
        setResetTrigger(prev => prev + 1);
        setRefreshTrigger(prev => prev + 1);
    };

    const isWorkflows = () => activeService() === 'workflows';

    return (
        <div class="se-root">
            {/* Service Header — icon, title, description */}
            <div class="se-service-header">
                <img
	                    src={`/icons/${activeService()}.svg`}
	                    alt=""
                        width="36"
                        height="36"
	                    class="se-service-icon"
                />
                <div class="se-service-info">
                    <div class="se-service-title-row">
                        <h1 class="se-service-title">{meta().label}</h1>
                        <Show when={meta().tag}><span class="badge badge-coming-up">{meta().tag}</span></Show>
                    </div>
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
                        <Show when={!NON_SQL_SERVICES.has(activeService())}>
                        <button
                            class={`se-mode-tab ${mode() === 'editor' ? 'active' : ''}`}
                            onClick={() => switchPrimaryMode('editor')}
                        >
                            <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false">
                                <path d="M9.4 16.6L4.8 12l4.6-4.6L8 6l-6 6 6 6 1.4-1.4zm5.2 0l4.6-4.6-4.6-4.6L16 6l6 6-6 6-1.4-1.4z"/>
                            </svg>
                            SQL Editor
                        </button>
                        </Show>
                        <button
                            class={`se-mode-tab ${mode() === 'explorer' ? 'active' : ''}`}
                            onClick={() => switchPrimaryMode('explorer')}
                        >
                            <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false">
                                <path d="M20 6H12L10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm0 12H4V8h16v10z"/>
                            </svg>
                            Data Explorer
                        </button>
                        <Show when={activeService() === 'secretmanager'}>
                            <button
                                class={`se-mode-tab ${mode() === 'stats' ? 'active' : ''}`}
                                onClick={() => setMode('stats')}
                            >
                                <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false">
                                    <path d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zM9 17H7v-7h2v7zm4 0h-2V7h2v10zm4 0h-2v-4h2v4z"/>
                                </svg>
                                Stats
                            </button>
                        </Show>
                        <Show when={activeService() === 'cloudfunctions'}>
                            <button
                                class={`se-mode-tab ${mode() === 'trigger' ? 'active' : ''}`}
                                onClick={() => setMode('trigger')}
                            >
                                <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false">
                                    <path d="M13 3c-4.97 0-9 4.03-9 9s4.03 9 9 9c.83 0 1.5-.67 1.5-1.5 0-.39-.15-.74-.39-1.01-.23-.26-.38-.61-.38-.99 0-.83.67-1.5 1.5-1.5H16c2.76 0 5-2.24 5-5 0-4.42-4.03-8-9-8z"/>
                                </svg>
                                Trigger Test
                            </button>
                        </Show>
                        <Show when={activeService() === 'dataproc'}>
                            <button
                                class={`se-mode-tab ${mode() === 'jobs' ? 'active' : ''}`}
                                onClick={() => setMode('jobs')}
                            >
                                <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false">
                                    <path d="M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z"/>
                                </svg>
                                Job Output
                            </button>
                        </Show>
                        <Show when={activeService() === 'cloudscheduler'}>
                            <button
                                class={`se-mode-tab ${mode() === 'history' ? 'active' : ''}`}
                                onClick={() => setMode('history')}
                            >
                                <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false">
                                    <path d="M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 3.15.75-1.23-4.5-2.67z"/>
                                </svg>
                                Job History
                            </button>
                        </Show>
                        <Show when={activeService() === 'alloydb'}>
                            <button
                                class={`se-mode-tab ${mode() === 'connection' ? 'active' : ''}`}
                                onClick={() => setMode('connection')}
                            >
                                <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false">
                                    <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z"/>
                                </svg>
                                Connection
                            </button>
                        </Show>
                        <Show when={SQL_SERVICES.some(s => s.id === activeService())}>
                            <button
                                class={`se-mode-tab ${mode() === 'db-history' ? 'active' : ''}`}
                                onClick={() => switchPrimaryMode('db-history')}
                            >
                                <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false">
                                    <path d="M13 3c-4.97 0-9 4.03-9 9s4.03 9 9 9c.83 0 1.5-.67 1.5-1.5 0-.39-.15-.74-.39-1.01-.23-.26-.38-.61-.38-.99 0-.83.67-1.5 1.5-1.5H16c2.76 0 5-2.24 5-5 0-4.42-4.03-8-9-8zm-5.5 9c-.83 0-1.5-.67-1.5-1.5S6.67 9 7.5 9 9 9.67 9 10.5 8.33 12 7.5 12zm3-4C8.67 8 8 7.33 8 6.5S8.67 5 10.5 5s2.5.67 2.5 1.5S11.33 8 10.5 8zm5 0c-.83 0-1.5-.67-1.5-1.5S14.67 5 15.5 5s2.5.67 2.5 1.5S16.33 8 15.5 8zm3 4c-.83 0-1.5-.67-1.5-1.5S17.67 9 18.5 9s2.5.67 2.5 1.5-.67 1.5-1.5 1.5z"/>
                                </svg>
                                History
                            </button>
                            <button
                                class={`se-mode-tab ${mode() === 'db-stats' ? 'active' : ''}`}
                                onClick={() => switchPrimaryMode('db-stats')}
                            >
                                <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false">
                                    <path d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zM9 17H7v-7h2v7zm4 0h-2V7h2v10zm4 0h-2v-4h2v4z"/>
                                </svg>
                                Stats
                            </button>
                        </Show>
                        <button
                            class={`se-mode-tab ${mode() === 'settings' ? 'active' : ''}`}
                            onClick={showSettings}
                        >
                            <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false">
                                <path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.49.49 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.484.484 0 0 0-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96a.49.49 0 0 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/>
                            </svg>
                            Settings
                        </button>
                        <button
                            class={`se-mode-tab ${mode() === 'sync' ? 'active' : ''}`}
                            onClick={() => setMode('sync')}
                        >
                            <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false">
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
                        <button
                            class={`se-mode-tab ${mode() === 'guide' ? 'active' : ''}`}
                            onClick={() => setMode('guide')}
                        >
                            <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false">
                                <path d="M19 2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h4l3 3 3-3h4a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2zm-5 12h-4v-2h4v2zm0-4h-4V8h4v2z"/>
                            </svg>
                            User Guide
                        </button>
                    </div>
                </div>

                <div style={{ display: mode() === 'editor' ? 'flex' : 'none', flex: '1', "min-height": '0', "flex-direction": 'column' }}>
                    <SQLEditor serviceId={activeService()} refreshTrigger={refreshTrigger} />
                </div>

                <Show when={mode() === 'explorer'}>
                    <div style={{ display: 'flex', flex: '1', "min-height": '0', "flex-direction": 'column' }}>
                        <DataBrowser
                            selectedService={props.selectedService}
                            onTabChange={props.onTabChange}
                            activeProject={props.activeProject}
                            projectRegion={props.projectRegion}
                            refreshTrigger={refreshTrigger}
                            subpath={props.subpath}
                            onSubpathChange={props.onSubpathChange}
                        />
                    </div>
                </Show>

                <Show when={mode() === 'sync'}>
                    <div style={{ display: 'flex', flex: '1', "min-height": '0', "flex-direction": 'column' }}>
                        <RemoteSyncPanel serviceId={activeService()} activeProject={props.activeProject} />
                    </div>
                </Show>

                <Show when={activeService() === 'cloudfunctions' && mode() === 'trigger'}>
                    <div style={{ display: 'flex', flex: '1', "min-height": '0', "flex-direction": 'column', padding: '16px' }}>
                        <TriggerTestPanel />
                    </div>
                </Show>

                <Show when={activeService() === 'dataproc' && mode() === 'jobs'}>
                    <div style={{ display: 'flex', flex: '1', "min-height": '0', "flex-direction": 'column', padding: '16px' }}>
                        <JobOutputPanel outputPath={props.subpath?.[1]} />
                    </div>
                </Show>

                <Show when={activeService() === 'cloudscheduler' && mode() === 'history'}>
                    <div style={{ display: 'flex', flex: '1', "min-height": '0', "flex-direction": 'column', padding: '16px' }}>
                        <SchedulerHistoryPanel jobName={props.subpath?.[1] ? `projects/local-project/locations/us-central1/jobs/${props.subpath[1]}` : null} />
                    </div>
                </Show>

                <Show when={activeService() === 'alloydb' && mode() === 'connection'}>
                    <div style={{ display: 'flex', flex: '1', "min-height": '0', "flex-direction": 'column', padding: '16px' }}>
                        <ConnectionInfoPanel project={props.activeProject} />
                    </div>
                </Show>

                <Show when={(mode() === 'db-history' || mode() === 'db-stats') && SQL_SERVICES.some(s => s.id === activeService())}>
                    <div style={{ display: 'flex', flex: '1', "min-height": '0', "flex-direction": 'column', padding: '16px' }}>
                        <DatabasePanels serviceId={activeService()} modeSignal={mode} />
                    </div>
                </Show>
                <Show when={activeService() === 'secretmanager' && mode() === 'stats'}>
                    <div style={{ display: 'flex', flex: '1', "min-height": '0', "flex-direction": 'column', padding: '16px' }}>
                        <SecretManagerStats activeProject={props.activeProject} />
                    </div>
                </Show>

                <Show when={mode() === 'settings'}>
                    <div style={{ display: 'flex', flex: '1', "min-height": '0', "flex-direction": 'column', padding: '24px', "overflow-y": 'auto' }}>
                        <ServiceSettings
                            serviceId={activeService()}
                            serviceLabel={meta().label}
                            onReset={triggerResetAndRefresh}
                        />
                    </div>
                </Show>

                <Show when={mode() === 'guide'}>
                    <div style={{ display: 'flex', flex: '1', "min-height": '0', "flex-direction": 'column', padding: '24px', "overflow-y": 'auto' }}>
                        <ServiceUserGuide serviceId={activeService()} projectId={props.activeProject} serviceLabel={meta().label} />
                    </div>
                </Show>
            </Show>
        </div>
    );
}

// ─── Service User Guide Component ────────────────────────────────────
function ServiceUserGuide(props) {
    const config = () => SERVICE_CONFIG[props.serviceId] || {};
    const sampleCode = () => SAMPLE_CODE[props.serviceId];
    const cliCommands = () => CLI_COMMANDS[props.serviceId];
    const [copiedVar, setCopiedVar] = createSignal(false);
    const [copiedSdk, setCopiedSdk] = createSignal(false);
    const [copiedCli, setCopiedCli] = createSignal(false);
    const [activeSdk, setActiveSdk] = createUrlBackedTab('sdk', ['python', 'nodejs', 'go', 'java', 'gcloud'], 'python', { history: 'replace' });
    const projectId = () => typeof props.projectId === 'function' ? props.projectId() : props.projectId || 'local-project';

    const envVar = () => config().envVar || '';
    const envValue = () => config().envValue || '';

    const handleCopyEnv = async () => {
        const cmd = `export ${envVar()}="${envValue()}"`;
        try {
            await navigator.clipboard.writeText(cmd);
            setCopiedVar(true);
            setTimeout(() => setCopiedVar(false), 2000);
        } catch {}
    };

    const sdkTabs = () => {
        if (!sampleCode()) return [];
        const keys = Object.keys(sampleCode());
        return keys.map(k => ({ id: k, label: k === 'nodejs' ? 'Node.js' : k === 'gcloud' ? 'gcloud CLI' : k.charAt(0).toUpperCase() + k.slice(1) }));
    };

    return (
        <div style={{ "max-width": "780px" }}>
            <h2 style={{ "font-size": "18px", "font-weight": "600", "margin-bottom": "4px" }}>User Guide</h2>
            <p style={{ "font-size": "13px", color: "var(--text-secondary)", "margin-bottom": "24px" }}>
                Configure your environment and use the SDK or CLI to connect to this service.
            </p>

            {/* Environment Variables */}
            <Show when={envVar()}>
                <div style={{ "margin-bottom": "28px" }}>
                    <h3 style={{ "font-size": "14px", "font-weight": "500", "margin-bottom": "10px" }}>Environment Variable</h3>
                    <p style={{ "font-size": "13px", color: "var(--text-secondary)", "margin-bottom": "10px", "line-height": "1.5" }}>
                        Set this environment variable to route {props.serviceLabel} SDK calls to LocalCloud:
                    </p>
                    <div style={{ display: "flex", gap: "8px", "align-items": "center" }}>
                        <code style={{
                            flex: "1",
                            padding: "10px 14px",
                            "border-radius": "8px",
                            background: "var(--surface-variant)",
                            "font-family": "var(--font-mono)",
                            "font-size": "13px",
                            border: "1px solid var(--border)",
                            color: "var(--text)",
                        }}>
                            export {envVar()}="{envValue()}"
                        </code>
                        <button
                            class="btn btn-secondary"
                            onClick={handleCopyEnv}
                            style={{ height: "38px", "font-size": "12px", padding: "0 14px", "flex-shrink": "0" }}
                        >
                            {copiedVar() ? 'Copied!' : 'Copy'}
                        </button>
                    </div>
                    <p style={{ "font-size": "11px", color: "var(--text-tertiary)", "margin-top": "8px" }}>
                        Or auto-configure all services: <code style={{ "font-size": "11px" }}>eval "$(curl -s http://localhost:8080/env?format=shell)"</code>
                    </p>
                </div>
            </Show>

            {/* SDK Setup */}
            <Show when={sampleCode()}>
                <div style={{ "margin-bottom": "28px" }}>
                    <h3 style={{ "font-size": "14px", "font-weight": "500", "margin-bottom": "10px" }}>SDK Setup</h3>
                    <p style={{ "font-size": "13px", color: "var(--text-secondary)", "margin-bottom": "12px", "line-height": "1.5" }}>
                        Initialise the Google Cloud SDK for this service — no code changes needed.
                        The SDKs detect the emulator host from the environment variable.
                    </p>

                    {/* SDK language tabs */}
                    <div style={{ display: "flex", gap: "4px", "margin-bottom": "10px" }}>
                        <For each={sdkTabs()}>
                            {(tab) => (
                                <button
                                    classList={{ "env-sample-tab": true, "active": activeSdk() === tab.id }}
                                    onClick={() => setActiveSdk(tab.id)}
                                    style={{ "font-size": "11px", padding: "4px 10px" }}
                                >
                                    {tab.label}
                                </button>
                            )}
                        </For>
                    </div>

                    <div style={{ position: "relative" }}>
                        <button
                            class="code-block-copy-btn"
                            onClick={async () => {
                                const code = sampleCode()?.[activeSdk()] || '';
                                try { await navigator.clipboard.writeText(code); setCopiedSdk(true); setTimeout(() => setCopiedSdk(false), 2000); } catch {}
                            }}
                            title="Copy to clipboard"
                            aria-label="Copy SDK code"
                        >
                            <Show when={copiedSdk()} fallback={
                                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                            }>
                                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg>
                            </Show>
                        </button>
                        <div style={{
                            padding: "14px 16px",
                            "border-radius": "8px",
                            background: "var(--surface-variant)",
                            border: "1px solid var(--border)",
                            "font-family": "var(--font-mono)",
                            "font-size": "12px",
                            "line-height": "1.6",
                            overflow: "auto",
                            "max-height": "400px",
                            "white-space": "pre",
                            "tab-size": "2",
                            color: "var(--text)",
                        }}>
                            {sampleCode()?.[activeSdk()] || 'No sample available for this language.'}
                        </div>
                    </div>
                </div>
            </Show>

            {/* CLI Commands */}
            <Show when={cliCommands()}>
                <div style={{ "margin-bottom": "28px" }}>
                    <h3 style={{ "font-size": "14px", "font-weight": "500", "margin-bottom": "10px" }}>CLI Quick Reference</h3>
                    <p style={{ "font-size": "13px", color: "var(--text-secondary)", "margin-bottom": "12px", "line-height": "1.5" }}>
                        Common CLI commands for {cliCommands().label}:
                    </p>
                    <div style={{ position: "relative" }}>
                        <button
                            class="code-block-copy-btn"
                            onClick={async () => {
                                const code = cliCommands()?.commands || '';
                                try { await navigator.clipboard.writeText(code); setCopiedCli(true); setTimeout(() => setCopiedCli(false), 2000); } catch {}
                            }}
                            title="Copy to clipboard"
                            aria-label="Copy CLI commands"
                        >
                            <Show when={copiedCli()} fallback={
                                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                            }>
                                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg>
                            </Show>
                        </button>
                        <div style={{
                            padding: "14px 16px",
                            "border-radius": "8px",
                            background: "var(--surface-variant)",
                            border: "1px solid var(--border)",
                            "font-family": "var(--font-mono)",
                            "font-size": "12px",
                            "line-height": "1.6",
                            overflow: "auto",
                            "max-height": "400px",
                            "white-space": "pre",
                            "tab-size": "2",
                            color: "var(--text)",
                        }}>
                            {cliCommands().commands}
                        </div>
                    </div>
                </div>
            </Show>

            {/* No env var / no sample — show generic help */}
            <Show when={!envVar() && !sampleCode() && !cliCommands()}>
                <div style={{
                    padding: "32px",
                    "text-align": "center",
                    color: "var(--text-secondary)",
                    "font-size": "13px",
                    background: "var(--surface-variant)",
                    "border-radius": "12px",
                    border: "1px solid var(--border)",
                }}>
                    <p>No specific guide available for this service yet.</p>
                    <p style={{ "margin-top": "8px", "font-size": "12px" }}>Visit <strong>Setup & SDKs</strong> for general configuration help.</p>
                </div>
            </Show>
        </div>
    );
}

// ─── Service Configuration Data ─────────────────────────────────────────
// Derived from services.yaml — used by the Settings tab to show read-only config
const SERVICE_CONFIG = {
    gcs:           { envVar: 'STORAGE_EMULATOR_HOST', envValue: 'http://localhost:4443', port: 4443, protocol: 'REST', type: 'External emulator', tier: 'Community', endpoint: 'http://localhost:4443', gcloudApi: 'storage', terraformVar: 'GOOGLE_STORAGE_CUSTOM_ENDPOINT', docPath: 'storage', sdkExample: `from google.cloud import storage\nclient = storage.Client()\nbucket = client.bucket("my-bucket")` },
    pubsub:        { envVar: 'PUBSUB_EMULATOR_HOST', envValue: 'localhost:8085', port: 8085, protocol: 'gRPC', type: 'External emulator', tier: 'Community', endpoint: 'localhost:8085', gcloudApi: 'pubsub', terraformVar: 'GOOGLE_PUBSUB_CUSTOM_ENDPOINT', docPath: 'pubsub', sdkExample: `from google.cloud import pubsub_v1\npublisher = pubsub_v1.PublisherClient()\ntopic = publisher.topic_path("project", "my-topic")` },
    firestore:     { envVar: 'FIRESTORE_EMULATOR_HOST', envValue: 'localhost:8086', port: 8086, protocol: 'gRPC', type: 'External emulator', tier: 'Community', endpoint: 'localhost:8086', gcloudApi: 'firestore', terraformVar: 'GOOGLE_FIRESTORE_CUSTOM_ENDPOINT', docPath: 'firestore', sdkExample: `from google.cloud import firestore\ndb = firestore.Client()\ndoc = db.collection("users").document("alice")` },
    bigquery:      { envVar: 'BIGQUERY_EMULATOR_HOST', envValue: 'http://localhost:9050', port: 9050, protocol: 'REST', type: 'External emulator', tier: 'Community', endpoint: 'http://localhost:9050', gcloudApi: 'bigquery', terraformVar: 'GOOGLE_BIGQUERY_CUSTOM_ENDPOINT', docPath: 'bigquery', sdkExample: `from google.cloud import bigquery\nclient = bigquery.Client()\nrows = client.query("SELECT 1").result()` },
    bigtable:      { envVar: 'BIGTABLE_EMULATOR_HOST', envValue: 'localhost:8087', port: 8087, protocol: 'gRPC', type: 'External emulator', tier: 'Pro', endpoint: 'localhost:8087', gcloudApi: null, terraformVar: 'GOOGLE_BIGTABLE_CUSTOM_ENDPOINT', docPath: 'bigtable', sdkExample: `from google.cloud import bigtable\nclient = bigtable.Client(project="project", admin=True)\ninstance = client.instance("my-instance")` },
    spanner:       { envVar: 'SPANNER_EMULATOR_HOST', envValue: 'localhost:9010', port: 9010, protocol: 'gRPC', type: 'External emulator', tier: 'Pro', endpoint: 'localhost:9010', gcloudApi: 'spanner', terraformVar: 'GOOGLE_SPANNER_CUSTOM_ENDPOINT', docPath: 'spanner', sdkExample: `from google.cloud import spanner\nclient = spanner.Client()\ninstance = client.instance("my-instance")` },
    memorystore:   { envVar: 'REDIS_HOST', envValue: 'localhost:6379', port: 6379, protocol: 'Redis', type: 'External emulator', tier: 'Community', endpoint: 'localhost:6379', gcloudApi: null, terraformVar: 'GOOGLE_REDIS_CUSTOM_ENDPOINT', docPath: 'memorystore', sdkExample: `import redis\nr = redis.Redis(host="localhost", port=6379)\nr.set("key", "value")` },
    secretmanager: { envVar: 'SECRET_MANAGER_EMULATOR_HOST', envValue: 'localhost:8080', port: 'gateway (8080)', protocol: 'gRPC', type: 'Facade (in-process)', tier: 'Community', endpoint: 'localhost:8080', gcloudApi: 'secretmanager', terraformVar: 'GOOGLE_SECRET_MANAGER_CUSTOM_ENDPOINT', docPath: 'secret-manager', sdkExample: `from google.cloud import secretmanager\nclient = secretmanager.SecretManagerServiceClient()\nsecret = client.create_secret(request={"parent": "projects/p", "secret_id": "my-secret"})` },
    cloudtasks:    { envVar: 'CLOUD_TASKS_EMULATOR_HOST', envValue: 'localhost:8080', port: 'gateway (8080)', protocol: 'gRPC', type: 'Facade (in-process)', tier: 'Community', endpoint: 'localhost:8080', gcloudApi: 'cloudtasks', terraformVar: 'GOOGLE_CLOUD_TASKS_CUSTOM_ENDPOINT', docPath: 'cloud-tasks', sdkExample: `from google.cloud import tasks_v2\nclient = tasks_v2.CloudTasksClient()\nqueue = client.create_queue(request={"parent": "projects/p/locations/us-central1", "queue": {}})` },
    cloudscheduler:{ envVar: 'CLOUD_SCHEDULER_EMULATOR_HOST', envValue: 'localhost:8080', port: 'gateway (8080)', protocol: 'gRPC', type: 'Facade (in-process)', tier: 'Community', endpoint: 'localhost:8080', gcloudApi: 'cloudscheduler', terraformVar: 'GOOGLE_CLOUD_SCHEDULER_CUSTOM_ENDPOINT', docPath: 'cloud-scheduler', sdkExample: `from google.cloud import scheduler_v1\nclient = scheduler_v1.CloudSchedulerClient()\njob = client.create_job(request={"parent": "projects/p/locations/us-central1", "job": {}})` },
    cloudfunctions:{ envVar: 'CLOUD_FUNCTIONS_EMULATOR_HOST', envValue: 'localhost:8080', port: 'gateway (8080)', protocol: 'gRPC', type: 'Facade (in-process)', tier: 'Community', endpoint: 'localhost:8080', gcloudApi: 'cloudfunctions', terraformVar: 'GOOGLE_CLOUD_FUNCTIONS_CUSTOM_ENDPOINT', docPath: 'cloud-functions', sdkExample: `gcloud functions deploy my-fn --runtime python311 --trigger-http` },
    alloydb:       { envVar: 'ALLOYDB_EMULATOR_HOST', envValue: 'localhost:8080', port: 'gateway (8080)', protocol: 'gRPC', type: 'Facade (in-process)', tier: 'Community', endpoint: 'localhost:8080', gcloudApi: 'alloydb', terraformVar: 'GOOGLE_ALLOYDB_CUSTOM_ENDPOINT', docPath: 'alloydb', sdkExample: `psql -h localhost -p 5432 -U postgres -d alloydb_<cluster_id>` },
    dataproc:      { envVar: 'DATAPROC_EMULATOR_HOST', envValue: 'localhost:8080', port: 'gateway (8080)', protocol: 'gRPC', type: 'Facade (in-process)', tier: 'Community', endpoint: 'localhost:8080', gcloudApi: 'dataproc', terraformVar: 'GOOGLE_DATAPROC_CUSTOM_ENDPOINT', docPath: 'dataproc', sdkExample: `gcloud dataproc clusters create my-cluster --region us-central1` },
    cloudiam:      { envVar: 'IAM_EMULATOR_HOST', envValue: 'localhost:8080', port: 'gateway (8080)', protocol: 'gRPC', type: 'Facade (in-process)', tier: 'Community', endpoint: 'localhost:8080', gcloudApi: 'iam', terraformVar: 'GOOGLE_IAM_CUSTOM_ENDPOINT', docPath: 'iam', sdkExample: `from google.cloud import iam\nclient = iam.IAMClient()` },
    kms:           { envVar: 'CLOUD_KMS_EMULATOR_HOST', envValue: 'http://localhost:8080', port: 'gateway (8080)', protocol: 'REST', type: 'Facade (in-process)', tier: 'Pro', endpoint: 'http://localhost:8080', gcloudApi: 'cloudkms', terraformVar: 'GOOGLE_KMS_CUSTOM_ENDPOINT', docPath: 'kms', sdkExample: `from google.cloud import kms\nclient = kms.KeyManagementServiceClient()` },
    logging:       { envVar: 'CLOUD_LOGGING_EMULATOR_HOST', envValue: 'localhost:8080', port: 'gateway (8080)', protocol: 'gRPC', type: 'Facade (in-process)', tier: 'Community', endpoint: 'localhost:8080', gcloudApi: 'logging', terraformVar: 'GOOGLE_LOGGING_CUSTOM_ENDPOINT', docPath: 'logging', sdkExample: `from google.cloud import logging_v2\nclient = logging_v2.LoggingServiceV2Client()` },
    monitoring:    { envVar: 'CLOUD_MONITORING_EMULATOR_HOST', envValue: 'localhost:8080', port: 'gateway (8080)', protocol: 'gRPC', type: 'Facade (in-process)', tier: 'Community', endpoint: 'localhost:8080', gcloudApi: 'monitoring', terraformVar: 'GOOGLE_MONITORING_CUSTOM_ENDPOINT', docPath: 'monitoring', sdkExample: `from google.cloud import monitoring_v3\nclient = monitoring_v3.MetricServiceClient()` },
    gke:           { envVar: 'GKE_EMULATOR_HOST', envValue: 'localhost:8080', port: 'gateway (8080)', protocol: 'gRPC', type: 'Facade (in-process)', tier: 'Pro', endpoint: 'localhost:8080', gcloudApi: 'container', terraformVar: 'GOOGLE_CONTAINER_CUSTOM_ENDPOINT', docPath: 'kubernetes-engine', sdkExample: `kubectl --kubeconfig=<(gcloud container clusters get-credentials my-cluster)` },
    compute:       { envVar: 'COMPUTE_EMULATOR_HOST', envValue: 'http://localhost:8080', port: 'gateway (8080)', protocol: 'REST', type: 'Facade (in-process)', tier: 'Pro', endpoint: 'http://localhost:8080', gcloudApi: 'compute', terraformVar: 'GOOGLE_COMPUTE_CUSTOM_ENDPOINT', docPath: 'compute', sdkExample: `from google.cloud import compute_v1\nclient = compute_v1.InstancesClient()` },
    cloudrun:      { envVar: 'CLOUD_RUN_EMULATOR_HOST', envValue: 'localhost:8080', port: 'gateway (8080)', protocol: 'gRPC', type: 'Facade (in-process)', tier: 'Pro', endpoint: 'localhost:8080', gcloudApi: 'run', terraformVar: 'GOOGLE_CLOUD_RUN_CUSTOM_ENDPOINT', docPath: 'cloud-run', sdkExample: `gcloud run deploy my-service --image gcr.io/project/image` },
    cloudsql:      { envVar: 'CLOUD_SQL_EMULATOR_HOST', envValue: 'http://localhost:8080', port: 'gateway (8080)', protocol: 'REST', type: 'Facade (in-process)', tier: 'Community', endpoint: 'http://localhost:8080', gcloudApi: 'sqladmin', terraformVar: 'GOOGLE_SQL_CUSTOM_ENDPOINT', docPath: 'sql', sdkExample: `psql -h localhost -p 5432 -U postgres -d <db_name>` },
    cloudbilling:  { envVar: 'CLOUD_BILLING_EMULATOR_HOST', envValue: 'http://localhost:8080', port: 'gateway (8080)', protocol: 'REST', type: 'Facade (in-process)', tier: 'Community', endpoint: 'http://localhost:8080', gcloudApi: 'cloudbilling', terraformVar: 'GOOGLE_CLOUD_BILLING_CUSTOM_ENDPOINT', docPath: 'billing', sdkExample: `from google.cloud import billing\nclient = billing.CloudBillingClient()` },
    serviceusage:  { envVar: 'SERVICE_USAGE_EMULATOR_HOST', envValue: 'http://localhost:8080', port: 'gateway (8080)', protocol: 'REST', type: 'Facade (in-process)', tier: 'Community', endpoint: 'http://localhost:8080', gcloudApi: null, terraformVar: 'GOOGLE_SERVICE_USAGE_CUSTOM_ENDPOINT', docPath: 'service-usage', sdkExample: `gcloud services enable compute.googleapis.com` },
    vertexai:      { envVar: 'AIPLATFORM_EMULATOR_HOST', envValue: 'http://localhost:8080', port: 'gateway (8080)', protocol: 'REST', type: 'Facade (in-process)', tier: 'Pro', endpoint: 'http://localhost:8080', gcloudApi: 'aiplatform', terraformVar: 'GOOGLE_VERTEX_AI_CUSTOM_ENDPOINT', docPath: 'vertex-ai', sdkExample: `from google.cloud import aiplatform\naiplatform.init(project="p", location="us-central1")` },
    workflows:     { envVar: 'WORKFLOWS_EMULATOR_HOST', envValue: 'localhost:8080', port: 'gateway (8080)', protocol: 'gRPC', type: 'Facade (in-process)', tier: 'Community', endpoint: 'localhost:8080', gcloudApi: 'workflows', terraformVar: 'GOOGLE_WORKFLOWS_CUSTOM_ENDPOINT', docPath: 'workflows', sdkExample: `gcloud workflows deploy my-wf --source=workflow.yaml` },
};

// ─── Service Settings Component ──────────────────────────────────────────
function ServiceSettings(props) {
    const config = () => SERVICE_CONFIG[props.serviceId] || null;
    const [resetting, setResetting] = createSignal(false);
    const [resetDone, setResetDone] = createSignal(false);
    const [resetError, setResetError] = createSignal(null);
    const [showConfirm, setShowConfirm] = createSignal(false);
    const [confirmText, setConfirmText] = createSignal('');
    const [copiedKey, setCopiedKey] = createSignal(null);

    const isFetchableService = () => !['gke', 'compute', 'cloudrun', 'cloudbilling', 'serviceusage'].includes(props.serviceId);
    const expectedText = () => `reset ${props.serviceId}`;

    const copyText = (text, key) => {
        navigator.clipboard.writeText(text).then(() => {
            setCopiedKey(key);
            setTimeout(() => setCopiedKey(null), 2000);
        }).catch(() => {});
    };

    const handleReset = async () => {
        const svc = props.serviceId;
        if (confirmText().toLowerCase() !== expectedText()) return;
        setShowConfirm(false);
        setConfirmText('');
        setResetting(true);
        setResetError(null);
        setResetDone(false);
        try {
            await api.resetService(svc, false);
            setResetDone(true);
            props.onReset?.();
            setTimeout(() => setResetDone(false), 5000);
        } catch (err) {
            setResetError(err.message || 'Reset failed');
        } finally {
            setResetting(false);
        }
    };

    const handleCloseConfirm = () => {
        setShowConfirm(false);
        setConfirmText('');
    };

    const navigateToSettingsPage = () => {
        history.pushState(null, '', '/settings');
        window.dispatchEvent(new PopStateEvent('popstate'));
    };

    const docUrl = () => config() ? `https://cloud.google.com/${config().docPath}/docs` : '#';
    const sdkUrl = () => config() ? `https://cloud.google.com/${config().docPath}/docs/reference/libraries` : '#';
    const terraformUrl = () => config() ? `https://registry.terraform.io/providers/hashicorp/google/latest/docs/resources/${config().docPath.replace(/-/g, '_')}` : '#';

    const Row = (props) => (
        <div style={{ display: 'flex', "justify-content": 'space-between', "align-items": 'center', padding: '10px 0', "border-bottom": '1px solid var(--border-subtle)' }}>
            <span style={{ "font-size": '12px', color: 'var(--text-secondary)' }}>{props.label}</span>
            <span style={{ "font-size": '13px', "font-weight": '500', color: 'var(--text-primary)', display: 'flex', "align-items": 'center', gap: '8px' }}>
                {props.value}
                {props.copy && (
                    <button
                        onClick={() => copyText(props.copy, props.copyKey)}
                        style={{
                            padding: '2px 6px',
                            border: '1px solid var(--border)',
                            "border-radius": '4px',
                            background: copiedKey() === props.copyKey ? 'var(--success, #16a34a)' : 'var(--bg)',
                            color: copiedKey() === props.copyKey ? '#fff' : 'var(--text-tertiary)',
                            cursor: 'pointer',
                            "font-size": '10px',
                            "line-height": '1.4',
                            transition: 'all 0.15s'
                        }}
                        title={`Copy ${props.copy}`}
                        aria-label={`Copy ${props.label}`}
                    >
                        {copiedKey() === props.copyKey ? 'Copied!' : 'Copy'}
                    </button>
                )}
            </span>
        </div>
    );

    const Card = (props) => (
        <div style={{
            border: '1px solid var(--border)',
            "border-radius": 'var(--radius-sm, 8px)',
            overflow: 'hidden',
            background: 'var(--surface)',
            "margin-bottom": '16px'
        }}>
            <div style={{
                padding: '10px 14px',
                background: 'var(--surface-variant)',
                "border-bottom": '1px solid var(--border)',
                display: 'flex',
                "align-items": 'center',
                gap: '8px'
            }}>
                {props.icon}
                <span style={{ "font-weight": '600', "font-size": '13px' }}>{props.title}</span>
            </div>
            <div style={{ padding: props.padding || '8px 18px' }}>
                {props.children}
            </div>
        </div>
    );

    return (
        <div style={{ "max-width": "680px" }}>
            {/* Connection Card */}
            <Show when={config()}>
                <Card
                    title="Connection"
                    icon={<svg width="14" height="14" viewBox="0 0 24 24" fill="var(--primary)" aria-hidden="true" focusable="false"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/></svg>}
                >
                    <Row label="Environment Variable" value={<code style={{ "font-size": '12px', "font-weight": '500' }}>{config().envVar}</code>} copy={`${config().envVar}=${config().envValue}`} copyKey="env" />
                    <Row label="Value" value={<code style={{ "font-size": '12px', "font-weight": '500' }}>{config().envValue}</code>} copy={config().envValue} copyKey="val" />
                    <Row label="Endpoint" value={<code style={{ "font-size": '12px', "font-weight": '500' }}>{config().endpoint}</code>} />
                    <div style={{
                        padding: '12px 0 4px',
                        display: 'flex',
                        gap: '8px',
                        "flex-wrap": 'wrap'
                    }}>
                        <button class="btn btn-secondary" style={{ "font-size": '11px', padding: '5px 12px' }} onClick={() => copyText(`${config().envVar}=${config().envValue}`, 'env')}>
                            {copiedKey() === 'env' ? '✓ Copied' : 'Copy env var'}
                        </button>
                        <button class="btn btn-secondary" style={{ "font-size": '11px', padding: '5px 12px' }} onClick={() => copyText(config().envValue, 'val')}>
                            {copiedKey() === 'val' ? '✓ Copied' : 'Copy endpoint'}
                        </button>
                    </div>
                </Card>
            </Show>

            {/* Configuration Card */}
            <Show when={config()}>
                <Card
                    title="Configuration"
                    icon={<svg width="14" height="14" viewBox="0 0 24 24" fill="var(--text-secondary)" aria-hidden="true" focusable="false"><path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.49.49 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.484.484 0 0 0-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96a.49.49 0 0 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/></svg>}
                >
                    <Row label="Protocol" value={<span class="badge badge-info" style={{ "font-size": '11px' }}>{config().protocol}</span>} />
                    <Row label="Port" value={<code style={{ "font-size": '12px' }}>{config().port}</code>} />
                    <Row label="Type" value={config().type} />
                    <Row label="Tier" value={<span class={`badge ${config().tier === 'Pro' ? 'badge-warning' : 'badge-info'}`} style={{ "font-size": '11px' }}>{config().tier}</span>} />
                    <Show when={config().gcloudApi}>
                        <Row label="gcloud API" value={<code style={{ "font-size": '11px' }}>{config().gcloudApi}</code>} />
                    </Show>
                    <Row label="Terraform" value={<code style={{ "font-size": '10px' }}>{config().terraformVar}</code>} copy={config().terraformVar} copyKey="tf" />
                </Card>
            </Show>

            {/* Resources Card */}
            <Show when={config()}>
                <Card
                    title="Resources & Guides"
                    icon={<svg width="14" height="14" viewBox="0 0 24 24" fill="var(--info)" aria-hidden="true" focusable="false"><path d="M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z"/></svg>}
                >
                    {/* LocalCloud Guides */}
                    <div style={{ display: 'flex', "flex-direction": 'column', gap: '4px', padding: '4px 0' }}>
                        {/* LocalCloud User Guide */}
                        <a
                            href="/settings"
                            onClick={(e) => { e.preventDefault(); navigateToSettingsPage(); }}
                            class="settings-resource-link"
                            style={{ display: 'flex', "align-items": 'center', gap: '8px', padding: '8px 10px', "border-radius": '6px', color: 'var(--text-secondary)', "text-decoration": 'none', "font-size": '13px', transition: 'background 0.15s, color 0.15s' }}
                        >
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="var(--primary)" aria-hidden="true" focusable="false"><path d="M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z"/></svg>
                            LocalCloud User Guide
                            <span style={{ "font-size": '9px', "font-weight": '600', color: 'var(--primary)', background: 'var(--primary-softer)', padding: '1px 6px', "border-radius": '3px', "margin-left": 'auto' }}>Get Started</span>
                        </a>
                        {/* LocalCloud Examples */}
                        <a
                            href={`/settings#${props.serviceId}`}
                            onClick={(e) => { e.preventDefault(); navigateToSettingsPage(); }}
                            class="settings-resource-link"
                            style={{ display: 'flex', "align-items": 'center', gap: '8px', padding: '8px 10px', "border-radius": '6px', color: 'var(--text-secondary)', "text-decoration": 'none', "font-size": '13px', transition: 'background 0.15s, color 0.15s' }}
                        >
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="var(--primary)" aria-hidden="true" focusable="false"><path d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-5 14H7v-2h7v2zm3-4H7v-2h10v2zm0-4H7V7h10v2z"/></svg>
                            Code Examples & CLI Commands
                            <span style={{ "font-size": '9px', "font-weight": '600', color: 'var(--text-tertiary)', background: 'var(--surface-variant)', padding: '1px 6px', "border-radius": '3px', "margin-left": 'auto' }}>SDK · CLI</span>
                        </a>
                    </div>

                    {/* Google Cloud Official Docs (separator) */}
                    <div style={{
                        margin: '8px 0',
                        display: 'flex',
                        "align-items": 'center',
                        gap: '8px',
                        padding: '0 10px'
                    }}>
                        <div style={{ flex: 1, height: '1px', background: 'var(--border-subtle)' }} />
                        <span style={{ "font-size": '9px', color: 'var(--text-tertiary)', "text-transform": 'uppercase', "letter-spacing": '0.5px', "font-weight": '600', "white-space": 'nowrap' }}>Google Cloud Docs</span>
                        <div style={{ flex: 1, height: '1px', background: 'var(--border-subtle)' }} />
                    </div>

                    <div style={{ display: 'flex', "flex-direction": 'column', gap: '4px', padding: '0 0 4px 0' }}>
                        <a
                            href={docUrl()}
                            target="_blank"
                            rel="noopener noreferrer"
                            class="settings-resource-link"
                            style={{ display: 'flex', "align-items": 'center', gap: '8px', padding: '8px 10px', "border-radius": '6px', color: 'var(--text-secondary)', "text-decoration": 'none', "font-size": '13px', transition: 'background 0.15s, color 0.15s' }}
                        >
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path d="M19 19H5V5h7V3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z"/></svg>
                            Google Cloud Documentation
                        </a>
                        <Show when={config().gcloudApi}>
                            <a
                                href={sdkUrl()}
                                target="_blank"
                                rel="noopener noreferrer"
                                class="settings-resource-link"
                                style={{ display: 'flex', "align-items": 'center', gap: '8px', padding: '8px 10px', "border-radius": '6px', color: 'var(--text-secondary)', "text-decoration": 'none', "font-size": '13px', transition: 'background 0.15s, color 0.15s' }}
                            >
                                <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path d="M9.4 16.6L4.8 12l4.6-4.6L8 6l-6 6 6 6 1.4-1.4zm5.2 0l4.6-4.6-4.6-4.6L16 6l6 6-6 6-1.4-1.4z"/></svg>
                                Client Library Reference
                            </a>
                        </Show>
                        <a
                            href={terraformUrl()}
                            target="_blank"
                            rel="noopener noreferrer"
                            class="settings-resource-link"
                            style={{ display: 'flex', "align-items": 'center', gap: '8px', padding: '8px 10px', "border-radius": '6px', color: 'var(--text-secondary)', "text-decoration": 'none', "font-size": '13px', transition: 'background 0.15s, color 0.15s' }}
                        >
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path d="M17.5 1.25a.5.5 0 0 1 1 0v2.5H21a.5.5 0 0 1 0 1h-2.5v2.5a.5.5 0 0 1-1 0v-2.5H15a.5.5 0 0 1 0-1h2.5v-2.5zm-11 4.5a1 1 0 0 1 1-1H11a.5.5 0 0 0 0-1H7.5a2 2 0 0 0-2 2v14a.5.5 0 0 0 .8.4l5.7-4.4 5.7 4.4a.5.5 0 0 0 .8-.4v-8.5a.5.5 0 0 0-1 0v7.48l-5.2-4a.5.5 0 0 0-.6 0l-5.2 4V5.75z"/></svg>
                            Terraform Registry
                        </a>
                    </div>
                    <Show when={config().sdkExample}>
                        <div style={{ "margin-top": '12px' }}>
                            <div style={{ display: 'flex', "align-items": 'center', gap: '6px', "margin-bottom": '6px' }}>
                                <span style={{ "font-size": '10px', "font-weight": '600', color: 'var(--text-tertiary)', "text-transform": 'uppercase', "letter-spacing": '0.5px' }}>Quick Example</span>
                                <button
                                    class="btn btn-secondary"
                                    onClick={() => copyText(config().sdkExample, 'sdk')}
                                    style={{ "font-size": '10px', padding: '2px 8px' }}
                                >
                                    {copiedKey() === 'sdk' ? 'Copied!' : 'Copy'}
                                </button>
                            </div>
                            <pre style={{
                                margin: 0,
                                padding: '10px 14px',
                                background: 'var(--bg-subtle)',
                                "border-radius": '6px',
                                "font-size": '11px',
                                "line-height": '1.6',
                                "font-family": 'var(--font-mono)',
                                color: 'var(--text-secondary)',
                                overflow: 'auto',
                                "max-height": '160px'
                            }}>{config().sdkExample}</pre>
                        </div>
                    </Show>
                </Card>
            </Show>

            {/* Danger Zone */}
            <div style={{
                border: '1px solid var(--danger-border, #fecaca)',
                "border-radius": 'var(--radius-sm, 8px)',
                overflow: 'hidden',
                background: 'var(--danger-bg, #fef2f2)'
            }}>
                <div style={{
                    padding: '10px 14px',
                    background: 'var(--danger-header-bg, #fee2e2)',
                    "border-bottom": '1px solid var(--danger-border, #fecaca)',
                    display: 'flex',
                    "align-items": 'center',
                    gap: '8px'
                }}>
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" style={{ color: 'var(--danger, #dc2626)' }} aria-hidden="true" focusable="false">
                        <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z"/>
                    </svg>
                    <span style={{ "font-weight": '600', "font-size": '13px', color: 'var(--danger, #dc2626)' }}>Danger Zone</span>
                </div>

                <div style={{ padding: '16px 20px' }}>
                    <Show when={isFetchableService()} fallback={
                        <div style={{ padding: '8px 0' }}>
                            <p style={{ margin: '0 0 4px', "font-size": '13px', color: 'var(--text-secondary)' }}>
                                This service does not have resettable local data.
                            </p>
                            <p style={{ margin: 0, "font-size": '11px', color: 'var(--text-tertiary)' }}>
                                {props.serviceLabel} is a connection-only facade — data lives in external resources.
                            </p>
                        </div>
                    }>
                        <div>
                            <div style={{ "margin-bottom": '12px' }}>
                                <p style={{ margin: '0 0 4px', "font-size": '13px', "font-weight": '600', color: 'var(--text-primary)' }}>
                                    Reset all {props.serviceLabel} data
                                </p>
                                <p style={{ margin: 0, "font-size": '12px', color: 'var(--text-secondary)', "line-height": '1.5' }}>
                                    This will permanently delete all data stored in the {props.serviceLabel} emulator. This action cannot be undone.
                                </p>
                            </div>

                            <Show when={resetDone()}>
                                <div style={{
                                    padding: '8px 12px',
                                    "margin-bottom": '12px',
                                    "border-radius": '6px',
                                    background: 'var(--success-bg, #ecfdf5)',
                                    color: 'var(--success, #059669)',
                                    "font-size": '12px',
                                    display: 'flex',
                                    "align-items": 'center',
                                    gap: '6px'
                                }}>
                                    <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></svg>
                                    All {props.serviceLabel} data has been reset successfully.
                                </div>
                            </Show>

                            <Show when={resetError()}>
                                <div style={{
                                    padding: '8px 12px',
                                    "margin-bottom": '12px',
                                    "border-radius": '6px',
                                    background: 'var(--danger-bg, #fef2f2)',
                                    color: 'var(--danger, #dc2626)',
                                    "font-size": '12px'
                                }}>
                                    Error: {resetError()}
                                </div>
                            </Show>

                            <button
                                class="btn btn-danger-filled"
                                onClick={() => setShowConfirm(true)}
                                disabled={resetting()}
                                style={{ "font-size": '12px', padding: '8px 18px', "border-radius": '6px' }}
                            >
                                <Show when={!resetting()} fallback={
                                    <><div class="loading-spinner" style={{ width: '12px', height: '12px', "border-width": '1.5px', "border-color": 'rgba(255,255,255,0.3)', "border-top-color": '#fff' }} /> Resetting…</>
                                }>
                                    Reset {props.serviceLabel}
                                </Show>
                            </button>
                        </div>
                    </Show>
                </div>
            </div>

            {/* Confirmation Modal */}
            <Show when={showConfirm()}>
                <div
                    style={{
                        position: 'fixed',
                        inset: 0,
                        background: 'rgba(0,0,0,0.45)',
                        display: 'flex',
                        "align-items": 'center',
                        "justify-content": 'center',
                        "z-index": 100
                    }}
                    onClick={handleCloseConfirm}
                >
                    <div
                        style={{
                            background: 'var(--surface)',
                            "border-radius": 'var(--radius, 12px)',
                            padding: '24px',
                            "max-width": '440px',
                            width: '90%',
                            "box-shadow": '0 16px 48px rgba(0,0,0,0.2)',
                            border: '1px solid var(--danger-border, #fecaca)'
                        }}
                        onClick={(e) => e.stopPropagation()}
                    >
                        <div style={{ display: 'flex', "align-items": 'flex-start', gap: '12px', "margin-bottom": '16px' }}>
                            <div style={{
                                width: '36px',
                                height: '36px',
                                "border-radius": '50%',
                                background: 'var(--danger, #dc2626)',
                                display: 'flex',
                                "align-items": 'center',
                                "justify-content": 'center',
                                "flex-shrink": 0
                            }}>
                                <svg width="18" height="18" viewBox="0 0 24 24" fill="#fff" aria-hidden="true" focusable="false">
                                    <path d="M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z"/>
                                </svg>
                            </div>
                            <div>
                                <h3 style={{ margin: '0 0 4px', "font-size": '16px', "font-weight": '600' }}>
                                    Reset {props.serviceLabel}?
                                </h3>
                                <p style={{ margin: 0, "font-size": '13px', color: 'var(--text-secondary)', "line-height": '1.5' }}>
                                    This will permanently delete all {props.serviceLabel} data. This action cannot be undone.
                                </p>
                            </div>
                        </div>

                        <div style={{ "margin-bottom": '16px' }}>
                            <label style={{ "font-size": '11px', color: 'var(--text-tertiary)', display: 'block', "margin-bottom": '6px' }}>
                                Type <code style={{ "font-weight": '600', color: 'var(--danger, #dc2626)', background: 'var(--danger-bg, #fef2f2)', padding: '1px 5px', "border-radius": '3px' }}>{expectedText()}</code> to confirm:
                            </label>
                            <input
                                type="text"
                                value={confirmText()}
                                onInput={(e) => setConfirmText(e.currentTarget.value)}
                                onKeyDown={(e) => { if (e.key === 'Enter' && confirmText().toLowerCase() === expectedText()) handleReset(); if (e.key === 'Escape') handleCloseConfirm(); }}
                                placeholder={expectedText()}
                                autofocus
                                style={{
                                    width: '100%',
                                    padding: '8px 12px',
                                    border: '1px solid var(--border)',
                                    "border-radius": '6px',
                                    "font-size": '13px',
                                    background: 'var(--bg)',
                                    color: 'var(--text-primary)',
                                    "box-sizing": 'border-box'
                                }}
                            />
                        </div>

                        <div style={{ display: 'flex', gap: '8px', "justify-content": 'flex-end' }}>
                            <button
                                class="btn btn-secondary"
                                onClick={handleCloseConfirm}
                                style={{ "font-size": '12px', padding: '6px 14px' }}
                            >
                                Cancel
                            </button>
                            <button
                                class="btn btn-danger-filled"
                                onClick={handleReset}
                                disabled={confirmText().toLowerCase() !== expectedText() || resetting()}
                                style={{
                                    "font-size": '12px',
                                    padding: '6px 14px',
                                    opacity: confirmText().toLowerCase() !== expectedText() ? 0.5 : 1
                                }}
                            >
                                Reset Data
                            </button>
                        </div>
                    </div>
                </div>
            </Show>
        </div>
    );
}

// ─── Secret Manager Stats Component ────────────────────────────────────
function SecretManagerStats(props) {
    const [stats, setStats] = createSignal(null);
    const [loading, setLoading] = createSignal(true);

    createEffect(() => {
        loadStats();
    });

    async function loadStats() {
        setLoading(true);
        try {
            const data = await api.secretManagerStats();
            setStats(data?.stats || null);
        } catch {
            setStats(null);
        } finally {
            setLoading(false);
        }
    }

    return (
        <div>
            <h3 style={{ margin: '0 0 16px', "font-size": '16px', "font-weight": '600' }}>Secret Manager</h3>
            <Show when={loading()}>
                <div class="loading-state"><div class="loading-spinner" /> Loading…</div>
            </Show>
            <Show when={!loading()}>
                <Show when={stats()} fallback={
                    <div class="empty-state">
                        <div class="empty-state-title">No statistics available</div>
                        <div class="empty-state-text">Create a secret to see usage data.</div>
                    </div>
                }>
                    <div style={{ display: 'grid', "grid-template-columns": 'repeat(auto-fit, minmax(140px, 1fr))', gap: '10px', "margin-bottom": '20px' }}>
                        <div class="stat-card">
                            <div class="stat-card-main">
                                <span class="stat-card-label">Secrets</span>
                                <span class="stat-card-value">{stats()?.total_secrets || 0}</span>
                            </div>
                        </div>
                        <div class="stat-card">
                            <div class="stat-card-main">
                                <span class="stat-card-label">Versions</span>
                                <span class="stat-card-value">{stats()?.total_versions || 0}</span>
                            </div>
                        </div>
                        <div class="stat-card">
                            <div class="stat-card-main">
                                <span class="stat-card-label">Enabled</span>
                                <span class="stat-card-value" style={{ color: 'var(--success)' }}>{stats()?.enabled_versions || 0}</span>
                            </div>
                        </div>
                        <Show when={stats()?.disabled_versions > 0}>
                            <div class="stat-card">
                                <div class="stat-card-main">
                                    <span class="stat-card-label">Disabled</span>
                                    <span class="stat-card-value" style={{ color: 'var(--warning)' }}>{stats()?.disabled_versions}</span>
                                </div>
                            </div>
                        </Show>
                    </div>
                </Show>
            </Show>
        </div>
    );
}

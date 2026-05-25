import { createSignal, createEffect, createMemo, Show, For, untrack, onCleanup } from 'solid-js';
import { api } from '../api.js';
import CsvImportWizard from '../components/CsvImportWizard.jsx';
import DataBreadcrumb from '../components/DataBreadcrumb.jsx';
import DatabaseExplorer from '../components/DatabaseExplorer.jsx';
import { formatDateTime, onActivate } from '../utils/a11y.js';
import { formatSize } from '../utils/format.js';
import CodeEditor from '../components/CodeEditor.jsx';
import { generateMockRow } from '../utils/mockGenerator.js';

const TABS = [
    { id: 'gcs', label: 'Cloud Storage' },
    { id: 'pubsub', label: 'Pub/Sub' },
    { id: 'firestore', label: 'Firestore' },
    { id: 'bigquery', label: 'BigQuery' },
    { id: 'secretmanager', label: 'Secret Manager' },
    { id: 'cloudtasks', label: 'Cloud Tasks' },
    { id: 'spanner', label: 'Spanner' },
    { id: 'bigtable', label: 'Bigtable' },
    { id: 'logging', label: 'Logging' },
    { id: 'monitoring', label: 'Monitoring' },
    { id: 'gke', label: 'GKE' },
    { id: 'compute', label: 'Compute' },
    { id: 'cloudrun', label: 'Cloud Run' },
    { id: 'memorystore', label: 'Memorystore' },
    { id: 'cloudsql', label: 'Cloud SQL' },
    { id: 'cloudscheduler', label: 'Cloud Scheduler' },
    { id: 'cloudfunctions', label: 'Cloud Functions' },
    { id: 'alloydb', label: 'AlloyDB' },
    { id: 'dataproc', label: 'Dataproc' },
    { id: 'cloudiam', label: 'Cloud IAM' },
];

const SERVICE_INFO = {
    gke: {
        name: 'GKE', port: 443, envVar: 'GKE_EMULATOR_HOST', envValue: 'localhost:443',
        protocol: 'gRPC', description: 'Use GKE SDK or kubectl to manage clusters.',
    },
    compute: {
        name: 'Compute Engine', port: 4443, envVar: 'COMPUTE_EMULATOR_HOST', envValue: 'localhost:4443',
        protocol: 'REST', description: 'Use the Compute Engine SDK to manage instances.',
    },
    cloudrun: {
        name: 'Cloud Run', port: 4443, envVar: 'CLOUD_RUN_EMULATOR_HOST', envValue: 'localhost:4443',
        protocol: 'gRPC', description: 'Use the Cloud Run SDK to manage services.',
    },
};

function EmptyState(props) {
    return (
        <div class="empty-state">
            <div class="empty-state-icon">{'\u2205'}</div>
            <div class="empty-state-title">{props.title}</div>
            <div class="empty-state-text">{props.message}</div>
        </div>
    );
}

function formatDate(ts) {
    if (!ts) return '--';
    return formatDateTime(ts) || ts;
}

// -- Reusable ConnectionInfoCard --
function ConnectionInfoCard(props) {
    const [copied, setCopied] = createSignal(false);

    const copyEnvVar = () => {
        const text = `${props.envVar}=${props.envValue}`;
        navigator.clipboard.writeText(text).then(() => {
            setCopied(true);
            setTimeout(() => setCopied(false), 2000);
        }).catch(() => { });
    };

    return (
        <div class="card" style={{ "max-width": "560px" }}>
            <div class="card-header">
                <h2>{props.name}</h2>
                <span class="badge badge-info">{props.protocol}</span>
            </div>
            <div class="card-body">
                <p style={{ "margin-bottom": "20px", "color": "var(--text-secondary)" }}>
                    {props.description}
                </p>
                <div style={{ "display": "flex", "flex-direction": "column", "gap": "12px" }}>
                    <div style={{ "display": "flex", "justify-content": "space-between", "align-items": "center" }}>
                        <span style={{ "font-size": "13px", "color": "var(--text-secondary)" }}>Port</span>
                        <code style={{ "font-size": "13px" }}>{props.port}</code>
                    </div>
                    <div style={{ "display": "flex", "justify-content": "space-between", "align-items": "center" }}>
                        <span style={{ "font-size": "13px", "color": "var(--text-secondary)" }}>Environment Variable</span>
                        <code style={{ "font-size": "12px" }}>{props.envVar}</code>
                    </div>
                    <div style={{ "display": "flex", "justify-content": "space-between", "align-items": "center" }}>
                        <span style={{ "font-size": "13px", "color": "var(--text-secondary)" }}>Value</span>
                        <code style={{ "font-size": "12px" }}>{props.envValue}</code>
                    </div>
                </div>
                <div style={{ "margin-top": "20px", "padding-top": "16px", "border-top": "1px solid var(--border)" }}>
                    <button class="btn btn-secondary" onClick={copyEnvVar}>
                        {copied() ? 'Copied!' : 'Copy env var'}
                    </button>
                </div>
            </div>
        </div>
    );
}

// -- GCS View --
function GcsView(props) {
    const [selectedBucket, setSelectedBucket] = createSignal(null);
    const [bucketObjects, setBucketObjects] = createSignal(null);
    const [objectsLoading, setObjectsLoading] = createSignal(false);

    const fetchBucketObjects = async (bucketName) => {
        setSelectedBucket(bucketName);
        setObjectsLoading(true);
        setBucketObjects(null);
        try {
            const result = await api.browse('gcs', bucketName);
            setBucketObjects(result.objects || []);
        } catch {
            setBucketObjects([]);
        } finally {
            setObjectsLoading(false);
        }
    };

    const goBack = () => {
        setSelectedBucket(null);
        setBucketObjects(null);
    };

    const d = () => props.data();

    return (
        <Show when={!selectedBucket()} fallback={
            <div>
                <button class="back-link" onClick={goBack}>
                    {'\u2190'} Back to buckets
                </button>
                <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px">
                    <h2 style="margin:0">Bucket: {selectedBucket()}</h2>
                    <Show when={props.onAdd}>
                        <button onClick={() => props.onAdd('Upload Object to ' + selectedBucket(), [
                            { name: 'key', type: 'text' },
                            { name: 'content', type: 'textarea' },
                            { name: 'contentType', type: 'text', value: 'application/json' }
                        ], async (formData) => {
                            await api.mutate('gcs', 'objects', { bucket: selectedBucket(), ...formData });
                        })} style="padding:6px 14px;border:none;border-radius:4px;background:var(--accent, #4285f4);color:white;cursor:pointer;font-size:13px">
                            + Upload Object
                        </button>
                    </Show>
                </div>
                <Show when={!objectsLoading()} fallback={
                    <div class="loading-state"><div class="loading-spinner" /> Loading objects…</div>
                }>
                    <Show when={bucketObjects() && bucketObjects().length > 0} fallback={
                        <div class="empty-state"
                            onDragOver={(e) => { e.preventDefault(); e.currentTarget.style.borderColor = 'var(--primary)'; e.currentTarget.style.background = 'var(--primary-softer)'; }}
                            onDragLeave={(e) => { e.currentTarget.style.borderColor = 'var(--border)'; e.currentTarget.style.background = ''; }}
                            onDrop={async (e) => {
                                e.preventDefault();
                                e.currentTarget.style.borderColor = 'var(--border)';
                                e.currentTarget.style.background = '';
                                const files = e.dataTransfer?.files;
                                if (!files || files.length === 0) return;
                                let uploaded = 0;
                                for (const file of files) {
                                    try {
                                        const text = await file.text();
                                        await api.mutate('gcs', 'objects', {
                                            bucket: selectedBucket(),
                                            key: file.name,
                                            content: text,
                                            contentType: file.type || 'text/plain',
                                        });
                                        uploaded++;
                                    } catch (err) { console.error('Upload failed for ' + file.name + ':', err); }
                                }
                                if (uploaded > 0) fetchBucketObjects(selectedBucket());
                            }}
                            style={{ border: '2px dashed var(--border)', 'border-radius': '8px', cursor: 'pointer', transition: 'border-color 150ms ease, background 150ms ease' }}
                        >
                            <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--text-tertiary)" stroke-width="1.5" aria-hidden="true" focusable="false" style={{ 'margin-bottom': '12px' }}>
                                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                                <polyline points="17 8 12 3 7 8" />
                                <line x1="12" y1="3" x2="12" y2="15" />
                            </svg>
                            <div class="empty-state-title">No objects found</div>
                            <div class="empty-state-text">Drag and drop text files here to upload (JSON, CSV, TXT, YAML), or use the SDK.</div>
                        </div>
                    }>
                        <div class="data-table-wrapper">
                            <table class="data-table">
                                <thead>
                                    <tr>
                                        <th>Name</th>
                                        <th>Size</th>
                                        <th>Content Type</th>
                                        <th>Updated</th>
                                        <th>Actions</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    <For each={bucketObjects()}>
                                        {(obj) => (
                                            <tr>
                                                <td style={{ "font-weight": "500" }}>{obj.name}</td>
                                                <td>{formatSize(obj.size)}</td>
                                                <td>{obj.contentType || '--'}</td>
                                                <td>{formatDate(obj.updated)}</td>
                                                <td>
                                                    <div style="display:flex;gap:4px;align-items:center">
                                                        <a
                                                            href={`${window.location.protocol}//${window.location.hostname}:4443/storage/v1/b/${selectedBucket()}/o/${encodeURIComponent(obj.name)}?alt=media`}
                                                            target="_blank"
                                                            rel="noopener noreferrer"
                                                            class="btn btn-secondary"
                                                            style={{ "height": "28px", "font-size": "12px", "padding": "0 10px" }}
                                                        >
                                                            Download
                                                        </a>
                                                        <Show when={props.onDelete}>
                                                            <button onClick={() => props.onDelete('Delete object "' + obj.name + '" from ' + selectedBucket() + '?', async () => {
                                                                await api.mutate('gcs', 'objects/delete', { bucket: selectedBucket(), key: obj.name });
                                                            })} style="padding:4px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:#ea4335;cursor:pointer;font-size:11px" title="Delete">Del</button>
                                                        </Show>
                                                    </div>
                                                </td>
                                            </tr>
                                        )}
                                    </For>
                                </tbody>
                            </table>
                        </div>
                    </Show>
                </Show>
            </div>
        }>
            <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px">
                <div />
                <Show when={props.onAdd}>
                    <button onClick={() => props.onAdd('Create Bucket', [
                        { name: 'name', type: 'text' },
                        { name: 'location', type: 'text', value: 'US' }
                    ], async (formData) => {
                        await api.mutate('gcs', 'buckets', formData);
                    })} style="padding:6px 14px;border:none;border-radius:4px;background:var(--accent, #4285f4);color:white;cursor:pointer;font-size:13px">
                        + Create Bucket
                    </button>
                </Show>
            </div>
            <Show when={d() && d().buckets && d().buckets.length > 0} fallback={
                <div class="empty-state">
                    <div class="empty-state-icon">{'\u2205'}</div>
                    <div class="empty-state-title">No buckets found</div>
                    <div class="empty-state-text">Create a bucket using the button above, or via the SDK:</div>
                    <div class="empty-state-hint"><code>client = storage.Client(){'\n'}client.create_bucket("my-bucket")</code></div>
                </div>
            }>
                <div class="data-table-wrapper">
                    <table class="data-table">
                        <thead>
                            <tr>
                                <th>Bucket Name</th>
                                <th>Location</th>
                                <th>Created</th>
                            </tr>
                        </thead>
                        <tbody>
                            <For each={d().buckets}>
                                {(bucket) => (
                                    <tr class="clickable-row" onClick={() => fetchBucketObjects(bucket.name)} onKeyDown={onActivate(() => fetchBucketObjects(bucket.name))} role="button" tabIndex="0">
                                        <td>{bucket.name}</td>
                                        <td>{bucket.location || '--'}</td>
                                        <td>{formatDate(bucket.timeCreated)}</td>
                                    </tr>
                                )}
                            </For>
                        </tbody>
                    </table>
                </div>
            </Show>
        </Show>
    );
}

// -- Pub/Sub View --
function PubSubView(props) {
    const d = () => props.data();
    const [pubsubMessages, setPubsubMessages] = createSignal([]);
    const [selectedSub, setSelectedSub] = createSignal('');
    const [messagesLoading, setMessagesLoading] = createSignal(false);

    const loadMessages = async (subName) => {
        setSelectedSub(subName);
        setMessagesLoading(true);
        try {
            const data = await api.browse('pubsub', 'messages/' + subName);
            setPubsubMessages(data.messages || []);
        } catch (e) { setPubsubMessages([]); }
        finally { setMessagesLoading(false); }
    };

    return (
        <div>
            <div class="section">
                <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px">
                    <h2 style="margin:0">Topics</h2>
                    <Show when={props.onAdd}>
                        <button onClick={() => props.onAdd('Create Topic', [
                            { name: 'name', type: 'text' },
                        ], async (formData) => {
                            await api.mutate('pubsub', 'topics', formData);
                        })} style="padding:6px 12px;border:none;border-radius:4px;background:var(--accent, #4285f4);color:white;cursor:pointer;font-size:12px">
                            + Create Topic
                        </button>
                    </Show>
                </div>
                <Show when={d() && d().topics && d().topics.length > 0} fallback={
                    <div class="empty-state">
                        <div class="empty-state-icon">{'\u2205'}</div>
                        <div class="empty-state-title">No topics found</div>
                    </div>
                }>
                    <div class="data-table-wrapper">
                        <table class="data-table">
                            <thead>
                                <tr>
                                    <th>Topic Name</th>
                                    <th>Messages</th>
                                    <th>Actions</th>
                                </tr>
                            </thead>
                            <tbody>
                                <For each={d().topics}>
                                    {(topic) => {
                                        const topicName = topic.name;
                                        return (
                                            <tr>
                                                <td style={{ "font-weight": "500" }}>{topicName}</td>
                                                <td>
                                                    <Show when={topic.messageCount != null} fallback="--">
                                                        <span class="badge badge-info">{topic.messageCount}</span>
                                                    </Show>
                                                </td>
                                                <td>
                                                    <div style="display:flex;gap:4px;align-items:center">
                                                        <Show when={props.onAdd}>
                                                            <button onClick={() => props.onAdd('Publish Message to ' + topicName, [
                                                                { name: 'data', type: 'textarea' },
                                                            ], async (formData) => {
                                                                await api.mutate('pubsub', 'messages', { topic: topicName, ...formData });
                                                            })} style="padding:4px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:var(--text-secondary);cursor:pointer;font-size:11px" title="Publish Message">Publish</button>
                                                        </Show>
                                                        <Show when={props.onDelete}>
                                                            <button onClick={() => props.onDelete('Delete topic "' + topicName + '"?', async () => {
                                                                await api.mutate('pubsub', 'topics/delete', { name: topicName });
                                                            })} style="padding:4px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:#ea4335;cursor:pointer;font-size:11px" title="Delete">Del</button>
                                                        </Show>
                                                    </div>
                                                </td>
                                            </tr>
                                        );
                                    }}
                                </For>
                            </tbody>
                        </table>
                    </div>
                </Show>
            </div>
            <div class="section" style={{ "margin-top": "24px" }}>
                <h2>Subscriptions</h2>
                <Show when={d() && d().subscriptions && d().subscriptions.length > 0} fallback={
                    <div class="empty-state">
                        <div class="empty-state-icon">{'\u2205'}</div>
                        <div class="empty-state-title">No subscriptions found</div>
                    </div>
                }>
                    <div class="data-table-wrapper">
                        <table class="data-table">
                            <thead>
                                <tr>
                                    <th>Subscription Name</th>
                                    <th>Topic</th>
                                    <th>Ack Deadline</th>
                                    <th>Actions</th>
                                </tr>
                            </thead>
                            <tbody>
                                <For each={d().subscriptions}>
                                    {(sub) => (
                                        <tr>
                                            <td style={{ "font-weight": "500" }}>{sub.name}</td>
                                            <td>{sub.topic || '--'}</td>
                                            <td>{sub.ackDeadlineSeconds != null ? sub.ackDeadlineSeconds + 's' : '--'}</td>
                                            <td>
                                                <button onClick={() => loadMessages(sub.name)}
                                                    style="padding:4px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:var(--text-secondary);cursor:pointer;font-size:11px"
                                                    title="View Messages">View Messages</button>
                                            </td>
                                        </tr>
                                    )}
                                </For>
                            </tbody>
                        </table>
                    </div>
                </Show>
            </div>
            {/* Messages section */}
            <Show when={selectedSub()}>
                <div class="section" style={{ "margin-top": "24px" }}>
                    <h2>Messages: {selectedSub()}</h2>
                    <Show when={messagesLoading()}>
                        <div class="loading-state"><div class="loading-spinner" /> Loading messages…</div>
                    </Show>
                    <Show when={!messagesLoading()}>
                        <Show when={pubsubMessages().length > 0} fallback={
                            <div class="empty-state">
                                <div class="empty-state-icon">{'\u2205'}</div>
                                <div class="empty-state-title">No messages found</div>
                                <div class="empty-state-text">No messages in this subscription.</div>
                            </div>
                        }>
                            <div class="data-table-wrapper">
                                <table class="data-table">
                                    <thead>
                                        <tr>
                                            <th>Message ID</th>
                                            <th>Publish Time</th>
                                            <th>Data</th>
                                            <th>Attributes</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        <For each={pubsubMessages()}>
                                            {(msg) => (
                                                <tr>
                                                    <td style={{ "font-weight": "500", "font-size": "12px" }}>{msg.messageId || msg.id || '--'}</td>
                                                    <td style={{ "font-size": "12px" }}>{formatDate(msg.publishTime)}</td>
                                                    <td style={{ "max-width": "300px", "overflow": "hidden", "text-overflow": "ellipsis", "white-space": "nowrap", "font-size": "12px" }}>
                                                        {msg.data ? (msg.data.length > 100 ? msg.data.substring(0, 100) + '…' : msg.data) : '--'}
                                                    </td>
                                                    <td style={{ "font-size": "12px", "color": "var(--text-secondary)" }}>
                                                        {msg.attributes ? (typeof msg.attributes === 'object' ? JSON.stringify(msg.attributes) : String(msg.attributes)) : '--'}
                                                    </td>
                                                </tr>
                                            )}
                                        </For>
                                    </tbody>
                                </table>
                            </div>
                        </Show>
                    </Show>
                </div>
            </Show>
        </div>
    );
}

// -- BigQuery INFORMATION_SCHEMA Browser --
function InfoSchemaBrowser(props) {
    const VIEWS = ['tables', 'columns', 'schemata', 'views', 'routines', 'partitions', 'table_storage'];
    const viewLabels = { tables: 'TABLES', columns: 'COLUMNS', schemata: 'SCHEMATA', views: 'VIEWS', routines: 'ROUTINES', partitions: 'PARTITIONS', table_storage: 'TABLE_STORAGE' };
    return (
        <div style="margin-bottom:20px;border:1px solid var(--border);border-radius:8px;overflow:hidden">
            <div style="display:flex;align-items:center;justify-content:space-between;padding:10px 14px;background:var(--surface-variant);border-bottom:1px solid var(--border)">
                <div style="display:flex;align-items:center;gap:8px">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="var(--primary)" aria-hidden="true"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z"/></svg>
                    <span style="font-size:13px;font-weight:600">INFORMATION_SCHEMA</span>
                </div>
                <button onClick={props.onClose} style="padding:2px 8px;border:none;background:none;color:var(--text-tertiary);cursor:pointer;font-size:16px;line-height:1">&times;</button>
            </div>
            <div style="display:flex;gap:2px;padding:8px 14px;background:var(--surface);border-bottom:1px solid var(--border);overflow-x:auto">
                {VIEWS.map(v => (
                    <button onClick={() => props.onSelectView(v)} style={{
                        padding: '5px 12px', border: 'none', borderRadius: '5px', cursor: 'pointer',
                        fontSize: '11px', fontWeight: props.view === v ? 600 : 400,
                        background: props.view === v ? 'var(--primary)' : 'transparent',
                        color: props.view === v ? '#fff' : 'var(--text-secondary)',
                        transition: 'all 0.15s', whiteSpace: 'nowrap'
                    }}>{viewLabels[v]}</button>
                ))}
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

// -- BigQuery View (drill-down: datasets -> tables -> data) --
function BigQueryView(props) {
    const d = () => props.data();
    const [selectedDataset, setSelectedDataset] = createSignal(null);
    const [selectedTable, setSelectedTable] = createSignal(null);
    const [tables, setTables] = createSignal([]);
    const [tableData, setTableData] = createSignal(null);
    const [subLoading, setSubLoading] = createSignal(false);
    const [showCreateDataset, setShowCreateDataset] = createSignal(false);
    const [showCreateTable, setShowCreateTable] = createSignal(false);
    const [showEditRow, setShowEditRow] = createSignal(false);
    const [editingRow, setEditingRow] = createSignal(null);
    const [bqActionLoading, setBqActionLoading] = createSignal(false);
    const [showInfoSchema, setShowInfoSchema] = createSignal(false);
    const [infoSchemaView, setInfoSchemaView] = createSignal('tables');
    const [infoSchemaData, setInfoSchemaData] = createSignal(null);
    const [showMerge, setShowMerge] = createSignal(false);
    const [isViewType, setIsViewType] = createSignal(false);

    const datasets = () => {
        const raw = d();
        if (!raw) return [];
        if (raw.items && Array.isArray(raw.items)) return raw.items;
        if (raw.datasets && Array.isArray(raw.datasets)) return raw.datasets;
        return [];
    };

    const dsName = (ds) => ds.datasetReference ? ds.datasetReference.datasetId : (ds.id || ds.name || '--');

    const updateSubpath = (dataset, table) => {
        if (props.onSubpathChange) {
            const parts = [];
            if (dataset) { parts.push(dataset); if (table) parts.push(table); }
            props.onSubpathChange(parts);
        }
    };

    const selectDataset = async (ds) => {
        const dsId = dsName(ds);
        setSelectedDataset(dsId);
        setSelectedTable(null);
        setTables([]);
        updateSubpath(dsId, null);
        setSubLoading(true);
        try {
            const result = await api.browse('bigquery', 'datasets/' + dsId);
            const tblList = result.tables || result.items || [];
            setTables(tblList);
        } catch { setTables([]); }
        finally { setSubLoading(false); }
    };

    const selectTable = async (tbl) => {
        const tblId = tbl.tableReference ? tbl.tableReference.tableId : (tbl.name || tbl.id);
        setSelectedTable(tblId);
        updateSubpath(selectedDataset(), tblId);
        setTableData(null);
        setSubLoading(true);
        try {
            const result = await api.browse('bigquery', 'datasets/' + selectedDataset() + '/tables/' + tblId + '/data');
            setTableData(result);
        } catch { setTableData(null); }
        finally { setSubLoading(false); }
    };

    const goBackToDatasets = () => { setSelectedDataset(null); setSelectedTable(null); updateSubpath(null, null); };
    const goBackToTables = () => { setSelectedTable(null); updateSubpath(selectedDataset(), null); };

    const handleCreateDataset = async (formData) => {
        setBqActionLoading(true);
        try {
            await api.mutate('bigquery', 'datasets', { datasetId: formData.datasetId, description: formData.description || '' });
            setShowCreateDataset(false);
            if (props.onRefresh) props.onRefresh();
        } catch (e) { alert('Failed to create dataset: ' + e.message); }
        finally { setBqActionLoading(false); }
    };

    const handleDeleteDataset = async (dsId) => {
        if (!confirm(`Delete dataset "${dsId}" and all its tables?`)) return;
        setBqActionLoading(true);
        try {
            await api.mutateSub('bigquery', 'datasets', 'delete', { datasetId: dsId, deleteContents: true });
            if (selectedDataset() === dsId) goBackToDatasets();
            if (props.onRefresh) props.onRefresh();
        } catch (e) { alert('Failed to delete dataset: ' + e.message); }
        finally { setBqActionLoading(false); }
    };

    const handleCreateTable = async (formData) => {
        setBqActionLoading(true);
        try {
            const schema = formData.columns ? formData.columns.split('\n').filter(l => l.trim()).map(line => {
                const parts = line.trim().split(':').map(s => s.trim());
                return { name: parts[0], type: parts[1]?.toUpperCase() || 'STRING', mode: parts[2]?.toUpperCase() || 'NULLABLE' };
            }) : [];
            const payload = {
                datasetId: selectedDataset(),
                tableId: formData.tableId,
                schema,
                description: formData.description || '',
                tableType: formData.tableType || 'TABLE',
                viewQuery: formData.tableType === 'VIEW' ? formData.viewQuery : undefined
            };
            if (formData.partitionType) {
                payload.timePartitioning = { type: formData.partitionType };
                if (formData.partitionField) payload.timePartitioning.field = formData.partitionField;
            }
            if (formData.clusteringFields) {
                payload.clustering = formData.clusteringFields.split(',').map(s => s.trim()).filter(Boolean);
            }
            await api.mutate('bigquery', 'tables', payload);
            setShowCreateTable(false);
            const result = await api.browse('bigquery', 'datasets/' + selectedDataset());
            setTables(result.tables || result.items || []);
        } catch (e) { alert('Failed to create table: ' + e.message); }
        finally { setBqActionLoading(false); }
    };

    const handleDeleteTable = async (tblId) => {
        if (!confirm(`Delete table "${selectedDataset()}.${tblId}"?`)) return;
        setBqActionLoading(true);
        try {
            await api.mutateSub('bigquery', 'tables', 'delete', { datasetId: selectedDataset(), tableId: tblId });
            if (selectedTable() === tblId) goBackToTables();
            const result = await api.browse('bigquery', 'datasets/' + selectedDataset());
            setTables(result.tables || result.items || []);
        } catch (e) { alert('Failed to delete table: ' + e.message); }
        finally { setBqActionLoading(false); }
    };

    const handleUpdateRow = async (formData) => {
        setBqActionLoading(true);
        try {
            const origRow = editingRow();
            const columns = tableData().columns;
            const firstCol = columns[0];
            const whereClause = `${firstCol} = '${String(origRow[firstCol]).replace(/'/g, "''")}'`;
            await api.mutateSub('bigquery', 'rows', 'update', {
                dataset: selectedDataset(),
                table: selectedTable(),
                setValues: formData,
                whereClause
            });
            setShowEditRow(false);
            setEditingRow(null);
            const result = await api.browse('bigquery', 'datasets/' + selectedDataset() + '/tables/' + selectedTable() + '/data');
            setTableData(result);
        } catch (e) { alert('Failed to update row: ' + e.message); }
        finally { setBqActionLoading(false); }
    };

    const loadInfoSchema = async (viewName) => {
        const vn = viewName || infoSchemaView() || 'tables';
        setInfoSchemaView(vn);
        setBqActionLoading(true);
        try {
            const data = await api.bigqueryInfoSchema(vn);
            setInfoSchemaData(data);
        } catch (e) { alert('Failed to load INFORMATION_SCHEMA: ' + e.message); }
        finally { setBqActionLoading(false); }
    };

    const handleMerge = async (formData) => {
        setBqActionLoading(true);
        try {
            const updateSet = {}, insertValues = {};
            if (formData.updateSet) formData.updateSet.split(',').filter(Boolean).forEach(pair => {
                const [col, val] = pair.trim().split('=').map(s => s.trim());
                if (col && val) updateSet[col] = val;
            });
            if (formData.insertValues) formData.insertValues.split(',').filter(Boolean).forEach(pair => {
                const [col, val] = pair.trim().split('=').map(s => s.trim());
                if (col && val) insertValues[col] = val;
            });
            await api.merge('bigquery', {
                dataset: selectedDataset(), table: selectedTable(),
                sourceQuery: formData.sourceQuery, mergeCondition: formData.mergeCondition,
                updateSet: Object.keys(updateSet).length > 0 ? updateSet : null,
                insertValues: Object.keys(insertValues).length > 0 ? insertValues : null
            });
            setShowMerge(false);
            const result = await api.browse('bigquery', 'datasets/' + selectedDataset() + '/tables/' + selectedTable() + '/data');
            setTableData(result);
        } catch (e) { alert('Merge failed: ' + e.message); }
        finally { setBqActionLoading(false); }
    };

    // Restore dataset from URL subpath
    createEffect(() => {
        const sp = typeof props.subpath === 'function' ? props.subpath() : props.subpath;
        if (!sp || sp.length === 0) {
            setSelectedDataset(null);
            setSelectedTable(null);
            return;
        }
        const dsList = datasets();
        if (!dsList || dsList.length === 0) return;
        const [dsId] = sp;
        const currentDs = untrack(() => selectedDataset());
        if (dsId && !currentDs) {
            const ds = dsList.find(d => dsName(d) === dsId);
            if (ds) selectDataset(ds);
        } else if (!dsId && currentDs) {
            setSelectedDataset(null);
            setSelectedTable(null);
        }
    });

    // Restore table from URL subpath once tables load
    createEffect(() => {
        const sp = typeof props.subpath === 'function' ? props.subpath() : props.subpath;
        if (!sp || sp.length < 2) {
            setSelectedTable(null);
            return;
        }
        const tblId = sp[1];
        const tbls = tables();
        const currentTbl = untrack(() => selectedTable());
        if (tbls.length > 0 && !currentTbl) {
            const tbl = tbls.find(t => (t.tableReference ? t.tableReference.tableId : (t.name || t.id)) === tblId);
            if (tbl) selectTable(tbl);
        } else if (!tblId && currentTbl) {
            setSelectedTable(null);
        }
    });

    // Breadcrumb
    const breadcrumbs = createMemo(() => {
        const crumbs = [{ label: 'Datasets', onClick: goBackToDatasets, active: !selectedDataset() }];
        if (selectedDataset()) {
            crumbs.push({ label: selectedDataset(), onClick: goBackToTables, active: !selectedTable() });
            if (selectedTable()) crumbs.push({ label: selectedTable(), onClick: null, active: true });
        }
        return crumbs;
    });

    // CSV Import
    const [showCsvImport, setShowCsvImport] = createSignal(false);
    const bqImportRow = async (targetCols, values) => {
        const row = {};
        targetCols.forEach((col, i) => { if (values[i] && values[i].trim()) row[col] = values[i]; });
        return await api.mutate('bigquery', 'rows', { dataset: selectedDataset(), table: selectedTable(), row });
    };

    // Tree sidebar content
    const TreeSidebar = () => (
        <Show when={datasets().length > 0}>
            <div class="data-tree">
                <For each={datasets()}>
                    {(ds) => {
                        const id = dsName(ds);
                        const isOpen = () => selectedDataset() === id;
                        return (
                            <div>
                                <button class={`data-tree-toggle ${!isOpen() ? 'collapsed' : ''}`} onClick={() => { if (isOpen()) goBackToDatasets(); else selectDataset(ds); }}>
                                    <svg width="10" height="10" viewBox="0 0 10 10" fill="currentColor" aria-hidden="true" focusable="false"><path d="M3 2l4 3-4 3z" /></svg>
                                    {id}
                                </button>
                                <Show when={isOpen()}>
                                    <div class="data-tree-group">
                                        <For each={tables()}>
                                            {(tbl) => {
                                                const tblId = tbl.tableReference ? tbl.tableReference.tableId : (tbl.name || tbl.id);
                                                return (
                                                    <button class={`data-tree-item ${selectedTable() === tblId ? 'active' : ''}`} onClick={() => selectTable(tbl)}>
                                                        <svg class="data-tree-item-icon" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true" focusable="false"><path d="M2 3h12v2H2zm0 4h12v2H2zm0 4h12v2H2z" /></svg>
                                                        {tblId}
                                                    </button>
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
    );

    // Content area (table data or table list or empty)
    const ContentArea = () => (
        <div class="data-tree-content">
            <Show when={subLoading()}>
                <div class="loading-state"><div class="loading-spinner" /> Loading…</div>
            </Show>
            <Show when={!subLoading()}>
                {/* Table data */}
                <Show when={selectedTable()}>
                    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:12px">
                        <h2 style="margin:0;font-size:16px">{selectedDataset()}.{selectedTable()}</h2>
                        <Show when={props.onAdd && tableData() && tableData().columns}>
                            <div style="display:flex;gap:6px">
                                <button onClick={() => setShowCsvImport(true)}
                                    style="padding:6px 12px;border:1px solid var(--border);border-radius:6px;background:var(--surface);color:var(--text-secondary);cursor:pointer;font-size:12px;display:flex;align-items:center;gap:5px;transition:border-color 0.15s, background 0.15s, color 0.15s"
                                    onMouseEnter={e => e.currentTarget.style.borderColor = 'var(--primary)'}
                                    onMouseLeave={e => e.currentTarget.style.borderColor = 'var(--border)'}>
                                    {'\u2191'} Import CSV
                                </button>
                                <button onClick={() => setShowMerge(true)}
                                    style="padding:6px 12px;border:1px solid var(--border);border-radius:6px;background:var(--surface);color:var(--text-secondary);cursor:pointer;font-size:12px;display:flex;align-items:center;gap:5px;transition:all 0.15s"
                                    onMouseEnter={e => { e.currentTarget.style.borderColor = 'var(--warning)'; e.currentTarget.style.color = 'var(--warning)'; }}
                                    onMouseLeave={e => { e.currentTarget.style.borderColor = 'var(--border)'; e.currentTarget.style.color = 'var(--text-secondary)'; }}
                                    title="MERGE upsert from a source query">
                                    <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><path d="M17 20.41L18.41 19 15 15.59 13.59 17M7.5 8H11v5.59L5.59 19 7 20.41l6-6V8h3.5L12 3.5"/></svg>
                                    Upsert
                                </button>
                                <button onClick={() => props.onAdd('Add BigQuery Row',
                                    tableData().columns.map(c => ({ name: c, type: 'text' })),
                                    async (formData) => {
                                        await api.mutate('bigquery', 'rows', { dataset: selectedDataset(), table: selectedTable(), row: formData });
                                    }
                                )} style="padding:6px 12px;border:none;border-radius:6px;background:var(--accent, #4285f4);color:white;cursor:pointer;font-size:12px">
                                    + Add Row
                                </button>
                            </div>
                        </Show>
                    </div>
                    <Show when={tableData() && tableData().columns && tableData().rows} fallback={
                        <div class="empty-state">
                            <div class="empty-state-icon">{'\u2205'}</div>
                            <div class="empty-state-title">No data found</div>
                            <div class="empty-state-text">This table is empty.</div>
                        </div>
                    }>
                        <Show when={tableData().rows.length > 0} fallback={
                            <div class="empty-state">
                                <div class="empty-state-icon">{'\u2205'}</div>
                                <div class="empty-state-title">No rows found</div>
                                <div class="empty-state-text">This table is empty.</div>
                            </div>
                        }>
                            <div class="data-table-wrapper">
                                <table class="data-table">
                                    <thead><tr><For each={tableData().columns}>{(col) => <th>{col}</th>}</For><th>Actions</th></tr></thead>
                                    <tbody>
                                        <For each={tableData().rows}>
                                            {(row) => (
                                                <tr>
                                                    <For each={tableData().columns}>
                                                        {(col) => <td>{row[col] != null ? String(row[col]) : '--'}</td>}
                                                    </For>
                                                    <td>
                                                        <Show when={props.onDelete}>
                                                            {(() => {
                                                                const columns = tableData().columns;
                                                                const firstCol = columns[0];
                                                                const value = row[firstCol];
                                                                return (
                                                                    <div style="display:flex;gap:4px">
                                                                        <button onClick={() => { setEditingRow(row); setShowEditRow(true); }}
                                                                            style="padding:4px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:var(--accent, #4285f4);cursor:pointer;font-size:11px" title="Edit row">Edit</button>
                                                                        <button onClick={() => props.onDelete('Delete this row?', async () => {
                                                                            await api.mutate('bigquery', 'rows/delete', {
                                                                                dataset: selectedDataset(),
                                                                                table: selectedTable(),
                                                                                whereClause: `${firstCol} = '${String(value).replace(/'/g, "''")}'`
                                                                            });
                                                                        })} style="padding:4px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:#ea4335;cursor:pointer;font-size:11px" title="Delete">Del</button>
                                                                    </div>
                                                                );
                                                            })()}
                                                        </Show>
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

                {/* Tables list (no table selected, dataset selected) */}
                <Show when={selectedDataset() && !selectedTable()}>
                    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:12px">
                        <h2 style="margin:0;font-size:16px">Dataset: {selectedDataset()}</h2>
                        <div style="display:flex;gap:6px">
                            <button onClick={() => setShowCreateTable(true)}
                                style="padding:6px 12px;border:none;border-radius:6px;background:var(--accent, #4285f4);color:white;cursor:pointer;font-size:12px">
                                + Create Table
                            </button>
                            <button onClick={() => handleDeleteDataset(selectedDataset())} disabled={bqActionLoading()}
                                style="padding:6px 12px;border:1px solid #ea4335;border-radius:6px;background:var(--surface);color:#ea4335;cursor:pointer;font-size:12px">
                                Delete Dataset
                            </button>
                        </div>
                    </div>
                    <Show when={tables().length > 0} fallback={
                        <div class="empty-state">
                            <div class="empty-state-icon">{'\u2205'}</div>
                            <div class="empty-state-title">No tables found</div>
                            <div class="empty-state-text">Create tables in this dataset.</div>
                        </div>
                    }>
                        <div class="data-table-wrapper">
                            <table class="data-table">
                                <thead><tr><th>Table Name</th><th>Kind</th><th>Type</th><th>Actions</th></tr></thead>
                                <tbody>
                                    <For each={tables()}>{(tbl) => {
                                        const tblId = tbl.tableReference ? tbl.tableReference.tableId : (tbl.name || tbl.id);
                                        const tableType = tbl.type || 'TABLE';
                                        return (
                                            <tr>
                                                <td class="clickable-row" onClick={() => selectTable(tbl)} onKeyDown={onActivate(() => selectTable(tbl))} role="button" tabIndex="0" style={{ "font-weight": "500" }}>{tblId}</td>
                                                <td>{tbl.kind || 'table'}</td>
                                                <td>{tableType}</td>
                                                <td><button onClick={(e) => { e.stopPropagation(); handleDeleteTable(tblId); }} style="padding:4px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:#ea4335;cursor:pointer;font-size:11px" title="Delete table">Del</button></td>
                                            </tr>
                                        );
                                    }}</For>
                                </tbody>
                            </table>
                        </div>
                    </Show>
                </Show>

                {/* Datasets list (nothing selected) */}
                <Show when={!selectedDataset()}>
                    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:12px">
                        <h2 style="margin:0;font-size:16px">BigQuery Datasets</h2>
                        <div style="display:flex;gap:6px">
                            <button onClick={() => { setShowInfoSchema(!showInfoSchema()); if (showInfoSchema()) loadInfoSchema('tables'); }}
                                style="padding:6px 12px;border:1px solid var(--border);border-radius:6px;background:var(--surface);color:var(--text-secondary);cursor:pointer;font-size:12px;display:flex;align-items:center;gap:5px;transition:all 0.15s"
                                onMouseEnter={e => { e.currentTarget.style.borderColor = 'var(--primary)'; e.currentTarget.style.color = 'var(--primary)'; }}
                                onMouseLeave={e => { e.currentTarget.style.borderColor = 'var(--border)'; e.currentTarget.style.color = 'var(--text-secondary)'; }}
                                title="Browse system INFORMATION_SCHEMA views">
                                <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z"/></svg>
                                {showInfoSchema() ? 'Datasets' : 'INFORMATION_SCHEMA'}
                            </button>
                            <button onClick={() => setShowCreateDataset(true)}
                                style="padding:6px 12px;border:none;border-radius:6px;background:var(--accent, #4285f4);color:white;cursor:pointer;font-size:12px">
                                + Create Dataset
                            </button>
                        </div>
                    </div>
                    <Show when={showInfoSchema()}>
                        <InfoSchemaBrowser view={infoSchemaView()} data={infoSchemaData()} loading={bqActionLoading()} onSelectView={loadInfoSchema} onClose={() => setShowInfoSchema(false)} />
                    </Show>
                    <Show when={!showInfoSchema()}>
                        <Show when={datasets().length > 0} fallback={
                            <div class="empty-state">
                                <div class="empty-state-icon">{'\u2205'}</div>
                                <div class="empty-state-title">No datasets found</div>
                                <div class="empty-state-text">Create a BigQuery dataset to see it here.</div>
                            </div>
                        }>
                            <div class="data-table-wrapper">
                                <table class="data-table">
                                    <thead><tr><th>Dataset</th><th>Kind</th><th>Location</th><th>Actions</th></tr></thead>
                                    <tbody>
                                        <For each={datasets()}>{(ds) => {
                                            const dsId = dsName(ds);
                                            return (
                                                <tr>
                                                    <td class="clickable-row" onClick={() => selectDataset(ds)} onKeyDown={onActivate(() => selectDataset(ds))} role="button" tabIndex="0" style={{ "font-weight": "500" }}>{dsId}</td>
                                                    <td>{ds.kind || 'dataset'}</td>
                                                    <td>{ds.location || '--'}</td>
                                                    <td><button onClick={(e) => { e.stopPropagation(); handleDeleteDataset(dsId); }} style="padding:4px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:#ea4335;cursor:pointer;font-size:11px" title="Delete dataset">Del</button></td>
                                                </tr>
                                            );
                                        }}</For>
                                    </tbody>
                                </table>
                            </div>
                        </Show>
                    </Show>
                </Show>
            </Show>
        </div>
    );

    return (
        <div>
            <Show when={selectedDataset()}>
                <DataBreadcrumb crumbs={breadcrumbs()} />
            </Show>
            <Show when={datasets().length > 0 && selectedDataset()} fallback={<ContentArea />}>
                <div class="data-tree-layout">
                    <TreeSidebar />
                    <ContentArea />
                </div>
            </Show>
            <CsvImportWizard
                show={showCsvImport()}
                onClose={() => setShowCsvImport(false)}
                tableName={selectedDataset() + '.' + selectedTable()}
                columns={tableData()?.columns || []}
                serviceName="BigQuery"
                onImportRow={bqImportRow}
                onImportDone={() => selectTable({ tableReference: { tableId: selectedTable() } })}
            />

            {/* Create Dataset Dialog */}
            <Show when={showCreateDataset()}>
                <div class="modal-overlay" onClick={() => setShowCreateDataset(false)}>
                    <div class="modal-content" onClick={e => e.stopPropagation()} style="max-width:400px">
                        <h3 style="margin:0 0 16px 0;font-size:16px;font-weight:600">Create Dataset</h3>
                        <form onSubmit={e => { e.preventDefault(); handleCreateDataset(Object.fromEntries(new FormData(e.target))); }}>
                            <label style="display:block;margin-bottom:4px;font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:0.5px;color:var(--text-tertiary)">Dataset ID *</label>
                            <input name="datasetId" required placeholder="my_dataset" style="width:100%;padding:9px 12px;border:1px solid var(--border);border-radius:6px;margin-bottom:12px;font-size:13px;background:var(--surface);color:var(--text)" />
                            <label style="display:block;margin-bottom:4px;font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:0.5px;color:var(--text-tertiary)">Description</label>
                            <input name="description" placeholder="Optional" style="width:100%;padding:9px 12px;border:1px solid var(--border);border-radius:6px;margin-bottom:16px;font-size:13px;background:var(--surface);color:var(--text)" />
                            <div style="display:flex;gap:8px;justify-content:flex-end">
                                <button type="button" onClick={() => setShowCreateDataset(false)} class="btn-cancel">Cancel</button>
                                <button type="submit" disabled={bqActionLoading()} class="btn-primary-sm">{bqActionLoading() ? 'Creating…' : 'Create'}</button>
                            </div>
                        </form>
                    </div>
                </div>
            </Show>

            {/* Create Table Dialog */}
            <Show when={showCreateTable()}>
                <div class="modal-overlay" onClick={() => setShowCreateTable(false)}>
                    <div class="modal-content" onClick={e => e.stopPropagation()} style="max-width:560px;max-height:85vh;overflow-y:auto">
                        <h3 style="margin:0 0 16px 0;font-size:16px;font-weight:600">Create Table in <span style="color:var(--primary)">{selectedDataset()}</span></h3>
                        <form onSubmit={e => { e.preventDefault(); handleCreateTable(Object.fromEntries(new FormData(e.target))); }}>
                            <div style="display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-bottom:12px">
                                <div>
                                    <label style="display:block;margin-bottom:4px;font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:0.5px;color:var(--text-tertiary)">Table ID *</label>
                                    <input name="tableId" required placeholder="my_table" style="width:100%;padding:9px 12px;border:1px solid var(--border);border-radius:6px;font-size:13px;background:var(--surface);color:var(--text)" />
                                </div>
                                <div>
                                    <label style="display:block;margin-bottom:4px;font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:0.5px;color:var(--text-tertiary)">Type</label>
                                    <select name="tableType" style="width:100%;padding:9px 12px;border:1px solid var(--border);border-radius:6px;font-size:13px;background:var(--surface);color:var(--text)" onChange={(e) => setIsViewType(e.target.value === 'VIEW')}>
                                        <option value="TABLE">Table</option>
                                        <option value="VIEW">View</option>
                                    </select>
                                </div>
                            </div>
                            <div style={{display: isViewType() ? 'block' : 'none', 'margin-bottom': '12px'}}>
                                <label style="display:block;margin-bottom:4px;font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:0.5px;color:var(--text-tertiary)">View Query *</label>
                                <textarea name="viewQuery" placeholder="SELECT * FROM `dataset.table` WHERE active = true" rows="3" style="width:100%;padding:9px 12px;border:1px solid var(--border);border-radius:6px;font-size:12px;font-family:var(--font-mono);background:var(--surface);color:var(--text);resize:vertical" />
                            </div>
                            <label style="display:block;margin-bottom:4px;font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:0.5px;color:var(--text-tertiary)">Schema <span style="font-weight:400;text-transform:none;letter-spacing:0;color:var(--text-tertiary);font-size:10px">(name:TYPE[:MODE], one per line)</span></label>
                            <textarea name="columns" placeholder="id:INT64&#10;name:STRING&#10;email:STRING:REQUIRED&#10;created_at:TIMESTAMP" rows="5" style="width:100%;padding:9px 12px;border:1px solid var(--border);border-radius:6px;margin-bottom:14px;font-size:12px;font-family:var(--font-mono);background:var(--surface);color:var(--text);resize:vertical" />
                            <details style="margin-bottom:14px;font-size:11px">
                                <summary style="cursor:pointer;font-weight:600;text-transform:uppercase;letter-spacing:0.5px;color:var(--text-tertiary);padding:4px 0">Partitioning & Clustering</summary>
                                <div style="display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:8px;padding-left:4px">
                                    <div>
                                        <label style="display:block;margin-bottom:4px;font-size:11px;color:var(--text-tertiary)">Partition type</label>
                                        <select name="partitionType" style="width:100%;padding:8px 10px;border:1px solid var(--border);border-radius:6px;font-size:12px;background:var(--surface);color:var(--text)">
                                            <option value="">None</option>
                                            <option value="DAY">DAY</option>
                                            <option value="HOUR">HOUR</option>
                                            <option value="MONTH">MONTH</option>
                                            <option value="YEAR">YEAR</option>
                                        </select>
                                    </div>
                                    <div>
                                        <label style="display:block;margin-bottom:4px;font-size:11px;color:var(--text-tertiary)">Partition field</label>
                                        <input name="partitionField" placeholder="event_date" style="width:100%;padding:8px 10px;border:1px solid var(--border);border-radius:6px;font-size:12px;background:var(--surface);color:var(--text)" />
                                    </div>
                                    <div style="grid-column:1/-1">
                                        <label style="display:block;margin-bottom:4px;font-size:11px;color:var(--text-tertiary)">Clustering columns (comma-separated)</label>
                                        <input name="clusteringFields" placeholder="user_id, event_type" style="width:100%;padding:8px 10px;border:1px solid var(--border);border-radius:6px;font-size:12px;background:var(--surface);color:var(--text)" />
                                    </div>
                                </div>
                            </details>
                            <label style="display:block;margin-bottom:4px;font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:0.5px;color:var(--text-tertiary)">Description</label>
                            <input name="description" placeholder="Optional description" style="width:100%;padding:9px 12px;border:1px solid var(--border);border-radius:6px;margin-bottom:18px;font-size:13px;background:var(--surface);color:var(--text)" />
                            <div style="display:flex;gap:8px;justify-content:flex-end">
                                <button type="button" onClick={() => setShowCreateTable(false)} class="btn-cancel">Cancel</button>
                                <button type="submit" disabled={bqActionLoading()} class="btn-primary-sm">{bqActionLoading() ? 'Creating…' : 'Create'}</button>
                            </div>
                        </form>
                    </div>
                </div>
            </Show>

            {/* Edit Row Dialog */}
            <Show when={showEditRow()}>
                <div class="modal-overlay" onClick={() => { setShowEditRow(false); setEditingRow(null); }}>
                    <div class="modal-content" onClick={e => e.stopPropagation()} style="max-width:600px;max-height:80vh;overflow-y:auto">
                        <h3 style="margin:0 0 16px 0;font-size:16px;font-weight:600">Edit Row in <span style="color:var(--primary)">{selectedDataset()}.{selectedTable()}</span></h3>
                        <Show when={editingRow()}>
                            <form onSubmit={e => { e.preventDefault(); handleUpdateRow(Object.fromEntries(new FormData(e.target))); }}>
                                <For each={tableData()?.columns || []}>{(col) => (
                                    <div style="margin-bottom:10px">
                                        <label style="display:block;margin-bottom:4px;font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:0.5px;color:var(--text-tertiary)">{col}</label>
                                        <input name={col} value={editingRow()?.[col] ?? ''} style="width:100%;padding:9px 12px;border:1px solid var(--border);border-radius:6px;font-size:13px;background:var(--surface);color:var(--text)" />
                                    </div>
                                )}</For>
                                <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:16px">
                                    <button type="button" onClick={() => { setShowEditRow(false); setEditingRow(null); }} class="btn-cancel">Cancel</button>
                                    <button type="submit" disabled={bqActionLoading()} class="btn-primary-sm">{bqActionLoading() ? 'Updating…' : 'Update'}</button>
                                </div>
                            </form>
                        </Show>
                    </div>
                </div>
            </Show>

            {/* MERGE (Upsert) Dialog */}
            <Show when={showMerge()}>
                <div class="modal-overlay" onClick={() => setShowMerge(false)}>
                    <div class="modal-content" onClick={e => e.stopPropagation()} style="max-width:600px;max-height:85vh;overflow-y:auto">
                        <h3 style="margin:0 0 16px 0;font-size:16px;font-weight:600">MERGE into <span style="color:var(--primary)">{selectedDataset()}.{selectedTable()}</span></h3>
                        <form onSubmit={e => { e.preventDefault(); handleMerge(Object.fromEntries(new FormData(e.target))); }}>
                            <label style="display:block;margin-bottom:4px;font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:0.5px;color:var(--text-tertiary)">Source Query (subquery or table reference)</label>
                            <textarea name="sourceQuery" required placeholder="SELECT id, name, updated_at FROM `other_dataset.source_table`" rows="3" style="width:100%;padding:9px 12px;border:1px solid var(--border);border-radius:6px;margin-bottom:14px;font-size:12px;font-family:var(--font-mono);background:var(--surface);color:var(--text);resize:vertical" />
                            <label style="display:block;margin-bottom:4px;font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:0.5px;color:var(--text-tertiary)">MERGE Condition (ON clause)</label>
                            <input name="mergeCondition" required placeholder="T.id = S.id" style="width:100%;padding:9px 12px;border:1px solid var(--border);border-radius:6px;margin-bottom:14px;font-size:13px;font-family:var(--font-mono);background:var(--surface);color:var(--text)" />
                            <details style="margin-bottom:14px">
                                <summary style="cursor:pointer;font-weight:600;font-size:11px;text-transform:uppercase;letter-spacing:0.5px;color:var(--text-tertiary);padding:4px 0">WHEN MATCHED — Update (T.col = S.source_col, comma-separated)</summary>
                                <input name="updateSet" placeholder="name = S.name, updated_at = S.updated_at" style="width:100%;padding:9px 12px;border:1px solid var(--border);border-radius:6px;margin-top:8px;font-size:12px;font-family:var(--font-mono);background:var(--surface);color:var(--text)" />
                            </details>
                            <details style="margin-bottom:18px">
                                <summary style="cursor:pointer;font-weight:600;font-size:11px;text-transform:uppercase;letter-spacing:0.5px;color:var(--text-tertiary);padding:4px 0">WHEN NOT MATCHED — Insert (col = S.source_col, comma-separated)</summary>
                                <input name="insertValues" placeholder="id = S.id, name = S.name, created_at = S.created_at" style="width:100%;padding:9px 12px;border:1px solid var(--border);border-radius:6px;margin-top:8px;font-size:12px;font-family:var(--font-mono);background:var(--surface);color:var(--text)" />
                            </details>
                            <div style="display:flex;gap:8px;justify-content:flex-end">
                                <button type="button" onClick={() => setShowMerge(false)} class="btn-cancel">Cancel</button>
                                <button type="submit" disabled={bqActionLoading()} class="btn-primary-sm">{bqActionLoading() ? 'Merging…' : 'MERGE'}</button>
                            </div>
                        </form>
                    </div>
                </div>
            </Show>
        </div>
    );
}

// -- Cloud Scheduler View --
function CloudSchedulerView(props) {
    const d = () => props.data();

    const stateBadgeClass = (state) => {
        const s = (state || '').toLowerCase();
        if (s === 'enabled') return 'badge-healthy';
        if (s === 'paused') return 'badge-warning';
        if (s === 'disabled') return 'badge-unhealthy';
        return 'badge-neutral';
    };

    return (
        <div>
            <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px">
                <div />
                <Show when={props.onAdd}>
                    <button onClick={() => props.onAdd('Create Job', [
                        { name: 'name', type: 'text' },
                        { name: 'schedule', type: 'text' },
                        { name: 'timeZone', type: 'text' },
                        { name: 'targetUrl', type: 'text' },
                    ], async (formData) => {
                        await api.mutate('cloudscheduler', 'jobs', formData);
                    })} style="padding:6px 14px;border:none;border-radius:4px;background:var(--accent, #4285f4);color:white;cursor:pointer;font-size:13px">
                        + Create Job
                    </button>
                </Show>
            </div>
            <Show when={d() && d().jobs && d().jobs.length > 0} fallback={
                <div class="empty-state">
                    <div class="empty-state-icon">{'\u2205'}</div>
                    <div class="empty-state-title">No jobs found</div>
                    <div class="empty-state-text">Create a scheduled job to see it here.</div>
                </div>
            }>
                <div class="data-table-wrapper">
                    <table class="data-table">
                        <thead>
                            <tr>
                                <th>Job Name</th>
                                <th>Schedule</th>
                                <th>Time Zone</th>
                                <th>State</th>
                                <th>Next Execution</th>
                                <th>Actions</th>
                            </tr>
                        </thead>
                        <tbody>
                            <For each={d().jobs}>
                                {(job) => {
                                    const nameParts = (job.name || '').split('/');
                                    const displayName = nameParts[nameParts.length - 1];
                                    return (
                                        <tr>
                                            <td style={{ "font-weight": "500" }}>{displayName}</td>
                                            <td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>{job.schedule || '--'}</td>
                                            <td>{job.timeZone || 'UTC'}</td>
                                            <td>
                                                <span class={`badge ${stateBadgeClass(job.state)}`}>
                                                    {job.state || 'UNKNOWN'}
                                                </span>
                                            </td>
                                            <td>{job.nextExecutionTime ? formatDate(job.nextExecutionTime) : '--'}</td>
                                            <td>
                                                <Show when={props.onDelete}>
                                                    <button onClick={() => props.onDelete('Delete job "' + displayName + '"?', async () => {
                                                        await api.mutate('cloudscheduler', 'jobs/delete', { name: job.name });
                                                    })} style="padding:4px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:#ea4335;cursor:pointer;font-size:11px" title="Delete">Del</button>
                                                </Show>
                                            </td>
                                        </tr>
                                    );
                                }}
                            </For>
                        </tbody>
                    </table>
                </div>
            </Show>
        </div>
    );
}

// -- Cloud Functions View --
function CloudFunctionsView(props) {
    const d = () => props.data();

    const stateBadgeClass = (state) => {
        const s = (state || '').toLowerCase();
        if (s === 'active') return 'badge-healthy';
        if (s === 'deploying') return 'badge-warning';
        if (s === 'failed') return 'badge-unhealthy';
        return 'badge-neutral';
    };

    return (
        <div>
            <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px">
                <div />
                <Show when={props.onAdd}>
                    <button onClick={() => props.onAdd('Create Function', [
                        { name: 'name', type: 'text' },
                        { name: 'runtime', type: 'text' },
                        { name: 'entryPoint', type: 'text' },
                    ], async (formData) => {
                        await api.mutate('cloudfunctions', 'functions', formData);
                    })} style="padding:6px 14px;border:none;border-radius:4px;background:var(--accent, #4285f4);color:white;cursor:pointer;font-size:13px">
                        + Create Function
                    </button>
                </Show>
            </div>
            <Show when={d() && d().functions && d().functions.length > 0} fallback={
                <div class="empty-state">
                    <div class="empty-state-icon">{'\u2205'}</div>
                    <div class="empty-state-title">No functions found</div>
                    <div class="empty-state-text">Create a Cloud Function to see it here.</div>
                </div>
            }>
                <div class="data-table-wrapper">
                    <table class="data-table">
                        <thead>
                            <tr>
                                <th>Function Name</th>
                                <th>Runtime</th>
                                <th>Entry Point</th>
                                <th>State</th>
                                <th>Created</th>
                                <th>Actions</th>
                            </tr>
                        </thead>
                        <tbody>
                            <For each={d().functions}>
                                {(fn) => {
                                    const nameParts = (fn.name || '').split('/');
                                    const displayName = nameParts[nameParts.length - 1];
                                    return (
                                        <tr>
                                            <td style={{ "font-weight": "500" }}>{displayName}</td>
                                            <td><span class="badge badge-neutral">{fn.runtime || '--'}</span></td>
                                            <td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>{fn.entryPoint || '--'}</td>
                                            <td>
                                                <span class={`badge ${stateBadgeClass(fn.state)}`}>
                                                    {fn.state || 'UNKNOWN'}
                                                </span>
                                            </td>
                                            <td>{fn.createdAt ? formatDate(fn.createdAt) : '--'}</td>
                                            <td>
                                                <Show when={props.onDelete}>
                                                    <button onClick={() => props.onDelete('Delete function "' + displayName + '"?', async () => {
                                                        await api.mutate('cloudfunctions', 'functions/delete', { name: fn.name });
                                                    })} style="padding:4px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:#ea4335;cursor:pointer;font-size:11px" title="Delete">Del</button>
                                                </Show>
                                            </td>
                                        </tr>
                                    );
                                }}
                            </For>
                        </tbody>
                    </table>
                </div>
            </Show>
        </div>
    );
}

// -- AlloyDB View --
function AlloyDBView(props) {
    const d = () => props.data();
    const [selectedCluster, setSelectedCluster] = createSignal(null);
    const [selectedDatabase, setSelectedDatabase] = createSignal(null);
    const [selectedTable, setSelectedTable] = createSignal(null);
    const [instancesData, setInstancesData] = createSignal([]);
    const [databasesData, setDatabasesData] = createSignal([]);
    const [tablesData, setTablesData] = createSignal([]);
    const [rowsData, setRowsData] = createSignal(null);
    const [subLoading, setSubLoading] = createSignal(false);

    const clusters = () => d()?.clusters || [];
    const clusterId = (cluster) => cluster.clusterId || (cluster.name || '').split('/').pop();
    const displayCluster = (cluster) => clusterId(cluster) || '--';

    const stateBadgeClass = (state) => {
        const s = (state || '').toLowerCase();
        if (s === 'ready' || s === 'running') return 'badge-healthy';
        if (s === 'creating' || s === 'restoring') return 'badge-warning';
        if (s === 'stopped' || s === 'failed') return 'badge-unhealthy';
        return 'badge-neutral';
    };

    const updateSubpath = (cluster, database, table) => {
        if (props.onSubpathChange) {
            const parts = [];
            if (cluster) {
                parts.push(cluster);
                if (database) {
                    parts.push(database);
                    if (table) parts.push(table);
                }
            }
            props.onSubpathChange(parts);
        }
    };

    const selectCluster = async (cluster) => {
        const id = typeof cluster === 'string' ? cluster : clusterId(cluster);
        if (!id) return;
        setSelectedCluster(id);
        setSelectedDatabase(null);
        setSelectedTable(null);
        setRowsData(null);
        setTablesData([]);
        updateSubpath(id, null, null);
        setSubLoading(true);
        try {
            const [instances, databases] = await Promise.all([
                api.browse('alloydb', 'instances/' + encodeURIComponent(id)),
                api.browse('alloydb', 'databases/' + encodeURIComponent(id)),
            ]);
            setInstancesData(instances.instances || []);
            setDatabasesData(databases.databases || []);
        } catch {
            setInstancesData([]);
            setDatabasesData([]);
        } finally {
            setSubLoading(false);
        }
    };

    const selectDatabase = async (databaseName) => {
        const cluster = selectedCluster();
        if (!cluster || !databaseName) return;
        setSelectedDatabase(databaseName);
        setSelectedTable(null);
        setRowsData(null);
        updateSubpath(cluster, databaseName, null);
        setSubLoading(true);
        try {
            const result = await api.browse('alloydb', 'tables/' + encodeURIComponent(cluster) + '/' + encodeURIComponent(databaseName));
            setTablesData(result.tables || []);
        } catch {
            setTablesData([]);
        } finally {
            setSubLoading(false);
        }
    };

    const selectTable = async (tableName) => {
        const cluster = selectedCluster();
        const database = selectedDatabase();
        if (!cluster || !database || !tableName) return;
        setSelectedTable(tableName);
        updateSubpath(cluster, database, tableName);
        setSubLoading(true);
        try {
            const result = await api.browse('alloydb', 'rows/' + encodeURIComponent(cluster) + '/' + encodeURIComponent(database) + '/' + encodeURIComponent(tableName));
            setRowsData(result);
        } catch {
            setRowsData({ columns: [], rows: [] });
        } finally {
            setSubLoading(false);
        }
    };

    const goBackToClusters = () => {
        setSelectedCluster(null);
        setSelectedDatabase(null);
        setSelectedTable(null);
        setInstancesData([]);
        setDatabasesData([]);
        setTablesData([]);
        setRowsData(null);
        updateSubpath(null, null, null);
    };

    const goBackToDatabases = () => {
        setSelectedDatabase(null);
        setSelectedTable(null);
        setTablesData([]);
        setRowsData(null);
        updateSubpath(selectedCluster(), null, null);
    };

    const goBackToTables = () => {
        setSelectedTable(null);
        setRowsData(null);
        updateSubpath(selectedCluster(), selectedDatabase(), null);
    };

    createEffect(() => {
        const raw = d();
        if (!raw) {
            goBackToClusters();
        }
    });

    createEffect(() => {
        const sp = typeof props.subpath === 'function' ? props.subpath() : props.subpath;
        if (!sp || sp.length === 0 || clusters().length === 0) return;
        const [cluster, database, table] = sp;
        if (cluster && untrack(() => selectedCluster()) !== cluster) {
            selectCluster(cluster).then(() => {
                if (database) {
                    selectDatabase(database).then(() => {
                        if (table) selectTable(table);
                    });
                }
            });
        }
    });

    const breadcrumbs = createMemo(() => {
        const crumbs = [{ label: 'Clusters', onClick: goBackToClusters, active: !selectedCluster() }];
        if (selectedCluster()) crumbs.push({ label: selectedCluster(), onClick: goBackToDatabases, active: !selectedDatabase() });
        if (selectedDatabase()) crumbs.push({ label: selectedDatabase(), onClick: goBackToTables, active: !selectedTable() });
        if (selectedTable()) crumbs.push({ label: selectedTable(), active: true });
        return crumbs;
    });

    const rowColumns = () => rowsData()?.columns || [];
    const rows = () => rowsData()?.rows || [];

    return (
        <div>
            <Show when={selectedCluster()}>
                <DataBreadcrumb crumbs={breadcrumbs()} />
            </Show>
            <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px">
                <div />
                <Show when={props.onAdd}>
                    <button onClick={() => props.onAdd('Create Cluster', [
                        { name: 'name', type: 'text' },
                        { name: 'databaseVersion', type: 'text' },
                    ], async (formData) => {
                        await api.mutate('alloydb', 'clusters', formData);
                    })} style="padding:6px 14px;border:none;border-radius:4px;background:var(--accent, #4285f4);color:white;cursor:pointer;font-size:13px">
                        + Create Cluster
                    </button>
                </Show>
            </div>
            <Show when={clusters().length > 0} fallback={
                <div class="empty-state">
                    <div class="empty-state-icon">{'\u2205'}</div>
                    <div class="empty-state-title">No clusters found</div>
                    <div class="empty-state-text">Create an AlloyDB cluster to see it here. Connect via localhost:5432 using the cluster database.</div>
                </div>
            }>
                <Show when={!selectedCluster()}>
                    <div class="data-table-wrapper">
                        <table class="data-table">
                            <thead>
                                <tr>
                                    <th>Cluster</th>
                                    <th>State</th>
                                    <th>Database</th>
                                    <th>Created</th>
                                    <th>Actions</th>
                                </tr>
                            </thead>
                            <tbody>
                                <For each={clusters()}>
                                    {(cluster) => {
                                        const name = displayCluster(cluster);
                                        return (
                                            <tr>
                                                <td style={{ "font-weight": "500" }}>
                                                    <button class="link-button" onClick={() => selectCluster(cluster)}>{name}</button>
                                                </td>
                                                <td><span class={`badge ${stateBadgeClass(cluster.state)}`}>{cluster.state || 'UNKNOWN'}</span></td>
                                                <td style={{ "font-family": "var(--font-mono)", "font-size": "11px" }}>{cluster.databaseName || '--'}</td>
                                                <td>{cluster.createdAt ? formatDate(cluster.createdAt) : '--'}</td>
                                                <td>
                                                    <Show when={props.onDelete}>
                                                        <button onClick={() => props.onDelete('Delete cluster "' + name + '"?', async () => {
                                                            await api.mutate('alloydb', 'clusters/delete', { name: cluster.name });
                                                        })} style="padding:4px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:#ea4335;cursor:pointer;font-size:11px" title="Delete">Del</button>
                                                    </Show>
                                                </td>
                                            </tr>
                                        );
                                    }}
                                </For>
                            </tbody>
                        </table>
                    </div>
                </Show>
                <Show when={selectedCluster() && !selectedDatabase()}>
                    <Show when={subLoading()} fallback={
                        <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:16px">
                            <div>
                                <h3 style="margin:0 0 10px 0">Instances</h3>
                                <div class="data-table-wrapper">
                                    <table class="data-table">
                                        <thead><tr><th>Instance</th><th>Type</th><th>State</th></tr></thead>
                                        <tbody>
                                            <For each={instancesData()} fallback={<tr><td colspan="3">No instances</td></tr>}>
                                                {(instance) => <tr><td>{instance.instanceId}</td><td>{instance.instanceType || '--'}</td><td><span class={`badge ${stateBadgeClass(instance.state)}`}>{instance.state || 'UNKNOWN'}</span></td></tr>}
                                            </For>
                                        </tbody>
                                    </table>
                                </div>
                            </div>
                            <div>
                                <h3 style="margin:0 0 10px 0">Databases</h3>
                                <div class="data-table-wrapper">
                                    <table class="data-table">
                                        <thead><tr><th>Database</th><th>Created</th></tr></thead>
                                        <tbody>
                                            <For each={databasesData()} fallback={<tr><td colspan="2">No databases</td></tr>}>
                                                {(database) => <tr><td><button class="link-button" onClick={() => selectDatabase(database.databaseName || database.name)}>{database.databaseName || database.name}</button></td><td>{database.createdAt ? formatDate(database.createdAt) : '--'}</td></tr>}
                                            </For>
                                        </tbody>
                                    </table>
                                </div>
                            </div>
                        </div>
                    }>
                        <div class="loading-state"><div class="loading-spinner" /> Loading…</div>
                    </Show>
                </Show>
                <Show when={selectedDatabase() && !selectedTable()}>
                    <Show when={subLoading()} fallback={
                        <div class="data-table-wrapper">
                            <table class="data-table">
                                <thead><tr><th>Table</th><th>Columns</th></tr></thead>
                                <tbody>
                                    <For each={tablesData()} fallback={<tr><td colspan="2">No tables</td></tr>}>
                                        {(table) => <tr><td><button class="link-button" onClick={() => selectTable(table.name)}>{table.name}</button></td><td>{(table.columns || []).map(c => c.name).join(', ')}</td></tr>}
                                    </For>
                                </tbody>
                            </table>
                        </div>
                    }>
                        <div class="loading-state"><div class="loading-spinner" /> Loading…</div>
                    </Show>
                </Show>
                <Show when={selectedTable()}>
                    <Show when={subLoading()} fallback={
                        <div class="data-table-wrapper">
                            <table class="data-table">
                                <thead><tr><For each={rowColumns()}>{(column) => <th>{column}</th>}</For></tr></thead>
                                <tbody>
                                    <For each={rows()} fallback={<tr><td colspan={Math.max(1, rowColumns().length)}>No rows</td></tr>}>
                                        {(row) => <tr><For each={rowColumns()}>{(column) => <td>{String(row[column] ?? '')}</td>}</For></tr>}
                                    </For>
                                </tbody>
                            </table>
                        </div>
                    }>
                        <div class="loading-state"><div class="loading-spinner" /> Loading…</div>
                    </Show>
                </Show>
            </Show>
        </div>
    );
}

// -- Dataproc View --
function DataprocView(props) {
    const d = () => props.data();
    const [showJobs, setShowJobs] = createSignal(false);
    const [jobsData, setJobsData] = createSignal(null);
    const [jobsLoading, setJobsLoading] = createSignal(false);

    const statusBadgeClass = (status) => {
        const s = (status || '').toLowerCase();
        if (s === 'running' || s === 'done') return 'badge-healthy';
        if (s === 'pending' || s === 'setup') return 'badge-warning';
        if (s === 'error' || s === 'cancelled' || s === 'failed') return 'badge-unhealthy';
        return 'badge-neutral';
    };

    const loadJobs = async () => {
        setShowJobs(true);
        setJobsLoading(true);
        try {
            const data = await api.browse('dataproc', 'jobs');
            setJobsData(data);
        } catch (e) { setJobsData(null); }
        finally { setJobsLoading(false); }
    };

    return (
        <div>
            <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px">
                <div style="display:flex;gap:8px;align-items:center">
                    <button onClick={() => setShowJobs(false)} class="btn btn-sm">
                        Clusters
                    </button>
                    <button onClick={loadJobs} class="btn btn-sm">
                        Jobs
                    </button>
                </div>
                <Show when={props.onAdd}>
                    <button onClick={() => props.onAdd('Create Cluster', [
                        { name: 'name', type: 'text' },
                        { name: 'imageVersion', type: 'text' },
                    ], async (formData) => {
                        await api.mutate('dataproc', 'clusters', formData);
                    })} style="padding:6px 14px;border:none;border-radius:4px;background:var(--accent, #4285f4);color:white;cursor:pointer;font-size:13px">
                        + Create Cluster
                    </button>
                </Show>
            </div>

            <Show when={!showJobs()}>
                <Show when={d() && d().clusters && d().clusters.length > 0} fallback={
                    <div class="empty-state">
                        <div class="empty-state-icon">{'\u2205'}</div>
                        <div class="empty-state-title">No clusters found</div>
                        <div class="empty-state-text">Create a Dataproc cluster to see it here.</div>
                    </div>
                }>
                    <div class="data-table-wrapper">
                        <table class="data-table">
                            <thead>
                                <tr>
                                    <th>Cluster Name</th>
                                    <th>Region</th>
                                    <th>Status</th>
                                    <th>Created</th>
                                    <th>Actions</th>
                                </tr>
                            </thead>
                            <tbody>
                                <For each={d().clusters}>
                                    {(cluster) => (
                                        <tr>
                                            <td style={{ "font-weight": "500" }}>{cluster.name}</td>
                                            <td>{cluster.region || '--'}</td>
                                            <td>
                                                <span class={`badge ${statusBadgeClass(cluster.status)}`}>
                                                    {cluster.status || 'UNKNOWN'}
                                                </span>
                                            </td>
                                            <td>{cluster.createdAt ? formatDate(cluster.createdAt) : '--'}</td>
                                            <td>
                                                <Show when={props.onDelete}>
                                                    <button onClick={() => props.onDelete('Delete cluster "' + cluster.name + '"?', async () => {
                                                        await api.mutate('dataproc', 'clusters/delete', { name: cluster.name });
                                                    })} style="padding:4px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:#ea4335;cursor:pointer;font-size:11px" title="Delete">Del</button>
                                                </Show>
                                            </td>
                                        </tr>
                                    )}
                                </For>
                            </tbody>
                        </table>
                    </div>
                </Show>
            </Show>

            <Show when={showJobs()}>
                <Show when={jobsLoading()}>
                    <div class="loading-state"><div class="loading-spinner" /> Loading jobs…</div>
                </Show>
                <Show when={!jobsLoading()}>
                    <Show when={jobsData() && jobsData().jobs && jobsData().jobs.length > 0} fallback={
                        <div class="empty-state">
                            <div class="empty-state-icon">{'\u2205'}</div>
                            <div class="empty-state-title">No jobs found</div>
                            <div class="empty-state-text">Submit a Spark job to see it here.</div>
                        </div>
                    }>
                        <div class="data-table-wrapper">
                            <table class="data-table">
                                <thead>
                                    <tr>
                                        <th>Job ID</th>
                                        <th>Cluster</th>
                                        <th>Type</th>
                                        <th>Status</th>
                                        <th>Created</th>
                                        <th>Output</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    <For each={jobsData().jobs}>
                                        {(job) => (
                                            <tr>
                                                <td style={{ "font-weight": "500", "font-size": "12px" }}>{job.jobId}</td>
                                                <td>{job.clusterName || '--'}</td>
                                                <td><span class="badge badge-neutral">{job.jobType || '--'}</span></td>
                                                <td>
                                                    <span class={`badge ${statusBadgeClass(job.status)}`}>
                                                        {job.status || 'UNKNOWN'}
                                                    </span>
                                                </td>
                                                <td>{job.createdAt ? formatDate(job.createdAt) : '--'}</td>
                                                <td style={{ "max-width": "200px", "overflow": "hidden", "text-overflow": "ellipsis", "white-space": "nowrap", "font-size": "11px" }}>
                                                    {job.driverOutputPath || '--'}
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

// -- Cloud IAM View --
function CloudIAMView(props) {
    const d = () => props.data();

    return (
        <div>
            <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px">
                <div />
                <Show when={props.onAdd}>
                    <button onClick={() => props.onAdd('Create Policy', [
                        { name: 'resource', type: 'text' },
                        { name: 'role', type: 'text' },
                        { name: 'members', type: 'text' },
                    ], async (formData) => {
                        await api.mutate('cloudiam', 'policies', formData);
                    })} style="padding:6px 14px;border:none;border-radius:4px;background:var(--accent, #4285f4);color:white;cursor:pointer;font-size:13px">
                        + Create Policy
                    </button>
                </Show>
            </div>
            <Show when={d() && d().policies && d().policies.length > 0} fallback={
                <div class="empty-state">
                    <div class="empty-state-icon">{'\u2205'}</div>
                    <div class="empty-state-title">No policies found</div>
                    <div class="empty-state-text">Create an IAM policy to see it here. All testIamPermissions calls return ALLOW.</div>
                </div>
            }>
                <div class="data-table-wrapper">
                    <table class="data-table">
                        <thead>
                            <tr>
                                <th>Resource Type</th>
                                <th>Resource ID</th>
                                <th>Policy</th>
                                <th>Actions</th>
                            </tr>
                        </thead>
                        <tbody>
                            <For each={d().policies}>
                                {(policy) => (
                                    <tr>
                                        <td><span class="badge badge-neutral">{policy.resourceType}</span></td>
                                        <td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>{policy.resourceId}</td>
                                        <td>{policy.policy || '--'}</td>
                                        <td>
                                            <Show when={props.onDelete}>
                                                <button onClick={() => props.onDelete('Delete policy for "' + policy.resourceId + '"?', async () => {
                                                    await api.mutate('cloudiam', 'policies/delete', { resourceType: policy.resourceType, resourceId: policy.resourceId });
                                                })} style="padding:4px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:#ea4335;cursor:pointer;font-size:11px" title="Delete">Del</button>
                                            </Show>
                                        </td>
                                    </tr>
                                )}
                            </For>
                        </tbody>
                    </table>
                </div>
            </Show>
        </div>
    );
}

// -- Secret Manager View --
function SecretManagerView(props) {
    const d = () => props.data();
    return (
        <div>
            <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px">
                <div />
                <Show when={props.onAdd}>
                    <button onClick={() => props.onAdd('Create Secret', [
                        { name: 'name', type: 'text' },
                        { name: 'value', type: 'textarea' }
                    ], async (formData) => {
                        await api.mutate('secretmanager', 'secrets', formData);
                    })} style="padding:6px 14px;border:none;border-radius:4px;background:var(--accent, #4285f4);color:white;cursor:pointer;font-size:13px">
                        + Create Secret
                    </button>
                </Show>
            </div>
            <Show when={d() && d().secrets && d().secrets.length > 0} fallback={
                <div class="empty-state">
                    <div class="empty-state-icon">{'\u2205'}</div>
                    <div class="empty-state-title">No secrets found</div>
                    <div class="empty-state-text">Create a secret to see it here.</div>
                </div>
            }>
                <div class="data-table-wrapper">
                    <table class="data-table">
                        <thead>
                            <tr>
                                <th>Secret Name</th>
                                <th>Versions</th>
                                <th>Created</th>
                                <th>Actions</th>
                            </tr>
                        </thead>
                        <tbody>
                            <For each={d().secrets}>
                                {(secret) => (
                                    <tr>
                                        <td style={{ "font-weight": "500" }}>
                                            <img src="/icons/secretmanager.svg" alt="" width="14" height="14" style={{ "margin-right": "6px", "vertical-align": "middle" }} />
                                            {secret.name}
                                        </td>
                                        <td>
                                            <Show when={secret.versionCount != null} fallback="--">
                                                <span class="badge badge-neutral">{secret.versionCount}</span>
                                            </Show>
                                        </td>
                                        <td>{formatDate(secret.created_at || secret.createTime)}</td>
                                        <td>
                                            <Show when={props.onDelete}>
                                                <button onClick={() => props.onDelete('Delete secret "' + secret.name + '"?', async () => {
                                                    await api.mutate('secretmanager', 'secrets/delete', { name: secret.name });
                                                })} style="padding:4px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:#ea4335;cursor:pointer;font-size:11px" title="Delete">Del</button>
                                            </Show>
                                        </td>
                                    </tr>
                                )}
                            </For>
                        </tbody>
                    </table>
                </div>
            </Show>
        </div>
    );
}

// -- Cloud Tasks View --
function CloudTasksView(props) {
    const d = () => props.data();

    const stateClass = (state) => {
        const s = (state || '').toLowerCase();
        if (s === 'running' || s === 'active') return 'badge-healthy';
        if (s === 'paused') return 'badge-warning';
        if (s === 'disabled') return 'badge-unhealthy';
        return 'badge-neutral';
    };

    return (
        <div>
            <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px">
                <div />
                <Show when={props.onAdd}>
                    <button onClick={() => props.onAdd('Create Queue', [
                        { name: 'name', type: 'text' },
                    ], async (formData) => {
                        await api.mutate('cloudtasks', 'queues', formData);
                    })} style="padding:6px 14px;border:none;border-radius:4px;background:var(--accent, #4285f4);color:white;cursor:pointer;font-size:13px">
                        + Create Queue
                    </button>
                </Show>
            </div>
            <Show when={d() && d().queues && d().queues.length > 0} fallback={
                <div class="empty-state">
                    <div class="empty-state-icon">{'\u2205'}</div>
                    <div class="empty-state-title">No queues found</div>
                    <div class="empty-state-text">Create a task queue to see it here.</div>
                </div>
            }>
                <div class="data-table-wrapper">
                    <table class="data-table">
                        <thead>
                            <tr>
                                <th>Queue Name</th>
                                <th>State</th>
                                <th>Tasks</th>
                                <th>Actions</th>
                            </tr>
                        </thead>
                        <tbody>
                            <For each={d().queues}>
                                {(queue) => (
                                    <tr>
                                        <td style={{ "font-weight": "500" }}>{queue.name}</td>
                                        <td>
                                            <span class={`badge ${stateClass(queue.state)}`}>
                                                {queue.state || 'UNKNOWN'}
                                            </span>
                                        </td>
                                        <td>
                                            <Show when={queue.taskCount != null} fallback="--">
                                                {queue.taskCount}
                                            </Show>
                                        </td>
                                        <td>
                                            <Show when={props.onDelete}>
                                                <button onClick={() => props.onDelete('Delete queue "' + queue.name + '"?', async () => {
                                                    await api.mutate('cloudtasks', 'queues/delete', { name: queue.name });
                                                })} style="padding:4px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:#ea4335;cursor:pointer;font-size:11px" title="Delete">Del</button>
                                            </Show>
                                        </td>
                                    </tr>
                                )}
                            </For>
                        </tbody>
                    </table>
                </div>
            </Show>
        </div>
    );
}

// -- Logging View --
function LoggingView(props) {
    const d = () => props.data();

    const severityClass = (severity) => {
        const s = (severity || '').toUpperCase();
        if (s === 'ERROR' || s === 'CRITICAL' || s === 'ALERT' || s === 'EMERGENCY') return 'badge-unhealthy';
        if (s === 'WARNING') return 'badge-warning';
        if (s === 'INFO' || s === 'NOTICE') return 'badge-info';
        return 'badge-neutral';
    };

    return (
        <Show when={d() && d().entries && d().entries.length > 0} fallback={
            <div class="empty-state">
                <div class="empty-state-icon">{'\u2205'}</div>
                <div class="empty-state-title">No log entries found</div>
                <div class="empty-state-text">Log entries will appear here once services write logs.</div>
            </div>
        }>
            <div class="data-table-wrapper">
                <table class="data-table">
                    <thead>
                        <tr>
                            <th>Timestamp</th>
                            <th>Severity</th>
                            <th>Log Name</th>
                            <th>Payload</th>
                        </tr>
                    </thead>
                    <tbody>
                        <For each={d().entries}>
                            {(entry) => (
                                <tr>
                                    <td style={{ "white-space": "nowrap", "font-size": "12px" }}>
                                        {formatDate(entry.timestamp)}
                                    </td>
                                    <td>
                                        <span class={`badge ${severityClass(entry.severity)}`}>
                                            {entry.severity || 'DEFAULT'}
                                        </span>
                                    </td>
                                    <td style={{ "font-size": "12px", "color": "var(--text-secondary)" }}>
                                        {entry.log_name || '--'}
                                    </td>
                                    <td style={{ "max-width": "400px", "overflow": "hidden", "text-overflow": "ellipsis", "white-space": "nowrap" }}>
                                        {entry.text_payload || '--'}
                                    </td>
                                </tr>
                            )}
                        </For>
                    </tbody>
                </table>
            </div>
        </Show>
    );
}

// -- Monitoring View --
function MonitoringView(props) {
    const d = () => props.data();

    const formatLabels = (labels) => {
        if (!labels || typeof labels !== 'object') return '--';
        const entries = Object.entries(labels);
        if (entries.length === 0) return '--';
        return entries.map(([k, v]) => `${k}=${v}`).join(', ');
    };

    return (
        <Show when={d() && d().time_series && d().time_series.length > 0} fallback={
            <div class="empty-state">
                <div class="empty-state-icon">{'\u2205'}</div>
                <div class="empty-state-title">No time series found</div>
                <div class="empty-state-text">Metric data will appear here once services report metrics.</div>
            </div>
        }>
            <div class="data-table-wrapper">
                <table class="data-table">
                    <thead>
                        <tr>
                            <th>Metric Type</th>
                            <th>Resource Type</th>
                            <th>Metric Labels</th>
                        </tr>
                    </thead>
                    <tbody>
                        <For each={d().time_series}>
                            {(ts) => (
                                <tr>
                                    <td style={{ "font-weight": "500" }}>{ts.metric_type || '--'}</td>
                                    <td>{ts.resource_type || '--'}</td>
                                    <td style={{ "font-size": "12px", "color": "var(--text-secondary)" }}>
                                        {formatLabels(ts.metric_labels)}
                                    </td>
                                </tr>
                            )}
                        </For>
                    </tbody>
                </table>
            </div>
        </Show>
    );
}

// -- Spanner View (drill-down: instances -> databases -> tables -> data) --
function SpannerView(props) {
    const d = () => props.data();
    const [selectedInstance, setSelectedInstance] = createSignal(null);
    const [selectedDatabase, setSelectedDatabase] = createSignal(null);
    const [selectedTable, setSelectedTable] = createSignal(null);
    const [databases, setDatabases] = createSignal([]);
    const [ddlData, setDdlData] = createSignal(null);
    const [tableData, setTableData] = createSignal(null);
    const [subLoading, setSubLoading] = createSignal(false);
    const [showCreateInstance, setShowCreateInstance] = createSignal(false);
    const [showCreateDatabase, setShowCreateDatabase] = createSignal(false);
    const [showCreateTable, setShowCreateTable] = createSignal(false);
    const [createName, setCreateName] = createSignal('');
    const [createDdl, setCreateDdl] = createSignal('');
    const [createError, setCreateError] = createSignal(null);
    const [creating, setCreating] = createSignal(false);

    const [showDdlModal, setShowDdlModal] = createSignal(false);
    const [ddlModalText, setDdlModalText] = createSignal('');
    const [toast, setToast] = createSignal(null);

    // Sub-tab navigation: 'browse' | 'history' | 'stats'
    const [activeSubTab, setActiveSubTab] = createSignal('browse');
    const [historyEntries, setHistoryEntries] = createSignal([]);
    const [historyLoading, setHistoryLoading] = createSignal(false);
    const [historyOffset, setHistoryOffset] = createSignal(0);
    const [historyTotal, setHistoryTotal] = createSignal(0);
    const [historyHasMore, setHistoryHasMore] = createSignal(false);
    let historyFetchId = 0;

    const [statsData, setStatsData] = createSignal(null);
    const [statsLoading, setStatsLoading] = createSignal(false);

    // Auto-fetch stats when database changes to prevent stale data
    createEffect(() => {
        const inst = selectedInstance();
        const db = selectedDatabase();
        if (inst && db && activeSubTab() === 'stats') {
            fetchSpannerStats();
        }
    });

    const fetchSpannerStats = async () => {
        const inst = selectedInstance();
        const db = selectedDatabase();
        if (!inst || !db) return;
        setStatsLoading(true);
        setStatsData(null);
        try {
            const result = await api.spannerStats(inst, db);
            setStatsData(result);
        } catch (e) {
            setStatsData(null);
        } finally {
            setStatsLoading(false);
        }
    };

    const fetchQueryHistory = async (offset = 0) => {
        const id = ++historyFetchId;
        setHistoryLoading(true);
        try {
            const resp = await api.queryHistory('spanner', 50, offset);
            if (id === historyFetchId) {
                setHistoryEntries(resp.entries || []);
                setHistoryTotal(resp.total || 0);
                setHistoryHasMore(resp.has_more || false);
                setHistoryOffset(offset);
            }
        } catch (e) {
            if (id === historyFetchId) {
                setHistoryEntries([]);
                setHistoryTotal(0);
                setHistoryHasMore(false);
            }
        } finally {
            if (id === historyFetchId) {
                setHistoryLoading(false);
            }
        }
    };

    const loadMoreHistory = () => {
        fetchQueryHistory(historyOffset() + 50);
    };

    // Extract column definition block between first ( and its matching closing )
    const extractColumnsDef = (stmt) => {
        const start = stmt.indexOf('(');
        if (start === -1) return null;
        let depth = 0;
        for (let i = start; i < stmt.length; i++) {
            const ch = stmt[i];
            if (ch === '(') depth++;
            else if (ch === ')') {
                depth--;
                if (depth === 0) return stmt.substring(start + 1, i);
            }
        }
        return null;
    };

    const parseColumnTypesFromDdl = (tableName) => {
        const ddl = ddlData();
        if (!ddl || !ddl.statements) return {};
        for (const stmt of ddl.statements) {
            const cleanStmt = stmt.replace(/--.*$/gm, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/\s+/g, ' ').trim();
            const tableMatch = cleanStmt.match(new RegExp(`CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:\\w+\\.)?(?:["\`]?\\w+["\`]?\\.)?["\`]?${tableName}["\`]?\\b`, 'i'));
            if (!tableMatch) continue;
            const columnsDef = extractColumnsDef(cleanStmt);
            if (!columnsDef) return {};
            const types = {};
            let depth = 0;
            let current = '';
            const parts = [];
            for (let i = 0; i < columnsDef.length; i++) {
                const char = columnsDef[i];
                if (char === '(') depth++;
                else if (char === ')') depth--;

                if (char === ',' && depth === 0) {
                    parts.push(current.trim());
                    current = '';
                } else {
                    current += char;
                }
            }
            if (current.trim()) parts.push(current.trim());

            for (const part of parts) {
                if (/^(?:CONSTRAINT|PRIMARY\s+KEY|FOREIGN\s+KEY|UNIQUE|CHECK)\b/i.test(part)) {
                    continue;
                }
                const tokens = part.split(/\s+/);
                if (tokens.length >= 2) {
                    const colName = tokens[0].replace(/`/g, '');
                    const colType = tokens[1].toUpperCase();
                    types[colName] = colType;
                }
            }
            return types;
        }
        return {};
    };

    const getTableDdl = (tableName) => {
        const ddl = ddlData();
        if (!ddl || !ddl.statements) return '';
        const matches = [];
        const tableRegex = new RegExp(`CREATE\\s+TABLE\\s+${tableName}\\b`, 'i');
        const indexRegex = new RegExp(`CREATE\\s+(?:UNIQUE\\s+)?(?:NULL_FILTERED\\s+)?INDEX\\s+\\w+\\s+ON\\s+${tableName}\\b`, 'i');

        for (const stmt of ddl.statements) {
            const clean = stmt.replace(/--.*$/gm, '').replace(/\/\*[\s\S]*?\*\//g, '').trim();
            if (tableRegex.test(clean) || indexRegex.test(clean)) {
                matches.push(stmt.trim());
            }
        }
        return matches.join(';\n\n') + (matches.length > 0 ? ';' : '');
    };

    const getTableIndexes = (tableName) => {
        const ddl = ddlData();
        if (!ddl || !ddl.statements) return [];
        const indexes = [];
        const indexRegex = new RegExp(`CREATE\\s+(?:UNIQUE\\s+)?(?:NULL_FILTERED\\s+)?INDEX\\s+(\\w+)\\s+ON\\s+${tableName}\\b`, 'i');
        for (const stmt of ddl.statements) {
            const clean = stmt.replace(/--.*$/gm, '').replace(/\/\*[\s\S]*?\*\//g, '').trim();
            const match = clean.match(indexRegex);
            if (match) {
                indexes.push(match[1]);
            }
        }
        return indexes;
    };

    const getInterleavedChildren = (tableName) => {
        const ddl = ddlData();
        if (!ddl || !ddl.statements) return [];
        const children = [];
        const childRegex = new RegExp(`CREATE\\s+TABLE\\s+(\\w+)[^;]*?INTERLEAVE\\s+IN\\s+PARENT\\s+${tableName}\\b`, 'is');
        for (const stmt of ddl.statements) {
            const clean = stmt.replace(/--.*$/gm, '').replace(/\/\*[\s\S]*?\*\//g, '').trim();
            const match = clean.match(childRegex);
            if (match) {
                children.push(match[1]);
            }
        }
        return children;
    };

    const handleDeleteTable = async (tableName) => {
        if (!confirm(`Are you sure you want to delete table ${tableName}? This will drop all rows and schema definitions, and cannot be undone.`)) return;

        const children = getInterleavedChildren(tableName);
        if (children.length > 0) {
            setToast({
                message: `Cannot delete table "${tableName}" because it has interleaved child tables: ${children.join(', ')}. Delete those tables first.`,
                type: 'error'
            });
            setTimeout(() => setToast(null), 8000);
            return;
        }

        let intervalId;
        let timerId;
        const startTime = Date.now();

        setToast({ message: `Deleting table "${tableName}"...`, type: 'info', elapsed: 0 });

        timerId = setInterval(() => {
            setToast(t => t ? { ...t, elapsed: (Date.now() - startTime) / 1000 } : null);
        }, 100);

        try {
            const indexes = getTableIndexes(tableName);
            const statements = indexes.map(idx => `DROP INDEX ${idx}`);
            statements.push(`DROP TABLE ${tableName}`);

            await api.mutate('spanner', 'ddl', {
                instance: selectedInstance(),
                database: selectedDatabase(),
                statements: statements
            });

            if (selectedTable() === tableName) {
                setSelectedTable(null);
                updateSubpath(selectedInstance(), selectedDatabase(), null);
            }

            // Poll API to check if table is actually deleted from schema
            const maxPollTime = 10000; // 10 seconds

            intervalId = setInterval(async () => {
                try {
                    const result = await api.browse('spanner', 'instances/' + selectedInstance() + '/' + selectedDatabase());
                    const currentTables = parseTables(result);
                    if (!currentTables.includes(tableName)) {
                        // Success! Table is gone.
                        clearInterval(intervalId);
                        clearInterval(timerId);

                        setDdlData(result);
                        setToast({ message: `Table "${tableName}" successfully deleted!`, type: 'success' });

                        // Dismiss toast after 3 seconds
                        setTimeout(() => setToast(null), 3000);
                    } else if (Date.now() - startTime > maxPollTime) {
                        // Timeout
                        clearInterval(intervalId);
                        clearInterval(timerId);
                        setToast({ message: `Deletion of "${tableName}" timed out.`, type: 'error' });
                        setTimeout(() => setToast(null), 4000);
                    }
                } catch (err) {
                    clearInterval(intervalId);
                    clearInterval(timerId);
                    setToast({ message: `Error verifying deletion: ${err.message}`, type: 'error' });
                    setTimeout(() => setToast(null), 4000);
                }
            }, 300);

        } catch (e) {
            clearInterval(intervalId);
            clearInterval(timerId);
            setToast({ message: `Failed to delete table: ${e.message}`, type: 'error' });
            setTimeout(() => setToast(null), 4000);
        }
    };

    const handleAddMockRow = async () => {
        const cols = tableData()?.columns;
        if (!cols) return;
        try {
            const typesMap = parseColumnTypesFromDdl(selectedTable());
            const columnDefs = cols.map(colName => ({
                name: colName,
                type: typesMap[colName] || 'STRING'
            }));

            const mockRow = generateMockRow(columnDefs);

            await api.mutate('spanner', 'rows', {
                instance: selectedInstance(),
                database: selectedDatabase(),
                table: selectedTable(),
                columns: Object.keys(mockRow),
                values: [Object.values(mockRow)]
            });

            await selectTable(selectedTable());
        } catch (e) {
            alert("Failed to insert mock data: " + e.message);
        }
    };

    const handleCreateInstance = async () => {
        const name = createName().trim();
        if (!name) return;
        setCreating(true); setCreateError(null);
        try {
            await api.mutate('spanner', 'createInstance', { instance: name, displayName: name });
            setShowCreateInstance(false); setCreateName('');
            if (props.onRefresh) await props.onRefresh();
        } catch (e) { setCreateError(e.message); }
        finally { setCreating(false); }
    };

    const handleCreateDatabase = async () => {
        const name = createName().trim();
        if (!name) return;
        setCreating(true); setCreateError(null);
        try {
            await api.mutate('spanner', 'createDatabase', { instance: selectedInstance(), database: name });
            setShowCreateDatabase(false); setCreateName('');
            // Refresh databases
            const result = await api.browse('spanner', 'instances/' + selectedInstance());
            setDatabases(result.databases || []);
        } catch (e) { setCreateError(e.message); }
        finally { setCreating(false); }
    };

    const parseDdlStatements = (ddlText) => {
        // Strip block comments
        let cleanText = ddlText.replace(/\/\*[\s\S]*?\*\//g, '');

        // Strip single-line comments
        cleanText = cleanText.split('\n')
            .map(line => {
                const index = line.indexOf('--');
                if (index !== -1) {
                    const before = line.substring(0, index);
                    const quoteCount = (before.match(/'/g) || []).length;
                    if (quoteCount % 2 === 0) return before;
                }
                const indexSlash = line.indexOf('//');
                if (indexSlash !== -1) {
                    const before = line.substring(0, indexSlash);
                    const quoteCount = (before.match(/'/g) || []).length;
                    if (quoteCount % 2 === 0) return before;
                }
                return line;
            })
            .join('\n');

        const statements = [];
        let current = '';
        let inSingleQuote = false;
        let inDoubleQuote = false;

        for (let i = 0; i < cleanText.length; i++) {
            const char = cleanText[i];
            if (char === "'" && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (char === '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            }

            if (char === ';' && !inSingleQuote && !inDoubleQuote) {
                const stmt = current.trim();
                if (stmt) statements.push(stmt);
                current = '';
            } else {
                current += char;
            }
        }
        const finalStmt = current.trim();
        if (finalStmt) statements.push(finalStmt);

        return statements;
    };

    const handleCreateTable = async () => {
        const ddl = createDdl().trim();
        if (!ddl) return;
        setCreating(true); setCreateError(null);
        try {
            const statements = parseDdlStatements(ddl);
            if (statements.length === 0) {
                throw new Error("No valid DDL statements found.");
            }
            await api.mutate('spanner', 'ddl', { instance: selectedInstance(), database: selectedDatabase(), statements });
            setShowCreateTable(false); setCreateDdl('');
            // Refresh tables
            const result = await api.browse('spanner', 'instances/' + selectedInstance() + '/' + selectedDatabase());
            setDdlData(result);
        } catch (e) { setCreateError(e.message); }
        finally { setCreating(false); }
    };

    const instances = () => {
        const raw = d();
        if (!raw) return [];
        return raw.instances || [];
    };

    // Extract table names from DDL statements
    const parseTables = (ddl) => {
        if (!ddl || !ddl.statements) return [];
        const tables = [];
        for (const stmt of ddl.statements) {
            const match = stmt.match(/CREATE\s+TABLE\s+(\w+)/i);
            if (match) tables.push(match[1]);
        }
        return tables;
    };

    const instanceName = (inst) => inst.displayName || inst.name?.split('/').pop() || '--';

    // Sync URL subpath when navigation state changes
    const updateSubpath = (inst, db, tbl) => {
        if (props.onSubpathChange) {
            const parts = [];
            if (inst) { parts.push(inst); if (db) { parts.push(db); if (tbl) parts.push(tbl); } }
            props.onSubpathChange(parts);
        }
    };

    const selectInstance = async (inst) => {
        const name = inst.name?.split('/').pop() || inst.displayName;
        setSelectedInstance(name);
        setSelectedDatabase(null);
        setSelectedTable(null);
        updateSubpath(name, null, null);
        setDatabases([]);
        setSubLoading(true);
        try {
            const result = await api.browse('spanner', 'instances/' + name);
            setDatabases(result.databases || []);
        } catch { setDatabases([]); }
        finally { setSubLoading(false); }
    };

    const selectDatabase = async (db) => {
        const dbName = db.name?.split('/').pop() || db;
        setSelectedDatabase(dbName);
        setSelectedTable(null);
        updateSubpath(selectedInstance(), dbName, null);
        setDdlData(null);
        setSubLoading(true);
        try {
            const result = await api.browse('spanner', 'instances/' + selectedInstance() + '/' + dbName);
            setDdlData(result);
        } catch { setDdlData(null); }
        finally { setSubLoading(false); }
    };

    const selectTable = async (tableName) => {
        setSelectedTable(tableName);
        updateSubpath(selectedInstance(), selectedDatabase(), tableName);
        setTableData(null);
        setSubLoading(true);
        try {
            const result = await api.browse('spanner', 'instances/' + selectedInstance() + '/' + selectedDatabase() + '/tables/' + tableName);
            setTableData(result);
        } catch { setTableData(null); }
        finally { setSubLoading(false); }
    };

    const goBackToInstances = () => {
        setSelectedInstance(null);
        setSelectedDatabase(null);
        setSelectedTable(null);
        updateSubpath(null, null, null);
    };

    const goBackToDatabases = () => {
        setSelectedDatabase(null);
        setSelectedTable(null);
        updateSubpath(selectedInstance(), null, null);
    };

    const goBackToTables = () => {
        setSelectedTable(null);
        updateSubpath(selectedInstance(), selectedDatabase(), null);
    };

    // Restore navigation from URL subpath on mount
    createEffect(() => {
        const sp = typeof props.subpath === 'function' ? props.subpath() : props.subpath;
        if (!sp || sp.length === 0) {
            setSelectedInstance(null);
            setSelectedDatabase(null);
            setSelectedTable(null);
            return;
        }
        const pendingIntervals = [];
        onCleanup(() => pendingIntervals.forEach(id => clearInterval(id)));

        const insts = instances();
        if (!insts || insts.length === 0) return;
        const [instName, dbName, tblName] = sp;
        const currentInst = untrack(() => selectedInstance());

        if (instName && !currentInst) {
            const inst = insts.find(i => (i.name?.split('/').pop() || i.displayName) === instName);
            if (inst) {
                selectInstance(inst).then(() => {
                    if (dbName) {
                        const checkDb = setInterval(() => {
                            const dbs = untrack(() => databases());
                            if (dbs.length > 0) {
                                clearInterval(checkDb);
                                const db = dbs.find(d => (d.name?.split('/').pop() || d) === dbName);
                                if (db) {
                                    selectDatabase(db).then(() => {
                                        if (tblName) {
                                            const checkTbl = setInterval(() => {
                                                const ddl = untrack(() => ddlData());
                                                if (ddl) {
                                                    clearInterval(checkTbl);
                                                    selectTable(tblName);
                                                }
                                            }, 100);
                                            pendingIntervals.push(checkTbl);
                                            setTimeout(() => clearInterval(checkTbl), 5000);
                                        }
                                    });
                                }
                            }
                        }, 100);
                        pendingIntervals.push(checkDb);
                        setTimeout(() => clearInterval(checkDb), 5000);
                    }
                });
            }
        } else if (!instName && currentInst) {
            setSelectedInstance(null);
            setSelectedDatabase(null);
            setSelectedTable(null);
        } else if (instName && currentInst === instName) {
            const currentDb = untrack(() => selectedDatabase());
            if (!dbName && currentDb) {
                setSelectedDatabase(null);
                setSelectedTable(null);
            } else if (dbName && !currentDb) {
                const checkDb = setInterval(() => {
                    const dbs = untrack(() => databases());
                    if (dbs.length > 0) {
                        clearInterval(checkDb);
                        const db = dbs.find(d => (d.name?.split('/').pop() || d) === dbName);
                        if (db) {
                            selectDatabase(db).then(() => {
                                if (tblName) {
                                    const checkTbl = setInterval(() => {
                                        const ddl = untrack(() => ddlData());
                                        if (ddl) {
                                            clearInterval(checkTbl);
                                            selectTable(tblName);
                                        }
                                    }, 100);
                                    pendingIntervals.push(checkTbl);
                                    setTimeout(() => clearInterval(checkTbl), 5000);
                                }
                            });
                        }
                    }
                }, 100);
                pendingIntervals.push(checkDb);
                setTimeout(() => clearInterval(checkDb), 5000);
            } else if (dbName && currentDb === dbName) {
                const currentTbl = untrack(() => selectedTable());
                if (!tblName && currentTbl) {
                    setSelectedTable(null);
                } else if (tblName && !currentTbl) {
                    selectTable(tblName);
                }
            }
        }
    });

    // Identify generated/stored columns from DDL (can't INSERT into these)
    const generatedColumns = createMemo(() => {
        const ddl = ddlData();
        if (!ddl || !ddl.statements) return new Set();
        const generated = new Set();
        for (const stmt of ddl.statements) {
            // Match: column_name TYPE AS (...) STORED
            const matches = stmt.matchAll(/^\s+(\w+)\s+\S+(?:\([^)]*\))?\s+AS\s*\(/gm);
            for (const m of matches) generated.add(m[1]);
            // Match: TOKENLIST AS (...)
            const tkn = stmt.matchAll(/^\s+(\w+)\s+TOKENLIST\s+AS/gm);
            for (const m of tkn) generated.add(m[1]);
        }
        return generated;
    });

    // Columns safe for INSERT (exclude generated/stored)
    const insertableColumns = createMemo(() => {
        const cols = tableData()?.columns || [];
        const gen = generatedColumns();
        return cols.filter(c => !gen.has(c));
    });

    // Parse column types from DDL for display — filtered to selected table only
    const columnTypes = createMemo(() => {
        const ddl = ddlData();
        const table = selectedTable();
        if (!ddl || !ddl.statements || !table) return {};
        const tableStmt = ddl.statements.find(s =>
            new RegExp('CREATE\\s+TABLE\\s+' + table + '\\b', 'i').test(s)
        );
        if (!tableStmt) return {};
        const types = {};
        const lines = tableStmt.split('\n');
        for (const line of lines) {
            const trimmed = line.trim().replace(/,$/, '');
            if (!trimmed || trimmed.startsWith(')') || trimmed.startsWith('--')) continue;
            if (/\bAS\s*\(/.test(trimmed) || /TOKENLIST\s+AS/.test(trimmed)) continue;
            const m = trimmed.match(/^(\w+)\s+(INT64|FLOAT64|BOOL|STRING\(\w+\)|STRING\(MAX\)|TIMESTAMP|DATE|BYTES\(\w+\))/i);
            if (m) types[m[1]] = m[2];
        }
        return types;
    });

    // Parse NOT NULL columns from DDL for validation — filtered to selected table only
    const notNullColumns = createMemo(() => {
        const ddl = ddlData();
        const table = selectedTable();
        if (!ddl || !ddl.statements || !table) return new Set();
        // Find the DDL statement for the currently selected table
        const tableStmt = ddl.statements.find(s =>
            new RegExp('CREATE\\s+TABLE\\s+' + table + '\\b', 'i').test(s)
        );
        if (!tableStmt) return new Set();
        const cols = new Set();
        const lines = tableStmt.split('\n');
        let parenDepth = 0;
        for (const line of lines) {
            const trimmed = line.trim().replace(/,$/, '');
            for (const ch of trimmed) { if (ch === '(') parenDepth++; if (ch === ')') parenDepth--; }
            if (parenDepth > 1) continue;
            if (/\bAS\s*\(/.test(trimmed) || /TOKENLIST\s+AS/.test(trimmed)) continue;
            const m = trimmed.match(/^(\w+)\s+(INT64|FLOAT64|BOOL|STRING|TIMESTAMP|DATE|BYTES)\b.*\bNOT\s+NULL\b/i);
            if (m) cols.add(m[1]);
        }
        return cols;
    });

    // CSV Import
    const [showCsvImport, setShowCsvImport] = createSignal(false);

    // Spanner-specific: type-aware SQL escaping for CSV import
    const spannerEscape = (val, colType) => {
        if (val === '' || val === null || val === undefined) return 'NULL';
        const trimmed = val.trim();
        const lower = trimmed.toLowerCase();
        if (lower === 'null') return 'NULL';
        const upperType = (colType || '').toUpperCase();
        if (upperType === 'BOOL') {
            if (lower === 'true' || lower === '1' || lower === 'yes') return 'TRUE';
            if (lower === 'false' || lower === '0' || lower === 'no') return 'FALSE';
            return 'NULL';
        }
        if (upperType === 'INT64') return /^-?\d+$/.test(trimmed) ? trimmed : 'NULL';
        if (upperType === 'FLOAT64' || upperType === 'FLOAT32' || upperType === 'NUMERIC') return /^-?\d+(\.\d+)?$/.test(trimmed) ? trimmed : 'NULL';
        return "'" + trimmed.replace(/'/g, "''") + "'";
    };

    const spannerImportRow = async (targetCols, values) => {
        const types = columnTypes();
        const valueLiterals = values.map((v, i) => spannerEscape(v, types[targetCols[i]]));
        const sql = `INSERT OR UPDATE INTO ${selectedTable()} (${targetCols.join(', ')}) VALUES (${valueLiterals.join(', ')})`;
        return await api.query('spanner', sql, { instance: selectedInstance(), database: selectedDatabase() });
    };

    const spannerImportBatch = async (targetCols, allValues) => {
        const types = columnTypes();
        const statements = allValues.map(values => {
            const valueLiterals = values.map((v, i) => spannerEscape(v, types[targetCols[i]]));
            return `INSERT OR UPDATE INTO ${selectedTable()} (${targetCols.join(', ')}) VALUES (${valueLiterals.join(', ')})`;
        });
        return await api.queryBatch('spanner', statements, { instance: selectedInstance(), database: selectedDatabase() });
    };

    // Breadcrumb — reactive memo tracks signal changes
    const breadcrumbs = createMemo(() => {
        const crumbs = [];
        crumbs.push({ label: 'Instances', onClick: goBackToInstances, active: !selectedInstance() });
        if (selectedInstance()) {
            crumbs.push({ label: selectedInstance(), onClick: goBackToDatabases, active: !selectedDatabase() });
            if (selectedDatabase()) {
                crumbs.push({ label: selectedDatabase(), onClick: goBackToTables, active: !selectedTable() });
                if (selectedTable()) {
                    crumbs.push({ label: selectedTable(), onClick: null, active: true });
                }
            }
        }
        return crumbs;
    });

    const Breadcrumb = () => <DataBreadcrumb crumbs={breadcrumbs()} />;

    const subTabStyle = (tab) => ({
        padding: '6px 14px',
        border: 'none',
        borderRadius: '4px',
        cursor: 'pointer',
        fontSize: '13px',
        fontWeight: activeSubTab() === tab ? '600' : '400',
        background: activeSubTab() === tab ? 'var(--primary)' : 'var(--surface)',
        color: activeSubTab() === tab ? '#fff' : 'var(--text-secondary)',
        transition: 'background 0.15s, color 0.15s',
    });

    return (
        <div>
            {/* Breadcrumb — always visible when drilled in */}
            <Show when={selectedInstance()}>
                <Breadcrumb />
            </Show>

            {/* Sub-tab navigation */}
            <div style="display:flex;gap:8px;margin-bottom:12px;margin-top:8px">
                <button style={subTabStyle('browse')} onClick={() => setActiveSubTab('browse')}>Browse</button>
                <button style={subTabStyle('history')} onClick={() => { setActiveSubTab('history'); fetchQueryHistory(); }}>History</button>
                <button style={subTabStyle('stats')} onClick={() => { setActiveSubTab('stats'); fetchSpannerStats(); }}
                    disabled={!selectedDatabase()}
                    title={selectedDatabase() ? 'View database statistics' : 'Select a database first'}>Stats</button>
            </div>

            {/* Query History Panel */}
            <Show when={activeSubTab() === 'history'}>
                <Show when={historyLoading()}>
                    <div class="loading-state"><div class="loading-spinner" /> Loading…</div>
                </Show>
                <Show when={!historyLoading()}>
                    <Show when={historyEntries().length === 0}>
                        <div style="padding:24px;text-align:center;color:var(--text-secondary);font-size:14px">
                            No query history yet. Run a Spanner SQL query to see it here.
                        </div>
                    </Show>
                    <Show when={historyEntries().length > 0}>
                        <div style="overflow-x:auto">
                            <table style="width:100%;border-collapse:collapse;font-size:13px">
                                <thead>
                                    <tr style="border-bottom:2px solid var(--border)">
                                        <th style="padding:8px 10px;text-align:left;white-space:nowrap">Time</th>
                                        <th style="padding:8px 10px;text-align:left">SQL</th>
                                        <th style="padding:8px 10px;text-align:left">Database</th>
                                        <th style="padding:8px 10px;text-align:right;white-space:nowrap">Duration</th>
                                        <th style="padding:8px 10px;text-align:right;white-space:nowrap">Rows</th>
                                        <th style="padding:8px 10px;text-align:center;white-space:nowrap">Status</th>
                                        <th style="padding:8px 10px;text-align:center;white-space:nowrap">Action</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    <For each={historyEntries()}>
                                        {(entry) => (
                                            <tr style="border-bottom:1px solid var(--border);transition:background 0.1s"
                                                onMouseEnter={e => e.currentTarget.style.background = 'var(--bg-secondary)'}
                                                onMouseLeave={e => e.currentTarget.style.background = 'transparent'}>
                                                <td style="padding:8px 10px;white-space:nowrap;color:var(--text-secondary);font-size:12px">
                                                    {entry.executed_at ? entry.executed_at.replace('T', ' ').substring(0, 19) : ''}
                                                </td>
                                                <td style="padding:8px 10px;max-width:400px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-family:monospace;font-size:12px">
                                                    {entry.sql}
                                                </td>
                                                <td style="padding:8px 10px;white-space:nowrap;color:var(--text-secondary);font-size:12px">
                                                    {entry.database || '-'}
                                                </td>
                                                <td style="padding:8px 10px;text-align:right;white-space:nowrap;font-family:monospace;font-size:12px">
                                                    {entry.duration_ms > 1000 ? (entry.duration_ms / 1000).toFixed(1) + 's' : entry.duration_ms + 'ms'}
                                                </td>
                                                <td style="padding:8px 10px;text-align:right;white-space:nowrap;font-family:monospace;font-size:12px">
                                                    {entry.row_count}
                                                </td>
                                                <td style="padding:8px 10px;text-align:center">
                                                    <span style={{
                                                        display:'inline-block',
                                                        padding:'2px 8px',
                                                        borderRadius:'10px',
                                                        fontSize:'11px',
                                                        fontWeight:'600',
                                                        background: entry.success ? 'rgba(52,199,89,0.15)' : 'rgba(255,69,58,0.15)',
                                                        color: entry.success ? '#34C759' : '#FF453A',
                                                    }}>
                                                        {entry.success ? 'OK' : 'FAIL'}
                                                    </span>
                                                </td>
                                                <td style="padding:8px 10px;text-align:center">
                                                    <Show when={entry.success && !entry.sql.startsWith('Batch:')}>
                                                        <button onClick={() => {
                                                            setActiveSubTab('browse');
                                                            // Set SQL in the parent's query editor if available
                                                            if (props.onSetQuery) props.onSetQuery(entry.sql);
                                                        }} style={{
                                                            padding:'4px 10px',
                                                            border:'1px solid var(--border)',
                                                            borderRadius:'4px',
                                                            background:'var(--surface)',
                                                            color:'var(--primary)',
                                                            cursor:'pointer',
                                                            fontSize:'11px',
                                                            transition:'border-color 0.15s',
                                                        }}
                                                            onMouseEnter={e => e.currentTarget.style.borderColor = 'var(--primary)'}
                                                            onMouseLeave={e => e.currentTarget.style.borderColor = 'var(--border)'}>
                                                            Rerun
                                                        </button>
                                                    </Show>
                                                </td>
                                            </tr>
                                        )}
                                    </For>
                                </tbody>
                            </table>
                        </div>
                        {/* Pagination controls */}
                        <div style="display:flex;justify-content:space-between;align-items:center;padding:12px 0;font-size:13px;color:var(--text-secondary)">
                            <span>Showing {historyEntries().length} of {historyTotal()} entries</span>
                            <Show when={historyHasMore()}>
                                <button onClick={loadMoreHistory} style={{
                                    padding:'6px 16px',
                                    border:'1px solid var(--border)',
                                    borderRadius:'4px',
                                    background:'var(--surface)',
                                    color:'var(--primary)',
                                    cursor:'pointer',
                                    fontSize:'13px',
                                    fontWeight:'500',
                                    transition:'border-color 0.15s',
                                }}
                                    onMouseEnter={e => e.currentTarget.style.borderColor = 'var(--primary)'}
                                    onMouseLeave={e => e.currentTarget.style.borderColor = 'var(--border)'}>
                                    Load More
                                </button>
                            </Show>
                        </div>
                    </Show>
                </Show>
            </Show>

            {/* Stats Panel */}
            <Show when={activeSubTab() === 'stats'}>
                <Show when={statsLoading()}>
                    <div class="loading-state"><div class="loading-spinner" /> Loading stats…</div>
                </Show>
                <Show when={!statsLoading()}>
                    <Show when={!statsData()}>
                        <div style="padding:24px;text-align:center;color:var(--text-secondary);font-size:14px">
                            Select a database and click Stats to view statistics.
                        </div>
                    </Show>
                    <Show when={statsData()}>
                        {/* Summary cards */}
                        <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(160px,1fr));gap:12px;margin-bottom:24px">
                            <div style="display:flex;flex-direction:column;align-items:center;gap:4px;padding:16px;border:1px solid var(--border);border-radius:8px;background:var(--surface)">
                                <span style="font-size:28px;font-weight:700;color:var(--accent,#4285f4)">{statsData().tableCount}</span>
                                <span style="font-size:12px;color:var(--text-secondary)">Tables</span>
                            </div>
                            <div style="display:flex;flex-direction:column;align-items:center;gap:4px;padding:16px;border:1px solid var(--border);border-radius:8px;background:var(--surface)">
                                <span style="font-size:28px;font-weight:700;color:var(--text)">{statsData().indexCount}</span>
                                <span style="font-size:12px;color:var(--text-secondary)">Indexes</span>
                            </div>
                            <div style="display:flex;flex-direction:column;align-items:center;gap:4px;padding:16px;border:1px solid var(--border);border-radius:8px;background:var(--surface)">
                                <span style="font-size:28px;font-weight:700;color:#34A853">{statsData().searchIndexCount}</span>
                                <span style="font-size:12px;color:var(--text-secondary)">Search Indexes</span>
                            </div>
                            <div style="display:flex;flex-direction:column;align-items:center;gap:4px;padding:16px;border:1px solid var(--border);border-radius:8px;background:var(--surface)">
                                <span style="font-size:28px;font-weight:700;color:#FBBC04">{statsData().vectorIndexCount}</span>
                                <span style="font-size:12px;color:var(--text-secondary)">Vector Indexes</span>
                            </div>
                            <div style="display:flex;flex-direction:column;align-items:center;gap:4px;padding:16px;border:1px solid var(--border);border-radius:8px;background:var(--surface)">
                                <span style="font-size:28px;font-weight:700;color:var(--text)">{statsData().totalObjects}</span>
                                <span style="font-size:12px;color:var(--text-secondary)">Total Objects</span>
                            </div>
                        </div>

                        {/* Detail table */}
                        <Show when={statsData().details && statsData().details.length > 0}>
                            <h3 style="font-size:14px;margin:0 0 8px 0;color:var(--text-secondary)">Objects</h3>
                            <div class="data-table-wrapper">
                                <table class="data-table" style="font-size:13px">
                                    <thead>
                                        <tr>
                                            <th>Type</th>
                                            <th>Name</th>
                                            <th style="text-align:right">Columns</th>
                                            <th style="text-align:center">Interleaved</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        <For each={statsData().details}>
                                            {(item) => (
                                                <tr>
                                                    <td>
                                                        <span style={{
                                                            display:'inline-block',
                                                            padding:'2px 8px',
                                                            borderRadius:'10px',
                                                            fontSize:'11px',
                                                            fontWeight:'600',
                                                            background: item.type === 'TABLE' ? 'rgba(66,133,244,0.15)' :
                                                                item.type === 'SEARCH_INDEX' ? 'rgba(52,168,83,0.15)' :
                                                                item.type === 'VECTOR_INDEX' ? 'rgba(251,188,4,0.15)' :
                                                                'rgba(128,128,128,0.15)',
                                                            color: item.type === 'TABLE' ? '#4285f4' :
                                                                item.type === 'SEARCH_INDEX' ? '#34A853' :
                                                                item.type === 'VECTOR_INDEX' ? '#FBBC04' :
                                                                '#808080',
                                                        }}>
                                                            {item.type === 'TABLE' ? 'TABLE' :
                                                             item.type === 'SEARCH_INDEX' ? 'SEARCH' :
                                                             item.type === 'VECTOR_INDEX' ? 'VECTOR' :
                                                             item.type}
                                                        </span>
                                                    </td>
                                                    <td style={{fontWeight:'500', fontFamily:'monospace', fontSize:'12px'}}>{item.name}</td>
                                                    <td style={{textAlign:'right'}}>
                                                        {item.columnCount != null ? item.columnCount : '-'}
                                                    </td>
                                                    <td style={{textAlign:'center'}}>
                                                        {item.hasInterleaved != null
                                                            ? (item.hasInterleaved
                                                                ? <span style={{color:'#34A853', fontSize:'14px'}}>&#10003;</span>
                                                                : <span style={{color:'var(--text-tertiary)', fontSize:'14px'}}>&#8212;</span>)
                                                            : '-'}
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
            </Show>

            {/* Browse Panel */}
            <Show when={activeSubTab() === 'browse'}>
            <Show when={subLoading()}>
                <div class="loading-state"><div class="loading-spinner" /> Loading…</div>
            </Show>
            <Show when={!subLoading()}>
                {/* Level 4: Table data */}
                <Show when={selectedTable()}>
                    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:12px">
                        <h2 style="margin:0;font-size:16px">{selectedTable()}</h2>
                        <Show when={props.onAdd && tableData() && tableData().columns}>
                            <div style="display:flex;gap:6px">
                                <button onClick={() => { setDdlModalText(getTableDdl(selectedTable())); setShowDdlModal(true); }}
                                    style="padding:6px 12px;border:1px solid var(--border);border-radius:4px;background:var(--surface);color:var(--text-secondary);cursor:pointer;font-size:12px;display:flex;align-items:center;gap:5px;transition:border-color 0.15s, background 0.15s, color 0.15s"
                                    onMouseEnter={e => e.currentTarget.style.borderColor = 'var(--primary)'}
                                    onMouseLeave={e => e.currentTarget.style.borderColor = 'var(--border)'}>
                                    Show DDL
                                </button>
                                <button onClick={() => setShowCsvImport(true)}
                                    style="padding:6px 12px;border:1px solid var(--border);border-radius:4px;background:var(--surface);color:var(--text-secondary);cursor:pointer;font-size:12px;display:flex;align-items:center;gap:5px;transition:border-color 0.15s, background 0.15s, color 0.15s"
                                    onMouseEnter={e => e.currentTarget.style.borderColor = 'var(--primary)'}
                                    onMouseLeave={e => e.currentTarget.style.borderColor = 'var(--border)'}>
                                    {'\u2191'} Import CSV
                                </button>
                                <button onClick={handleAddMockRow}
                                    style="padding:6px 12px;border:1px solid var(--border);border-radius:4px;background:var(--surface);color:var(--text-secondary);cursor:pointer;font-size:12px;display:flex;align-items:center;gap:5px;transition:border-color 0.15s, background 0.15s, color 0.15s"
                                    onMouseEnter={e => e.currentTarget.style.borderColor = 'var(--primary)'}
                                    onMouseLeave={e => e.currentTarget.style.borderColor = 'var(--border)'}>
                                    + Add Mock Row
                                </button>
                                <button onClick={() => props.onAdd('Add Spanner Row',
                                    tableData().columns.map(c => ({ name: c, type: 'text' })),
                                    async (formData) => {
                                        await api.mutate('spanner', 'rows', {
                                            instance: selectedInstance(),
                                            database: selectedDatabase(),
                                            table: selectedTable(),
                                            columns: Object.keys(formData),
                                            values: [Object.values(formData)]
                                        });
                                    }
                                )} style="padding:6px 12px;border:none;border-radius:4px;background:var(--accent, #4285f4);color:white;cursor:pointer;font-size:12px">
                                    + Add Row
                                </button>
                                <button onClick={() => handleDeleteTable(selectedTable())}
                                    style="padding:6px 12px;border:1px solid var(--error);border-radius:4px;background:var(--surface);color:var(--error);cursor:pointer;font-size:12px;display:flex;align-items:center;gap:5px;transition:border-color 0.15s, background 0.15s, color 0.15s"
                                    onMouseEnter={e => { e.currentTarget.style.background = 'rgba(234, 67, 53, 0.08)'; }}
                                    onMouseLeave={e => { e.currentTarget.style.background = 'var(--surface)'; }}>
                                    Delete Table
                                </button>
                            </div>
                        </Show>
                    </div>
                    <Show when={tableData() && tableData().columns} fallback={
                        <div class="empty-state">
                            <div class="empty-state-icon">{'\u2205'}</div>
                            <div class="empty-state-title">No data found</div>
                            <div class="empty-state-text">Could not load table schema.</div>
                        </div>
                    }>
                        <div class="data-table-wrapper" style="overflow-x:auto">
                            <table class="data-table" style="min-width:max-content">
                                <thead><tr><For each={tableData().columns}>{(col) => <th>{col}</th>}</For><th style="position:sticky;right:0;background:var(--surface);z-index:2;border-left:1px solid var(--border)">Actions</th></tr></thead>
                                <tbody>
                                    <Show when={tableData().rows && tableData().rows.length > 0} fallback={
                                        <tr><td colspan={tableData().columns.length + 1} style="text-align:center;padding:24px;color:var(--text-secondary);font-style:italic">
                                            No rows yet. Use "+ Add Row" or "Import CSV" to insert data.
                                        </td></tr>
                                    }>
                                        <For each={tableData().rows}>
                                            {(row) => (
                                                <tr>
                                                    <For each={tableData().columns}>
                                                        {(col) => <td style="white-space:nowrap;max-width:300px;overflow:hidden;text-overflow:ellipsis" title={row[col] != null ? String(row[col]) : ''}>{row[col] != null ? String(row[col]) : '--'}</td>}
                                                    </For>
                                                    <td style="position:sticky;right:0;background:var(--surface);z-index:1;border-left:1px solid var(--border)">
                                                        <div style="display:flex;gap:4px;align-items:center">
                                                            <Show when={props.onEdit}>
                                                                {(() => {
                                                                    const columns = tableData().columns;
                                                                    return (
                                                                        <button onClick={() => props.onEdit('Edit Spanner Row',
                                                                            columns.map(c => ({ name: c, type: 'text', value: row[c] != null ? String(row[c]) : '' })),
                                                                            async (formData) => {
                                                                                await api.mutate('spanner', 'rows/update', {
                                                                                    instance: selectedInstance(),
                                                                                    database: selectedDatabase(),
                                                                                    table: selectedTable(),
                                                                                    columns: Object.keys(formData),
                                                                                    values: [Object.values(formData)]
                                                                                });
                                                                            }
                                                                        )} style="padding:4px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:var(--text-secondary);cursor:pointer;font-size:11px" title="Edit">Edit</button>
                                                                    );
                                                                })()}
                                                            </Show>
                                                            <Show when={props.onDelete}>
                                                                {(() => {
                                                                    const columns = tableData().columns;
                                                                    return (
                                                                        <button onClick={() => props.onDelete('Delete this row?', async () => {
                                                                            await api.mutate('spanner', 'rows/delete', {
                                                                                instance: selectedInstance(),
                                                                                database: selectedDatabase(),
                                                                                table: selectedTable(),
                                                                                keyColumns: [columns[0]],
                                                                                keyValues: [[row[columns[0]]]]
                                                                            });
                                                                        })} style="padding:4px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:#ea4335;cursor:pointer;font-size:11px" title="Delete">Del</button>
                                                                    );
                                                                })()}
                                                            </Show>
                                                        </div>
                                                    </td>
                                                </tr>
                                            )}
                                        </For>
                                    </Show>
                                </tbody>
                            </table>
                        </div>
                    </Show>
                </Show>

                {/* Level 3: Tables from DDL */}
                <Show when={selectedDatabase() && !selectedTable()}>
                    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px">
                        <div />
                        <button class="btn btn-primary" style="height:30px;font-size:11px;padding:0 12px" onClick={() => { setCreateDdl(''); setCreateError(null); setShowCreateTable(true); }}>+ Create Table</button>
                    </div>
                    {(() => {
                        const tables = parseTables(ddlData());
                        return (
                            <Show when={tables.length > 0} fallback={
                                <div class="empty-state">
                                    <div class="empty-state-icon">{'\u2205'}</div>
                                    <div class="empty-state-title">No tables found</div>
                                    <div class="empty-state-text">Create tables in this database.</div>
                                </div>
                            }>
                                <div class="data-table-wrapper">
                                    <table class="data-table">
                                        <thead><tr><th>Table Name</th><th style="text-align:right">Actions</th></tr></thead>
                                        <tbody>
                                            <For each={tables}>
                                                {(tbl) => (
                                                    <tr class="clickable-row" onClick={() => selectTable(tbl)} onKeyDown={onActivate(() => selectTable(tbl))} role="button" tabIndex="0">
                                                        <td style={{ "font-weight": "500" }}>{tbl}</td>
                                                        <td style="text-align:right" onClick={(e) => e.stopPropagation()}>
                                                            <button class="btn btn-secondary"
                                                                style="height:24px;font-size:11px;padding:0 8px;margin-right:6px"
                                                                onClick={() => { setDdlModalText(getTableDdl(tbl)); setShowDdlModal(true); }}>
                                                                Show DDL
                                                            </button>
                                                            <button class="btn btn-secondary"
                                                                style="height:24px;font-size:11px;padding:0 8px;color:var(--error)"
                                                                onClick={() => handleDeleteTable(tbl)}>
                                                                Delete
                                                            </button>
                                                        </td>
                                                    </tr>
                                                )}
                                            </For>
                                        </tbody>
                                    </table>
                                </div>
                            </Show>
                        );
                    })()}
                </Show>

                {/* Level 2: Databases */}
                <Show when={selectedInstance() && !selectedDatabase()}>
                    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px">
                        <div />
                        <button class="btn btn-primary" style="height:30px;font-size:11px;padding:0 12px" onClick={() => { setCreateName(''); setCreateError(null); setShowCreateDatabase(true); }}>+ Create Database</button>
                    </div>
                    <Show when={databases().length > 0} fallback={
                        <div class="empty-state">
                            <div class="empty-state-icon">{'\u2205'}</div>
                            <div class="empty-state-title">No databases found</div>
                            <div class="empty-state-text">Create a database in this instance.</div>
                        </div>
                    }>
                        <div class="data-table-wrapper">
                            <table class="data-table">
                                <thead><tr><th>Database Name</th></tr></thead>
                                <tbody>
                                    <For each={databases()}>
                                        {(db) => {
                                            const dbName = db.name?.split('/').pop() || db;
                                            return (
                                                <tr class="clickable-row" onClick={() => selectDatabase(db)} onKeyDown={onActivate(() => selectDatabase(db))} role="button" tabIndex="0">
                                                    <td style={{ "font-weight": "500" }}>{dbName}</td>
                                                </tr>
                                            );
                                        }}
                                    </For>
                                </tbody>
                            </table>
                        </div>
                    </Show>
                </Show>

                {/* Level 1: Instances */}
                <Show when={!selectedInstance()}>
                    <div style="display:flex;justify-content:flex-end;margin-bottom:8px">
                        <button class="btn btn-primary" style="height:30px;font-size:11px;padding:0 12px" onClick={() => { setCreateName(''); setCreateError(null); setShowCreateInstance(true); }}>+ Create Instance</button>
                    </div>
                    <Show when={instances().length > 0} fallback={
                        <div class="empty-state">
                            <div class="empty-state-icon">{'\u2205'}</div>
                            <div class="empty-state-title">No Spanner instances found</div>
                            <div class="empty-state-text">Click "Create Instance" above to get started.</div>
                        </div>
                    }>
                        <div class="data-table-wrapper">
                            <table class="data-table">
                                <thead><tr><th>Instance</th><th>State</th><th>Nodes</th><th>Created</th></tr></thead>
                                <tbody>
                                    <For each={instances()}>
                                        {(inst) => {
                                            const instId = inst.name?.split('/').pop() || '';
                                            const hasAlias = inst.displayName && inst.displayName !== instId;
                                            return (
                                                <tr class="clickable-row" onClick={() => selectInstance(inst)} onKeyDown={onActivate(() => selectInstance(inst))} role="button" tabIndex="0">
                                                    <td style={{ "font-weight": "500" }}>
                                                        {inst.displayName || instId || '--'}
                                                        {hasAlias && (
                                                            <span style="font-weight: normal; font-size: 11px; color: var(--text-secondary); margin-left: 6px">
                                                                ({instId})
                                                            </span>
                                                        )}
                                                    </td>
                                                    <td><span class={`badge ${inst.state === 'READY' ? 'badge-healthy' : 'badge-neutral'}`}>{inst.state || '--'}</span></td>
                                                    <td>{inst.nodeCount || '--'}</td>
                                                    <td>{formatDate(inst.createTime)}</td>
                                                </tr>
                                            );
                                        }}
                                    </For>
                                </tbody>
                            </table>
                        </div>
                    </Show>
                </Show>

                {/* Create Instance Modal */}
                <Show when={showCreateInstance()}>
                    <div class="modal-overlay" role="dialog" aria-modal="true" aria-labelledby="spanner-create-instance-title" onClick={(e) => { if (e.target === e.currentTarget) setShowCreateInstance(false); }}>
                        <div class="card modal-card" onClick={(e) => e.stopPropagation()}>
                            <h2 id="spanner-create-instance-title" style="margin-bottom:16px">Create Spanner Instance</h2>
                            <Show when={createError()}><div class="alert alert-error" role="alert" style="margin-bottom:12px">{createError()}</div></Show>
                            <div style="margin-bottom:16px">
                                <label class="form-label" for="spanner-instance-id">Instance ID</label>
                                <input id="spanner-instance-id" name="spanner-instance-id" autocomplete="off" type="text" class="form-input form-input-mono" value={createName()} onInput={(e) => setCreateName(e.currentTarget.value)} placeholder="my-instance" />
                            </div>
                            <div style="display:flex;gap:8px;justify-content:flex-end">
                                <button class="btn btn-secondary" onClick={() => setShowCreateInstance(false)}>Cancel</button>
                                <button class="btn btn-primary" onClick={handleCreateInstance} disabled={creating() || !createName().trim()}>{creating() ? 'Creating…' : 'Create'}</button>
                            </div>
                        </div>
                    </div>
                </Show>

                {/* Create Database Modal */}
                <Show when={showCreateDatabase()}>
                    <div class="modal-overlay" role="dialog" aria-modal="true" aria-labelledby="spanner-create-database-title" onClick={(e) => { if (e.target === e.currentTarget) setShowCreateDatabase(false); }}>
                        <div class="card modal-card" onClick={(e) => e.stopPropagation()}>
                            <h2 id="spanner-create-database-title" style="margin-bottom:16px">Create Database in {selectedInstance()}</h2>
                            <Show when={createError()}><div class="alert alert-error" role="alert" style="margin-bottom:12px">{createError()}</div></Show>
                            <div style="margin-bottom:16px">
                                <label class="form-label" for="spanner-database-name">Database Name</label>
                                <input id="spanner-database-name" name="spanner-database-name" autocomplete="off" type="text" class="form-input form-input-mono" value={createName()} onInput={(e) => setCreateName(e.currentTarget.value)} placeholder="my-database" />
                            </div>
                            <div style="display:flex;gap:8px;justify-content:flex-end">
                                <button class="btn btn-secondary" onClick={() => setShowCreateDatabase(false)}>Cancel</button>
                                <button class="btn btn-primary" onClick={handleCreateDatabase} disabled={creating() || !createName().trim()}>{creating() ? 'Creating…' : 'Create'}</button>
                            </div>
                        </div>
                    </div>
                </Show>

                {/* Create Table Modal */}
                <Show when={showCreateTable()}>
                    <div class="modal-overlay" role="dialog" aria-modal="true" aria-labelledby="spanner-create-table-title" onClick={(e) => { if (e.target === e.currentTarget) setShowCreateTable(false); }}>
                        <div class="card modal-card spanner-ddl-modal-card" onClick={(e) => e.stopPropagation()}>
                            <div class="spanner-ddl-modal-header">
                                <h2 id="spanner-create-table-title" style="margin-bottom:4px">Create Table in {selectedDatabase()}</h2>
                                <p style="font-size:12px;color:var(--text-secondary);margin-bottom:0">Enter one or more Spanner DDL statements.</p>
                            </div>
                            <div class="spanner-ddl-modal-body">
                                <Show when={createError()}><div class="alert alert-error spanner-ddl-error" role="alert">{createError()}</div></Show>
                                <CodeEditor
                                    value={createDdl()}
                                    onChange={setCreateDdl}
                                    dialect="googlesql"
                                    lineNumbers={true}
                                    placeholder={"CREATE TABLE MyTable (\n  Id STRING(36) NOT NULL,\n  Name STRING(100),\n  CreatedAt TIMESTAMP\n) PRIMARY KEY (Id)"}
                                    onRun={handleCreateTable}
                                    class="sql-editor-fill"
                                    style={{
                                        border: '1px solid var(--border)',
                                        'border-radius': 'var(--radius-sm)',
                                        overflow: 'auto',
                                        resize: 'vertical',
                                        'min-height': '160px',
                                        flex: '1'
                                    }}
                                />
                            </div>
                            <div class="spanner-ddl-modal-actions">
                                <button class="btn btn-secondary" onClick={() => setShowCreateTable(false)}>Cancel</button>
                                <button class="btn btn-primary" onClick={handleCreateTable} disabled={creating() || !createDdl().trim()}>{creating() ? 'Executing…' : 'Execute DDL'}</button>
                            </div>
                        </div>
                    </div>
                </Show>

                {/* CSV Import — shared wizard component */}
                <CsvImportWizard
                    show={showCsvImport()}
                    onClose={() => setShowCsvImport(false)}
                    tableName={selectedTable()}
                    columns={insertableColumns()}
                    columnTypes={columnTypes()}
                    notNullColumns={notNullColumns()}
                    serviceName="Spanner"
                    onImportRow={spannerImportRow}
                    onImportBatch={spannerImportBatch}
                    onImportDone={() => selectTable(selectedTable())}
                />

                {/* DDL Modal */}
                <Show when={showDdlModal()}>
                    <div class="modal-overlay" role="dialog" aria-modal="true" aria-labelledby="spanner-ddl-title" onClick={(e) => { if (e.target === e.currentTarget) setShowDdlModal(false); }}>
                        <div class="card modal-card" onClick={(e) => e.stopPropagation()} style="width:800px;max-width:90vw">
                            <h2 id="spanner-ddl-title" style="margin-bottom:4px">Schema DDL for {selectedTable() || 'Table'}</h2>
                            <p style="font-size:12px;color:var(--text-secondary);margin-bottom:16px">The DDL query used to define this table and its indexes.</p>
                            <div style="border:1px solid var(--border);border-radius:var(--radius-sm);overflow:hidden;margin-bottom:16px;background:var(--sql-editor-gutter)">
                                <CodeEditor
                                    value={ddlModalText()}
                                    dialect="googlesql"
                                    lineNumbers={true}
                                    readOnly={true}
                                    height="300px"
                                    maxHeight="450px"
                                />
                            </div>
                            <div style="display:flex;justify-content:flex-end">
                                <button class="btn btn-secondary" onClick={() => setShowDdlModal(false)}>Close</button>
                            </div>
                        </div>
                    </div>
                </Show>

                {/* Floating Toast Notification */}
                <Show when={toast()}>
                    <style>{`
                        @keyframes slideIn {
                            from { transform: translateX(120%); opacity: 0; }
                            to { transform: translateX(0); opacity: 1; }
                        }
                    `}</style>
                    <div style={{
                        position: 'fixed',
                        top: '24px',
                        right: '24px',
                        'z-index': 9999,
                        display: 'flex',
                        'align-items': 'center',
                        gap: '12px',
                        padding: '12px 18px',
                        background: 'var(--surface-overlay, rgba(255, 255, 255, 0.95))',
                        border: `1px solid ${toast().type === 'success' ? 'var(--success, #2e7d32)' : toast().type === 'error' ? 'var(--error, #d32f2f)' : 'var(--primary, #4285f4)'}`,
                        'border-left': `4px solid ${toast().type === 'success' ? 'var(--success, #2e7d32)' : toast().type === 'error' ? 'var(--error, #d32f2f)' : 'var(--primary, #4285f4)'}`,
                        'border-radius': '6px',
                        'box-shadow': '0 4px 12px rgba(0, 0, 0, 0.15)',
                        'backdrop-filter': 'blur(8px)',
                        transition: 'all 0.3s ease',
                        animation: 'slideIn 0.3s ease-out'
                    }}>
                        {/* Icon */}
                        <Show when={toast().type === 'success'}>
                            <svg width="18" height="18" viewBox="0 0 24 24" fill="var(--success, #2e7d32)">
                                <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z" />
                            </svg>
                        </Show>
                        <Show when={toast().type === 'info'}>
                            <div class="loading-spinner" style="width:16px;height:16px;border-width:2px;margin:0" />
                        </Show>
                        <Show when={toast().type === 'error'}>
                            <svg width="18" height="18" viewBox="0 0 24 24" fill="var(--error, #d32f2f)">
                                <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z" />
                            </svg>
                        </Show>

                        {/* Message & Timer */}
                        <div style="display:flex;flex-direction:column;gap:2px">
                            <span style="font-weight:500;font-size:13px;color:var(--text)">{toast().message}</span>
                            <Show when={toast().type === 'info' && toast().elapsed !== undefined}>
                                <span style="font-size:11px;color:var(--text-secondary)">Elapsed: {toast().elapsed.toFixed(1)}s</span>
                            </Show>
                        </div>
                    </div>
                </Show>
            </Show>
            </Show>
        </div>
    );
}

function CloudSqlView(props) {
    const tables = () => props.data()?.tables || [];

    return (
        <div>
            <h2 style={{ margin: '0 0 16px 0' }}>Cloud SQL Catalog</h2>
            <Show when={tables().length > 0} fallback={
                <EmptyState title="No Cloud SQL tables found" message="Enable Cloud SQL or create instances, databases, and users to see catalog tables." />
            }>
                <div class="table-grid">
                    <For each={tables()}>
                        {(table) => (
                            <div class="card">
                                <div style={{ display: 'flex', 'align-items': 'center', 'justify-content': 'space-between', gap: '12px', 'margin-bottom': '10px' }}>
                                    <strong>{table.name}</strong>
                                    <span class="badge badge-info">{(table.columns || []).length} cols</span>
                                </div>
                                <div class="data-table-wrapper">
                                    <table class="data-table">
                                        <thead>
                                            <tr><th>Column</th><th>Type</th></tr>
                                        </thead>
                                        <tbody>
                                            <For each={table.columns || []}>
                                                {(col) => <tr><td>{col.name || col}</td><td>{col.type || '--'}</td></tr>}
                                            </For>
                                        </tbody>
                                    </table>
                                </div>
                            </div>
                        )}
                    </For>
                </div>
            </Show>
        </div>
    );
}

// -- Memorystore (Redis/Valkey) View with database drill-down --
function formatRedisValue(value, type) {
    if (value === null || value === undefined) return '';
    if (typeof value === 'string') return value;
    if (typeof value === 'object') {
        try {
            return JSON.stringify(value);
        } catch (e) {
            return String(value);
        }
    }
    return String(value);
}

function MemorystoreView(props) {
    const d = () => props.data();
    const [selectedDb, setSelectedDb] = createSignal(null);
    const [keysData, setKeysData] = createSignal(null);
    const [subLoading, setSubLoading] = createSignal(false);
    const [namespaceFilter, setNamespaceFilter] = createSignal('');

    const databases = () => {
        const raw = d();
        if (!raw) return [];
        // If response has 'databases' array, we're in database listing mode
        if (raw.databases) return raw.databases;
        // Legacy: if response has 'keys' directly, show single db view
        return [];
    };

    const keys = () => {
        const kd = keysData();
        if (!kd) return [];
        return kd.keys || [];
    };

    const namespaces = () => {
        const kd = keysData();
        if (!kd) return [];
        return kd.namespaces || [];
    };

    const filteredKeys = () => {
        const filter = namespaceFilter();
        if (!filter) return keys();
        return keys().filter(k => k.key.startsWith(filter + ':'));
    };

    const updateSubpath = (db, ns) => {
        if (props.onSubpathChange) {
            const parts = [];
            if (db !== null && db !== undefined) parts.push('db' + db);
            if (ns) parts.push(ns);
            props.onSubpathChange(parts);
        }
    };

    const selectDatabase = async (dbIndex) => {
        setSelectedDb(dbIndex);
        setNamespaceFilter('');
        updateSubpath(dbIndex, null);
        setSubLoading(true);
        try {
            const result = await api.browse('memorystore/db/' + dbIndex);
            setKeysData(result);
        } catch { setKeysData(null); }
        finally { setSubLoading(false); }
    };

    const goBack = () => {
        setSelectedDb(null);
        setKeysData(null);
        setNamespaceFilter('');
        updateSubpath(null, null);
    };

    const selectNamespace = (ns) => {
        setNamespaceFilter(ns);
        updateSubpath(selectedDb(), ns);
    };

    const clearNamespace = () => {
        setNamespaceFilter('');
        updateSubpath(selectedDb(), null);
    };

    // Restore navigation from URL subpath
    createEffect(() => {
        const sp = typeof props.subpath === 'function' ? props.subpath() : props.subpath;
        // If subpath is empty, reset to database listing
        if (!sp || sp.length === 0) {
            setSelectedDb(null);
            setKeysData(null);
            setNamespaceFilter('');
            return;
        }
        const dbs = databases();
        if (!dbs || dbs.length === 0) return;
        const [dbPart, nsPart] = sp;
        const currentDb = untrack(() => selectedDb());
        if (dbPart && dbPart.startsWith('db') && currentDb === null) {
            const idx = parseInt(dbPart.slice(2));
            if (!isNaN(idx)) {
                selectDatabase(idx).then(() => {
                    if (nsPart) setNamespaceFilter(nsPart);
                });
            }
        } else if (!dbPart && currentDb !== null) {
            setSelectedDb(null);
            setKeysData(null);
            setNamespaceFilter('');
        } else if (dbPart && dbPart.startsWith('db') && currentDb !== null) {
            const idx = parseInt(dbPart.slice(2));
            if (currentDb === idx) {
                const currentNs = untrack(() => namespaceFilter());
                if (!nsPart && currentNs) {
                    setNamespaceFilter('');
                } else if (nsPart && !currentNs) {
                    setNamespaceFilter(nsPart);
                }
            }
        }
    });

    // Reset when parent data is cleared (e.g. service tab re-selected triggers refetch)
    createEffect(() => {
        const raw = d();
        if (!raw) {
            setSelectedDb(null);
            setKeysData(null);
            setNamespaceFilter('');
        }
    });

    // Breadcrumb
    const breadcrumbs = createMemo(() => {
        const crumbs = [{ label: 'Databases', onClick: goBack, active: selectedDb() === null }];
        if (selectedDb() !== null) {
            crumbs.push({ label: 'db' + selectedDb(), onClick: () => { clearNamespace(); }, active: !namespaceFilter() });
            if (namespaceFilter()) {
                crumbs.push({ label: namespaceFilter() + ':*', onClick: null, active: true });
            }
        }
        return crumbs;
    });

    const refreshKeys = async () => {
        if (selectedDb() !== null) {
            await selectDatabase(selectedDb());
        }
    };

    return (
        <div>
            <Show when={selectedDb() !== null}>
                <DataBreadcrumb crumbs={breadcrumbs()} />
            </Show>

            <Show when={selectedDb() === null}>
                {/* Database listing view */}
                <Show when={databases().length > 0} fallback={
                    <div class="empty-state">
                        <div class="empty-state-icon">{'\u2205'}</div>
                        <div class="empty-state-title">No databases available</div>
                        <div class="empty-state-text">Valkey is not running or not reachable.</div>
                    </div>
                }>
                    <div class="data-table-wrapper">
                        <table class="data-table">
                            <thead><tr><th>Database</th><th>Keys</th><th>Actions</th></tr></thead>
                            <tbody>
                                <For each={databases()}>
                                    {(db) => (
                                        <tr onClick={() => selectDatabase(db.index)} onKeyDown={onActivate(() => selectDatabase(db.index))} role="button" tabIndex="0" style={{ cursor: 'pointer' }}
                                            class="clickable-row">
                                            <td style={{ "font-weight": "500" }}>
                                                <span style="color:var(--accent, #4285f4)">db{db.index}</span>
                                            </td>
                                            <td>
                                                <span class={`badge ${db.keyCount > 0 ? 'badge-info' : 'badge-neutral'}`}>
                                                    {db.keyCount} {db.keyCount === 1 ? 'key' : 'keys'}
                                                </span>
                                            </td>
                                            <td>
                                                <Show when={db.keyCount > 0 && props.onDelete}>
                                                    <button onClick={(e) => {
                                                        e.stopPropagation(); props.onDelete('Flush all keys in db' + db.index + '?', async () => {
                                                            await api.mutate('memorystore', 'flushdb', { db: db.index });
                                                            if (props.onRefresh) await props.onRefresh();
                                                        });
                                                    }} style="padding:4px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:#ea4335;cursor:pointer;font-size:11px" title="Flush DB">Flush</button>
                                                </Show>
                                            </td>
                                        </tr>
                                    )}
                                </For>
                            </tbody>
                        </table>
                    </div>
                </Show>
            </Show>

            <Show when={selectedDb() !== null}>
                {/* Keys view within selected database */}
                <Show when={subLoading()}>
                    <div class="loading-state"><div class="loading-spinner" /> Loading keys…</div>
                </Show>
                <Show when={!subLoading()}>
                    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:12px;flex-wrap:wrap;gap:8px">
                        <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap">
                            {/* Namespace filter chips */}
                            <Show when={namespaces().length > 0}>
                                <span style="font-size:12px;color:var(--text-secondary)">Namespaces:</span>
                                <button onClick={clearNamespace}
                                    style={`padding:3px 10px;border:1px solid ${!namespaceFilter() ? 'var(--accent, #4285f4)' : 'var(--border)'};border-radius:12px;background:${!namespaceFilter() ? 'var(--accent, #4285f4)' : 'var(--bg)'};color:${!namespaceFilter() ? 'white' : 'var(--text-secondary)'};cursor:pointer;font-size:11px`}>
                                    All
                                </button>
                                <For each={namespaces()}>
                                    {(ns) => (
                                        <button onClick={() => selectNamespace(ns)}
                                            style={`padding:3px 10px;border:1px solid ${namespaceFilter() === ns ? 'var(--accent, #4285f4)' : 'var(--border)'};border-radius:12px;background:${namespaceFilter() === ns ? 'var(--accent, #4285f4)' : 'var(--bg)'};color:${namespaceFilter() === ns ? 'white' : 'var(--text-secondary)'};cursor:pointer;font-size:11px`}>
                                            {ns}:*
                                        </button>
                                    )}
                                </For>
                            </Show>
                        </div>
                        <Show when={props.onAdd}>
                            <button onClick={() => props.onAdd('Set Key in db' + selectedDb(), [
                                { name: 'key', type: 'text' },
                                { name: 'value', type: 'textarea' },
                                { name: 'type', type: 'select', value: 'string', options: ['string', 'list', 'hash', 'set', 'zset'] },
                                { name: 'ttl', type: 'text' }
                            ], async (formData) => {
                                await api.mutate('memorystore', 'keys', { ...formData, db: selectedDb() });
                                await refreshKeys();
                            })} style="padding:6px 14px;border:none;border-radius:4px;background:var(--accent, #4285f4);color:white;cursor:pointer;font-size:13px">
                                + Set Key
                            </button>
                        </Show>
                    </div>
                    <Show when={filteredKeys().length > 0} fallback={
                        <div class="empty-state">
                            <div class="empty-state-icon">{'\u2205'}</div>
                            <div class="empty-state-title">{namespaceFilter() ? 'No keys matching "' + namespaceFilter() + ':*"' : 'No keys in db' + selectedDb()}</div>
                            <div class="empty-state-text">{namespaceFilter() ? 'Try a different namespace filter.' : 'Use the + Set Key button or valkey-cli to add keys.'}</div>
                        </div>
                    }>
                        <div class="data-table-wrapper">
                            <table class="data-table">
                                <thead><tr><th>Key</th><th>Type</th><th>Value</th><th>TTL</th><th>Actions</th></tr></thead>
                                <tbody>
                                    <For each={filteredKeys()}>
                                        {(k) => (
                                            <tr>
                                                <td style={{ "font-weight": "500" }}>{k.key}</td>
                                                <td><span class="badge badge-neutral">{k.type}</span></td>
                                                <td style={{ "max-width": "300px", "overflow": "hidden", "text-overflow": "ellipsis", "white-space": "nowrap", "font-size": "12px" }}>{formatRedisValue(k.value, k.type)}</td>
                                                <td>{k.ttl || 'none'}</td>
                                                <td>
                                                    <div style="display:flex;gap:4px">
                                                        <Show when={props.onEdit}>
                                                            <button onClick={() => props.onEdit('Edit Key: ' + k.key, [
                                                                { name: 'key', type: 'text', value: k.key },
                                                                { name: 'value', type: 'textarea', value: typeof k.value === 'object' ? JSON.stringify(k.value) : (k.value || '') },
                                                                { name: 'type', type: 'text', value: k.type || 'string' },
                                                                { name: 'ttl', type: 'text', value: k.ttl || '' }
                                                            ], async (formData) => {
                                                                await api.mutate('memorystore', 'keys', { ...formData, db: selectedDb(), _method: 'PUT' });
                                                                await refreshKeys();
                                                            })} style="padding:4px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:var(--text-secondary);cursor:pointer;font-size:11px" title="Edit">Edit</button>
                                                        </Show>
                                                        <Show when={props.onDelete}>
                                                            <button onClick={() => props.onDelete('Delete key "' + k.key + '"?', async () => {
                                                                await api.mutate('memorystore', 'keys/delete', { key: k.key, db: selectedDb() });
                                                                await refreshKeys();
                                                            })} style="padding:4px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:#ea4335;cursor:pointer;font-size:11px" title="Delete">Del</button>
                                                        </Show>
                                                    </div>
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

// -- Firestore View (drill-down: collections -> documents) --
function FirestoreView(props) {
    const d = () => props.data();
    const [selectedCollection, setSelectedCollection] = createSignal(null);
    const [documents, setDocuments] = createSignal([]);
    const [subLoading, setSubLoading] = createSignal(false);

    const collections = () => {
        const raw = d();
        if (!raw) return [];
        return raw.collections || [];
    };

    const colName = (col) => typeof col === 'string' ? col : (col.name || col.id || '--');

    const updateSubpath = (collection) => {
        if (props.onSubpathChange) {
            props.onSubpathChange(collection ? [collection] : []);
        }
    };

    const selectCollection = async (name) => {
        setSelectedCollection(name);
        updateSubpath(name);
        setDocuments([]);
        setSubLoading(true);
        try {
            const result = await api.browse('firestore', name);
            setDocuments(result.documents || []);
        } catch { setDocuments([]); }
        finally { setSubLoading(false); }
    };

    const goBack = () => { setSelectedCollection(null); setDocuments([]); updateSubpath(null); };

    // Restore navigation from URL subpath
    createEffect(() => {
        const sp = typeof props.subpath === 'function' ? props.subpath() : props.subpath;
        if (!sp || sp.length === 0) {
            setSelectedCollection(null);
            setDocuments([]);
            return;
        }
        const cols = collections();
        if (!cols || cols.length === 0) return;
        const [colId] = sp;
        const currentCol = untrack(() => selectedCollection());
        if (colId && !currentCol) {
            const exists = cols.find(c => colName(c) === colId);
            if (exists) selectCollection(colId);
        } else if (!colId && currentCol) {
            setSelectedCollection(null);
            setDocuments([]);
        }
    });

    // Breadcrumb
    const breadcrumbs = createMemo(() => {
        const crumbs = [{ label: 'Collections', onClick: goBack, active: !selectedCollection() }];
        if (selectedCollection()) crumbs.push({ label: selectedCollection(), onClick: null, active: true });
        return crumbs;
    });

    // CSV Import
    const [showCsvImport, setShowCsvImport] = createSignal(false);
    const firestoreImportRow = async (targetCols, values) => {
        const fields = {};
        let docId = null;
        targetCols.forEach((col, i) => {
            const val = values[i]?.trim() || '';
            if (col === '__id' || col === 'documentId' || col === '_id') { docId = val; }
            else if (val) { fields[col] = val; }
        });
        if (!docId) docId = 'doc-' + Date.now() + '-' + Math.random().toString(36).slice(2, 6);
        return await api.mutate('firestore', 'documents', { collection: selectedCollection(), documentId: docId, fields });
    };

    const docFields = () => {
        const docs = documents();
        if (!docs || docs.length === 0) return [];
        const fieldSet = new Set();
        docs.forEach(doc => { Object.keys(doc).forEach(k => { if (k !== '__id') fieldSet.add(k); }); });
        return Array.from(fieldSet);
    };

    // Tree sidebar
    const TreeSidebar = () => (
        <Show when={collections().length > 0}>
            <div class="data-tree">
                <For each={collections()}>
                    {(col) => {
                        const name = colName(col);
                        return (
                            <button class={`data-tree-item ${selectedCollection() === name ? 'active' : ''}`} onClick={() => selectCollection(name)}>
                                <svg class="data-tree-item-icon" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true" focusable="false"><path d="M2 2h5l2 2h5v10H2V2zm1 1v10h10V5H8.5L6.5 3H3z" /></svg>
                                {name}
                            </button>
                        );
                    }}
                </For>
            </div>
        </Show>
    );

    // Content area
    const ContentArea = () => (
        <div class="data-tree-content">
            <Show when={subLoading()}>
                <div class="loading-state"><div class="loading-spinner" /> Loading…</div>
            </Show>
            <Show when={!subLoading()}>
                <Show when={selectedCollection()}>
                    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px">
                        <h2 style="margin:0;font-size:16px">{selectedCollection()}</h2>
                        <Show when={props.onAdd}>
                            <div style="display:flex;gap:6px">
                                <button onClick={() => setShowCsvImport(true)}
                                    style="padding:6px 12px;border:1px solid var(--border);border-radius:4px;background:var(--surface);color:var(--text-secondary);cursor:pointer;font-size:12px;display:flex;align-items:center;gap:5px;transition:border-color 0.15s, background 0.15s, color 0.15s"
                                    onMouseEnter={e => e.currentTarget.style.borderColor = 'var(--primary)'}
                                    onMouseLeave={e => e.currentTarget.style.borderColor = 'var(--border)'}>
                                    {'\u2191'} Import CSV
                                </button>
                                <button onClick={() => props.onAdd('Add Document to ' + selectedCollection(), [
                                    { name: 'documentId', type: 'text' },
                                    { name: 'fields', type: 'textarea', value: '{}' }
                                ], async (formData) => {
                                    let parsedFields = {};
                                    try { parsedFields = JSON.parse(formData.fields || '{}'); } catch (e) { alert('Invalid JSON in fields: ' + e.message); return; }
                                    await api.mutate('firestore', 'documents', { collection: selectedCollection(), documentId: formData.documentId, fields: parsedFields });
                                })} style="padding:6px 14px;border:none;border-radius:4px;background:var(--accent, #4285f4);color:white;cursor:pointer;font-size:13px">
                                    + Add Document
                                </button>
                            </div>
                        </Show>
                    </div>
                    <Show when={documents().length > 0} fallback={
                        <div class="empty-state">
                            <div class="empty-state-icon">{'\u2205'}</div>
                            <div class="empty-state-title">No documents found</div>
                            <div class="empty-state-text">This collection is empty.</div>
                        </div>
                    }>
                        <div class="data-table-wrapper">
                            <table class="data-table">
                                <thead>
                                    <tr>
                                        <th>Document ID</th>
                                        <For each={docFields()}>{(f) => <th>{f}</th>}</For>
                                        <th>Actions</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    <For each={documents()}>
                                        {(doc) => (
                                            <tr>
                                                <td style="font-weight:500">{doc.__id || doc.id || '--'}</td>
                                                <For each={docFields()}>
                                                    {(f) => <td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:12px">{doc[f] != null ? (typeof doc[f] === 'object' ? JSON.stringify(doc[f]) : String(doc[f])) : '--'}</td>}
                                                </For>
                                                <td>
                                                    <div style="display:flex;gap:4px">
                                                        <Show when={props.onEdit}>
                                                            <button onClick={() => {
                                                                const docData = {};
                                                                docFields().forEach(f => { if (doc[f] != null) docData[f] = doc[f]; });
                                                                const editFields = [
                                                                    { name: 'documentId', type: 'text', value: doc.__id || doc.id || '' },
                                                                    { name: 'fields', type: 'textarea', value: JSON.stringify(docData, null, 2) }
                                                                ];
                                                                props.onEdit('Edit Document', editFields, async (formData) => {
                                                                    let parsedFields = {};
                                                                    try { parsedFields = JSON.parse(formData.fields || '{}'); } catch (e) { alert('Invalid JSON in fields: ' + e.message); return; }
                                                                    await api.mutate('firestore', 'documents', { collection: selectedCollection(), documentId: formData.documentId, fields: parsedFields });
                                                                });
                                                            }} style="padding:4px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:var(--text-secondary);cursor:pointer;font-size:11px" title="Edit">Edit</button>
                                                        </Show>
                                                        <Show when={props.onDelete}>
                                                            <button onClick={() => props.onDelete('Delete document "' + (doc.__id || doc.id) + '" from ' + selectedCollection() + '?', async () => {
                                                                await api.mutate('firestore', 'documents/delete', { collection: selectedCollection(), documentId: doc.__id || doc.id });
                                                            })} style="padding:4px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:#ea4335;cursor:pointer;font-size:11px" title="Delete">Del</button>
                                                        </Show>
                                                    </div>
                                                </td>
                                            </tr>
                                        )}
                                    </For>
                                </tbody>
                            </table>
                        </div>
                    </Show>
                </Show>

                <Show when={!selectedCollection()}>
                    <Show when={collections().length > 0} fallback={
                        <div class="empty-state">
                            <div class="empty-state-icon">{'\u2205'}</div>
                            <div class="empty-state-title">No collections found</div>
                            <div class="empty-state-text">Create Firestore collections to see them here.</div>
                        </div>
                    }>
                        <div class="data-table-wrapper">
                            <table class="data-table">
                                <thead><tr><th>Collection</th></tr></thead>
                                <tbody>
                                    <For each={collections()}>
                                        {(col) => (
                                            <tr class="clickable-row" onClick={() => selectCollection(colName(col))} onKeyDown={onActivate(() => selectCollection(colName(col)))} role="button" tabIndex="0">
                                                <td style="font-weight:500">{colName(col)}</td>
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

    return (
        <div>
            <Show when={selectedCollection()}>
                <DataBreadcrumb crumbs={breadcrumbs()} />
            </Show>
            <Show when={collections().length > 0 && selectedCollection()} fallback={<ContentArea />}>
                <div class="data-tree-layout">
                    <TreeSidebar />
                    <ContentArea />
                </div>
            </Show>
            <CsvImportWizard
                show={showCsvImport()}
                onClose={() => setShowCsvImport(false)}
                tableName={selectedCollection()}
                columns={docFields().length > 0 ? ['__id', ...docFields()] : null}
                serviceName="Firestore"
                onImportRow={firestoreImportRow}
                onImportDone={() => selectCollection(selectedCollection())}
            />
        </div>
    );
}

// -- Bigtable View (drill-down: instances -> tables -> rows) --
function BigtableView(props) {
    const d = () => props.data();
    const [selectedInstance, setSelectedInstance] = createSignal(null);
    const [selectedTable, setSelectedTable] = createSignal(null);
    const [rows, setRows] = createSignal([]);
    const [subLoading, setSubLoading] = createSignal(false);

    const instances = () => {
        const raw = d();
        if (!raw) return [];
        if (Array.isArray(raw)) return raw;
        if (raw.tables) {
            const byInst = {};
            raw.tables.forEach(t => {
                const inst = t.instance || 'default';
                if (!byInst[inst]) byInst[inst] = { id: inst, type: 'instance', tables: [] };
                byInst[inst].tables.push({ id: t.table, name: t.table, columnFamilies: [] });
            });
            return Object.values(byInst);
        }
        return [];
    };

    const currentTables = () => {
        const inst = instances().find(i => i.id === selectedInstance());
        return inst ? (inst.tables || []) : [];
    };

    const updateSubpath = (inst, table) => {
        if (props.onSubpathChange) {
            const parts = [];
            if (inst) { parts.push(inst); if (table) parts.push(table); }
            props.onSubpathChange(parts);
        }
    };

    const selectInstance = (instId) => {
        setSelectedInstance(instId);
        setSelectedTable(null);
        setRows([]);
        updateSubpath(instId, null);
    };

    const selectTable = async (instanceId, tableId) => {
        setSelectedInstance(instanceId);
        setSelectedTable(tableId);
        updateSubpath(instanceId, tableId);
        setRows([]);
        setSubLoading(true);
        try {
            const result = await api.browse('bigtable', 'tables/' + instanceId + '/' + tableId);
            setRows(result.rows || []);
        } catch { setRows([]); }
        finally { setSubLoading(false); }
    };

    const goBackToTables = () => { setSelectedTable(null); setRows([]); updateSubpath(selectedInstance(), null); };
    const goBackToInstances = () => { setSelectedInstance(null); setSelectedTable(null); setRows([]); updateSubpath(null, null); };

    // Restore navigation from URL subpath
    createEffect(() => {
        const sp = typeof props.subpath === 'function' ? props.subpath() : props.subpath;
        if (!sp || sp.length === 0) {
            setSelectedInstance(null);
            setSelectedTable(null);
            setRows([]);
            return;
        }
        const insts = instances();
        if (!insts || insts.length === 0) return;
        const [instId, tblId] = sp;
        const currentInst = untrack(() => selectedInstance());
        const currentTbl = untrack(() => selectedTable());
        if (instId && !currentInst) {
            const inst = insts.find(i => i.id === instId);
            if (inst) {
                if (tblId) {
                    selectTable(instId, tblId);
                } else {
                    selectInstance(instId);
                }
            }
        } else if (!instId && currentInst) {
            setSelectedInstance(null);
            setSelectedTable(null);
            setRows([]);
        } else if (instId && currentInst === instId) {
            if (!tblId && currentTbl) {
                setSelectedTable(null);
                setRows([]);
            } else if (tblId && !currentTbl) {
                selectTable(instId, tblId);
            }
        }
    });

    // Breadcrumb
    const breadcrumbs = createMemo(() => {
        const crumbs = [{ label: 'Instances', onClick: goBackToInstances, active: !selectedInstance() }];
        if (selectedInstance()) {
            crumbs.push({ label: selectedInstance(), onClick: goBackToTables, active: !selectedTable() });
            if (selectedTable()) crumbs.push({ label: selectedTable(), onClick: null, active: true });
        }
        return crumbs;
    });

    // CSV Import
    const [showCsvImport, setShowCsvImport] = createSignal(false);
    const bigtableImportRow = async (targetCols, values) => {
        const rkIdx = targetCols.indexOf('rowKey');
        const rowKey = (rkIdx >= 0 ? values[rkIdx] : '') || 'row-' + Date.now() + '-' + Math.random().toString(36).slice(2, 6);
        const cells = {};
        targetCols.forEach((col, i) => {
            if (col === 'rowKey') return;
            const val = values[i]?.trim() || '';
            if (val) {
                const cfCol = col.includes(':') ? col : 'cf1:' + col;
                cells[cfCol] = val;
            }
        });
        return await api.mutate('bigtable', 'rows', { table: selectedInstance() + '/' + selectedTable(), rowKey, cells });
    };

    const rowColumns = () => {
        const r = rows();
        if (!r || r.length === 0) return [];
        const colSet = new Set();
        r.forEach(row => { Object.keys(row.cells || {}).forEach(k => colSet.add(k)); });
        return Array.from(colSet);
    };

    // Tree sidebar
    const TreeSidebar = () => (
        <Show when={instances().length > 0}>
            <div class="data-tree">
                <For each={instances()}>
                    {(inst) => {
                        const isOpen = () => selectedInstance() === inst.id;
                        return (
                            <div>
                                <button class={`data-tree-toggle ${!isOpen() ? 'collapsed' : ''}`} onClick={() => { if (isOpen()) goBackToInstances(); else selectInstance(inst.id); }}>
                                    <svg width="10" height="10" viewBox="0 0 10 10" fill="currentColor" aria-hidden="true" focusable="false"><path d="M3 2l4 3-4 3z" /></svg>
                                    {inst.id}
                                </button>
                                <Show when={isOpen()}>
                                    <div class="data-tree-group">
                                        <For each={inst.tables || []}>
                                            {(tbl) => {
                                                const tblId = tbl.id || tbl.name;
                                                return (
                                                    <button class={`data-tree-item ${selectedTable() === tblId ? 'active' : ''}`} onClick={() => selectTable(inst.id, tblId)}>
                                                        <svg class="data-tree-item-icon" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true" focusable="false"><path d="M2 3h12v2H2zm0 4h12v2H2zm0 4h12v2H2z" /></svg>
                                                        {tblId}
                                                    </button>
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
    );

    // Content area
    const ContentArea = () => (
        <div class="data-tree-content">
            <Show when={subLoading()}>
                <div class="loading-state"><div class="loading-spinner" /> Loading…</div>
            </Show>
            <Show when={!subLoading()}>
                {/* Rows */}
                <Show when={selectedTable()}>
                    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:12px">
                        <h2 style="margin:0;font-size:16px">{selectedInstance()} / {selectedTable()}</h2>
                        <Show when={props.onAdd}>
                            <div style="display:flex;gap:6px">
                                <button onClick={() => setShowCsvImport(true)}
                                    style="padding:6px 12px;border:1px solid var(--border);border-radius:4px;background:var(--surface);color:var(--text-secondary);cursor:pointer;font-size:12px;display:flex;align-items:center;gap:5px;transition:border-color 0.15s, background 0.15s, color 0.15s"
                                    onMouseEnter={e => e.currentTarget.style.borderColor = 'var(--primary)'}
                                    onMouseLeave={e => e.currentTarget.style.borderColor = 'var(--border)'}>
                                    {'\u2191'} Import CSV
                                </button>
                                <button onClick={() => props.onAdd('Add Row to ' + selectedTable(), [
                                    { name: 'rowKey', type: 'text' },
                                    { name: 'columnFamily', type: 'text' },
                                    { name: 'column', type: 'text' },
                                    { name: 'value', type: 'textarea' }
                                ], async (formData) => {
                                    await api.mutate('bigtable', 'rows', { table: selectedInstance() + '/' + selectedTable(), ...formData });
                                })} style="padding:6px 14px;border:none;border-radius:4px;background:var(--accent, #4285f4);color:white;cursor:pointer;font-size:13px">
                                    + Add Row
                                </button>
                            </div>
                        </Show>
                    </div>
                    <Show when={rows().length > 0} fallback={
                        <div class="empty-state">
                            <div class="empty-state-icon">{'\u2205'}</div>
                            <div class="empty-state-title">No rows found</div>
                            <div class="empty-state-text">This table is empty.</div>
                        </div>
                    }>
                        <div class="data-table-wrapper">
                            <table class="data-table">
                                <thead><tr><th>Row Key</th><For each={rowColumns()}>{(c) => <th>{c}</th>}</For><th>Actions</th></tr></thead>
                                <tbody>
                                    <For each={rows()}>
                                        {(row) => (
                                            <tr>
                                                <td style="font-weight:500">{row.rowKey || row.row_key || '--'}</td>
                                                <For each={rowColumns()}>
                                                    {(c) => {
                                                        const val = (row.cells || {})[c];
                                                        return <td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:12px">{val != null ? (typeof val === 'object' ? JSON.stringify(val) : String(val)) : '--'}</td>;
                                                    }}
                                                </For>
                                                <td>
                                                    <Show when={props.onDelete}>
                                                        <button onClick={() => props.onDelete('Delete row "' + (row.rowKey || row.row_key) + '" from ' + selectedTable() + '?', async () => {
                                                            await api.mutate('bigtable', 'rows/delete', { table: selectedInstance() + '/' + selectedTable(), rowKey: row.rowKey || row.row_key });
                                                        })} style="padding:4px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:#ea4335;cursor:pointer;font-size:11px" title="Delete">Del</button>
                                                    </Show>
                                                </td>
                                            </tr>
                                        )}
                                    </For>
                                </tbody>
                            </table>
                        </div>
                    </Show>
                </Show>

                {/* Tables list */}
                <Show when={selectedInstance() && !selectedTable()}>
                    <h2 style="margin:0 0 12px 0;font-size:16px">Instance: {selectedInstance()}</h2>
                    <Show when={currentTables().length > 0} fallback={
                        <div class="empty-state">
                            <div class="empty-state-icon">{'\u2205'}</div>
                            <div class="empty-state-title">No tables</div>
                            <div class="empty-state-text">This instance has no tables.</div>
                        </div>
                    }>
                        <div class="data-table-wrapper">
                            <table class="data-table">
                                <thead><tr><th>Table</th><th>Column Families</th></tr></thead>
                                <tbody>
                                    <For each={currentTables()}>
                                        {(tbl) => (
                                            <tr class="clickable-row" onClick={() => selectTable(selectedInstance(), tbl.id || tbl.name)} onKeyDown={onActivate(() => selectTable(selectedInstance(), tbl.id || tbl.name))} role="button" tabIndex="0">
                                                <td style="font-weight:500">{tbl.id || tbl.name}</td>
                                                <td style="font-size:12px;color:var(--text-secondary)">{(tbl.columnFamilies || []).join(', ') || '--'}</td>
                                            </tr>
                                        )}
                                    </For>
                                </tbody>
                            </table>
                        </div>
                    </Show>
                </Show>

                {/* Instances list */}
                <Show when={!selectedInstance()}>
                    <Show when={instances().length > 0} fallback={
                        <div class="empty-state">
                            <div class="empty-state-icon">{'\u2205'}</div>
                            <div class="empty-state-title">No instances found</div>
                            <div class="empty-state-text">Create Bigtable instances to see them here.</div>
                        </div>
                    }>
                        <div class="data-table-wrapper">
                            <table class="data-table">
                                <thead><tr><th>Instance</th><th>Tables</th></tr></thead>
                                <tbody>
                                    <For each={instances()}>
                                        {(inst) => (
                                            <tr class="clickable-row" onClick={() => selectInstance(inst.id)} onKeyDown={onActivate(() => selectInstance(inst.id))} role="button" tabIndex="0">
                                                <td style="font-weight:500">{inst.id}</td>
                                                <td style="font-size:12px;color:var(--text-secondary)">{(inst.tables || []).length} table(s)</td>
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

    return (
        <div>
            <Show when={selectedInstance()}>
                <DataBreadcrumb crumbs={breadcrumbs()} />
            </Show>
            <Show when={instances().length > 0 && selectedInstance()} fallback={<ContentArea />}>
                <div class="data-tree-layout">
                    <TreeSidebar />
                    <ContentArea />
                </div>
            </Show>
            <CsvImportWizard
                show={showCsvImport()}
                onClose={() => setShowCsvImport(false)}
                tableName={selectedInstance() + '/' + selectedTable()}
                columns={['rowKey', ...rowColumns()]}
                serviceName="Bigtable"
                onImportRow={bigtableImportRow}
                onImportDone={() => selectTable(selectedInstance(), selectedTable())}
            />
        </div>
    );
}

// -- CRUD Modal Component --
function CrudModal(props) {
    const [formData, setFormData] = createSignal({});

    createEffect(() => {
        if (props.show) {
            const initial = {};
            (props.fields || []).forEach(f => { initial[f.name] = f.value || ''; });
            setFormData(initial);
        }
    });

    const updateField = (name, value) => {
        setFormData(prev => ({ ...prev, [name]: value }));
    };

    return (
        <Show when={props.show}>
            <div role="dialog" aria-modal="true" aria-labelledby="crud-modal-title" style="position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.5);display:flex;align-items:center;justify-content:center;z-index:1000;overscroll-behavior:contain">
                <div style="background:var(--surface);border-radius:8px;padding:24px;min-width:400px;max-width:600px;max-height:80vh;overflow-y:auto;border:1px solid var(--border)">
                    <h3 id="crud-modal-title" style="margin:0 0 16px 0;color:var(--text)">{props.title}</h3>
                    <Show when={props.error}>
                        <div class="alert alert-error" role="alert" style="margin-bottom:12px">{props.error}</div>
                    </Show>
                    <For each={props.fields || []}>
                        {(field) => {
                            const fieldId = `crud-field-${String(field.name).replace(/[^a-zA-Z0-9_-]/g, '-')}`;
                            return (
                                <div style="margin-bottom:12px">
                                    <label for={fieldId} style="display:block;margin-bottom:4px;font-size:12px;color:var(--text-secondary)">{field.name}</label>
                                    {field.type === 'select' ? (
                                        <select id={fieldId} name={field.name} value={formData()[field.name] || ''} onChange={e => updateField(field.name, e.target.value)}
                                            style="width:100%;padding:8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:var(--text);font-size:13px">
                                            <For each={field.options || []}>{opt => <option value={opt}>{opt}</option>}</For>
                                        </select>
                                    ) : field.type === 'textarea' ? (
                                        <textarea id={fieldId} name={field.name} autocomplete="off" value={formData()[field.name] || ''} onInput={e => updateField(field.name, e.target.value)}
                                            rows="4" style="width:100%;padding:8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:var(--text);font-size:13px;font-family:monospace;resize:vertical" />
                                    ) : (
                                        <input id={fieldId} name={field.name} autocomplete="off" type="text" value={formData()[field.name] || ''} onInput={e => updateField(field.name, e.target.value)}
                                            style="width:100%;padding:8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:var(--text);font-size:13px" />
                                    )}
                                </div>
                            );
                        }}
                    </For>
                    <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:16px">
                        <button disabled={props.submitting} onClick={props.onClose} style="padding:8px 16px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:var(--text);cursor:pointer">Cancel</button>
                        <button disabled={props.submitting} onClick={() => props.onSubmit(formData())} style={{
                            padding: '8px 16px',
                            border: 'none',
                            'border-radius': '4px',
                            background: 'var(--accent, #4285f4)',
                            color: 'white',
                            cursor: props.submitting ? 'default' : 'pointer',
                            opacity: props.submitting ? 0.7 : 1,
                        }}>{props.submitting ? 'Saving...' : (props.mode === 'edit' ? 'Save' : 'Create')}</button>
                    </div>
                </div>
            </div>
        </Show>
    );
}

// -- Delete Confirmation Component --
function DeleteConfirmation(props) {
    return (
        <Show when={props.show}>
            <div role="dialog" aria-modal="true" aria-labelledby="delete-confirm-title" style="position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.5);display:flex;align-items:center;justify-content:center;z-index:1000;overscroll-behavior:contain">
                <div style="background:var(--surface);border-radius:8px;padding:24px;min-width:350px;border:1px solid var(--border)">
                    <h3 id="delete-confirm-title" style="margin:0 0 8px 0;color:var(--text)">Confirm Delete</h3>
                    <p style="color:var(--text-secondary);margin:0 0 16px 0">{props.message || 'Are you sure you want to delete this item?'}</p>
                    <div style="display:flex;gap:8px;justify-content:flex-end">
                        <button onClick={props.onClose} style="padding:8px 16px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:var(--text);cursor:pointer">Cancel</button>
                        <button onClick={props.onConfirm} style="padding:8px 16px;border:none;border-radius:4px;background:#ea4335;color:white;cursor:pointer">Delete</button>
                    </div>
                </div>
            </div>
        </Show>
    );
}

// Services that only need a connection info card
const CONNECTION_ONLY = new Set(['gke', 'compute', 'cloudrun']);

// Services that fetch data from browse API
const FETCH_SERVICES = new Set([
    'gcs', 'pubsub', 'firestore', 'bigquery', 'secretmanager', 'cloudtasks',
    'logging', 'monitoring', 'spanner', 'bigtable', 'memorystore', 'cloudsql',
    'cloudscheduler', 'cloudfunctions', 'alloydb', 'dataproc', 'cloudiam',
]);

const STANDARD_DATABASE_EXPLORER_SERVICES = new Set([
    'spanner', 'bigquery', 'alloydb', 'cloudsql', 'bigtable', 'firestore', 'memorystore',
]);

export default function DataBrowser(props) {
    const [selectedTab, setSelectedTab] = createSignal('gcs');
    const [data, setData] = createSignal(null);
    const [loading, setLoading] = createSignal(true);
    const [error, setError] = createSignal(null);
    const [serviceHealth, setServiceHealth] = createSignal({});

    // CRUD modal state
    const [showCrudModal, setShowCrudModal] = createSignal(false);
    const [crudFields, setCrudFields] = createSignal([]);
    const [crudTitle, setCrudTitle] = createSignal('');
    const [crudMode, setCrudMode] = createSignal('add');
    const [crudCallback, setCrudCallback] = createSignal(null);
    const [crudError, setCrudError] = createSignal(null);
    const [crudSubmitting, setCrudSubmitting] = createSignal(false);
    const [showDeleteConfirm, setShowDeleteConfirm] = createSignal(false);
    const [deleteMessage, setDeleteMessage] = createSignal('');
    const [deleteCallback, setDeleteCallback] = createSignal(null);

    // Tab sync is handled by the parent (app.jsx) via props.selectedService.
    // No duplicate hash listener needed here — app.jsx already listens to hashchange.

    // Fetch service health once on mount for tab indicators.
    // Health polling is handled globally by app.jsx — no need for redundant polling here.
    const loadHealth = async () => {
        try {
            const data = await api.health();
            setServiceHealth(data.services || {});
        } catch (e) { }
    };
    loadHealth();

    // Also sync from parent prop (for dashboard click navigation)
    createEffect(() => {
        const svc = props.selectedService ? props.selectedService() : null;
        if (svc && TABS.find(t => t.id === svc)) {
            setSelectedTab(svc);
        }
    });

    const fetchData = async (tab) => {
        if (!FETCH_SERVICES.has(tab)) return;
        setLoading(true);
        setError(null);
        try {
            let result;
            if (tab === 'pubsub') {
                const [topicsRes, subsRes] = await Promise.all([
                    api.browse('pubsub'),
                    api.browse('pubsub/subscriptions'),
                ]);
                result = { topics: topicsRes.topics || [], subscriptions: subsRes.subscriptions || [] };
            } else if (tab === 'cloudsql') {
                const schema = await api.schema('cloudsql');
                result = {
                    ...schema,
                    tables: (schema.tables || []).filter(table => CLOUDSQL_CATALOG_TABLES.has(table.name)),
                };
            } else {
                result = await api.browse(tab);
            }
            setData(result);
        } catch (err) {
            setError('Could not load data: ' + err.message);
        } finally {
            setLoading(false);
        }
    };

    const loadData = () => fetchData(selectedTab());

    // Watch for parent-triggered refresh/reset
    createEffect(() => {
        const trigger = props.refreshTrigger?.();
        if (trigger > 0) loadData();
    });
    createEffect(() => {
        const trigger = props.resetTrigger?.();
        if (trigger > 0) {
            setLoading(true);
            api.resetService(selectedTab(), false).then(() => loadData());
        }
    });

    const handleAdd = (title, fields, callback) => {
        setCrudTitle(title);
        setCrudFields(fields);
        setCrudMode('add');
        setCrudCallback(() => callback);
        setCrudError(null);
        setCrudSubmitting(false);
        setShowCrudModal(true);
    };

    const handleEdit = (title, fields, callback) => {
        setCrudTitle(title);
        setCrudFields(fields);
        setCrudMode('edit');
        setCrudCallback(() => callback);
        setCrudError(null);
        setCrudSubmitting(false);
        setShowCrudModal(true);
    };

    const handleDelete = (message, callback) => {
        setDeleteMessage(message);
        setDeleteCallback(() => callback);
        setShowDeleteConfirm(true);
    };

    const handleCrudSubmit = async (formData) => {
        setCrudError(null);
        setCrudSubmitting(true);
        try {
            const cb = crudCallback();
            if (cb) await cb(formData);
            setShowCrudModal(false);
            if (!STANDARD_DATABASE_EXPLORER_SERVICES.has(selectedTab())) {
                loadData();
            }
        } catch (err) {
            setCrudError(err.message || 'Operation failed');
        } finally {
            setCrudSubmitting(false);
        }
    };

    const handleDeleteConfirm = async () => {
        try {
            const cb = deleteCallback();
            if (cb) await cb();
            setShowDeleteConfirm(false);
            loadData();
        } catch (err) {
            setShowDeleteConfirm(false);
            setError('Delete failed: ' + (err.message || 'Unknown error'));
        }
    };

    // Fetch data when tab or project changes
    // props.activeProject is a signal accessor — call it to track reactively
    let prevTab = null;
    createEffect(() => {
        const tab = selectedTab();
        const proj = typeof props.activeProject === 'function' ? props.activeProject() : props.activeProject;
        setError(null);
        setData(null); // Clear stale data immediately to prevent showing wrong service content
        // Only clear subpath when service tab actually changes, not on initial mount
        if (prevTab !== null && prevTab !== tab) {
            if (props.onSubpathChange) props.onSubpathChange([]);
        }
        prevTab = tab;
        if (FETCH_SERVICES.has(tab)) {
            fetchData(tab);
        }
    });

    const renderServiceView = () => {
        const tab = selectedTab();

        // Connection-only services
        if (CONNECTION_ONLY.has(tab)) {
            const info = SERVICE_INFO[tab];
            if (info) {
                return (
                    <ConnectionInfoCard
                        name={info.name}
                        port={info.port}
                        envVar={info.envVar}
                        envValue={info.envValue}
                        protocol={info.protocol}
                        description={info.description}
                    />
                );
            }
        }

        // Fetched data services
        if (loading()) {
            return <div class="loading-state"><div class="loading-spinner" /> Loading…</div>;
        }
        if (error()) {
            return (
                <div>
                    <div class="alert alert-error" role="alert">{error()}</div>
                    <button class="btn btn-secondary" onClick={() => fetchData(tab)}>Retry</button>
                </div>
            );
        }

        switch (tab) {
            case 'spanner':
            case 'bigquery':
            case 'alloydb':
            case 'cloudsql':
            case 'bigtable':
            case 'firestore':
            case 'memorystore':
                return <DatabaseExplorer serviceId={tab} data={data} onRefresh={loadData} onAdd={handleAdd} onEdit={handleEdit} onDelete={handleDelete} subpath={props.subpath} onSubpathChange={props.onSubpathChange} />;
            case 'gcs': return <GcsView data={data} onAdd={handleAdd} onEdit={handleEdit} onDelete={handleDelete} />;
            case 'pubsub': return <PubSubView data={data} onAdd={handleAdd} onDelete={handleDelete} />;
            case 'secretmanager': return <SecretManagerView data={data} onAdd={handleAdd} onDelete={handleDelete} />;
            case 'cloudtasks': return <CloudTasksView data={data} onAdd={handleAdd} onDelete={handleDelete} />;
            case 'logging': return <LoggingView data={data} />;
            case 'monitoring': return <MonitoringView data={data} />;
            case 'cloudscheduler': return <CloudSchedulerView data={data} onAdd={handleAdd} onDelete={handleDelete} />;
            case 'cloudfunctions': return <CloudFunctionsView data={data} onAdd={handleAdd} onDelete={handleDelete} />;
            case 'dataproc': return <DataprocView data={data} onAdd={handleAdd} onDelete={handleDelete} />;
            case 'cloudiam': return <CloudIAMView data={data} onAdd={handleAdd} onDelete={handleDelete} />;
            default: return null;
        }
    };

    const activeTabLabel = () => {
        const tab = TABS.find(t => t.id === selectedTab());
        return tab ? tab.label : selectedTab();
    };

    return (
        <div style="padding:16px 24px;overflow-y:auto;flex:1;min-height:0;display:flex;flex-direction:column">
            {/* Service Content — full width */}
            {renderServiceView()}

            <CrudModal
                show={showCrudModal()}
                onClose={() => setShowCrudModal(false)}
                onSubmit={handleCrudSubmit}
                title={crudTitle()}
                fields={crudFields()}
                mode={crudMode()}
                error={crudError()}
                submitting={crudSubmitting()}
            />
            <DeleteConfirmation
                show={showDeleteConfirm()}
                onClose={() => setShowDeleteConfirm(false)}
                onConfirm={handleDeleteConfirm}
                message={deleteMessage()}
            />
        </div>
    );
}

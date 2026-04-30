import { createSignal, createEffect, onCleanup, Show, For } from 'solid-js';
import { api } from '../api.js';


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
];

const SERVICE_INFO = {
    firestore: {
        name: 'Firestore',
        port: 8086,
        envVar: 'FIRESTORE_EMULATOR_HOST',
        envValue: 'localhost:8086',
        protocol: 'gRPC',
        description: 'Use the Firestore SDK to browse documents.',
    },
    spanner: {
        name: 'Spanner',
        port: 9010,
        envVar: 'SPANNER_EMULATOR_HOST',
        envValue: 'localhost:9010',
        protocol: 'gRPC',
        description: 'Use Spanner SDK to browse databases.',
    },
    bigtable: {
        name: 'Bigtable',
        port: 8087,
        envVar: 'BIGTABLE_EMULATOR_HOST',
        envValue: 'localhost:8087',
        protocol: 'gRPC',
        description: 'Use the Bigtable SDK to browse tables.',
    },
    gke: {
        name: 'GKE',
        port: 443,
        envVar: 'GKE_EMULATOR_HOST',
        envValue: 'localhost:443',
        protocol: 'gRPC',
        description: 'Use GKE SDK or kubectl to manage clusters.',
    },
    compute: {
        name: 'Compute Engine',
        port: 4443,
        envVar: 'COMPUTE_EMULATOR_HOST',
        envValue: 'localhost:4443',
        protocol: 'REST',
        description: 'Use the Compute Engine SDK to manage instances.',
    },
    cloudrun: {
        name: 'Cloud Run',
        port: 4443,
        envVar: 'CLOUD_RUN_EMULATOR_HOST',
        envValue: 'localhost:4443',
        protocol: 'gRPC',
        description: 'Use the Cloud Run SDK to manage services.',
    },
};

function formatSize(bytes) {
    if (bytes == null) return '--';
    const n = Number(bytes);
    if (n === 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(n) / Math.log(1024));
    return `${(n / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0)} ${units[i]}`;
}

function formatDate(ts) {
    if (!ts) return '--';
    try {
        const d = new Date(ts);
        if (isNaN(d.getTime())) return ts;
        return d.toLocaleString();
    } catch {
        return ts;
    }
}

// -- Reusable ConnectionInfoCard --
function ConnectionInfoCard(props) {
    const [copied, setCopied] = createSignal(false);

    const copyEnvVar = () => {
        const text = `${props.envVar}=${props.envValue}`;
        navigator.clipboard.writeText(text).then(() => {
            setCopied(true);
            setTimeout(() => setCopied(false), 2000);
        }).catch(() => {});
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
                            {name: 'key', type: 'text'},
                            {name: 'content', type: 'textarea'},
                            {name: 'contentType', type: 'text', value: 'application/json'}
                        ], async (formData) => {
                            await api.mutate('gcs', 'objects', { bucket: selectedBucket(), ...formData });
                        })} style="padding:6px 14px;border:none;border-radius:4px;background:var(--accent, #4285f4);color:white;cursor:pointer;font-size:13px">
                            + Upload Object
                        </button>
                    </Show>
                </div>
                <Show when={!objectsLoading()} fallback={
                    <div class="loading-state"><div class="loading-spinner" /> Loading objects...</div>
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
                            style={{ border: '2px dashed var(--border)', 'border-radius': '8px', cursor: 'pointer', transition: 'all 150ms ease' }}
                        >
                            <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--text-tertiary)" stroke-width="1.5" style={{ 'margin-bottom': '12px' }}>
                                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                                <polyline points="17 8 12 3 7 8"/>
                                <line x1="12" y1="3" x2="12" y2="15"/>
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
                        {name: 'name', type: 'text'},
                        {name: 'location', type: 'text', value: 'US'}
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
                                    <tr class="clickable-row" onClick={() => fetchBucketObjects(bucket.name)}>
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
                            {name: 'name', type: 'text'},
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
                                                                {name: 'data', type: 'textarea'},
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
                        <div class="loading-state"><div class="loading-spinner" /> Loading messages...</div>
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
                                                        {msg.data ? (msg.data.length > 100 ? msg.data.substring(0, 100) + '...' : msg.data) : '--'}
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

// -- BigQuery View (drill-down: datasets -> tables -> data) --
function BigQueryView(props) {
    const d = () => props.data();
    const [selectedDataset, setSelectedDataset] = createSignal(null);
    const [selectedTable, setSelectedTable] = createSignal(null);
    const [tables, setTables] = createSignal([]);
    const [tableData, setTableData] = createSignal(null);
    const [subLoading, setSubLoading] = createSignal(false);

    const datasets = () => {
        const raw = d();
        if (!raw) return [];
        // Handle both {items: [...]} and {datasets: [...]} shapes
        if (raw.items && Array.isArray(raw.items)) return raw.items;
        if (raw.datasets && Array.isArray(raw.datasets)) return raw.datasets;
        return [];
    };

    const selectDataset = async (ds) => {
        const dsId = ds.datasetReference ? ds.datasetReference.datasetId : (ds.id || ds.name);
        setSelectedDataset(dsId);
        setSelectedTable(null);
        setTables([]);
        setSubLoading(true);
        try {
            const result = await api.browse('bigquery', 'datasets/' + dsId);
            // BigQuery tables list returns {tables: [...]} or {items: [...]}
            const tblList = result.tables || result.items || [];
            setTables(tblList);
        } catch { setTables([]); }
        finally { setSubLoading(false); }
    };

    const selectTable = async (tbl) => {
        const tblId = tbl.tableReference ? tbl.tableReference.tableId : (tbl.name || tbl.id);
        setSelectedTable(tblId);
        setTableData(null);
        setSubLoading(true);
        try {
            const result = await api.browse('bigquery', 'datasets/' + selectedDataset() + '/tables/' + tblId + '/data');
            setTableData(result);
        } catch { setTableData(null); }
        finally { setSubLoading(false); }
    };

    const goBackToDatasets = () => {
        setSelectedDataset(null);
        setSelectedTable(null);
    };

    const goBackToTables = () => {
        setSelectedTable(null);
    };

    return (
        <div>
            <Show when={subLoading()}>
                <div class="loading-state"><div class="loading-spinner" /> Loading...</div>
            </Show>
            <Show when={!subLoading()}>
                {/* Level 3: Table data */}
                <Show when={selectedTable()}>
                    <button class="back-link" onClick={goBackToTables}>
                        {'\u2190'} Back to tables
                    </button>
                    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px">
                        <h2 style="margin:0">Table: {selectedDataset()}.{selectedTable()}</h2>
                        <Show when={props.onAdd && tableData() && tableData().columns}>
                            <button onClick={() => props.onAdd('Add BigQuery Row',
                                tableData().columns.map(c => ({name: c, type: 'text'})),
                                async (formData) => {
                                    await api.mutate('bigquery', 'rows', { dataset: selectedDataset(), table: selectedTable(), row: formData });
                                }
                            )} style="padding:6px 12px;border:none;border-radius:4px;background:var(--accent, #4285f4);color:white;cursor:pointer;font-size:12px">
                                + Add Row
                            </button>
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
                                                                    <button onClick={() => props.onDelete('Delete this row?', async () => {
                                                                        await api.mutate('bigquery', 'rows/delete', {
                                                                            dataset: selectedDataset(),
                                                                            table: selectedTable(),
                                                                            whereClause: `${firstCol} = '${String(value).replace(/'/g, "''")}'`
                                                                        });
                                                                    })} style="padding:4px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:#ea4335;cursor:pointer;font-size:11px" title="Delete">Del</button>
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

                {/* Level 2: Tables in dataset */}
                <Show when={selectedDataset() && !selectedTable()}>
                    <button class="back-link" onClick={goBackToDatasets}>
                        {'\u2190'} Back to datasets
                    </button>
                    <h2>Dataset: {selectedDataset()}</h2>
                    <Show when={tables().length > 0} fallback={
                        <div class="empty-state">
                            <div class="empty-state-icon">{'\u2205'}</div>
                            <div class="empty-state-title">No tables found</div>
                            <div class="empty-state-text">Create tables in this dataset.</div>
                        </div>
                    }>
                        <div class="data-table-wrapper">
                            <table class="data-table">
                                <thead><tr><th>Table Name</th><th>Kind</th><th>Type</th></tr></thead>
                                <tbody>
                                    <For each={tables()}>
                                        {(tbl) => {
                                            const tblId = tbl.tableReference ? tbl.tableReference.tableId : (tbl.name || tbl.id);
                                            return (
                                                <tr class="clickable-row" onClick={() => selectTable(tbl)}>
                                                    <td style={{ "font-weight": "500" }}>{tblId}</td>
                                                    <td>{tbl.kind || 'table'}</td>
                                                    <td>{tbl.type || '--'}</td>
                                                </tr>
                                            );
                                        }}
                                    </For>
                                </tbody>
                            </table>
                        </div>
                    </Show>
                </Show>

                {/* Level 1: Datasets */}
                <Show when={!selectedDataset()}>
                    <Show when={datasets().length > 0} fallback={
                        <div class="empty-state">
                            <div class="empty-state-icon">{'\u2205'}</div>
                            <div class="empty-state-title">No datasets found</div>
                            <div class="empty-state-text">Create a BigQuery dataset to see it here.</div>
                        </div>
                    }>
                        <div class="data-table-wrapper">
                            <table class="data-table">
                                <thead>
                                    <tr>
                                        <th>Dataset</th>
                                        <th>Kind</th>
                                        <th>Location</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    <For each={datasets()}>
                                        {(ds) => {
                                            const dsName = ds.datasetReference
                                                ? ds.datasetReference.datasetId
                                                : (ds.id || ds.name || '--');
                                            return (
                                                <tr class="clickable-row" onClick={() => selectDataset(ds)}>
                                                    <td style={{ "font-weight": "500" }}>{dsName}</td>
                                                    <td>{ds.kind || 'dataset'}</td>
                                                    <td>{ds.location || '--'}</td>
                                                </tr>
                                            );
                                        }}
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

// -- Secret Manager View --
function SecretManagerView(props) {
    const d = () => props.data();
    return (
        <div>
            <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px">
                <div />
                <Show when={props.onAdd}>
                    <button onClick={() => props.onAdd('Create Secret', [
                        {name: 'name', type: 'text'},
                        {name: 'value', type: 'textarea'}
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
                        {name: 'name', type: 'text'},
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

    const handleCreateTable = async () => {
        const ddl = createDdl().trim();
        if (!ddl) return;
        setCreating(true); setCreateError(null);
        try {
            await api.mutate('spanner', 'ddl', { instance: selectedInstance(), database: selectedDatabase(), statements: [ddl] });
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

    const selectInstance = async (inst) => {
        const name = inst.name?.split('/').pop() || inst.displayName;
        setSelectedInstance(name);
        setSelectedDatabase(null);
        setSelectedTable(null);
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
    };

    const goBackToDatabases = () => {
        setSelectedDatabase(null);
        setSelectedTable(null);
    };

    const goBackToTables = () => {
        setSelectedTable(null);
    };

    return (
        <div>
            <Show when={subLoading()}>
                <div class="loading-state"><div class="loading-spinner" /> Loading...</div>
            </Show>
            <Show when={!subLoading()}>
                {/* Level 4: Table data */}
                <Show when={selectedTable()}>
                    <button class="back-link" onClick={goBackToTables}>
                        {'\u2190'} Back to tables
                    </button>
                    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px">
                        <h2 style="margin:0">Table: {selectedTable()}</h2>
                        <Show when={props.onAdd && tableData() && tableData().columns}>
                            <button onClick={() => props.onAdd('Add Spanner Row',
                                tableData().columns.map(c => ({name: c, type: 'text'})),
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
                                                        <div style="display:flex;gap:4px;align-items:center">
                                                            <Show when={props.onEdit}>
                                                                {(() => {
                                                                    const columns = tableData().columns;
                                                                    return (
                                                                        <button onClick={() => props.onEdit('Edit Spanner Row',
                                                                            columns.map(c => ({name: c, type: 'text', value: row[c] != null ? String(row[c]) : ''})),
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
                                    </tbody>
                                </table>
                            </div>
                        </Show>
                    </Show>
                </Show>

                {/* Level 3: Tables from DDL */}
                <Show when={selectedDatabase() && !selectedTable()}>
                    <button class="back-link" onClick={goBackToDatabases}>
                        {'\u2190'} Back to databases
                    </button>
                    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px">
                        <h2 style="margin:0">Database: {selectedDatabase()}</h2>
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
                                        <thead><tr><th>Table Name</th></tr></thead>
                                        <tbody>
                                            <For each={tables}>
                                                {(tbl) => (
                                                    <tr class="clickable-row" onClick={() => selectTable(tbl)}>
                                                        <td style={{ "font-weight": "500" }}>{tbl}</td>
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
                    <button class="back-link" onClick={goBackToInstances}>
                        {'\u2190'} Back to instances
                    </button>
                    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px">
                        <h2 style="margin:0">Instance: {selectedInstance()}</h2>
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
                                                <tr class="clickable-row" onClick={() => selectDatabase(db)}>
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
                                        {(inst) => (
                                            <tr class="clickable-row" onClick={() => selectInstance(inst)}>
                                                <td style={{ "font-weight": "500" }}>{instanceName(inst)}</td>
                                                <td><span class={`badge ${inst.state === 'READY' ? 'badge-healthy' : 'badge-neutral'}`}>{inst.state || '--'}</span></td>
                                                <td>{inst.nodeCount || '--'}</td>
                                                <td>{formatDate(inst.createTime)}</td>
                                            </tr>
                                        )}
                                    </For>
                                </tbody>
                            </table>
                        </div>
                    </Show>
                </Show>

                {/* Create Instance Modal */}
                <Show when={showCreateInstance()}>
                    <div class="modal-overlay" role="dialog" aria-modal="true" onClick={(e) => { if (e.target === e.currentTarget) setShowCreateInstance(false); }}>
                        <div class="card modal-card" onClick={(e) => e.stopPropagation()}>
                            <h2 style="margin-bottom:16px">Create Spanner Instance</h2>
                            <Show when={createError()}><div class="alert alert-error" style="margin-bottom:12px">{createError()}</div></Show>
                            <div style="margin-bottom:16px">
                                <label class="form-label">Instance ID</label>
                                <input type="text" class="form-input form-input-mono" value={createName()} onInput={(e) => setCreateName(e.currentTarget.value)} placeholder="my-instance" />
                            </div>
                            <div style="display:flex;gap:8px;justify-content:flex-end">
                                <button class="btn btn-secondary" onClick={() => setShowCreateInstance(false)}>Cancel</button>
                                <button class="btn btn-primary" onClick={handleCreateInstance} disabled={creating() || !createName().trim()}>{creating() ? 'Creating...' : 'Create'}</button>
                            </div>
                        </div>
                    </div>
                </Show>

                {/* Create Database Modal */}
                <Show when={showCreateDatabase()}>
                    <div class="modal-overlay" role="dialog" aria-modal="true" onClick={(e) => { if (e.target === e.currentTarget) setShowCreateDatabase(false); }}>
                        <div class="card modal-card" onClick={(e) => e.stopPropagation()}>
                            <h2 style="margin-bottom:16px">Create Database in {selectedInstance()}</h2>
                            <Show when={createError()}><div class="alert alert-error" style="margin-bottom:12px">{createError()}</div></Show>
                            <div style="margin-bottom:16px">
                                <label class="form-label">Database Name</label>
                                <input type="text" class="form-input form-input-mono" value={createName()} onInput={(e) => setCreateName(e.currentTarget.value)} placeholder="my-database" />
                            </div>
                            <div style="display:flex;gap:8px;justify-content:flex-end">
                                <button class="btn btn-secondary" onClick={() => setShowCreateDatabase(false)}>Cancel</button>
                                <button class="btn btn-primary" onClick={handleCreateDatabase} disabled={creating() || !createName().trim()}>{creating() ? 'Creating...' : 'Create'}</button>
                            </div>
                        </div>
                    </div>
                </Show>

                {/* Create Table Modal */}
                <Show when={showCreateTable()}>
                    <div class="modal-overlay" role="dialog" aria-modal="true" onClick={(e) => { if (e.target === e.currentTarget) setShowCreateTable(false); }}>
                        <div class="card modal-card" onClick={(e) => e.stopPropagation()} style="max-width:600px">
                            <h2 style="margin-bottom:4px">Create Table in {selectedDatabase()}</h2>
                            <p style="font-size:12px;color:var(--text-secondary);margin-bottom:16px">Enter a Spanner DDL statement.</p>
                            <Show when={createError()}><div class="alert alert-error" style="margin-bottom:12px">{createError()}</div></Show>
                            <div style="margin-bottom:16px">
                                <label class="form-label">DDL Statement</label>
                                <textarea class="form-input form-input-mono" style="min-height:120px;resize:vertical;font-size:12px" value={createDdl()} onInput={(e) => setCreateDdl(e.currentTarget.value)}
                                    placeholder={"CREATE TABLE MyTable (\n  Id STRING(36) NOT NULL,\n  Name STRING(100),\n  CreatedAt TIMESTAMP\n) PRIMARY KEY (Id)"} />
                            </div>
                            <div style="display:flex;gap:8px;justify-content:flex-end">
                                <button class="btn btn-secondary" onClick={() => setShowCreateTable(false)}>Cancel</button>
                                <button class="btn btn-primary" onClick={handleCreateTable} disabled={creating() || !createDdl().trim()}>{creating() ? 'Executing...' : 'Execute DDL'}</button>
                            </div>
                        </div>
                    </div>
                </Show>
            </Show>
        </div>
    );
}

// -- Memorystore (Redis) View --
function MemorystoreView(props) {
    const d = () => props.data();
    const keys = () => {
        const raw = d();
        if (!raw) return [];
        return raw.keys || [];
    };
    return (
        <div>
            <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px">
                <div />
                <Show when={props.onAdd}>
                    <button onClick={() => props.onAdd('Set Key', [
                        {name: 'key', type: 'text'},
                        {name: 'value', type: 'textarea'},
                        {name: 'type', type: 'select', value: 'string', options: ['string', 'list', 'hash', 'set', 'zset']},
                        {name: 'ttl', type: 'text'}
                    ], async (formData) => {
                        await api.mutate('memorystore', 'keys', formData);
                    })} style="padding:6px 14px;border:none;border-radius:4px;background:var(--accent, #4285f4);color:white;cursor:pointer;font-size:13px">
                        + Set Key
                    </button>
                </Show>
            </div>
            <Show when={keys().length > 0} fallback={
                <div class="empty-state">
                    <div class="empty-state-icon">{'\u2205'}</div>
                    <div class="empty-state-title">No Redis keys found</div>
                    <div class="empty-state-text">Use redis-cli to set some keys.</div>
                </div>
            }>
                <div class="data-table-wrapper">
                    <table class="data-table">
                        <thead><tr><th>Key</th><th>Type</th><th>Value</th><th>TTL</th><th>Actions</th></tr></thead>
                        <tbody>
                            <For each={keys()}>
                                {(k) => (
                                    <tr>
                                        <td style={{ "font-weight": "500" }}>{k.key}</td>
                                        <td><span class="badge badge-neutral">{k.type}</span></td>
                                        <td style={{ "max-width": "300px", "overflow": "hidden", "text-overflow": "ellipsis", "white-space": "nowrap", "font-size": "12px" }}>{k.value}</td>
                                        <td>{k.ttl || 'none'}</td>
                                        <td>
                                            <div style="display:flex;gap:4px">
                                                <Show when={props.onEdit}>
                                                    <button onClick={() => props.onEdit('Edit Key: ' + k.key, [
                                                        {name: 'key', type: 'text', value: k.key},
                                                        {name: 'value', type: 'textarea', value: typeof k.value === 'object' ? JSON.stringify(k.value) : (k.value || '')},
                                                        {name: 'type', type: 'text', value: k.type || 'string'},
                                                        {name: 'ttl', type: 'text', value: k.ttl || ''}
                                                    ], async (formData) => {
                                                        await api.mutate('memorystore', 'keys', { ...formData, _method: 'PUT' });
                                                    })} style="padding:4px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:var(--text-secondary);cursor:pointer;font-size:11px" title="Edit">Edit</button>
                                                </Show>
                                                <Show when={props.onDelete}>
                                                    <button onClick={() => props.onDelete('Delete key "' + k.key + '"?', async () => {
                                                        await api.mutate('memorystore', 'keys/delete', { key: k.key });
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

    const selectCollection = async (colName) => {
        setSelectedCollection(colName);
        setDocuments([]);
        setSubLoading(true);
        try {
            const result = await api.browse('firestore', colName);
            setDocuments(result.documents || []);
        } catch { setDocuments([]); }
        finally { setSubLoading(false); }
    };

    const goBack = () => {
        setSelectedCollection(null);
        setDocuments([]);
    };

    // Extract all unique field names from documents for column headers
    const docFields = () => {
        const docs = documents();
        if (!docs || docs.length === 0) return [];
        const fieldSet = new Set();
        docs.forEach(doc => {
            Object.keys(doc).forEach(k => {
                if (k !== '__id') fieldSet.add(k);
            });
        });
        return Array.from(fieldSet);
    };

    return (
        <div>
            <Show when={subLoading()}>
                <div class="loading-state"><div class="loading-spinner" /> Loading...</div>
            </Show>
            <Show when={!subLoading()}>
                {/* Level 2: Documents in collection */}
                <Show when={selectedCollection()}>
                    <button class="back-link" onClick={goBack}>
                        {'\u2190'} Back to collections
                    </button>
                    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px">
                        <h2 style="margin:0">Collection: {selectedCollection()}</h2>
                        <Show when={props.onAdd}>
                            <button onClick={() => props.onAdd('Add Document to ' + selectedCollection(), [
                                {name: 'documentId', type: 'text'},
                                {name: 'fields', type: 'textarea', value: '{}'}
                            ], async (formData) => {
                                let parsedFields = {};
                                try { parsedFields = JSON.parse(formData.fields || '{}'); } catch (e) { alert('Invalid JSON in fields: ' + e.message); return; }
                                await api.mutate('firestore', 'documents', { collection: selectedCollection(), documentId: formData.documentId, fields: parsedFields });
                            })} style="padding:6px 14px;border:none;border-radius:4px;background:var(--accent, #4285f4);color:white;cursor:pointer;font-size:13px">
                                + Add Document
                            </button>
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
                                                                    {name: 'documentId', type: 'text', value: doc.__id || doc.id || ''},
                                                                    {name: 'fields', type: 'textarea', value: JSON.stringify(docData, null, 2)}
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

                {/* Level 1: Collections */}
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
                                        {(col) => {
                                            const colName = typeof col === 'string' ? col : (col.name || col.id || '--');
                                            return (
                                                <tr class="clickable-row" onClick={() => selectCollection(colName)}>
                                                    <td style="font-weight:500">{colName}</td>
                                                </tr>
                                            );
                                        }}
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

// -- Bigtable View (drill-down: instances -> tables -> rows) --
function BigtableView(props) {
    const d = () => props.data();
    const [selectedInstance, setSelectedInstance] = createSignal(null);
    const [selectedTable, setSelectedTable] = createSignal(null);
    const [rows, setRows] = createSignal([]);
    const [subLoading, setSubLoading] = createSignal(false);

    // Browse response is now an array of instances: [{id, type, tables: [{id, columnFamilies}]}]
    const instances = () => {
        const raw = d();
        if (!raw) return [];
        // Support both array (new) and {tables: [...]} (legacy) formats
        if (Array.isArray(raw)) return raw;
        if (raw.tables) {
            // Legacy flat format — group by instance
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

    const selectTable = async (instanceId, tableId) => {
        setSelectedInstance(instanceId);
        setSelectedTable(tableId);
        setRows([]);
        setSubLoading(true);
        try {
            const result = await api.browse('bigtable', 'tables/' + instanceId + '/' + tableId);
            setRows(result.rows || []);
        } catch { setRows([]); }
        finally { setSubLoading(false); }
    };

    const goBackToTables = () => { setSelectedTable(null); setRows([]); };
    const goBackToInstances = () => { setSelectedInstance(null); setSelectedTable(null); setRows([]); };

    // Extract all unique column families/qualifiers from rows
    const rowColumns = () => {
        const r = rows();
        if (!r || r.length === 0) return [];
        const colSet = new Set();
        r.forEach(row => {
            const cells = row.cells || {};
            Object.keys(cells).forEach(k => colSet.add(k));
        });
        return Array.from(colSet);
    };

    return (
        <div>
            <Show when={subLoading()}>
                <div class="loading-state"><div class="loading-spinner" /> Loading...</div>
            </Show>
            <Show when={!subLoading()}>
                {/* Level 3: Rows in table */}
                <Show when={selectedTable()}>
                    <button class="back-link" onClick={goBackToTables}>
                        {'\u2190'} Back to {selectedInstance()}
                    </button>
                    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px">
                        <h2 style="margin:0">{selectedInstance()} / {selectedTable()}</h2>
                        <Show when={props.onAdd}>
                            <button onClick={() => props.onAdd('Add Row to ' + selectedTable(), [
                                {name: 'rowKey', type: 'text'},
                                {name: 'columnFamily', type: 'text'},
                                {name: 'column', type: 'text'},
                                {name: 'value', type: 'textarea'}
                            ], async (formData) => {
                                await api.mutate('bigtable', 'rows', { table: selectedInstance() + '/' + selectedTable(), ...formData });
                            })} style="padding:6px 14px;border:none;border-radius:4px;background:var(--accent, #4285f4);color:white;cursor:pointer;font-size:13px">
                                + Add Row
                            </button>
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
                                <thead>
                                    <tr>
                                        <th>Row Key</th>
                                        <For each={rowColumns()}>{(c) => <th>{c}</th>}</For>
                                        <th>Actions</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    <For each={rows()}>
                                        {(row) => (
                                            <tr>
                                                <td style="font-weight:500">{row.rowKey || row.row_key || '--'}</td>
                                                <For each={rowColumns()}>
                                                    {(c) => {
                                                        const cells = row.cells || {};
                                                        const val = cells[c];
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

                {/* Level 2: Tables in instance */}
                <Show when={selectedInstance() && !selectedTable()}>
                    <button class="back-link" onClick={goBackToInstances}>
                        {'\u2190'} Back to instances
                    </button>
                    <h2 style="margin-bottom:16px">Instance: {selectedInstance()}</h2>
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
                                            <tr class="clickable-row" onClick={() => selectTable(selectedInstance(), tbl.id || tbl.name)}>
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

                {/* Level 1: Instances */}
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
                                            <tr class="clickable-row" onClick={() => setSelectedInstance(inst.id)}>
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
            <div style="position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.5);display:flex;align-items:center;justify-content:center;z-index:1000">
                <div style="background:var(--surface);border-radius:8px;padding:24px;min-width:400px;max-width:600px;max-height:80vh;overflow-y:auto;border:1px solid var(--border)">
                    <h3 style="margin:0 0 16px 0;color:var(--text)">{props.title}</h3>
                    <For each={props.fields || []}>
                        {(field) => (
                            <div style="margin-bottom:12px">
                                <label style="display:block;margin-bottom:4px;font-size:12px;color:var(--text-secondary)">{field.name}</label>
                                {field.type === 'select' ? (
                                    <select value={formData()[field.name] || ''} onChange={e => updateField(field.name, e.target.value)}
                                        style="width:100%;padding:8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:var(--text);font-size:13px">
                                        <For each={field.options || []}>{opt => <option value={opt}>{opt}</option>}</For>
                                    </select>
                                ) : field.type === 'textarea' ? (
                                    <textarea value={formData()[field.name] || ''} onInput={e => updateField(field.name, e.target.value)}
                                        rows="4" style="width:100%;padding:8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:var(--text);font-size:13px;font-family:monospace;resize:vertical" />
                                ) : (
                                    <input type="text" value={formData()[field.name] || ''} onInput={e => updateField(field.name, e.target.value)}
                                        style="width:100%;padding:8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:var(--text);font-size:13px" />
                                )}
                            </div>
                        )}
                    </For>
                    <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:16px">
                        <button onClick={props.onClose} style="padding:8px 16px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:var(--text);cursor:pointer">Cancel</button>
                        <button onClick={() => props.onSubmit(formData())} style="padding:8px 16px;border:none;border-radius:4px;background:var(--accent, #4285f4);color:white;cursor:pointer">{props.mode === 'edit' ? 'Save' : 'Create'}</button>
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
            <div style="position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.5);display:flex;align-items:center;justify-content:center;z-index:1000">
                <div style="background:var(--surface);border-radius:8px;padding:24px;min-width:350px;border:1px solid var(--border)">
                    <h3 style="margin:0 0 8px 0;color:var(--text)">Confirm Delete</h3>
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
const FETCH_SERVICES = new Set(['gcs', 'pubsub', 'firestore', 'bigquery', 'secretmanager', 'cloudtasks', 'logging', 'monitoring', 'spanner', 'bigtable', 'memorystore']);

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
    const [showDeleteConfirm, setShowDeleteConfirm] = createSignal(false);
    const [deleteMessage, setDeleteMessage] = createSignal('');
    const [deleteCallback, setDeleteCallback] = createSignal(null);

    // Tab sync is handled by the parent (app.jsx) via props.selectedService.
    // No duplicate hash listener needed here — app.jsx already listens to hashchange.

    // Fetch service health for tab indicators
    const loadHealth = async () => {
        try {
            const data = await api.health();
            setServiceHealth(data.services || {});
        } catch (e) {}
    };
    loadHealth();
    // Note: health polling continues when Data Explorer is hidden (SQL Editor shown).
    // This is intentional — keeps status dots updated in the sidebar.
    const healthTimer = setInterval(loadHealth, 30000);
    onCleanup(() => clearInterval(healthTimer));

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
        setShowCrudModal(true);
    };

    const handleEdit = (title, fields, callback) => {
        setCrudTitle(title);
        setCrudFields(fields);
        setCrudMode('edit');
        setCrudCallback(() => callback);
        setShowCrudModal(true);
    };

    const handleDelete = (message, callback) => {
        setDeleteMessage(message);
        setDeleteCallback(() => callback);
        setShowDeleteConfirm(true);
    };

    const handleCrudSubmit = async (formData) => {
        try {
            const cb = crudCallback();
            if (cb) await cb(formData);
            setShowCrudModal(false);
            loadData();
        } catch (err) {
            setError('Operation failed: ' + (err.message || 'Unknown error'));
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
    createEffect(() => {
        const tab = selectedTab();
        const proj = typeof props.activeProject === 'function' ? props.activeProject() : props.activeProject;
        setError(null);
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
            return <div class="loading-state"><div class="loading-spinner" /> Loading...</div>;
        }
        if (error()) {
            return (
                <div>
                    <div class="alert alert-error">{error()}</div>
                    <button class="btn btn-secondary" onClick={() => fetchData(tab)}>Retry</button>
                </div>
            );
        }

        switch (tab) {
            case 'gcs': return <GcsView data={data} onAdd={handleAdd} onEdit={handleEdit} onDelete={handleDelete} />;
            case 'pubsub': return <PubSubView data={data} onAdd={handleAdd} onDelete={handleDelete} />;
            case 'firestore': return <FirestoreView data={data} onAdd={handleAdd} onEdit={handleEdit} onDelete={handleDelete} />;
            case 'bigquery': return <BigQueryView data={data} onAdd={handleAdd} onDelete={handleDelete} />;
            case 'secretmanager': return <SecretManagerView data={data} onAdd={handleAdd} onDelete={handleDelete} />;
            case 'cloudtasks': return <CloudTasksView data={data} onAdd={handleAdd} onDelete={handleDelete} />;
            case 'logging': return <LoggingView data={data} />;
            case 'monitoring': return <MonitoringView data={data} />;
            case 'spanner': return <SpannerView data={data} onRefresh={loadData} onAdd={handleAdd} onEdit={handleEdit} onDelete={handleDelete} />;
            case 'bigtable': return <BigtableView data={data} onAdd={handleAdd} onDelete={handleDelete} />;
            case 'memorystore': return <MemorystoreView data={data} onAdd={handleAdd} onEdit={handleEdit} onDelete={handleDelete} />;
            default: return null;
        }
    };

    const activeTabLabel = () => {
        const tab = TABS.find(t => t.id === selectedTab());
        return tab ? tab.label : selectedTab();
    };

    return (
        <div>
            {/* Service Content — full width */}
            {renderServiceView()}

            <CrudModal
                show={showCrudModal()}
                onClose={() => setShowCrudModal(false)}
                onSubmit={handleCrudSubmit}
                title={crudTitle()}
                fields={crudFields()}
                mode={crudMode()}
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

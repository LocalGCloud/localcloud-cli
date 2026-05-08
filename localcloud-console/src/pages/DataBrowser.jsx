import { createSignal, createEffect, createMemo, Show, For } from 'solid-js';
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
        if (!sp || sp.length === 0) return;
        const instances = d()?.instances;
        if (!instances || instances.length === 0) return;
        const [instName, dbName, tblName] = sp;
        // Only restore if not already navigated
        if (instName && !selectedInstance()) {
            const inst = instances.find(i => (i.name?.split('/').pop() || i.displayName) === instName);
            if (inst) {
                selectInstance(inst).then(() => {
                    if (dbName) {
                        // Wait for databases to load, then select
                        const checkDb = setInterval(() => {
                            const dbs = databases();
                            if (dbs.length > 0) {
                                clearInterval(checkDb);
                                const db = dbs.find(d => (d.name?.split('/').pop() || d) === dbName);
                                if (db) {
                                    selectDatabase(db).then(() => {
                                        if (tblName) {
                                            // Wait for DDL to load, then select table
                                            const checkTbl = setInterval(() => {
                                                const ddl = ddlData();
                                                if (ddl) {
                                                    clearInterval(checkTbl);
                                                    selectTable(tblName);
                                                }
                                            }, 100);
                                            setTimeout(() => clearInterval(checkTbl), 5000);
                                        }
                                    });
                                }
                            }
                        }, 100);
                        setTimeout(() => clearInterval(checkDb), 5000);
                    }
                });
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

    // Parse column types from DDL for display
    const columnTypes = createMemo(() => {
        const ddl = ddlData();
        if (!ddl || !ddl.statements) return {};
        const types = {};
        for (const stmt of ddl.statements) {
            const lines = stmt.split('\n');
            for (const line of lines) {
                const trimmed = line.trim().replace(/,$/, '');
                if (!trimmed || trimmed.startsWith(')') || trimmed.startsWith('--')) continue;
                if (/\bAS\s*\(/.test(trimmed) || /TOKENLIST\s+AS/.test(trimmed)) continue;
                const m = trimmed.match(/^(\w+)\s+(INT64|FLOAT64|BOOL|STRING\(\w+\)|STRING\(MAX\)|TIMESTAMP|DATE|BYTES\(\w+\))/i);
                if (m) types[m[1]] = m[2];
            }
        }
        return types;
    });

    // Parse NOT NULL columns from DDL for validation
    const notNullColumns = createMemo(() => {
        const ddl = ddlData();
        if (!ddl || !ddl.statements) return new Set();
        const cols = new Set();
        for (const stmt of ddl.statements) {
            const lines = stmt.split('\n');
            let parenDepth = 0;
            for (const line of lines) {
                const trimmed = line.trim().replace(/,$/, '');
                // Track paren depth to skip generated column bodies
                for (const ch of trimmed) { if (ch === '(') parenDepth++; if (ch === ')') parenDepth--; }
                // Only parse top-level column definitions (depth 1 = inside CREATE TABLE parens)
                if (parenDepth > 1) continue;
                if (/\bAS\s*\(/.test(trimmed) || /TOKENLIST\s+AS/.test(trimmed)) continue;
                const m = trimmed.match(/^(\w+)\s+(INT64|FLOAT64|BOOL|STRING|TIMESTAMP|DATE|BYTES)\b.*\bNOT\s+NULL\b/i);
                if (m) cols.add(m[1]);
            }
        }
        return cols;
    });

    // CSV Import state
    const [showCsvImport, setShowCsvImport] = createSignal(false);
    const [csvFile, setCsvFile] = createSignal(null);
    const [csvParsed, setCsvParsed] = createSignal(null); // {headers, rows, delimiter, encoding}
    const [csvMapping, setCsvMapping] = createSignal({}); // csvHeader -> tableColumn
    const [csvErrors, setCsvErrors] = createSignal([]); // [{row, col, message}]
    const [csvImporting, setCsvImporting] = createSignal(false);
    const [csvImportResult, setCsvImportResult] = createSignal(null);
    const [csvSelectedRows, setCsvSelectedRows] = createSignal(new Set());
    const [csvWarnings, setCsvWarnings] = createSignal([]); // [{row, col, message}] — yellow, don't block
    const [csvStep, setCsvStep] = createSignal('upload'); // upload | mapping | preview | importing | done

    // CSV reactive derived values (after signal declarations)
    const csvMappedTargets = createMemo(() => new Set(Object.values(csvMapping()).filter(v => v)));
    const csvMappedCount = createMemo(() => Object.values(csvMapping()).filter(v => v).length);

    const parseCSV = (text) => {
        // Strip UTF-8 BOM if present
        if (text.charCodeAt(0) === 0xFEFF) text = text.slice(1);
        // Auto-detect delimiter
        const firstLine = text.split('\n')[0];
        const delimiters = [',', '\t', ';', '|'];
        let bestDelim = ',', bestCount = 0;
        for (const d of delimiters) {
            const count = (firstLine.match(new RegExp(d === '|' ? '\\|' : (d === '\t' ? '\t' : d), 'g')) || []).length;
            if (count > bestCount) { bestCount = count; bestDelim = d; }
        }
        const lines = [];
        let current = '', inQuote = false, row = [];
        for (let i = 0; i < text.length; i++) {
            const ch = text[i];
            if (ch === '"') {
                if (inQuote && text[i + 1] === '"') { current += '"'; i++; }
                else inQuote = !inQuote;
            } else if (ch === bestDelim && !inQuote) {
                row.push(current.trim()); current = '';
            } else if ((ch === '\n' || ch === '\r') && !inQuote) {
                if (ch === '\r' && text[i + 1] === '\n') i++;
                row.push(current.trim()); current = '';
                if (row.some(c => c !== '')) lines.push(row);
                row = [];
            } else { current += ch; }
        }
        row.push(current.trim());
        if (row.some(c => c !== '')) lines.push(row);
        if (lines.length < 2) return null;
        return { headers: lines[0], rows: lines.slice(1), delimiter: bestDelim === '\t' ? 'TAB' : bestDelim, rowCount: lines.length - 1 };
    };

    const handleCsvFile = (file) => {
        if (!file) return;
        setCsvFile(file);
        const reader = new FileReader();
        reader.onload = (e) => {
            const parsed = parseCSV(e.target.result);
            if (!parsed) { setCsvErrors([{row: -1, col: '', message: 'Could not parse CSV. Check format.'}]); return; }
            setCsvParsed(parsed);
            // Auto-map: match CSV headers to insertable table columns (exclude generated)
            const cols = insertableColumns();
            const mapping = {};
            for (const h of parsed.headers) {
                const exact = cols.find(c => c === h);
                const ci = cols.find(c => c.toLowerCase() === h.toLowerCase());
                const snake = cols.find(c => c.toLowerCase() === h.replace(/([A-Z])/g, '_$1').toLowerCase().replace(/^_/, ''));
                if (exact) mapping[h] = exact;
                else if (ci) mapping[h] = ci;
                else if (snake) mapping[h] = snake;
                else mapping[h] = '';
            }
            setCsvMapping(mapping);
            setCsvStep('mapping');
            setCsvErrors([]);
        };
        reader.readAsText(file);
    };

    const validateCsvRows = () => {
        const parsed = csvParsed();
        const mapping = csvMapping();
        if (!parsed) return;
        const cols = tableData()?.columns || [];
        const errors = [];
        const mappedCols = Object.values(mapping).filter(v => v);
        if (mappedCols.length === 0) {
            errors.push({row: -1, col: '', message: 'No columns mapped. Map at least one CSV column to a table column.'});
            setCsvErrors(errors);
            return;
        }
        // Check NOT NULL constraints and field counts
        const nnCols = notNullColumns();
        const types = columnTypes();
        const warnings = []; // {row, col, message} — yellow indicators, don't block import
        parsed.rows.forEach((row, rowIdx) => {
            // Check row has correct number of fields — hard error
            if (row.length !== parsed.headers.length) {
                errors.push({row: rowIdx, col: '', message: `Row ${rowIdx + 1}: expected ${parsed.headers.length} fields, got ${row.length}`});
                return;
            }
            // Check NOT NULL violations — warning (will fail on insert but let user decide)
            parsed.headers.forEach((h, colIdx) => {
                const targetCol = mapping[h];
                if (!targetCol) return;
                const val = (colIdx < row.length ? row[colIdx] : '').trim();
                const isEmpty = !val || val.toLowerCase() === 'null';
                if (isEmpty && nnCols.has(targetCol)) {
                    warnings.push({row: rowIdx, col: targetCol, message: `Row ${rowIdx + 1}: ${targetCol} is NOT NULL but value is empty`});
                }
            });
        });
        // Check unmapped NOT NULL columns — warning (global)
        for (const nn of nnCols) {
            if (!mappedCols.includes(nn)) {
                warnings.push({row: -1, col: nn, message: `Required column ${nn} (NOT NULL) has no CSV mapping — will insert NULL`});
            }
        }
        setCsvWarnings(warnings);
        setCsvErrors(errors);
        // Select all valid rows by default
        const errorRows = new Set(errors.map(e => e.row));
        const selected = new Set();
        parsed.rows.forEach((_, i) => { if (!errorRows.has(i)) selected.add(i); });
        setCsvSelectedRows(selected);
        setCsvStep('preview');
    };

    // Escape a value for Spanner SQL INSERT — type-aware
    const spannerEscape = (val, colType) => {
        if (val === '' || val === null || val === undefined) return 'NULL';
        const trimmed = val.trim();
        const lower = trimmed.toLowerCase();
        if (lower === 'null') return 'NULL';
        const upperType = (colType || '').toUpperCase();
        // BOOL columns
        if (upperType === 'BOOL') {
            if (lower === 'true' || lower === '1' || lower === 'yes') return 'TRUE';
            if (lower === 'false' || lower === '0' || lower === 'no') return 'FALSE';
            return 'NULL';
        }
        // INT64 columns — bare number
        if (upperType === 'INT64') {
            if (/^-?\d+$/.test(trimmed)) return trimmed;
            return 'NULL';
        }
        // FLOAT64 columns — bare number
        if (upperType === 'FLOAT64' || upperType === 'FLOAT32' || upperType === 'NUMERIC') {
            if (/^-?\d+(\.\d+)?$/.test(trimmed)) return trimmed;
            return 'NULL';
        }
        // TIMESTAMP / DATE — quoted string
        if (upperType === 'TIMESTAMP' || upperType === 'DATE') {
            return "'" + trimmed.replace(/'/g, "''") + "'";
        }
        // STRING, BYTES, JSON, or unknown — always quote
        return "'" + trimmed.replace(/'/g, "''") + "'";
    };

    const executeCsvImport = async () => {
        const parsed = csvParsed();
        const mapping = csvMapping();
        const selected = csvSelectedRows();
        if (!parsed || selected.size === 0) return;
        setCsvImporting(true);
        setCsvStep('importing');
        const mappedHeaders = parsed.headers.filter(h => mapping[h]);
        const targetCols = mappedHeaders.map(h => mapping[h]);
        let imported = 0, failed = 0;
        const failedRows = [];
        for (const rowIdx of [...selected].sort((a, b) => a - b)) {
            const row = parsed.rows[rowIdx];
            const values = mappedHeaders.map((h) => {
                const colIdx = parsed.headers.indexOf(h);
                return colIdx < row.length ? row[colIdx] : '';
            });
            // Build INSERT SQL — type-aware escaping using column types from DDL
            const types = columnTypes();
            const valueLiterals = values.map((v, vi) => spannerEscape(v, types[targetCols[vi]]));
            const sql = `INSERT OR UPDATE INTO ${selectedTable()} (${targetCols.join(', ')}) VALUES (${valueLiterals.join(', ')})`;
            try {
                const result = await api.query('spanner', sql, {
                    instance: selectedInstance(),
                    database: selectedDatabase()
                });
                if (result.error) {
                    failed++;
                    let errMsg = result.error;
                    if (errMsg.includes('failed to marshal')) errMsg = 'Constraint violation (likely NOT NULL, duplicate key, or type mismatch)';
                    failedRows.push({row: rowIdx + 1, error: errMsg});
                } else {
                    imported++;
                }
            } catch (e) {
                failed++;
                failedRows.push({row: rowIdx + 1, error: e.message || 'Insert failed'});
            }
        }
        setCsvImportResult({imported, failed, failedRows, total: selected.size});
        setCsvImporting(false);
        setCsvStep('done');
        if (imported > 0) selectTable(selectedTable()); // refresh table data
    };

    const resetCsvImport = () => {
        setCsvFile(null); setCsvParsed(null); setCsvMapping({});
        setCsvErrors([]); setCsvWarnings([]); setCsvImporting(false); setCsvImportResult(null);
        setCsvSelectedRows(new Set()); setCsvStep('upload');
    };

    // Breadcrumb — reactive memo tracks signal changes
    const breadcrumbs = createMemo(() => {
        const crumbs = [];
        crumbs.push({label: 'Instances', onClick: goBackToInstances, active: !selectedInstance()});
        if (selectedInstance()) {
            crumbs.push({label: selectedInstance(), onClick: goBackToDatabases, active: !selectedDatabase()});
            if (selectedDatabase()) {
                crumbs.push({label: selectedDatabase(), onClick: goBackToTables, active: !selectedTable()});
                if (selectedTable()) {
                    crumbs.push({label: selectedTable(), onClick: null, active: true});
                }
            }
        }
        return crumbs;
    });

    const Breadcrumb = () => (
        <nav class="spanner-breadcrumb" style="display:flex;align-items:center;gap:0;margin-bottom:12px;font-size:13px;flex-wrap:wrap">
            <For each={breadcrumbs()}>
                {(crumb, i) => (
                    <>
                        <Show when={i() > 0}>
                            <span style="color:var(--text-tertiary);margin:0 6px;font-size:10px;user-select:none">{'\u203A'}</span>
                        </Show>
                        <Show when={crumb.onClick && !crumb.active} fallback={
                            <span style="font-weight:600;color:var(--text)">{crumb.label}</span>
                        }>
                            <button onClick={crumb.onClick} style="background:none;border:none;padding:2px 6px;border-radius:4px;color:var(--text-secondary);cursor:pointer;font-size:13px;transition:all 0.15s" onMouseEnter={e => {e.currentTarget.style.background='var(--surface-hover)';e.currentTarget.style.color='var(--primary)'}} onMouseLeave={e => {e.currentTarget.style.background='none';e.currentTarget.style.color='var(--text-secondary)'}}>
                                {crumb.label}
                            </button>
                        </Show>
                    </>
                )}
            </For>
        </nav>
    );

    return (
        <div>
            {/* Breadcrumb — always visible when drilled in */}
            <Show when={selectedInstance()}>
                <Breadcrumb />
            </Show>

            <Show when={subLoading()}>
                <div class="loading-state"><div class="loading-spinner" /> Loading...</div>
            </Show>
            <Show when={!subLoading()}>
                {/* Level 4: Table data */}
                <Show when={selectedTable()}>
                    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:12px">
                        <h2 style="margin:0;font-size:16px">{selectedTable()}</h2>
                        <Show when={props.onAdd && tableData() && tableData().columns}>
                            <div style="display:flex;gap:6px">
                                <button onClick={() => { resetCsvImport(); setShowCsvImport(true); }}
                                    style="padding:6px 12px;border:1px solid var(--border);border-radius:4px;background:var(--surface);color:var(--text-secondary);cursor:pointer;font-size:12px;display:flex;align-items:center;gap:5px;transition:all 0.15s"
                                    onMouseEnter={e => e.currentTarget.style.borderColor='var(--primary)'}
                                    onMouseLeave={e => e.currentTarget.style.borderColor='var(--border)'}>
                                    {'\u2191'} Import CSV
                                </button>
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

                {/* CSV Import Modal */}
                <Show when={showCsvImport()}>
                    <div class="modal-overlay" role="dialog" aria-modal="true" onClick={(e) => { if (e.target === e.currentTarget) { setShowCsvImport(false); resetCsvImport(); }}}>
                        <div class="card modal-card" onClick={(e) => e.stopPropagation()} style="max-width:900px;width:90vw;max-height:85vh;display:flex;flex-direction:column">
                            {/* Header with steps */}
                            <div style="margin-bottom:16px">
                                <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:12px">
                                    <h2 style="margin:0;font-size:16px">Import CSV to {selectedTable()}</h2>
                                    <button onClick={() => { setShowCsvImport(false); resetCsvImport(); }} style="background:none;border:none;color:var(--text-tertiary);cursor:pointer;font-size:18px;padding:4px">{'\u00D7'}</button>
                                </div>
                                {/* Step indicator */}
                                <div style="display:flex;gap:4px;align-items:center">
                                    <For each={[{id:'upload',label:'Upload'},{id:'mapping',label:'Map Columns'},{id:'preview',label:'Preview'},{id:'importing',label:'Import'},{id:'done',label:'Done'}]}>
                                        {(step, i) => (
                                            <>
                                                <Show when={i() > 0}><div style={`width:20px;height:1px;background:${['upload','mapping','preview','importing','done'].indexOf(csvStep()) >= i() ? 'var(--primary)' : 'var(--border)'}`} /></Show>
                                                <div style={`font-size:11px;padding:3px 8px;border-radius:10px;font-weight:${csvStep() === step.id ? '600' : '400'};background:${csvStep() === step.id ? 'var(--primary)' : (['upload','mapping','preview','importing','done'].indexOf(csvStep()) > ['upload','mapping','preview','importing','done'].indexOf(step.id) ? 'var(--surface-hover)' : 'transparent')};color:${csvStep() === step.id ? 'white' : 'var(--text-tertiary)'};transition:all 0.2s`}>
                                                    {step.label}
                                                </div>
                                            </>
                                        )}
                                    </For>
                                </div>
                            </div>

                            {/* Step content */}
                            <div style="flex:1;overflow-y:auto;min-height:0">
                                {/* Step 1: Upload */}
                                <Show when={csvStep() === 'upload'}>
                                    <div
                                        onDragOver={(e) => { e.preventDefault(); e.currentTarget.style.borderColor = 'var(--primary)'; e.currentTarget.style.background = 'var(--surface-hover)'; }}
                                        onDragLeave={(e) => { e.currentTarget.style.borderColor = 'var(--border)'; e.currentTarget.style.background = 'transparent'; }}
                                        onDrop={(e) => { e.preventDefault(); e.currentTarget.style.borderColor = 'var(--border)'; e.currentTarget.style.background = 'transparent'; handleCsvFile(e.dataTransfer.files[0]); }}
                                        style="border:2px dashed var(--border);border-radius:8px;padding:40px 24px;text-align:center;cursor:pointer;transition:all 0.2s"
                                        onClick={() => { const input = document.createElement('input'); input.type = 'file'; input.accept = '.csv,.tsv,.txt'; input.onchange = (e) => handleCsvFile(e.target.files[0]); input.click(); }}
                                    >
                                        <div style="font-size:32px;margin-bottom:8px;opacity:0.4">{'\u2191'}</div>
                                        <div style="font-size:14px;font-weight:500;margin-bottom:4px;color:var(--text)">Drop CSV file here or click to browse</div>
                                        <div style="font-size:12px;color:var(--text-tertiary)">Supports .csv, .tsv, .txt with comma, tab, semicolon, or pipe delimiters</div>
                                    </div>
                                    <Show when={csvErrors().length > 0}>
                                        <div class="alert alert-error" style="margin-top:12px">{csvErrors()[0].message}</div>
                                    </Show>
                                </Show>

                                {/* Step 2: Column Mapping — interactive drag-to-connect mapper */}
                                <Show when={csvStep() === 'mapping'}>
                                    {(() => {
                                        const ROW_H = 40;
                                        const HDR_H = 30;
                                        const COL_W = 240;
                                        const GAP = 200;
                                        const TOTAL_W = COL_W + GAP + COL_W;
                                        const [connecting, setConnecting] = createSignal(null); // source header being dragged
                                        const [mousePos, setMousePos] = createSignal({x: 0, y: 0});
                                        let containerRef;

                                        const onMouseMove = (e) => {
                                            if (!connecting() || !containerRef) return;
                                            const inner = containerRef.firstElementChild;
                                            if (!inner) return;
                                            const innerRect = inner.getBoundingClientRect();
                                            // Map pixel position in the inner div to SVG viewBox coordinates
                                            const px = e.clientX - innerRect.left;
                                            const py = e.clientY - innerRect.top;
                                            const svgX = (px / innerRect.width) * TOTAL_W;
                                            const svgY = (py / innerRect.height) * inner.offsetHeight;
                                            setMousePos({x: svgX, y: svgY});
                                        };

                                        const startConnect = (header) => {
                                            // If already mapped, unmap first
                                            const current = csvMapping()[header];
                                            if (current) {
                                                setCsvMapping(prev => ({...prev, [header]: ''}));
                                            }
                                            setConnecting(header);
                                        };

                                        const finishConnect = (targetCol) => {
                                            const src = connecting();
                                            if (!src) return;
                                            // Remove any existing mapping TO this target
                                            const newMapping = {...csvMapping()};
                                            for (const [k, v] of Object.entries(newMapping)) {
                                                if (v === targetCol) newMapping[k] = '';
                                            }
                                            newMapping[src] = targetCol;
                                            setCsvMapping(newMapping);
                                            setConnecting(null);
                                        };

                                        const cancelConnect = () => setConnecting(null);

                                        const deleteMapping = (header) => {
                                            setCsvMapping(prev => ({...prev, [header]: ''}));
                                        };

                                        return (
                                            <div>
                                                {/* Stats bar */}
                                                <div style="display:flex;gap:16px;font-size:12px;color:var(--text-secondary);margin-bottom:8px;flex-wrap:wrap;align-items:center">
                                                    <span>{'\u2713'} {csvParsed()?.rowCount} rows</span>
                                                    <span>Delimiter: <strong>{csvParsed()?.delimiter}</strong></span>
                                                    <span>File: <strong>{csvFile()?.name}</strong></span>
                                                    <span style="margin-left:auto">
                                                        <strong style={`color:${csvMappedCount() === (csvParsed()?.headers || []).length ? '#34a853' : 'var(--text)'}`}>{csvMappedCount()}/{(csvParsed()?.headers || []).length}</strong> mapped
                                                    </span>
                                                </div>
                                                <div style="font-size:11px;color:var(--text-tertiary);margin-bottom:12px">
                                                    Click source column, then click target to connect. Click a mapped source to disconnect.
                                                </div>

                                                {/* Interactive mapping diagram */}
                                                <div
                                                    ref={el => containerRef = el}
                                                    onMouseMove={onMouseMove}
                                                    onClick={(e) => { if (e.target === e.currentTarget || e.target.tagName === 'svg') cancelConnect(); }}
                                                    style={`position:relative;width:100%;overflow-x:auto;overflow-y:auto;max-height:420px;margin-bottom:12px;cursor:${connecting() ? 'crosshair' : 'default'}`}
                                                >
                                                    {(() => {
                                                        const csvHeaders = csvParsed()?.headers || [];
                                                        const tableCols = insertableColumns();
                                                        const mapping = csvMapping();
                                                        const mappedTargets = csvMappedTargets();
                                                        const types = columnTypes();
                                                        const nnCols = notNullColumns();
                                                        const targetIdx = {};
                                                        tableCols.forEach((c, i) => { targetIdx[c] = i; });
                                                        const leftH = HDR_H + csvHeaders.length * ROW_H;
                                                        const rightH = HDR_H + tableCols.length * ROW_H;
                                                        const svgH = Math.max(leftH, rightH);
                                                        const conn = connecting();
                                                        const mp = mousePos();
                                                        const connIdx = conn ? csvHeaders.indexOf(conn) : -1;

                                                        return (
                                                            <div style={`position:relative;min-width:${TOTAL_W}px;height:${svgH}px`}>
                                                                {/* SVG — lines + drag preview */}
                                                                <svg style={`position:absolute;top:0;left:0;width:100%;height:100%;pointer-events:none;z-index:1`} viewBox={`0 0 ${TOTAL_W} ${svgH}`} preserveAspectRatio="none">
                                                                    {/* Existing connections */}
                                                                    <For each={csvHeaders}>
                                                                        {(header, i) => {
                                                                            const target = mapping[header];
                                                                            if (!target) return null;
                                                                            const tIdx = targetIdx[target];
                                                                            if (tIdx === undefined) return null;
                                                                            const y1 = HDR_H + i() * ROW_H + ROW_H / 2;
                                                                            const y2 = HDR_H + tIdx * ROW_H + ROW_H / 2;
                                                                            const x1 = COL_W;
                                                                            const x2 = COL_W + GAP;
                                                                            const midX = (x1 + x2) / 2;
                                                                            const midY = (y1 + y2) / 2;
                                                                            const sampleVal = csvParsed()?.rows[0]?.[i()] || '';
                                                                            const displayVal = sampleVal.length > 16 ? sampleVal.slice(0, 14) + '..' : sampleVal;
                                                                            return (
                                                                                <>
                                                                                    <path d={`M ${x1} ${y1} C ${midX} ${y1}, ${midX} ${y2}, ${x2} ${y2}`}
                                                                                        fill="none" stroke="#34a853" stroke-width="1.5" opacity="0.5" />
                                                                                    <circle cx={x1} cy={y1} r="4" fill="#34a853" />
                                                                                    <circle cx={x2} cy={y2} r="4" fill="#34a853" />
                                                                                    <rect x={midX - 48} y={midY - 9} width="96" height="18" rx="9" fill="var(--surface, #1e1e2e)" stroke="#34a853" stroke-width="0.5" opacity="0.9" />
                                                                                    <text x={midX} y={midY + 3} text-anchor="middle" fill="#34a853" font-size="9" font-family="var(--font-mono, monospace)">{displayVal}</text>
                                                                                </>
                                                                            );
                                                                        }}
                                                                    </For>
                                                                    {/* Drag preview line */}
                                                                    <Show when={conn && connIdx >= 0}>
                                                                        {(() => {
                                                                            const y1 = HDR_H + connIdx * ROW_H + ROW_H / 2;
                                                                            return (
                                                                                <path d={`M ${COL_W} ${y1} C ${COL_W + GAP/2} ${y1}, ${mp.x - GAP/4} ${mp.y}, ${mp.x} ${mp.y}`}
                                                                                    fill="none" stroke="#4285f4" stroke-width="2" stroke-dasharray="6,3" opacity="0.7" />
                                                                            );
                                                                        })()}
                                                                    </Show>
                                                                </svg>

                                                                {/* Left: CSV Source Columns */}
                                                                <div style={`position:absolute;top:0;left:0;width:${COL_W}px`}>
                                                                    <div style={`height:${HDR_H}px;display:flex;align-items:center;padding:0 12px;font-size:10px;font-weight:700;text-transform:uppercase;letter-spacing:0.06em;color:var(--text-tertiary)`}>Source (CSV)</div>
                                                                    <For each={csvHeaders}>
                                                                        {(header) => {
                                                                            const isMapped = () => !!csvMapping()[header];
                                                                            const isActive = () => connecting() === header;
                                                                            return (
                                                                                <div
                                                                                    onClick={() => isMapped() ? deleteMapping(header) : startConnect(header)}
                                                                                    style={`height:${ROW_H}px;display:flex;align-items:center;gap:8px;padding:0 12px;border-bottom:1px solid var(--border-subtle);cursor:pointer;transition:all 0.15s;user-select:none;background:${isActive() ? 'rgba(66,133,244,0.1)' : isMapped() ? 'transparent' : 'rgba(251,188,4,0.06)'};border-right:${isActive() ? '2px solid #4285f4' : '2px solid transparent'}`}
                                                                                    onMouseEnter={e => { if (!connecting()) e.currentTarget.style.background = isMapped() ? 'rgba(234,67,53,0.06)' : 'rgba(66,133,244,0.08)'; }}
                                                                                    onMouseLeave={e => { if (!connecting()) e.currentTarget.style.background = isMapped() ? 'transparent' : 'rgba(251,188,4,0.06)'; }}
                                                                                    title={isMapped() ? 'Click to disconnect' : 'Click to start connecting'}
                                                                                >
                                                                                    <div style={`width:8px;height:8px;border-radius:50%;flex-shrink:0;border:2px solid ${isActive() ? '#4285f4' : isMapped() ? '#34a853' : '#fbbc04'};background:${isActive() ? '#4285f4' : isMapped() ? '#34a853' : 'transparent'};transition:all 0.15s`} />
                                                                                    <div style="flex:1;min-width:0">
                                                                                        <div style="font-size:12px;font-weight:500;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">{header}</div>
                                                                                    </div>
                                                                                    <Show when={isMapped()}>
                                                                                        <span style="font-size:9px;color:#ea4335;opacity:0.6">{'\u00D7'}</span>
                                                                                    </Show>
                                                                                </div>
                                                                            );
                                                                        }}
                                                                    </For>
                                                                </div>

                                                                {/* Right: Table Target Columns */}
                                                                <div style={`position:absolute;top:0;right:0;width:${COL_W}px`}>
                                                                    <div style={`height:${HDR_H}px;display:flex;align-items:center;padding:0 12px;font-size:10px;font-weight:700;text-transform:uppercase;letter-spacing:0.06em;color:var(--text-tertiary)`}>Target (Table)</div>
                                                                    <For each={tableCols}>
                                                                        {(col) => {
                                                                            const isMapped = () => csvMappedTargets().has(col);
                                                                            const isNN = nnCols.has(col);
                                                                            const colType = types[col] || '';
                                                                            const canReceive = () => !!connecting() && !isMapped();
                                                                            return (
                                                                                <div
                                                                                    onClick={() => { if (connecting()) finishConnect(col); }}
                                                                                    style={`height:${ROW_H}px;display:flex;align-items:center;gap:8px;padding:0 12px;border-bottom:1px solid var(--border-subtle);transition:all 0.15s;user-select:none;cursor:${connecting() ? 'pointer' : 'default'};background:${canReceive() ? 'rgba(66,133,244,0.06)' : isMapped() ? 'transparent' : (isNN ? 'rgba(234,67,53,0.04)' : 'rgba(100,100,100,0.03)')};border-left:${canReceive() ? '2px solid #4285f4' : '2px solid transparent'}`}
                                                                                    onMouseEnter={e => { if (connecting() && !isMapped()) e.currentTarget.style.background = 'rgba(66,133,244,0.12)'; }}
                                                                                    onMouseLeave={e => { if (connecting()) e.currentTarget.style.background = canReceive() ? 'rgba(66,133,244,0.06)' : 'transparent'; }}
                                                                                >
                                                                                    <div style={`width:8px;height:8px;border-radius:50%;flex-shrink:0;border:2px solid ${isMapped() ? '#34a853' : (isNN && !isMapped() ? '#ea4335' : 'var(--border)')};background:${isMapped() ? '#34a853' : 'transparent'};transition:all 0.15s`} />
                                                                                    <div style="flex:1;min-width:0">
                                                                                        <div style={`font-size:12px;font-weight:500;color:${isMapped() ? 'var(--text)' : 'var(--text-tertiary)'};white-space:nowrap;overflow:hidden;text-overflow:ellipsis`}>{col}</div>
                                                                                    </div>
                                                                                    <Show when={isNN}>
                                                                                        <span style={`font-size:8px;padding:1px 5px;border-radius:3px;font-weight:600;white-space:nowrap;background:${isMapped() ? 'rgba(52,168,83,0.1)' : 'rgba(234,67,53,0.12)'};color:${isMapped() ? '#34a853' : '#ea4335'}`}>{isMapped() ? 'REQ \u2713' : 'REQUIRED'}</span>
                                                                                    </Show>
                                                                                    <span style={`font-size:9px;padding:1px 5px;border-radius:3px;font-family:var(--font-mono, monospace);white-space:nowrap;background:${isMapped() ? 'rgba(52,168,83,0.1)' : 'var(--surface-hover)'};color:${isMapped() ? '#34a853' : 'var(--text-tertiary)'}`}>{colType || '?'}</span>
                                                                                </div>
                                                                            );
                                                                        }}
                                                                    </For>
                                                                </div>
                                                            </div>
                                                        );
                                                    })()}
                                                </div>

                                                {/* Legend */}
                                                <div style="display:flex;gap:12px;font-size:11px;color:var(--text-tertiary);margin-bottom:12px;flex-wrap:wrap">
                                                    <span style="display:flex;align-items:center;gap:4px"><span style="width:8px;height:8px;border-radius:50%;background:#34a853;display:inline-block" /> Mapped</span>
                                                    <span style="display:flex;align-items:center;gap:4px"><span style="width:8px;height:8px;border-radius:50%;border:2px solid #fbbc04;display:inline-block;box-sizing:border-box" /> Unmapped source</span>
                                                    <span style="display:flex;align-items:center;gap:4px"><span style="width:8px;height:8px;border-radius:50%;border:2px solid #ea4335;display:inline-block;box-sizing:border-box" /> Required (NOT NULL)</span>
                                                    <span style="display:flex;align-items:center;gap:4px"><span style="width:8px;height:8px;border-radius:50%;border:2px solid var(--border);display:inline-block;box-sizing:border-box" /> Optional</span>
                                                </div>

                                                <Show when={csvErrors().length > 0}>
                                                    <div class="alert alert-error" style="margin-bottom:12px">{csvErrors()[0].message}</div>
                                                </Show>
                                                <div style="display:flex;gap:8px;justify-content:flex-end">
                                                    <button class="btn btn-secondary" onClick={() => { resetCsvImport(); }}>Back</button>
                                                    <button class="btn btn-primary" onClick={validateCsvRows}>Validate & Preview</button>
                                                </div>
                                            </div>
                                        );
                                    })()}
                                </Show>

                                {/* Step 3: Preview + Validation */}
                                <Show when={csvStep() === 'preview'}>
                                    {(() => {
                                        const parsed = csvParsed();
                                        const mapping = csvMapping();
                                        const errors = csvErrors();
                                        const warnings = csvWarnings();
                                        const selected = csvSelectedRows();
                                        const errorRows = new Set(errors.map(e => e.row));
                                        const mappedHeaders = (parsed?.headers || []).filter(h => mapping[h]);
                                        const validCount = [...selected].length;
                                        const errorCount = errors.length;
                                        const warnCount = warnings.filter(w => w.row >= 0).length;
                                        // Build per-row-col warning lookup: "rowIdx:colName" -> message
                                        const warnCells = {};
                                        const warnRows = new Set();
                                        for (const w of warnings) {
                                            if (w.row >= 0 && w.col) {
                                                warnCells[w.row + ':' + w.col] = w.message;
                                                warnRows.add(w.row);
                                            }
                                        }
                                        const globalWarnings = warnings.filter(w => w.row < 0);
                                        return (
                                            <div>
                                                <div style="display:flex;gap:16px;align-items:center;margin-bottom:12px;font-size:12px;flex-wrap:wrap">
                                                    <span style="color:var(--text-secondary)">{parsed?.rowCount} total rows</span>
                                                    <span style="color:#34a853;font-weight:500">{'\u2713'} {validCount} selected for import</span>
                                                    <Show when={errorCount > 0}>
                                                        <span style="color:#ea4335;font-weight:500">{'\u2717'} {errorCount} errors</span>
                                                    </Show>
                                                    <Show when={warnCount > 0}>
                                                        <span style="color:#fbbc04;font-weight:500">{'\u26A0'} {warnCount} warnings</span>
                                                    </Show>
                                                    <div style="flex:1" />
                                                    <button onClick={() => {
                                                        const all = new Set();
                                                        (parsed?.rows || []).forEach((_, i) => { if (!errorRows.has(i)) all.add(i); });
                                                        setCsvSelectedRows(all);
                                                    }} style="font-size:11px;background:none;border:1px solid var(--border);border-radius:4px;padding:3px 8px;color:var(--text-secondary);cursor:pointer">Select All Valid</button>
                                                    <button onClick={() => setCsvSelectedRows(new Set())} style="font-size:11px;background:none;border:1px solid var(--border);border-radius:4px;padding:3px 8px;color:var(--text-secondary);cursor:pointer">Deselect All</button>
                                                </div>

                                                {/* Global warnings (unmapped NOT NULL columns) */}
                                                <Show when={globalWarnings.length > 0}>
                                                    <div style="background:rgba(251,188,4,0.08);border:1px solid rgba(251,188,4,0.3);border-radius:6px;padding:8px 12px;margin-bottom:12px;font-size:12px">
                                                        <div style="font-weight:600;color:#fbbc04;margin-bottom:4px">{'\u26A0'} Warnings</div>
                                                        <For each={globalWarnings}>
                                                            {(w) => <div style="color:var(--text-secondary);margin-bottom:2px">{w.message}</div>}
                                                        </For>
                                                    </div>
                                                </Show>

                                                <Show when={errorCount > 0}>
                                                    <div style="background:var(--surface-hover);border:1px solid #ea433530;border-radius:6px;padding:8px 12px;margin-bottom:12px;font-size:12px">
                                                        <div style="font-weight:600;color:#ea4335;margin-bottom:4px">Errors</div>
                                                        <For each={errors.slice(0, 5)}>
                                                            {(err) => <div style="color:var(--text-secondary);margin-bottom:2px">{err.message}</div>}
                                                        </For>
                                                        <Show when={errors.length > 5}>
                                                            <div style="color:var(--text-tertiary);margin-top:4px">...and {errors.length - 5} more</div>
                                                        </Show>
                                                    </div>
                                                </Show>

                                                <div class="data-table-wrapper" style="max-height:300px;overflow:auto">
                                                    <table class="data-table" style="min-width:max-content">
                                                        <thead>
                                                            <tr>
                                                                <th style="width:32px;position:sticky;left:0;z-index:3;background:var(--surface)"></th>
                                                                <th style="width:40px">Row</th>
                                                                <For each={mappedHeaders}>{(h) => {
                                                                    const col = mapping[h];
                                                                    const types = columnTypes();
                                                                    const nnCols = notNullColumns();
                                                                    const colType = types[col] || '';
                                                                    const isNN = nnCols.has(col);
                                                                    return (
                                                                        <th>
                                                                            <div style="display:flex;flex-direction:column;gap:2px">
                                                                                <span>{col}</span>
                                                                                <div style="display:flex;gap:4px;align-items:center">
                                                                                    <span style="font-size:8px;font-weight:400;font-family:var(--font-mono,monospace);opacity:0.7">{colType}</span>
                                                                                    <Show when={isNN}>
                                                                                        <span style="font-size:7px;padding:0 3px;border-radius:2px;background:rgba(234,67,53,0.15);color:#ea4335;font-weight:600">REQ</span>
                                                                                    </Show>
                                                                                </div>
                                                                            </div>
                                                                        </th>
                                                                    );
                                                                }}</For>
                                                                <th>Status</th>
                                                            </tr>
                                                        </thead>
                                                        <tbody>
                                                            <For each={(parsed?.rows || []).slice(0, 100)}>
                                                                {(row, rowIdx) => {
                                                                    const hasError = errorRows.has(rowIdx());
                                                                    const hasWarn = warnRows.has(rowIdx());
                                                                    const isSelected = selected.has(rowIdx());
                                                                    const bgColor = hasError ? 'rgba(234,67,53,0.05)' : hasWarn ? 'rgba(251,188,4,0.05)' : '';
                                                                    return (
                                                                        <tr style={`background:${bgColor}`}>
                                                                            <td style="position:sticky;left:0;z-index:2;background:var(--surface)">
                                                                                <input type="checkbox" checked={isSelected} disabled={hasError}
                                                                                    onChange={(e) => {
                                                                                        const next = new Set(csvSelectedRows());
                                                                                        if (e.target.checked) next.add(rowIdx()); else next.delete(rowIdx());
                                                                                        setCsvSelectedRows(next);
                                                                                    }} />
                                                                            </td>
                                                                            <td style="font-size:10px;color:var(--text-tertiary)">{rowIdx() + 1}</td>
                                                                            <For each={mappedHeaders}>
                                                                                {(h) => {
                                                                                    const ci = (parsed?.headers || []).indexOf(h);
                                                                                    const cellVal = ci >= 0 && ci < row.length ? row[ci] : '';
                                                                                    const targetCol = mapping[h];
                                                                                    const cellWarn = warnCells[rowIdx() + ':' + targetCol];
                                                                                    return (
                                                                                        <td style={`max-width:180px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:11px;${cellWarn ? 'border-bottom:2px solid #fbbc04;' : ''}`} title={cellWarn || ''}>
                                                                                            {cellWarn ? <span style="color:#fbbc04;margin-right:3px" title={cellWarn}>{'\u26A0'}</span> : null}
                                                                                            {cellVal || <span style="color:var(--text-tertiary);font-style:italic">null</span>}
                                                                                        </td>
                                                                                    );
                                                                                }}
                                                                            </For>
                                                                            <td>
                                                                                <Show when={hasError} fallback={
                                                                                    <Show when={hasWarn} fallback={<span style="color:#34a853;font-size:11px">{'\u2713'}</span>}>
                                                                                        <span style="color:#fbbc04;font-size:11px">{'\u26A0'}</span>
                                                                                    </Show>
                                                                                }>
                                                                                    <span style="color:#ea4335;font-size:11px">{'\u2717'}</span>
                                                                                </Show>
                                                                            </td>
                                                                        </tr>
                                                                    );
                                                                }}
                                                            </For>
                                                        </tbody>
                                                    </table>
                                                </div>
                                                <Show when={(parsed?.rows || []).length > 100}>
                                                    <div style="text-align:center;font-size:11px;color:var(--text-tertiary);margin-top:6px">Showing first 100 of {parsed?.rowCount} rows</div>
                                                </Show>

                                                <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:16px">
                                                    <button class="btn btn-secondary" onClick={() => setCsvStep('mapping')}>Back</button>
                                                    <button class="btn btn-primary" onClick={executeCsvImport} disabled={validCount === 0}>
                                                        Import {validCount} Row{validCount !== 1 ? 's' : ''}
                                                    </button>
                                                </div>
                                            </div>
                                        );
                                    })()}
                                </Show>

                                {/* Step 4: Importing */}
                                <Show when={csvStep() === 'importing'}>
                                    <div style="text-align:center;padding:40px">
                                        <div class="loading-spinner" style="margin:0 auto 16px" />
                                        <div style="font-size:14px;font-weight:500">Importing rows...</div>
                                        <div style="font-size:12px;color:var(--text-secondary);margin-top:4px">Inserting into {selectedTable()}</div>
                                    </div>
                                </Show>

                                {/* Step 5: Done */}
                                <Show when={csvStep() === 'done'}>
                                    {(() => {
                                        const r = csvImportResult();
                                        return (
                                            <div style="padding:16px 0">
                                                <div style="text-align:center;margin-bottom:20px">
                                                    <div style={`font-size:36px;margin-bottom:8px;${r?.failed === 0 ? 'color:#34a853' : 'color:#fbbc04'}`}>{r?.failed === 0 ? '\u2713' : '\u26A0'}</div>
                                                    <div style="font-size:16px;font-weight:600;margin-bottom:4px">Import Complete</div>
                                                    <div style="font-size:13px;color:var(--text-secondary)">
                                                        <span style="color:#34a853;font-weight:500">{r?.imported}</span> imported
                                                        <Show when={r?.failed > 0}> &middot; <span style="color:#ea4335;font-weight:500">{r?.failed}</span> failed</Show>
                                                        &nbsp;of {r?.total} rows
                                                    </div>
                                                </div>
                                                <Show when={r?.failedRows?.length > 0}>
                                                    <div style="background:var(--surface-hover);border:1px solid #ea433530;border-radius:6px;padding:10px 12px;margin-bottom:16px;max-height:160px;overflow-y:auto">
                                                        <div style="font-size:11px;font-weight:600;color:#ea4335;margin-bottom:6px;text-transform:uppercase;letter-spacing:0.04em">Failed Rows</div>
                                                        <For each={r.failedRows}>
                                                            {(fr) => <div style="font-size:12px;color:var(--text-secondary);margin-bottom:3px;font-family:var(--font-mono)">Row {fr.row}: {fr.error}</div>}
                                                        </For>
                                                    </div>
                                                </Show>
                                                <div style="display:flex;gap:8px;justify-content:flex-end">
                                                    <button class="btn btn-secondary" onClick={() => { setShowCsvImport(false); resetCsvImport(); }}>Close</button>
                                                    <Show when={r?.failed > 0}>
                                                        <button class="btn btn-primary" onClick={() => { resetCsvImport(); }}>Import Another</button>
                                                    </Show>
                                                </div>
                                            </div>
                                        );
                                    })()}
                                </Show>
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

    // Fetch service health once on mount for tab indicators.
    // Health polling is handled globally by app.jsx — no need for redundant polling here.
    const loadHealth = async () => {
        try {
            const data = await api.health();
            setServiceHealth(data.services || {});
        } catch (e) {}
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
        setData(null); // Clear stale data immediately to prevent showing wrong service content
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
            case 'spanner': return <SpannerView data={data} onRefresh={loadData} onAdd={handleAdd} onEdit={handleEdit} onDelete={handleDelete} subpath={props.subpath} onSubpathChange={props.onSubpathChange} />;
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

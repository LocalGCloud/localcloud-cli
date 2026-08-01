import { createSignal, createEffect, createMemo, Show, For, onCleanup, onMount } from 'solid-js';
import { api } from '../api.js';
import DataBreadcrumb from '../components/DataBreadcrumb.jsx';
import DatabaseExplorer from '../components/DatabaseExplorer.jsx';
import { onActivate } from '../utils/a11y.js';
import { formatDateTime, formatSize } from '../utils/format.js';
import { GCP_REGIONS, getZonesForRegion } from '../data/gcpLocations.js';
import ComboBox from '../components/ComboBox.jsx';
import { EditorView, lineNumbers, highlightActiveLine } from '@codemirror/view';
import { EditorState } from '@codemirror/state';
import { json as jsonLang } from '@codemirror/lang-json';
import { sql as sqlLang, PostgreSQL } from '@codemirror/lang-sql';
import { syntaxHighlighting, defaultHighlightStyle } from '@codemirror/language';
import { getActionWarning } from '../data/compatibility.js';

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
    { id: 'kms', label: 'Cloud KMS' },
];

const SERVICE_INFO = {
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

function EmptyState(props) {
    return (
        <div class="empty-state">
            <div class="empty-state-icon">{'\u2205'}</div>
            <div class="empty-state-title">{props.title}</div>
            <div class="empty-state-text">{props.message}</div>
            <Show when={props.snippet}>
                <div class="empty-state-snippet">
                    <code>{props.snippet}</code>
                    <button class="empty-state-copy" onClick={async () => {
                        try {
                            await navigator.clipboard.writeText(props.snippet);
                            const btn = document.activeElement;
                            if (btn) { btn.textContent = 'Copied!'; setTimeout(() => btn.textContent = 'Copy', 1500); }
                        } catch {}
                    }}>Copy</button>
                </div>
            </Show>
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
    const [currentPrefix, setCurrentPrefix] = createSignal('');
    const [browseData, setBrowseData] = createSignal({ folders: [], objects: [], prefix: '' });
    const [objectsLoading, setObjectsLoading] = createSignal(false);

    const fetchBucketContents = async (bucketName, prefix) => {
        setSelectedBucket(bucketName);
        setCurrentPrefix(prefix || '');
        setObjectsLoading(true);
        setBrowseData({ folders: [], objects: [], prefix: '' });
        try {
            const query = `?delimiter=/&prefix=${encodeURIComponent(prefix || '')}`;
            const result = await api.browse('gcs', bucketName + query);
            setBrowseData({ folders: result.folders || [], objects: result.objects || [], prefix: result.prefix || '' });
        } catch {
            setBrowseData({ folders: [], objects: [], prefix: prefix || '' });
        } finally {
            setObjectsLoading(false);
        }
    };

    const navigateToFolder = (folderPrefix) => {
        fetchBucketContents(selectedBucket(), folderPrefix);
    };

    const goBackToBuckets = () => {
        setSelectedBucket(null);
        setBrowseData({ folders: [], objects: [], prefix: '' });
        setCurrentPrefix('');
    };

    // Build breadcrumb from current prefix (using DataBreadcrumb-compatible format)
    // Consistent with DatabaseExplorer: root crumb is the service name, all levels clickable.
    const breadcrumbs = () => {
        const p = currentPrefix();
        const soi = selectedObject();
        const crumbs = [
            { label: 'Cloud Storage', type: 'service', onClick: goBackToBuckets, active: !selectedBucket() },
        ];
        if (!selectedBucket()) return crumbs;
        crumbs.push({ label: selectedBucket(), type: 'bucket', onClick: () => { setSelectedObject(null); fetchBucketContents(selectedBucket(), ''); }, active: !p && !soi });
        if (!p) {
            if (soi) crumbs.push({ label: soi.name, type: 'file', active: true });
            return crumbs;
        }
        const parts = p.split('/').filter(Boolean);
        let accum = '';
        parts.forEach((part, i) => {
            accum += part + '/';
            crumbs.push({
                label: part,
                type: 'folder',
                onClick: i < parts.length - 1 ? () => fetchBucketContents(selectedBucket(), accum) : null,
                active: i === parts.length - 1 && !soi
            });
        });
        if (soi) crumbs.push({ label: soi.name, type: 'file', active: true });
        return crumbs;
    };

    const refreshCurrent = () => {
        if (selectedBucket()) fetchBucketContents(selectedBucket(), currentPrefix());
    };

    const d = () => props.data();
    const bd = () => browseData();

    const handleUploadObject = (keyPrefix) => {
        const p = currentPrefix();
        props.onAdd('Upload Object to ' + selectedBucket() + (p ? '/' + p : ''), [
            { name: 'name', type: 'text', placeholder: 'filename.json' },
            { name: 'content', type: 'textarea' },
            { name: 'contentType', type: 'text', value: 'application/json' }
        ], async (formData) => {
            const fullKey = (p || '') + (keyPrefix || '') + formData.name;
            await api.mutate('gcs', 'objects', { bucket: selectedBucket(), key: fullKey, content: formData.content, contentType: formData.contentType });
            refreshCurrent();
        });
    };

    const handleCreateFolder = () => {
        const p = currentPrefix();
        props.onAdd('Create Folder in ' + selectedBucket() + (p ? '/' + p : ''), [
            { name: 'name', type: 'text', placeholder: 'folder-name' }
        ], async (formData) => {
            await api.mutate('gcs', 'folders', { bucket: selectedBucket(), prefix: p, name: formData.name });
            refreshCurrent();
        });
    };

    const handleDeleteObject = (objName) => {
        props.onDelete('Delete "' + objName + '" from ' + selectedBucket() + '?', async () => {
            await api.mutate('gcs', 'objects/delete', { bucket: selectedBucket(), key: objName });
            refreshCurrent();
        });
    };

    const handleDeleteFolder = (folderPrefix) => {
        props.onDelete('Delete folder "' + folderPrefix + '" and all its contents from ' + selectedBucket() + '?', async () => {
            // Delete the folder placeholder object
            await api.mutate('gcs', 'objects/delete', { bucket: selectedBucket(), key: folderPrefix });
            refreshCurrent();
        });
    };

    const downloadUrl = (objName) =>
        `${window.location.protocol}//${window.location.hostname}:24081/storage/v1/b/${selectedBucket()}/o/${encodeURIComponent(objName)}?alt=media`;

    const isEmpty = () => !bd() || (bd().folders.length === 0 && bd().objects.length === 0);

    // ─── File Content View State ────────────────────────────────────
    const ONE_MB = 1048576;
    const [selectedObject, setSelectedObject] = createSignal(null);
    const [objectContent, setObjectContent] = createSignal(null);
    const [contentLoading, setContentLoading] = createSignal(false);
    const [contentError, setContentError] = createSignal(null);

    const isSmallFile = (obj) => obj && obj.size != null && Number(obj.size) < ONE_MB;

    const handleViewObject = async (obj) => {
        if (!isSmallFile(obj)) {
            window.open(downloadUrl(obj.name), '_blank');
            return;
        }
        setSelectedObject(obj);
        setObjectContent(null);
        setContentError(null);
        setContentLoading(true);
        try {
            const result = await api.gcsFileContent(selectedBucket(), obj.name);
            setObjectContent(result);
        } catch (e) {
            setContentError(e.message || 'Failed to load file content');
        } finally {
            setContentLoading(false);
        }
    };

    const goBackFromObject = () => {
        setSelectedObject(null);
        setObjectContent(null);
        setContentError(null);
    };

    // ─── CSV parser for file content ────────────────────────────────
    function parseCsv(content) {
        if (!content) return [];
        const rows = [];
        let row = [];
        let cell = '';
        let inQuotes = false;
        for (let i = 0; i < content.length; i++) {
            const ch = content[i];
            const next = content[i + 1];
            if (inQuotes) {
                if (ch === '"') {
                    if (next === '"') { cell += '"'; i++; }
                    else inQuotes = false;
                } else {
                    cell += ch;
                }
            } else {
                if (ch === '"') { inQuotes = true; }
                else if (ch === ',' || ch === '\t') { row.push(cell); cell = ''; }
                else if (ch === '\n' || (ch === '\r' && next === '\n')) {
                    row.push(cell);
                    cell = '';
                    if (row.length > 0 || rows.length > 0) rows.push(row);
                    row = [];
                    if (ch === '\r') i++;
                    if (rows.length > 5000) break; // safety limit
                }
                else if (ch === '\r') {
                    row.push(cell); cell = '';
                    if (row.length > 0 || rows.length > 0) rows.push(row);
                    row = [];
                    if (rows.length > 5000) break;
                }
                else { cell += ch; }
            }
        }
        if (cell || row.length > 0) { row.push(cell); rows.push(row); }
        return rows;
    }

    // ─── CodeMirror viewer for file content ─────────────────────────
    let cmViewerRef;
    let cmViewerInstance;

    function mountCodeViewer(content, lang) {
        if (cmViewerInstance) cmViewerInstance.destroy();
        if (!cmViewerRef) return;
        const langExt = lang === 'json' ? jsonLang() : lang === 'sql' ? sqlLang({ dialect: PostgreSQL }) : [];
        cmViewerInstance = new EditorView({
            parent: cmViewerRef,
            state: EditorState.create({
                doc: content || '',
                extensions: [
                    lineNumbers(),
                    highlightActiveLine(),
                    EditorState.readOnly.of(true),
                    EditorView.editable.of(false),
                    langExt,
                    syntaxHighlighting(defaultHighlightStyle),
                    EditorView.theme({
                        '&': { fontSize: '13px', fontFamily: 'var(--font-mono)', backgroundColor: 'var(--bg)', color: 'var(--text)' },
                        '.cm-scroller': { fontFamily: 'var(--font-mono)', lineHeight: '21px', overflow: 'auto' },
                        '.cm-content': { padding: '12px 0', caretColor: 'transparent' },
                        '.cm-line': { padding: '0 12px' },
                        '.cm-gutters': { backgroundColor: 'var(--surface-variant)', borderRight: '1px solid var(--border)', color: 'var(--text-tertiary)', fontSize: '12px', minWidth: '40px' },
                        '.cm-gutterElement': { padding: '0 8px 0 4px', minWidth: '32px', textAlign: 'right' },
                        '.cm-activeLineGutter': { backgroundColor: 'var(--surface-hover)', color: 'var(--text)' },
                        '.cm-activeLine': { backgroundColor: 'var(--primary-softer)' },
                    }),
                ],
            }),
        });
    }

    createEffect(() => {
        const oc = objectContent();
        if (oc && selectedObject()) {
            mountCodeViewer(oc.content, oc.detectedType || 'text');
        }
    });

    onCleanup(() => {
        if (cmViewerInstance) cmViewerInstance.destroy();
    });

    // Drop zone for file upload
    const onDropFiles = async (e) => {
        e.preventDefault();
        e.currentTarget.style.borderColor = 'var(--border)';
        e.currentTarget.style.background = '';
        const files = e.dataTransfer?.files;
        if (!files || files.length === 0) return;
        let uploaded = 0;
        for (const file of files) {
            try {
                const text = await file.text();
                const fullKey = (currentPrefix() || '') + file.name;
                await api.mutate('gcs', 'objects', {
                    bucket: selectedBucket(),
                    key: fullKey,
                    content: text,
                    contentType: file.type || 'text/plain',
                });
                uploaded++;
            } catch (err) { console.error('Upload failed for ' + file.name + ':', err); }
        }
        if (uploaded > 0) refreshCurrent();
    };

    const dropZoneStyle = {
        border: '2px dashed var(--border)',
        'border-radius': '8px',
        cursor: 'pointer',
        transition: 'border-color 150ms ease, background 150ms ease'
    };

    return (
        <div>
            {/* Fixed-height header — shared by both bucket-list and bucket-contents views */}
            <div class="data-explorer-header">
                <div class="data-explorer-path">
                    <DataBreadcrumb crumbs={breadcrumbs()} />
                </div>
                <div class="data-explorer-actions">
                    <Show when={!selectedBucket()}>
                        <Show when={props.onAdd}>
                            <button class="btn btn-primary" onClick={() => {
                                const defaultLoc = props.projectLocation?.() || 'us-central1';
                                props.onAdd('Create Bucket', [
                                    { name: 'name', type: 'text', placeholder: 'my-bucket' },
                                    { name: 'location', type: 'combo', value: defaultLoc, placeholder: 'Type or select a region...', options: GCP_REGIONS },
                                    { name: 'zone', type: 'combo', value: '', placeholder: 'Type or select a zone (optional)', optionsFn: (fd) => fd.location ? getZonesForRegion(fd.location) : [] }
                                ], async (formData) => {
                                    const payload = { name: formData.name, location: formData.location };
                                    if (formData.zone) payload.zone = formData.zone;
                                    await api.mutate('gcs', 'buckets', payload);
                                });
                            }}>+ Create Bucket</button>
                        </Show>
                    </Show>
                    <Show when={selectedBucket() && !selectedObject()}>
                        <Show when={props.onAdd}>
                            <button class="btn btn-secondary" onClick={handleCreateFolder} style="gap:4px">
                                <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"/><line x1="12" y1="11" x2="12" y2="17" stroke="currentColor" stroke-width="2"/><line x1="9" y1="14" x2="15" y2="14" stroke="currentColor" stroke-width="2"/></svg>
                                New Folder
                            </button>
                            <button class="btn btn-primary" onClick={() => handleUploadObject('')} style="gap:4px">
                                <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
                                Upload File
                            </button>
                        </Show>
                    </Show>
                    <Show when={selectedObject()}>
                        <button class="btn btn-secondary" onClick={goBackFromObject} style="gap:4px;font-size:12px">
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg>
                            Back to bucket
                        </button>
                        <a href={downloadUrl(selectedObject().name)} target="_blank" rel="noopener noreferrer"
                            class="btn btn-secondary"
                            style={{ "height": "32px", "font-size": "12px", "padding": "0 12px", "display": "inline-flex", "align-items": "center", "gap": "4px" }}
                        >
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"/></svg>
                            Download
                        </a>
                    </Show>
                </div>
            </div>

            <Show when={!selectedBucket()} fallback={
                <Show when={!selectedObject()} fallback={
                    <div class="file-content-panel">
                        <Show when={contentLoading()}>
                            <div class="loading-state"><div class="loading-spinner" /> Loading file content…</div>
                        </Show>
                        <Show when={contentError()}>
                            <div class="empty-state">
                                <div class="empty-state-icon">⚠</div>
                                <div class="empty-state-title">Failed to load file</div>
                                <div class="empty-state-text">{contentError()}</div>
                            </div>
                        </Show>
                        <Show when={objectContent()}>
                            <div class="file-content-meta" style="display:flex;align-items:center;gap:16px;padding:8px 12px;background:var(--surface-variant);border:1px solid var(--border);border-radius:6px;margin-bottom:12px;font-size:12px;color:var(--text-secondary)">
                                <span style="font-weight:600;color:var(--text)">{selectedObject()?.name}</span>
                                <span>{formatSize(selectedObject()?.size)}</span>
                                <span class="badge badge-info">{objectContent()?.detectedType || 'text'}</span>
                            </div>
                            <Show when={objectContent()?.warning}>
                                <div style="display:flex;align-items:flex-start;gap:8px;padding:8px 12px;background:#fef3c7;border:1px solid #f59e0b;border-radius:6px;margin-bottom:12px;font-size:12px;color:#92400e">
                                    <span style="flex-shrink:0;font-size:14px">⚠</span>
                                    <span>{objectContent().warning}</span>
                                </div>
                            </Show>
                            <Show when={objectContent()?.detectedType === 'csv'} fallback={
                                <div class="file-content-viewer" ref={cmViewerRef}
                                    style="border:1px solid var(--border);border-radius:6px;overflow:hidden;max-height:70vh;overflow-y:auto"
                                />
                            }>
                                {(() => {
                                    const rows = parseCsv(objectContent()?.content || '');
                                    if (rows.length === 0) return <div class="empty-state"><div class="empty-state-text">Empty CSV file</div></div>;
                                    const headers = rows[0];
                                    const dataRows = rows.length > 1 ? rows.slice(1) : [];
                                    const isHeader = dataRows.length > 0;
                                    return (
                                        <div class="data-table-wrapper" style="max-height:70vh;overflow:auto;border:1px solid var(--border);border-radius:6px">
                                            <table class="data-table" style="font-size:12px">
                                                <thead>
                                                    <tr>
                                                        {headers.map((h, i) => <th key={i} style="position:sticky;top:0;background:var(--bg);z-index:1">{isHeader ? h : `Col ${i + 1}`}</th>)}
                                                    </tr>
                                                </thead>
                                                <tbody>
                                                    {(isHeader ? dataRows : rows).map((row, ri) => (
                                                        <tr key={ri}>
                                                            {(isHeader ? row : row).map((cell, ci) => <td key={ci} style="font-family:var(--font-mono);font-size:11px;max-width:300px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" title={String(cell)}>{String(cell)}</td>)}
                                                        </tr>
                                                    ))}
                                                </tbody>
                                            </table>
                                            <div style="padding:6px 12px;font-size:10px;color:var(--text-tertiary);border-top:1px solid var(--border)">{(isHeader ? dataRows : rows).length} rows × {headers.length} columns</div>
                                        </div>
                                    );
                                })()}
                            </Show>
                        </Show>
                    </div>
                }>

                <Show when={!objectsLoading()} fallback={
                    <div class="loading-state"><div class="loading-spinner" /> Loading…</div>
                }>
                    <Show when={!isEmpty()} fallback={
                        <div class="empty-state"
                            onDragOver={(e) => { e.preventDefault(); e.currentTarget.style.borderColor = 'var(--primary)'; e.currentTarget.style.background = 'var(--primary-softer)'; }}
                            onDragLeave={(e) => { e.currentTarget.style.borderColor = 'var(--border)'; e.currentTarget.style.background = ''; }}
                            onDrop={onDropFiles}
                            style={dropZoneStyle}
                        >
                            <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--text-tertiary)" stroke-width="1.5" aria-hidden="true" focusable="false" style={{ 'margin-bottom': '12px' }}>
                                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                                <polyline points="17 8 12 3 7 8" />
                                <line x1="12" y1="3" x2="12" y2="15" />
                            </svg>
                            <div class="empty-state-title">This folder is empty</div>
                            <div class="empty-state-text">Drag and drop files here, or use Upload File / New Folder above.</div>
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
                                        <th style="width:120px">Actions</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {/* Folders first */}
                                    <For each={bd().folders}>
                                        {(folder) => (
                                            <tr class="clickable-row" onClick={() => navigateToFolder(folder.prefix)} onKeyDown={onActivate(() => navigateToFolder(folder.prefix))} role="button" tabIndex="0">
                                                <td style={{ "font-weight": "500", display: "flex", "align-items": "center", gap: "6px" }}>
                                                    <svg width="16" height="16" viewBox="0 0 24 24" fill="var(--warning, #f9ab00)" aria-hidden="true"><path d="M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"/></svg>
                                                    {folder.name}/
                                                </td>
                                                <td style="color:var(--text-tertiary)">--</td>
                                                <td style="color:var(--text-tertiary)">folder</td>
                                                <td style="color:var(--text-tertiary)">--</td>
                                                <td>
                                                    <Show when={props.onDelete}>
                                                        <button onClick={(e) => { e.stopPropagation(); handleDeleteFolder(folder.prefix); }}
                                                            style="padding:4px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:#ea4335;cursor:pointer;font-size:11px" title="Delete folder">Del</button>
                                                    </Show>
                                                </td>
                                            </tr>
                                        )}
                                    </For>
                                    {/* Objects */}
                                    <For each={bd().objects}>
                                        {(obj) => {
                                            const canView = isSmallFile(obj);
                                            return (
                                            <tr class={canView ? 'clickable-row' : ''} onClick={canView ? () => handleViewObject(obj) : undefined} onKeyDown={canView ? onActivate(() => handleViewObject(obj)) : undefined} role={canView ? 'button' : undefined} tabIndex={canView ? 0 : undefined}>
                                                <td style={{ "font-weight": "500", display: "flex", "align-items": "center", gap: "6px" }}>
                                                    <svg width="14" height="14" viewBox="0 0 24 24" fill="var(--text-tertiary)" aria-hidden="true"><path d="M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm-1 2l5 5h-5V4zM8 14h8v2H8v-2zm0 4h8v2H8v-2zm0-8h5v2H8v-2z"/></svg>
                                                    {obj.name}
                                                </td>
                                                <td>{formatSize(obj.size)}</td>
                                                <td>{obj.contentType || '--'}</td>
                                                <td>{formatDate(obj.updated)}</td>
                                                <td>
                                                    <div style="display:flex;gap:4px;align-items:center">
                                                        <Show when={canView}>
                                                            <button onClick={(e) => { e.stopPropagation(); handleViewObject(obj); }}
                                                                class="btn btn-primary"
                                                                style={{ "height": "28px", "font-size": "12px", "padding": "0 10px" }}
                                                            >View</button>
                                                        </Show>
                                                        <a href={downloadUrl(obj.name)} target="_blank" rel="noopener noreferrer"
                                                            onClick={(e) => e.stopPropagation()}
                                                            class="btn btn-secondary"
                                                            style={{ "height": "28px", "font-size": "12px", "padding": "0 10px" }}
                                                        >Download</a>
                                                        <Show when={props.onDelete}>
                                                            <button onClick={(e) => { e.stopPropagation(); handleDeleteObject(obj.name); }}
                                                                style="padding:4px 8px;border:1px solid var(--border);border-radius:4px;background:var(--bg);color:#ea4335;cursor:pointer;font-size:11px" title="Delete">Del</button>
                                                        </Show>
                                                    </div>
                                                </td>
                                            </tr>
                                        )}}
                                    </For>
                                </tbody>
                            </table>
                        </div>
                    </Show>
                </Show>
                </Show>
        }>
            <Show when={d() && d().buckets && d().buckets.length > 0} fallback={
                <div class="empty-state">
                    <div class="empty-state-icon">{'\u2205'}</div>
                    <div class="empty-state-title">No buckets found</div>
                    <div class="empty-state-text">Create a bucket using the button above, or via the SDK:</div>
                    <div class="empty-state-snippet">
                        <code>client = storage.Client()
client.create_bucket("my-bucket")</code>
                    </div>
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
                                    <tr class="clickable-row" onClick={() => fetchBucketContents(bucket.name, '')} onKeyDown={onActivate(() => fetchBucketContents(bucket.name, ''))} role="button" tabIndex="0">
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
    </div>
    );
}

// -- Pub/Sub View --
function PubSubView(props) {
    const d = () => props.data();
    const [pubsubMessages, setPubsubMessages] = createSignal([]);
    const [selectedSub, setSelectedSub] = createSignal('');
    const [messagesLoading, setMessagesLoading] = createSignal(false);
    const [publishMenuOpen, setPublishMenuOpen] = createSignal(null);

    const loadMessages = async (subName) => {
        setSelectedSub(subName);
        setMessagesLoading(true);
        try {
            const data = await api.browse('pubsub', 'messages/' + subName);
            setPubsubMessages(data.messages || []);
        } catch (e) { setPubsubMessages([]); }
        finally { setMessagesLoading(false); }
    };

    const publishMockMessages = async (topicName, count) => {
        try {
            await api.mutateSub('pubsub', 'messages', 'mock', { topic: topicName, count });
            props.onRefresh?.();
        } catch (e) {
            console.error('Failed to publish mock messages:', e);
        }
        setPublishMenuOpen(null);
    };

    createEffect(() => {
        if (!publishMenuOpen()) return;
        const handler = (e) => {
            if (!e.target.closest('[aria-label="Publish options"]') && !e.target.closest('button[onclick*="publishMockMessages"]') && !e.target.closest('div[style*="position:absolute"]')) {
                setPublishMenuOpen(null);
            }
        };
        document.addEventListener('click', handler);
        onCleanup(() => document.removeEventListener('click', handler));
    });

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
                    <EmptyState
                        title="No topics found"
                        message="Create a topic using the button above, or via the SDK:"
                        snippet={`publisher = pubsub_v1.PublisherClient()
topic_path = publisher.topic_path("local-project", "my-topic")
topic = publisher.create_topic(name=topic_path)`}
                    />
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
                                                             <div style="position:relative;display:inline-flex">
                                                                 <button onClick={() => publishMockMessages(topicName, 1)} style="padding:4px 10px;border:none;border-radius:4px 0 0 4px;background:var(--accent, #4285f4);color:white;cursor:pointer;font-size:11px;display:flex;align-items:center;gap:4px;white-space:nowrap" title="Publish 1 mock message">
                                                                     <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/></svg>
                                                                     Publish Mock
                                                                 </button>
                                                                 <button onClick={() => setPublishMenuOpen(publishMenuOpen() === topicName ? null : topicName)} style="padding:4px 6px;border:none;border-left:1px solid rgba(255,255,255,0.3);border-radius:0 4px 4px 0;background:var(--accent, #4285f4);color:white;cursor:pointer;font-size:10px;line-height:1" aria-label="Publish options">
                                                                     <svg width="10" height="10" viewBox="0 0 24 24" fill="currentColor"><path d="M7 10l5 5 5-5z"/></svg>
                                                                 </button>
                                                                 <Show when={publishMenuOpen() === topicName}>
                                                                     <div style="position:absolute;right:0;top:calc(100% + 4px);z-index:20;background:var(--surface);border:1px solid var(--border);border-radius:8px;box-shadow:0 8px 24px rgba(0,0,0,0.15);min-width:200px;padding:4px;overflow:hidden">
                                                                         <button onClick={() => publishMockMessages(topicName, 1)} class="publish-option-item">
                                                                             <svg width="14" height="14" viewBox="0 0 24 24" fill="var(--primary)" aria-hidden="true"><path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/></svg>
                                                                             Publish 1 mock message
                                                                         </button>
                                                                         <button onClick={() => publishMockMessages(topicName, 10)} class="publish-option-item">
                                                                             <svg width="14" height="14" viewBox="0 0 24 24" fill="var(--primary)" aria-hidden="true"><path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/><path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z" transform="translate(4,0)" opacity="0.5"/></svg>
                                                                             Publish 10 mock messages
                                                                         </button>
                                                                         <button onClick={() => publishMockMessages(topicName, 50)} class="publish-option-item">
                                                                             <svg width="14" height="14" viewBox="0 0 24 24" fill="var(--primary)" aria-hidden="true"><path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/><path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z" transform="translate(3,0)" opacity="0.5"/><path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z" transform="translate(6,0)" opacity="0.3"/></svg>
                                                                             Publish 50 mock messages
                                                                         </button>
                                                                         <div style="height:1px;background:var(--border);margin:4px 8px"></div>
                                                                         <button onClick={() => { setPublishMenuOpen(null); props.onAdd('Publish Message to ' + topicName, [{ name: 'data', type: 'textarea' }], async (formData) => { await api.mutate('pubsub', 'messages', { topic: topicName, ...formData }); }); }} class="publish-option-item">
                                                                             <svg width="14" height="14" viewBox="0 0 24 24" fill="var(--text-secondary)" aria-hidden="true"><path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z"/></svg>
                                                                             Publish manually…
                                                                         </button>
                                                                     </div>
                                                                 </Show>
                                                             </div>
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

// -- Cloud IAM View --
function CloudIAMView(props) {
    const d = () => props.data();
    const [viewMode, setViewMode] = createSignal('resources'); // 'resources' | 'principals'

    // ── Metadata for create form ────────────────────────────────────────
    const [iamMeta, setIamMeta] = createSignal(null);
    const [metaError, setMetaError] = createSignal(null);
    const [showCreateForm, setShowCreateForm] = createSignal(false);
    const [createError, setCreateError] = createSignal(null);
    const [createSubmitting, setCreateSubmitting] = createSignal(false);

    // Form state
    const [selResourceType, setSelResourceType] = createSignal('');
    const [resourceId, setResourceId] = createSignal('');
    const [memberInput, setMemberInput] = createSignal('');
    const [memberType, setMemberType] = createSignal('user'); // user, serviceAccount, group, domain
    // bindings: [{ role: 'roles/...', members: ['...'] }]
    const [bindings, setBindings] = createSignal([]);
    // current role being composed (before adding to bindings)
    const [selectedRole, setSelectedRole] = createSignal('');

    onMount(async () => {
        try {
            const meta = await api.iamMetadata();
            setIamMeta(meta);
        } catch (e) {
            setMetaError(e.message || 'Failed to load IAM metadata');
        }
    });

    const resourceTypes = () => iamMeta()?.resourceTypes || [];
    const allRoles = () => iamMeta()?.roles || [];
    const roleCategories = () => iamMeta()?.categories || [];

    // Find role metadata by id
    const findRole = (roleId) => allRoles().find(r => r.id === roleId);

    // Filtered roles for search
    const roleFilter = () => {
        const q = selectedRole().toLowerCase().trim();
        const exact = allRoles().find(r => r.id.toLowerCase() === q);
        if (exact) return allRoles(); // show all when exact match selected
        return allRoles().filter(r =>
            !q || r.id.toLowerCase().includes(q) || r.title.toLowerCase().includes(q) ||
            r.description.toLowerCase().includes(q)
        );
    };

    // Group filtered roles by category
    const groupedRoles = createMemo(() => {
        const groups = new Map();
        for (const cat of roleCategories()) groups.set(cat, []);
        groups.set('Other', []);
        for (const r of roleFilter()) {
            const cat = roleCategories().includes(r.category) ? r.category : 'Other';
            groups.get(cat).push(r);
        }
        // Remove empty groups
        const result = [];
        for (const [cat, roles] of groups) {
            if (roles.length > 0) result.push({ category: cat, roles });
        }
        return result;
    });

    const selectedRoleMeta = () => findRole(selectedRole());

    // Add current role+members to bindings
    const addBinding = () => {
        const role = selectedRole();
        const member = buildMember();
        if (!role || !member) return;
        // Check if this role already has a binding — merge members
        const existing = bindings().find(b => b.role === role);
        if (existing) {
            if (!existing.members.includes(member)) {
                setBindings(bindings().map(b =>
                    b.role === role ? { ...b, members: [...b.members, member] } : b
                ));
            }
        } else {
            setBindings([...bindings(), { role, members: [member] }]);
        }
        setMemberInput('');
    };

    const removeBinding = (role) => {
        setBindings(bindings().filter(b => b.role !== role));
    };

    const buildMember = () => {
        const val = memberInput().trim();
        if (!val) return '';
        // If user typed a full prefix already, use as-is
        if (val.includes(':')) return val;
        return `${memberType()}:${val}`;
    };

    const resourceFull = () => {
        const rt = selResourceType();
        const rid = resourceId().trim();
        if (!rt && !rid) return '';
        if (!rt) return rid;
        if (!rid) return rt;
        return `${rt}/${rid}`;
    };

    const handleCreate = async () => {
        const resource = resourceFull();
        if (!resource) { setCreateError('Please select a resource type and enter a resource ID'); return; }
        if (bindings().length === 0) { setCreateError('Please add at least one role binding'); return; }
        setCreateError(null);
        setCreateSubmitting(true);
        try {
            // Submit each binding as a separate policy call,
            // or combine into one policy if the backend supports it.
            // For simplicity, submit each binding one at a time.
            for (const b of bindings()) {
                for (const m of b.members) {
                    await api.mutate('cloudiam', 'policies', { resource, role: b.role, members: m });
                }
            }
            // Reset form and reload data
            resetForm();
            setShowCreateForm(false);
            // Trigger parent DataBrowser to reload via custom event
            window.dispatchEvent(new CustomEvent('localcloud:refresh-data'));
        } catch (e) {
            setCreateError(e.message || 'Failed to create policy');
        } finally {
            setCreateSubmitting(false);
        }
    };

    const resetForm = () => {
        setSelResourceType('');
        setResourceId('');
        setMemberInput('');
        setMemberType('user');
        setBindings([]);
        setSelectedRole('');
        setCreateError(null);
    };

    // ── Existing data views ──────────────────────────────────────────────

    // Transform resource-oriented policies into principal-oriented map
    const principals = createMemo(() => {
        const map = new Map();
        const policies = d()?.policies || [];
        for (const policy of policies) {
            for (const binding of (policy.bindings || [])) {
                for (const member of (binding.members || [])) {
                    if (!map.has(member)) map.set(member, []);
                    map.get(member).push({
                        role: binding.role,
                        resourceType: policy.resourceType,
                        resourceId: policy.resourceId,
                    });
                }
            }
        }
        return Array.from(map.entries()).sort((a, b) => a[0].localeCompare(b[0]));
    });

    const roleColor = (role) => {
        if (role?.includes('admin') || role?.includes('Admin')) return 'badge-unhealthy';
        if (role?.includes('editor')) return 'badge-warning';
        if (role?.includes('viewer') || role?.includes('Viewer')) return 'badge-info';
        if (role?.includes('owner')) return 'badge-unhealthy';
        return 'badge-neutral';
    };

    const roleTitle = (roleId) => {
        const meta = findRole(roleId);
        return meta ? meta.title : roleId;
    };

    return (
        <div>
            {/* ── Header with view toggle + create button ──────────────── */}
            <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px">
                <div style="display:flex;gap:8px;align-items:center">
                    <button
                        onClick={() => setViewMode('resources')}
                        style={{ padding: '5px 12px', border: '1px solid var(--border)', borderRadius: '6px', background: viewMode() === 'resources' ? 'var(--primary)' : 'var(--surface)', color: viewMode() === 'resources' ? '#fff' : 'var(--text-secondary)', cursor: 'pointer', fontSize: '12px', fontWeight: viewMode() === 'resources' ? 600 : 400, transition: 'all 0.15s' }}
                    >By Resource</button>
                    <button
                        onClick={() => setViewMode('principals')}
                        style={{ padding: '5px 12px', border: '1px solid var(--border)', borderRadius: '6px', background: viewMode() === 'principals' ? 'var(--primary)' : 'var(--surface)', color: viewMode() === 'principals' ? '#fff' : 'var(--text-secondary)', cursor: 'pointer', fontSize: '12px', fontWeight: viewMode() === 'principals' ? 600 : 400, transition: 'all 0.15s' }}
                    >By Principal</button>
                </div>
                <Show when={props.onAdd}>
                    <button onClick={() => { setShowCreateForm(!showCreateForm()); resetForm(); }}
                        class="btn btn-primary" style={{ 'font-size': '13px', height: '34px' }}>
                        {showCreateForm() ? 'Cancel' : '+ Grant Access'}
                    </button>
                </Show>
            </div>

            {/* ── Create Policy Form ──────────────────────────────────── */}
            <Show when={showCreateForm()}>
                <div style="border:2px solid var(--primary);border-radius:12px;padding:24px;margin-bottom:20px;background:var(--surface)">
                    <h3 style="margin:0 0 4px 0;font-size:16px;display:flex;align-items:center;gap:8px">
                        <svg width="20" height="20" viewBox="0 0 24 24" fill="var(--primary)" aria-hidden="true">
                            <path d="M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm0 10.99h7c-.53 4.12-3.28 7.79-7 8.94V12H5V6.3l7-3.11v8.8z"/>
                        </svg>
                        Grant Access to Resource
                    </h3>
                    <p style="margin:'0 0 20px 0';font-size:13px;color:var(--text-secondary)">
                        Add principals (members) with specific roles on a resource. IAM policies are stored but <strong>not enforced</strong> in localcloud — all testIamPermissions calls return ALLOW.
                    </p>

                    {/* Error banner */}
                    <Show when={createError()}>
                        <div style="padding:'8px 12px';background:'#fce8e6';border:'1px solid #ea4335';border-radius:'6px';color:'#c5221f';font-size:'13px';margin-bottom:'16px'" role="alert">{createError()}</div>
                    </Show>

                    <div style="display:flex;flex-direction:column;gap:16px">
                        {/* ── Resource section ────────────────────────── */}
                        <div>
                            <label style="display:block;font-size:12px;font-weight:600;margin-bottom:6px;color:var(--text-secondary);text-transform:uppercase;letter-spacing:0.5px">Resource</label>
                            <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
                                <div style="flex:1;min-width:200px">
                                    <ComboBox
                                        value={selResourceType()}
                                        onChange={setSelResourceType}
                                        options={resourceTypes().map(rt => rt.id)}
                                        placeholder="Select service…"
                                    />
                                </div>
                                <span style="color:var(--text-tertiary);font-size:14px">/</span>
                                <input
                                    type="text"
                                    class="create-dialog-input create-dialog-input-mono"
                                    style="flex:2;min-width:150px"
                                    value={resourceId()}
                                    onInput={e => setResourceId(e.currentTarget.value)}
                                    placeholder={
                                        (() => {
                                            const rt = resourceTypes().find(r => r.id === selResourceType());
                                            return rt ? `e.g. ${rt.resourcePattern.split('/').pop()}` : 'Resource ID (e.g. my-bucket)';
                                        })()
                                    }
                                />
                            </div>
                            <Show when={selResourceType()}>
                                {(() => {
                                    const rt = resourceTypes().find(r => r.id === selResourceType());
                                    return rt ? (
                                        <div style="margin-top:6px;font-size:11px;color:var(--text-tertiary);display:flex;align-items:flex-start;gap:4px">
                                            <svg width="12" height="12" viewBox="0 0 24 24" fill="var(--text-tertiary)" style="flex-shrink:0;margin-top:1px" aria-hidden="true"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z"/></svg>
                                            <span>{rt.description}</span>
                                        </div>
                                    ) : null;
                                })()}
                            </Show>
                        </div>

                        {/* ── Role & Member section ───────────────────── */}
                        <div>
                            <label style="display:block;font-size:12px;font-weight:600;margin-bottom:6px;color:var(--text-secondary);text-transform:uppercase;letter-spacing:0.5px">Add Role Binding</label>
                            <div style="border:1px solid var(--border);border-radius:8px;padding:14px;background:var(--surface-variant)">
                                {/* Role picker */}
                                <div style="margin-bottom:12px">
                                    <label style="display:block;font-size:11px;font-weight:600;margin-bottom:4px;color:var(--text-secondary)">Role</label>
                                    <div style="position:relative">
                                        <input
                                            type="text"
                                            class="create-dialog-input create-dialog-input-mono"
                                            style="width:100%;box-sizing:border-box"
                                            value={selectedRole()}
                                            onInput={e => setSelectedRole(e.currentTarget.value)}
                                            placeholder="Search roles by name or description…"
                                            autocomplete="off"
                                        />
                                        {/* Role dropdown */}
                                        <Show when={selectedRole() && !selectedRoleMeta()}>
                                            <div style="position:absolute;top:100%;left:0;right:0;z-index:20;max-height:300px;overflow-y:auto;background:var(--surface);border:1px solid var(--border);border-radius:0 0 8px 8px;box-shadow:0 8px 24px rgba(0,0,0,0.12)">
                                                <For each={groupedRoles()}>
                                                    {({ category, roles }) => (
                                                        <div>
                                                            <div style="padding:'4px 12px';font-size:'10px';font-weight:600;color:var(--text-tertiary);text-transform:uppercase;letter-spacing:0.5px;background:var(--surface-variant)">{category}</div>
                                                            <For each={roles}>
                                                                {(role) => (
                                                                    <div
                                                                        onClick={() => setSelectedRole(role.id)}
                                                                        style={{ padding: '8px 12px', cursor: 'pointer', borderBottom: '1px solid var(--border)', transition: 'background 0.1s', ':hover': 'background: var(--primary-softer)' }}
                                                                        class="hover:bg"
                                                                    >
                                                                        <div style="display:flex;align-items:center;gap:8px;margin-bottom:2px">
                                                                            <span style={{ 'font-weight': 600, 'font-size': '12px' }}>{role.title}</span>
                                                                            <span style={{ 'font-size': '10px', color: 'var(--text-tertiary)', 'font-family': 'var(--font-mono)' }}>{role.id}</span>
                                                                            <Show when={role.stage !== 'GA'}>
                                                                                <span class="badge badge-warning" style="font-size:9px;padding:0 4px">{role.stage}</span>
                                                                            </Show>
                                                                        </div>
                                                                        <div style="font-size:11px;color:var(--text-secondary);line-height:1.4">{role.description}</div>
                                                                    </div>
                                                                )}
                                                            </For>
                                                        </div>
                                                    )}
                                                </For>
                                            </div>
                                        </Show>
                                    </div>
                                    {/* Selected role info */}
                                    <Show when={selectedRoleMeta()}>
                                        <div style="margin-top:8px;padding:10px 12px;background:var(--primary-softer);border:1px solid var(--primary-soft);border-radius:6px">
                                            <div style="display:flex;align-items:center;gap:8px;margin-bottom:4px">
                                                <span style={{ 'font-weight': 600, 'font-size': '12px' }}>{selectedRoleMeta().title}</span>
                                                <span class={`badge ${roleColor(selectedRole())}`} style="font-size:10px">{selectedRoleMeta().stage}</span>
                                                <button onClick={() => setSelectedRole('')} style="margin-left:auto;background:none;border:none;color:var(--text-tertiary);cursor:pointer;font-size:16px;line-height:1;padding:0" title="Clear role">&times;</button>
                                            </div>
                                            <div style="font-size:11px;color:var(--text-secondary);margin-bottom:6px">{selectedRoleMeta().description}</div>
                                            <div style="font-size:10px;color:var(--text-tertiary);margin-bottom:4px;font-weight:600">
                                                {selectedRoleMeta().permissions.length} permission{selectedRoleMeta().permissions.length !== 1 ? 's' : ''}:
                                            </div>
                                            <div style="display:flex;flex-wrap:wrap;gap:3px">
                                                <For each={selectedRoleMeta().permissions}>
                                                    {(perm) => (
                                                        <code style="font-size:10px;padding:1px 6px;background:var(--surface);border:1px solid var(--border);border-radius:3px;font-family:var(--font-mono)">{perm}</code>
                                                    )}
                                                </For>
                                            </div>
                                        </div>
                                    </Show>
                                </div>

                                {/* Member input */}
                                <div style="margin-bottom:12px">
                                    <label style="display:block;font-size:11px;font-weight:600;margin-bottom:4px;color:var(--text-secondary)">Member (Principal)</label>
                                    <div style="display:flex;gap:6px;align-items:center">
                                        <select
                                            value={memberType()}
                                            onChange={e => setMemberType(e.target.value)}
                                            style={{ padding: '6px 8px', border: '1px solid var(--border)', borderRadius: '6px', background: 'var(--surface)', color: 'var(--text)', fontSize: '12px', 'font-family': 'var(--font-mono)' }}
                                        >
                                            <option value="user">user:</option>
                                            <option value="serviceAccount">serviceAccount:</option>
                                            <option value="group">group:</option>
                                            <option value="domain">domain:</option>
                                        </select>
                                        <input
                                            type="text"
                                            class="create-dialog-input create-dialog-input-mono"
                                            style="flex:1"
                                            value={memberInput()}
                                            onInput={e => setMemberInput(e.currentTarget.value)}
                                            placeholder={
                                                memberType() === 'user' ? 'alice@example.com' :
                                                memberType() === 'serviceAccount' ? 'my-sa@project.iam.gserviceaccount.com' :
                                                memberType() === 'group' ? 'eng@example.com' :
                                                'example.com'
                                            }
                                            onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); addBinding(); } }}
                                        />
                                    </div>
                                    <div style="margin-top:4px;font-size:10px;color:var(--text-tertiary)">
                                        Preview: <code style="font-family:var(--font-mono)">{buildMember() || '(enter a value)'}</code>
                                    </div>
                                </div>

                                <button
                                    onClick={addBinding}
                                    disabled={!selectedRoleMeta() || !memberInput().trim()}
                                    class="btn btn-secondary"
                                    style={{ 'font-size': '12px', height: '30px', opacity: (!selectedRoleMeta() || !memberInput().trim()) ? 0.5 : 1 }}
                                >
                                    + Add to Policy
                                </button>
                            </div>
                        </div>

                        {/* ── Bindings Preview ────────────────────────── */}
                        <Show when={bindings().length > 0}>
                            <div>
                                <label style="display:block;font-size:12px;font-weight:600;margin-bottom:6px;color:var(--text-secondary);text-transform:uppercase;letter-spacing:0.5px">Policy Preview</label>
                                <div style="border:1px solid var(--border);border-radius:8px;overflow:hidden">
                                    <div style="padding:8px 14px;background:var(--surface-variant);border-bottom:1px solid var(--border);font-size:11px;color:var(--text-secondary)">
                                        Resource: <code style="font-family:var(--font-mono);font-weight:600;color:var(--text)">{resourceFull() || '(not set)'}</code>
                                    </div>
                                    <div style="padding:8px 14px;display:flex;flex-direction:column;gap:6px">
                                        <For each={bindings()}>
                                            {(binding, idx) => (
                                                <div style="display:flex;align-items:flex-start;gap:8px;padding:6px 0;border-bottom:idx() < bindings().length - 1 ? '1px solid var(--border)' : 'none'}">
                                                    <div style="flex:1">
                                                        <div style="display:flex;align-items:center;gap:6px;margin-bottom:4px">
                                                            <span class={`badge ${roleColor(binding.role)}`} style="font-size:11px">{roleTitle(binding.role)}</span>
                                                            <code style="font-size:10px;color:var(--text-tertiary);font-family:var(--font-mono)">{binding.role}</code>
                                                        </div>
                                                        <div style="display:flex;flex-wrap:wrap;gap:3px">
                                                            <For each={binding.members}>
                                                                {(m) => (
                                                                    <span style="font-size:11px;padding:1px 8px;background:var(--surface);border:1px solid var(--border);border-radius:12px;font-family:var(--font-mono);color:var(--text-secondary)">{m}</span>
                                                                )}
                                                            </For>
                                                        </div>
                                                    </div>
                                                    <button onClick={() => removeBinding(binding.role)}
                                                        style="background:none;border:none;color:var(--text-tertiary);cursor:pointer;font-size:16px;padding:0 4px;line-height:1"
                                                        title="Remove binding">&times;</button>
                                                </div>
                                            )}
                                        </For>
                                    </div>
                                </div>
                            </div>
                        </Show>

                        {/* ── Submit ──────────────────────────────────── */}
                        <div style="display:flex;justify-content:flex-end;gap:8px;padding-top:8px">
                            <button onClick={() => { setShowCreateForm(false); resetForm(); }}
                                class="create-dialog-btn-cancel">Cancel</button>
                            <button onClick={handleCreate}
                                disabled={createSubmitting() || bindings().length === 0 || !resourceFull()}
                                class="create-dialog-btn-submit"
                                style={{ background: 'var(--primary)', color: '#fff', opacity: (createSubmitting() || bindings().length === 0 || !resourceFull()) ? 0.5 : 1 }}>
                                {createSubmitting() ? 'Creating…' : `Grant Access (${bindings().length} role${bindings().length !== 1 ? 's' : ''})`}
                            </button>
                        </div>
                    </div>
                </div>
            </Show>

            {/* ── Policies List ──────────────────────────────────────── */}
            <Show when={d() && d().policies && d().policies.length > 0} fallback={
                <Show when={!showCreateForm()}>
                    <div class="empty-state">
                        <div class="empty-state-icon">{'\u2205'}</div>
                        <div class="empty-state-title">No policies found</div>
                        <div class="empty-state-text">Grant access to a resource to get started. IAM policies are stored but not enforced — all testIamPermissions calls return ALLOW.</div>
                    </div>
                </Show>
            }>
                {/* Resource-oriented view */}
                <Show when={viewMode() === 'resources'}>
                    <div class="data-table-wrapper">
                        <table class="data-table">
                            <thead>
                                <tr>
                                    <th>Resource</th>
                                    <th>Bindings</th>
                                    <th style="width:80px">Actions</th>
                                </tr>
                            </thead>
                            <tbody>
                                <For each={d().policies}>
                                    {(policy) => (
                                        <tr>
                                            <td>
                                                <div style="display:flex;flex-direction:column;gap:2px">
                                                    <span style={{ 'font-weight': 500 }}>{policy.resourceId}</span>
                                                    <span class="badge badge-neutral" style={{ 'align-self': 'flex-start', 'font-size': '10px' }}>{policy.resourceType}</span>
                                                </div>
                                            </td>
                                            <td>
                                                <div style="display:flex;flex-direction:column;gap:4px">
                                                    <For each={policy.bindings || []}>
                                                        {(binding) => (
                                                            <div style="display:flex;align-items:center;gap:6px;flex-wrap:wrap">
                                                                <span class={`badge ${roleColor(binding.role)}`} style={{ 'font-size': '10px' }} title={roleTitle(binding.role)}>{roleTitle(binding.role)}</span>
                                                                <span style={{ 'font-size': '11px', color: 'var(--text-secondary)' }}>
                                                                    {(binding.members || []).join(', ')}
                                                                </span>
                                                            </div>
                                                        )}
                                                    </For>
                                                </div>
                                            </td>
                                            <td>
                                                <Show when={props.onDelete}>
                                                    <button onClick={() => props.onDelete('Delete policy for "' + policy.resourceId + '"? This removes all role bindings on this resource.', async () => {
                                                        await api.mutate('cloudiam', 'policies/delete', { resource: policy.resourceType + ':' + policy.resourceId });
                                                    })} class="btn btn-danger" style={{ height: '26px', 'font-size': '11px', padding: '0 8px' }} title="Delete">Del</button>
                                                </Show>
                                            </td>
                                        </tr>
                                    )}
                                </For>
                            </tbody>
                        </table>
                    </div>
                </Show>
                {/* Principal-oriented view */}
                <Show when={viewMode() === 'principals'}>
                    <div style="display:flex;flex-direction:column;gap:12px">
                        <For each={principals()}>
                            {([member, bindings]) => (
                                <div style="border:1px solid var(--border);border-radius:8px;overflow:hidden">
                                    <div style="padding:10px 14px;background:var(--surface-variant);border-bottom:1px solid var(--border);display:flex;align-items:center;gap:8px">
                                        <svg width="14" height="14" viewBox="0 0 24 24" fill="var(--primary)" aria-hidden="true"><path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/></svg>
                                        <span style={{ 'font-weight': 600, 'font-size': '13px' }}>{member}</span>
                                        <span class="badge badge-info" style={{ 'margin-left': 'auto' }}>{bindings.length} role{bindings.length !== 1 ? 's' : ''}</span>
                                    </div>
                                    <div style="padding:8px 14px;display:flex;flex-direction:column;gap:6px">
                                        <For each={bindings}>
                                            {(b) => (
                                                <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;padding:4px 0">
                                                    <span class={`badge ${roleColor(b.role)}`} style={{ 'font-size': '10px' }} title={roleTitle(b.role)}>{roleTitle(b.role)}</span>
                                                    <span style={{ 'font-size': '12px', color: 'var(--text-secondary)' }}>on</span>
                                                    <span style={{ 'font-size': '12px', 'font-weight': 500 }}>{b.resourceType}:{b.resourceId}</span>
                                                </div>
                                            )}
                                        </For>
                                    </div>
                                </div>
                            )}
                        </For>
                    </div>
                </Show>
            </Show>
        </div>
    );
}

// -- Cloud KMS View --
function KmsView(props) {
    const d = () => props.data();
    const [selectedKeyRing, setSelectedKeyRing] = createSignal(null);
    const [selectedCryptoKey, setSelectedCryptoKey] = createSignal(null);
    const [cryptoKeys, setCryptoKeys] = createSignal([]);
    const [versions, setVersions] = createSignal([]);
    const [subLoading, setSubLoading] = createSignal(false);
    const [viewError, setViewError] = createSignal(null);

    async function loadCryptoKeys(keyRingId) {
        setSelectedKeyRing(keyRingId);
        setSelectedCryptoKey(null);
        setCryptoKeys([]);
        setVersions([]);
        setViewError(null);
        setSubLoading(true);
        try {
            const data = await api.kmsCryptoKeys(keyRingId);
            setCryptoKeys(data?.cryptoKeys || []);
        } catch (e) {
            setViewError('Failed to load crypto keys: ' + e.message);
            setCryptoKeys([]);
        } finally {
            setSubLoading(false);
        }
    }

    async function loadVersions(keyRingId, cryptoKeyId) {
        setSelectedCryptoKey(cryptoKeyId);
        setVersions([]);
        setViewError(null);
        setSubLoading(true);
        try {
            const data = await api.kmsVersions(keyRingId, cryptoKeyId);
            setVersions(data?.versions || []);
        } catch (e) {
            setViewError('Failed to load versions: ' + e.message);
            setVersions([]);
        } finally {
            setSubLoading(false);
        }
    }

    function goBackToKeyRings() {
        setSelectedKeyRing(null);
        setSelectedCryptoKey(null);
        setCryptoKeys([]);
        setVersions([]);
        setViewError(null);
    }

    function goBackToCryptoKeys() {
        setSelectedCryptoKey(null);
        setVersions([]);
        setViewError(null);
    }

    async function handleVersionAction(keyRingId, cryptoKeyId, version, action) {
        setViewError(null);
        const newState = action === 'enable' ? 'ENABLED' : action === 'disable' ? 'DISABLED' : 'DESTROYED';

        // Optimistic update
        setVersions(prev => prev.map(v =>
            v.versionNumber === version ? { ...v, state: newState } : v
        ));

        try {
            if (action === 'destroy') {
                if (!confirm(`Destroy version ${version} of "${cryptoKeyId}"? This cannot be undone.`)) {
                    await loadVersions(keyRingId, cryptoKeyId);
                    return;
                }
                await api.kmsDestroyVersion({ keyRingId, cryptoKeyId, version });
            } else if (action === 'enable') {
                await api.kmsEnableVersion({ keyRingId, cryptoKeyId, version });
            } else if (action === 'disable') {
                await api.kmsDisableVersion({ keyRingId, cryptoKeyId, version });
            }
        } catch (e) {
            setViewError('Failed: ' + e.message);
            await loadVersions(keyRingId, cryptoKeyId);
        }
    }

    async function handleSetPrimary(keyRingId, cryptoKeyId, version) {
        setViewError(null);
        try {
            await api.kmsSetPrimaryVersion({ keyRingId, cryptoKeyId, version });
            // Refresh crypto keys to show updated primary version
            const data = await api.kmsCryptoKeys(keyRingId);
            setCryptoKeys(data?.cryptoKeys || []);
        } catch (e) {
            setViewError('Failed to set primary: ' + e.message);
        }
    }

    const stateBadge = (state) => {
        const s = (state || '').toUpperCase();
        if (s === 'ENABLED') return 'badge-healthy';
        if (s === 'DISABLED') return 'badge-warning';
        if (s === 'DESTROYED') return 'badge-unhealthy';
        return 'badge-neutral';
    };

    return (
        <Show when={!selectedKeyRing()} fallback={
            <Show when={!selectedCryptoKey()} fallback={
                <div>
                    <button class="back-link" onClick={goBackToCryptoKeys}>
                        {'\u2190'} Back to crypto keys
                    </button>
                    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px">
                        <h2 style="margin:0;font-size:16px">{selectedKeyRing()} / {selectedCryptoKey()}</h2>
                    </div>
                    <Show when={viewError()}>
                        <div class="alert alert-error" role="alert">{viewError()}</div>
                    </Show>
                    <Show when={!subLoading()} fallback={
                        <div class="loading-state"><div class="loading-spinner" /> Loading versions…</div>
                    }>
                        <Show when={versions().length > 0} fallback={
                            <div class="empty-state">
                                <div class="empty-state-title">No versions found</div>
                                <div class="empty-state-text">This crypto key has no versions.</div>
                            </div>
                        }>
                            <div class="data-table-wrapper">
                                <table class="data-table">
                                    <thead>
                                        <tr>
                                            <th>Version</th>
                                            <th>State</th>
                                            <th>Algorithm</th>
                                            <th>Created</th>
                                            <th>Actions</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        <For each={versions()}>
                                            {(v) => (
                                                <tr>
                                                    <td style={{ 'font-family': 'var(--font-mono)', 'font-weight': 600 }}>v{v.versionNumber}</td>
                                                    <td><span class={`badge ${stateBadge(v.state)}`}>{v.state || 'UNKNOWN'}</span></td>
                                                    <td style={{ 'font-size': '12px', color: 'var(--text-secondary)' }}>{v.algorithm}</td>
                                                    <td style={{ 'font-size': '12px', color: 'var(--text-secondary)' }}>{formatDate(v.createdAt)}</td>
                                                    <td>
                                                        <div style="display:flex;gap:4px;flex-wrap:wrap">
                                                            <Show when={v.state === 'DISABLED'}>
                                                                <button class="btn btn-secondary" style={{ height: '26px', 'font-size': '11px', padding: '0 8px' }}
                                                                    onClick={() => handleVersionAction(selectedKeyRing(), selectedCryptoKey(), v.versionNumber, 'enable')}>
                                                                    Enable
                                                                </button>
                                                            </Show>
                                                            <Show when={v.state === 'ENABLED'}>
                                                                <button class="btn btn-secondary" style={{ height: '26px', 'font-size': '11px', padding: '0 8px' }}
                                                                    onClick={() => handleVersionAction(selectedKeyRing(), selectedCryptoKey(), v.versionNumber, 'disable')}>
                                                                    Disable
                                                                </button>
                                                            </Show>
                                                            <Show when={v.state !== 'DESTROYED'}>
                                                                <button class="btn btn-danger" style={{ height: '26px', 'font-size': '11px', padding: '0 8px' }}
                                                                    onClick={() => handleVersionAction(selectedKeyRing(), selectedCryptoKey(), v.versionNumber, 'destroy')}>
                                                                    Destroy
                                                                </button>
                                                            </Show>
                                                            <Show when={v.state === 'ENABLED'}>
                                                                <button class="btn btn-secondary" style={{ height: '26px', 'font-size': '11px', padding: '0 8px' }}
                                                                    onClick={() => handleSetPrimary(selectedKeyRing(), selectedCryptoKey(), v.versionNumber)}>
                                                                    Set Primary
                                                                </button>
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
                <div>
                    <button class="back-link" onClick={goBackToKeyRings}>
                        {'\u2190'} Back to key rings
                    </button>
                    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px">
                        <h2 style="margin:0;font-size:16px">Key Ring: {selectedKeyRing()}</h2>
                        <Show when={props.onAdd}>
                            <button onClick={() => props.onAdd('Create Crypto Key in ' + selectedKeyRing(), [
                                { name: 'cryptoKeyId', type: 'text' },
                                { name: 'purpose', type: 'text', value: 'ENCRYPT_DECRYPT' },
                                { name: 'algorithm', type: 'text', value: 'GOOGLE_SYMMETRIC_ENCRYPTION' },
                            ], async (formData) => {
                                await api.kmsCreateCryptoKey({ keyRingId: selectedKeyRing(), ...formData });
                                await loadCryptoKeys(selectedKeyRing());
                            })} class="btn btn-primary" style={{ 'font-size': '13px', height: '34px' }}>
                                + Create Crypto Key
                            </button>
                        </Show>
                    </div>
                    <Show when={viewError()}>
                        <div class="alert alert-error" role="alert">{viewError()}</div>
                    </Show>
                    <Show when={!subLoading()} fallback={
                        <div class="loading-state"><div class="loading-spinner" /> Loading crypto keys…</div>
                    }>
                        <Show when={cryptoKeys().length > 0} fallback={
                            <div class="empty-state">
                                <div class="empty-state-title">No crypto keys found</div>
                                <div class="empty-state-text">Create a crypto key to get started.</div>
                            </div>
                        }>
                            <div class="data-table-wrapper">
                                <table class="data-table">
                                    <thead>
                                        <tr>
                                            <th>Key Name</th>
                                            <th>Purpose</th>
                                            <th>Algorithm</th>
                                            <th>Primary Version</th>
                                            <th>Created</th>
                                            <th>Actions</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        <For each={cryptoKeys()}>
                                            {(key) => (
                                                <tr class="clickable-row" onClick={() => loadVersions(selectedKeyRing(), key.cryptoKeyId)} onKeyDown={onActivate(() => loadVersions(selectedKeyRing(), key.cryptoKeyId))} role="button" tabIndex="0">
                                                    <td style={{ 'font-weight': 500 }}>{key.cryptoKeyId}</td>
                                                    <td><span class="badge badge-neutral">{key.purpose}</span></td>
                                                    <td style={{ 'font-size': '12px', color: 'var(--text-secondary)' }}>{key.algorithm}</td>
                                                    <td style={{ 'font-family': 'var(--font-mono)', 'font-size': '12px' }}>v{key.primaryVersion}</td>
                                                    <td style={{ 'font-size': '12px', color: 'var(--text-secondary)' }}>{formatDate(key.createdAt)}</td>
                                                    <td onClick={(e) => e.stopPropagation()}>
                                                        <Show when={props.onDelete}>
                                                            <button onClick={() => props.onDelete('Delete crypto key "' + key.cryptoKeyId + '" from ' + selectedKeyRing() + '?', async () => {
                                                                await api.kmsDeleteCryptoKey({ keyRingId: selectedKeyRing(), cryptoKeyId: key.cryptoKeyId });
                                                                await loadCryptoKeys(selectedKeyRing());
                                                            })} class="btn btn-danger" style={{ height: '26px', 'font-size': '11px', padding: '0 8px' }} title="Delete">Del</button>
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
                </div>
            </Show>
        }>
            {/* Key Rings list */}
            <div>
                <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px">
                    <div />
                    <Show when={props.onAdd}>
                        <button onClick={() => props.onAdd('Create Key Ring', [
                            { name: 'keyRingId', type: 'text' },
                            { name: 'locationId', type: 'text', value: 'global' },
                        ], async (formData) => {
                            await api.kmsCreateKeyRing(formData);
                            if (props.onRefresh) props.onRefresh();
                        })} class="btn btn-primary" style={{ 'font-size': '13px', height: '34px' }}>
                            + Create Key Ring
                        </button>
                    </Show>
                </div>
                <Show when={d() && d().keyRings && d().keyRings.length > 0} fallback={
                    <div class="empty-state">
                        <div class="empty-state-icon">{'\u2205'}</div>
                        <div class="empty-state-title">No key rings found</div>
                        <div class="empty-state-text">Create a key ring to organize your crypto keys.</div>
                    </div>
                }>
                    <div class="data-table-wrapper">
                        <table class="data-table">
                            <thead>
                                <tr>
                                    <th>Key Ring Name</th>
                                    <th>Location</th>
                                    <th>Created</th>
                                </tr>
                            </thead>
                            <tbody>
                                <For each={d().keyRings}>
                                    {(kr) => (
                                        <tr class="clickable-row" onClick={() => loadCryptoKeys(kr.keyRingId)} onKeyDown={onActivate(() => loadCryptoKeys(kr.keyRingId))} role="button" tabIndex="0">
                                            <td style={{ 'font-weight': 500 }}>{kr.keyRingId}</td>
                                            <td><span class="badge badge-neutral">{kr.locationId}</span></td>
                                            <td>{formatDate(kr.createdAt)}</td>
                                        </tr>
                                    )}
                                </For>
                            </tbody>
                        </table>
                    </div>
                </Show>
            </div>
        </Show>
    );
}

// -- Secret Manager View --
function SecretManagerView(props) {
    const d = () => props.data();
    const [selectedSecret, setSelectedSecret] = createSignal(null);
    const [versions, setVersions] = createSignal([]);
    const [versionsLoading, setVersionsLoading] = createSignal(false);
    const [viewError, setViewError] = createSignal(null);
    // Map of version -> revealed payload value
    const [revealedValues, setRevealedValues] = createSignal({});
    const [loadingValue, setLoadingValue] = createSignal(null); // version number being loaded

    async function loadVersions(secretName) {
        setSelectedSecret(secretName);
        setVersionsLoading(true);
        setViewError(null);
        setRevealedValues({});
        try {
            const data = await api.secretManagerVersions(secretName);
            setVersions(data?.versions || []);
        } catch (e) {
            setViewError('Failed to load versions: ' + e.message);
            setVersions([]);
        } finally {
            setVersionsLoading(false);
        }
    }

    function goBack() {
        setSelectedSecret(null);
        setVersions([]);
        setViewError(null);
        setRevealedValues({});
    }

    // Optimistic update: changes local state immediately, no list re-fetch
    async function handleVersionAction(secretName, version, action) {
        setViewError(null);
        const newState = action === 'enable' ? 'ENABLED' : action === 'disable' ? 'DISABLED' : 'DESTROYED';

        // Save original state before optimistic update for potential revert
        const original = versions().find(v => v.version === version);
        const originalState = original?.state;

        // Optimistic local update
        setVersions(prev => prev.map(v =>
            v.version === version ? { ...v, state: newState } : v
        ));

        try {
            if (action === 'destroy') {
                if (!confirm(`Destroy version ${version} of "${secretName}"? This cannot be undone.`)) {
                    // Revert optimistic update using saved original state
                    if (originalState) {
                        setVersions(prev => prev.map(v =>
                            v.version === version ? { ...v, state: originalState } : v
                        ));
                    }
                    return;
                }
                await api.destroySecretVersion(secretName, version);
            } else if (action === 'enable') {
                await api.enableSecretVersion(secretName, version);
            } else if (action === 'disable') {
                await api.disableSecretVersion(secretName, version);
            }
        } catch (e) {
            setViewError('Failed: ' + e.message);
            // Revert optimistic update on failure
            await loadVersions(secretName);
        }
    }

    async function toggleSecretValue(secretName, version) {
        // If already revealed, hide it
        if (revealedValues()[version] !== undefined) {
            setRevealedValues(prev => { const n = { ...prev }; delete n[version]; return n; });
            return;
        }
        // Fetch and reveal
        setLoadingValue(version);
        try {
            const data = await api.getSecretVersionPayload(secretName, version);
            setRevealedValues(prev => ({ ...prev, [version]: data?.value || '(empty)' }));
        } catch (e) {
            setViewError('Failed to load value: ' + e.message);
        } finally {
            setLoadingValue(null);
        }
    }

    const stateBadge = (state) => {
        const s = (state || '').toUpperCase();
        if (s === 'ENABLED') return 'badge-healthy';
        if (s === 'DISABLED') return 'badge-warning';
        if (s === 'DESTROYED') return 'badge-unhealthy';
        return 'badge-neutral';
    };

    // Versions sub-view
    return (
        <Show when={!selectedSecret()} fallback={
            <div>
                <button class="back-link" onClick={goBack}>
                    {'\u2190'} Back to secrets
                </button>
                <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px">
                    <h2 style="margin:0">Secret: {selectedSecret()}</h2>
                    <Show when={props.onAdd}>
                        <button onClick={() => props.onAdd('Add Version to ' + selectedSecret(), [
                            { name: 'value', type: 'textarea' }
                        ], async (formData) => {
                            await api.addSecretVersion(selectedSecret(), formData.value);
                            await loadVersions(selectedSecret());
                        })} class="btn btn-primary" style={{ "font-size": '13px', height: '34px' }}>
                            + Add Version
                        </button>
                    </Show>
                </div>
                <Show when={viewError()}>
                    <div class="alert alert-error" role="alert">{viewError()}</div>
                </Show>
                <Show when={!versionsLoading()} fallback={
                    <div class="loading-state"><div class="loading-spinner" /> Loading versions…</div>
                }>
                    <Show when={versions().length > 0} fallback={
                        <div class="empty-state">
                            <div class="empty-state-title">No versions found</div>
                            <div class="empty-state-text">Add a version to get started.</div>
                        </div>
                    }>
                        <div class="data-table-wrapper">
                            <table class="data-table">
                                <thead>
                                    <tr>
                                        <th>Version</th>
                                        <th>State</th>
                                        <th>Value</th>
                                        <th>Created</th>
                                        <th>Actions</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    <For each={versions()}>
                                        {(v) => {
                                            const isRevealed = () => revealedValues()[v.version] !== undefined;
                                            const isLoadingThis = () => loadingValue() === v.version;
                                            return (
                                            <tr>
                                                <td style={{ "font-family": 'var(--font-mono)', "font-weight": '600' }}>
                                                    v{v.version}
                                                </td>
                                                <td>
                                                    <span class={`badge ${stateBadge(v.state)}`}>{v.state || 'UNKNOWN'}</span>
                                                </td>
                                                <td style={{ "min-width": '180px' }}>
                                                    <Show when={isRevealed()} fallback={
                                                        <button class="btn btn-secondary"
                                                            style={{ height: '26px', "font-size": '11px', padding: '0 8px', "font-family": 'var(--font-mono)', "letter-spacing": '2px' }}
                                                            onClick={() => toggleSecretValue(selectedSecret(), v.version)}
                                                            disabled={isLoadingThis()}>
                                                            {isLoadingThis() ? '…' : '••••••••'}
                                                        </button>
                                                    }>
                                                        <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                                                            <code style={{
                                                                "font-family": 'var(--font-mono)',
                                                                "font-size": '12px',
                                                                color: 'var(--text)',
                                                                padding: '2px 6px',
                                                                background: 'var(--bg-subtle)',
                                                                "border-radius": '4px',
                                                                "word-break": 'break-all',
                                                                "max-width": '280px',
                                                                overflow: 'hidden',
                                                                "text-overflow": 'ellipsis',
                                                                "white-space": 'nowrap',
                                                            }}>{revealedValues()[v.version]}</code>
                                                            <button class="btn btn-secondary"
                                                                style={{ height: '22px', "font-size": '10px', padding: '0 6px', "flex-shrink": '0' }}
                                                                onClick={() => toggleSecretValue(selectedSecret(), v.version)}>
                                                                Hide
                                                            </button>
                                                        </div>
                                                    </Show>
                                                </td>
                                                <td style={{ "font-size": '12px', color: 'var(--text-secondary)' }}>
                                                    {formatDate(v.created_at)}
                                                </td>
                                                <td>
                                                    <div style="display:flex;gap:4px">
                                                        <Show when={v.state === 'DISABLED'}>
                                                            <button class="btn btn-secondary" style={{ height: '26px', "font-size": '11px', padding: '0 8px' }}
                                                                onClick={() => handleVersionAction(selectedSecret(), v.version, 'enable')}>
                                                                Enable
                                                            </button>
                                                        </Show>
                                                        <Show when={v.state === 'ENABLED'}>
                                                            <button class="btn btn-secondary" style={{ height: '26px', "font-size": '11px', padding: '0 8px' }}
                                                                onClick={() => handleVersionAction(selectedSecret(), v.version, 'disable')}>
                                                                Disable
                                                            </button>
                                                        </Show>
                                                        <Show when={v.state !== 'DESTROYED'}>
                                                            <button class="btn btn-danger" style={{ height: '26px', "font-size": '11px', padding: '0 8px' }}
                                                                onClick={() => handleVersionAction(selectedSecret(), v.version, 'destroy')}>
                                                                Destroy
                                                            </button>
                                                        </Show>
                                                    </div>
                                                </td>
                                            </tr>
                                        );}}
                                    </For>
                                </tbody>
                            </table>
                        </div>
                    </Show>
                </Show>
            </div>
        }>
            {/* Secrets list */}
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
                <EmptyState
                    title="No secrets found"
                    message="Create a secret to store API keys, passwords, and certificates."
                    snippet={`from google.cloud import secretmanager
client = secretmanager.SecretManagerServiceClient()
parent = "projects/local-project"
secret = client.create_secret(
    request={"parent": parent, "secret_id": "my-secret",
             "secret": {"replication": {"automatic": {}}}})`}
                />
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
                                    <tr class="clickable-row" onClick={() => loadVersions(secret.name)} onKeyDown={onActivate(() => loadVersions(secret.name))} role="button" tabIndex="0">
                                        <td style={{ "font-weight": "500" }}>
                                            <img src="/icons/secretmanager.svg" alt="" width="14" height="14" style={{ "margin-right": "6px", "vertical-align": "middle" }} />
                                            {secret.name}
                                        </td>
                                        <td>
                                            <Show when={secret.version_count != null} fallback="--">
                                                <span class="badge badge-neutral">{secret.version_count}</span>
                                            </Show>
                                        </td>
                                        <td>{formatDate(secret.created_at || secret.createTime)}</td>
                                        <td onClick={(e) => e.stopPropagation()}>
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
        </Show>
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
            <div role="dialog" aria-modal="true" aria-labelledby="crud-modal-title" class="modal-overlay" onClick={props.onClose}>
                <div class="create-dialog" style="width:min(480px, calc(100vw - 32px))" onClick={(e) => e.stopPropagation()}>
                    <div class="create-dialog-accent" style="background:var(--primary)" />
                    <div class="create-dialog-header">
                        <h2 id="crud-modal-title" class="create-dialog-title">{props.title}</h2>
                        <p class="create-dialog-context">{props.mode === 'edit' ? 'Update existing record' : 'Add a new record'}</p>
                    </div>
                    <div class="create-dialog-body">
                        <Show when={props.error}>
                            <div class="create-dialog-error" role="alert">{props.error}</div>
                        </Show>
                        <Show when={props.warning}>
                            <div class="alert alert-warning" role="alert" style="font-size:12px;margin-bottom:12px">{props.warning}</div>
                        </Show>
                        <For each={props.fields || []}>
                            {(field) => {
                                const fieldId = `crud-field-${String(field.name).replace(/[^a-zA-Z0-9_-]/g, '-')}`;
                                return (
                                    <div class="create-dialog-field">
                                        <label class="create-dialog-label" for={fieldId}>{field.name}</label>
                                        {field.type === 'select' ? (
                                            <select id={fieldId} class="create-dialog-select" value={formData()[field.name] || ''} onChange={e => updateField(field.name, e.target.value)}>
                                                <Show when={field.placeholder && !(formData()[field.name])}>
                                                    <option value="" disabled selected hidden>{field.placeholder}</option>
                                                </Show>
                                                <For each={typeof field.optionsFn === 'function' ? (field.optionsFn(formData()) || []) : (field.options || [])}>{opt => <option value={opt}>{opt}</option>}</For>
                                            </select>
                                        ) : field.type === 'combo' ? (
                                            <ComboBox
                                                value={formData()[field.name] || ''}
                                                onChange={v => updateField(field.name, v)}
                                                options={typeof field.optionsFn === 'function' ? (field.optionsFn(formData()) || []) : (field.options || [])}
                                                placeholder={field.placeholder || ''}
                                            />
                                        ) : field.type === 'textarea' ? (
                                            <textarea id={fieldId} class="create-dialog-input" style="resize:vertical;font-family:var(--font-mono);font-size:12px;min-height:80px" autocomplete="off" value={formData()[field.name] || ''} onInput={e => updateField(field.name, e.target.value)} />
                                        ) : field.type === 'autocomplete' ? (
                                            <>
                                                <input id={fieldId} autocomplete="off" type="text" class="create-dialog-input create-dialog-input-mono" value={formData()[field.name] || ''} onInput={e => updateField(field.name, e.target.value)} list={`${fieldId}-list`} placeholder={field.placeholder || ''} />
                                                <datalist id={`${fieldId}-list`}>
                                                    <For each={field.options || []}>{opt => <option value={opt} />}</For>
                                                </datalist>
                                            </>
                                        ) : (
                                            <input id={fieldId} autocomplete="off" type="text" class="create-dialog-input create-dialog-input-mono" value={formData()[field.name] || ''} onInput={e => updateField(field.name, e.target.value)} placeholder={field.placeholder || ''} />
                                        )}
                                    </div>
                                );
                            }}
                        </For>
                    </div>
                    <div class="create-dialog-footer">
                        <button disabled={props.submitting} class="create-dialog-btn-cancel" onClick={props.onClose}>Cancel</button>
                        <button disabled={props.submitting} class="create-dialog-btn-submit" style="background:var(--primary);color:#fff" onClick={() => props.onSubmit(formData())}>
                            {props.submitting ? 'Saving\u2026' : (props.mode === 'edit' ? 'Save' : 'Create')}
                        </button>
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
            <div role="dialog" aria-modal="true" aria-labelledby="delete-confirm-title" class="modal-overlay" onClick={props.onClose}>
                <div class="create-dialog" style="width:min(400px, calc(100vw - 32px))" onClick={(e) => e.stopPropagation()}>
                    <div class="create-dialog-accent" style="background:var(--error)" />
                    <div class="create-dialog-header">
                        <div class="create-dialog-header-icon" style="color:var(--error);border-color:var(--error-soft);background:var(--error-soft)">
                            <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
                        </div>
                        <h2 id="delete-confirm-title" class="create-dialog-title">Confirm Delete</h2>
                        <p class="create-dialog-context">{props.message || 'Are you sure you want to delete this item?'}</p>
                    </div>
                    <div class="create-dialog-body" style="padding-top:12px">
                        <Show when={props.warning}>
                            <div class="alert alert-warning" role="alert" style="font-size:12px;margin-bottom:12px">{props.warning}</div>
                        </Show>
                        <p style="font-size:13px;color:var(--text-secondary);line-height:1.5;margin:0">This action cannot be undone. All associated data will be permanently removed.</p>
                    </div>
                    <div class="create-dialog-footer">
                        <button class="create-dialog-btn-cancel" onClick={props.onClose}>Cancel</button>
                        <button class="create-dialog-btn-submit" style="background:var(--error);color:#fff" onClick={props.onConfirm}>Delete</button>
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
    'cloudscheduler', 'cloudfunctions', 'alloydb', 'dataproc', 'cloudiam', 'kms',
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
    const [crudWarning, setCrudWarning] = createSignal(null);
    const [crudSubmitting, setCrudSubmitting] = createSignal(false);
    const [showDeleteConfirm, setShowDeleteConfirm] = createSignal(false);
    const [deleteMessage, setDeleteMessage] = createSignal('');
    const [deleteWarning, setDeleteWarning] = createSignal(null);
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

    let fetchSeq = 0;
    const fetchData = async (tab) => {
        const id = ++fetchSeq;
        if (!FETCH_SERVICES.has(tab)) {
            setLoading(false);
            return;
        }
        setLoading(true);
        setError(null);
        try {
            let result;
            if (tab === 'pubsub') {
                const [topicsRes, subsRes] = await Promise.all([
                    api.browse('pubsub'),
                    api.browse('pubsub/subscriptions'),
                ]);
                if (id !== fetchSeq) return;
                result = { topics: topicsRes.topics || [], subscriptions: subsRes.subscriptions || [] };
            } else if (tab === 'cloudsql') {
                result = await api.browse('cloudsql');
                if (id !== fetchSeq) return;
            } else if (tab === 'bigtable') {
                result = await api.browse('bigtable');
                if (id !== fetchSeq) return;
            } else if (tab === 'memorystore') {
                result = await api.browse('memorystore');
                if (id !== fetchSeq) return;
            } else {
                result = await api.browse(tab);
                if (id !== fetchSeq) return;
            }
            if (id !== fetchSeq) return;
            setData(result);
        } catch (err) {
            if (id !== fetchSeq) return;
            setError('Could not load data: ' + err.message);
        } finally {
            if (id === fetchSeq) setLoading(false);
        }
    };

    const loadData = () => fetchData(selectedTab());

    // Watch for parent-triggered refresh
    createEffect(() => {
        const trigger = props.refreshTrigger?.();
        if (trigger > 0) loadData();
    });

    const handleAdd = (title, fields, callback) => {
        setCrudTitle(title);
        setCrudFields(fields);
        setCrudMode('add');
        setCrudCallback(() => callback);
        setCrudError(null);
        setCrudWarning(getActionWarning(selectedTab(), 'console', title));
        setCrudSubmitting(false);
        setShowCrudModal(true);
    };

    const handleEdit = (title, fields, callback) => {
        setCrudTitle(title);
        setCrudFields(fields);
        setCrudMode('edit');
        setCrudCallback(() => callback);
        setCrudError(null);
        setCrudWarning(getActionWarning(selectedTab(), 'console', title));
        setCrudSubmitting(false);
        setShowCrudModal(true);
    };

    const handleDelete = (message, callback) => {
        setDeleteMessage(message);
        setDeleteWarning(getActionWarning(selectedTab(), 'console', message));
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

    // Event-based refresh: CloudIAMView dispatches localcloud:refresh-data after create
    onMount(() => {
        const handler = () => {
            const tab = selectedTab();
            if (FETCH_SERVICES.has(tab)) fetchData(tab);
        };
        window.addEventListener('localcloud:refresh-data', handler);
        onCleanup(() => window.removeEventListener('localcloud:refresh-data', handler));
    });

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
        fetchData(tab);
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
            case 'memorystore':
            case 'firestore':
                return <DatabaseExplorer serviceId={tab} data={data} onRefresh={loadData} onAdd={handleAdd} onEdit={handleEdit} onDelete={handleDelete} subpath={props.subpath} onSubpathChange={props.onSubpathChange} />;
            case 'gcs': return <GcsView data={data} onAdd={handleAdd} onEdit={handleEdit} onDelete={handleDelete} projectLocation={props.projectRegion || (() => 'us-central1')} />;
            case 'pubsub': return <PubSubView data={data} onAdd={handleAdd} onDelete={handleDelete} onRefresh={loadData} />;
            case 'secretmanager': return <SecretManagerView data={data} onAdd={handleAdd} onDelete={handleDelete} />;
            case 'cloudtasks': return <CloudTasksView data={data} onAdd={handleAdd} onDelete={handleDelete} />;
            case 'logging': return <LoggingView data={data} />;
            case 'monitoring': return <MonitoringView data={data} />;
            case 'cloudscheduler': return <CloudSchedulerView data={data} onAdd={handleAdd} onDelete={handleDelete} />;
            case 'cloudfunctions': return <CloudFunctionsView data={data} onAdd={handleAdd} onDelete={handleDelete} />;
            case 'dataproc': return null; // Dataproc UI is now owned by ServiceExplorer's DataprocPanel; DataprocView kept as fallback.
            case 'cloudiam': return <CloudIAMView data={data} onAdd={handleAdd} onDelete={handleDelete} />;
            case 'kms': return <KmsView data={data} onAdd={handleAdd} onDelete={handleDelete} onRefresh={loadData} />;
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
                warning={crudWarning()}
                submitting={crudSubmitting()}
            />
            <DeleteConfirmation
                show={showDeleteConfirm()}
                onClose={() => setShowDeleteConfirm(false)}
                onConfirm={handleDeleteConfirm}
                message={deleteMessage()}
                warning={deleteWarning()}
            />
        </div>
    );
}

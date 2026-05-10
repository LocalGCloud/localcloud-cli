import { createSignal, createEffect, Show, For, onCleanup } from 'solid-js';
import { api } from '../api.js';
import { SchemaExplorer } from './SchemaExplorer.jsx';
import { SyncFilterBuilder } from './SyncFilterBuilder.jsx';
import { formatNumber, onActivate } from '../utils/a11y.js';

export function RemoteSyncPanel(props) {
    const [connected, setConnected] = createSignal(false);
    const [authStatus, setAuthStatus] = createSignal(null);
    const [selectedResource, setSelectedResource] = createSignal(null);
    const [preview, setPreview] = createSignal(null);
    const [previewLoading, setPreviewLoading] = createSignal(false);
    const [syncManifests, setSyncManifests] = createSignal([]);
    const [panel, setPanel] = createSignal('empty'); // empty|preview|sync|progress|detail
    const [filters, setFilters] = createSignal([]);
    const [rowLimit, setRowLimit] = createSignal(1000000);
    const [costEstimate, setCostEstimate] = createSignal(null);
    const [syncProgress, setSyncProgress] = createSignal(null);
    const [syncError, setSyncError] = createSignal(null);
    const [connectProject, setConnectProject] = createSignal('');
    const [connectToken, setConnectToken] = createSignal('');
    const [selectedManifest, setSelectedManifest] = createSignal(null);
    const [confirmDelete, setConfirmDelete] = createSignal(null); // manifest id or null
    const [previewError, setPreviewError] = createSignal(null);
    const [manifestError, setManifestError] = createSignal(null);
    const [historyHeight, setHistoryHeight] = createSignal(200);
    const [historyOpen, setHistoryOpen] = createSignal(true);

    // Track active polling interval for cleanup on unmount
    let activePolling = null;
    onCleanup(() => { if (activePolling) clearInterval(activePolling); });

    // Resizable bottom panel drag handler
    const startDrag = (e) => {
        e.preventDefault();
        const startY = e.clientY;
        const startH = historyHeight();
        const onMove = (ev) => {
            const delta = startY - ev.clientY;
            setHistoryHeight(Math.max(80, Math.min(500, startH + delta)));
        };
        const onUp = () => {
            document.removeEventListener('mousemove', onMove);
            document.removeEventListener('mouseup', onUp);
            document.body.style.cursor = '';
            document.body.style.userSelect = '';
        };
        document.body.style.cursor = 'ns-resize';
        document.body.style.userSelect = 'none';
        document.addEventListener('mousemove', onMove);
        document.addEventListener('mouseup', onUp);
    };
    const adjustHistoryHeight = (delta) => setHistoryHeight(h => Math.max(80, Math.min(500, h + delta)));
    const onHistoryResizeKeyDown = (e) => {
        if (e.key === 'ArrowUp') {
            e.preventDefault();
            adjustHistoryHeight(16);
        } else if (e.key === 'ArrowDown') {
            e.preventDefault();
            adjustHistoryHeight(-16);
        }
    };

    // Reset state when serviceId changes (e.g. switching from GCS to BigQuery)
    createEffect(() => {
        const _sid = props.serviceId; // track serviceId
        setSelectedResource(null);
        setPreview(null);
        setPreviewError(null);
        setPanel('empty');
        setFilters([]);
        setCostEstimate(null);
        setSyncProgress(null);
        setSyncError(null);
        setSyncManifests([]);
        setSelectedManifest(null);
        setConfirmDelete(null);
        setManifestError(null);
        if (activePolling) { clearInterval(activePolling); activePolling = null; }
    });

    // Check auth (re-runs when serviceId changes due to loadManifests dependency)
    createEffect(async () => {
        const _sid = props.serviceId; // track serviceId to re-check on switch
        try {
            const s = await api.syncAuthStatus();
            setAuthStatus(s);
            setConnected(s?.connected === true || s?.connected === 'true');
            if (s?.connected === true || s?.connected === 'true') loadManifests();
        } catch (e) { setConnected(false); }
    });

    const loadManifests = async () => {
        try {
            const resp = await api.syncServiceManifests(props.serviceId);
            // Server wraps list in {manifests: [...]}, extract the array
            const m = resp?.manifests ?? resp;
            setSyncManifests(Array.isArray(m) ? m : []);
            setManifestError(null);
        } catch (e) { setManifestError(e.message); }
    };

    const handleSelect = async (node) => {
        setSelectedResource(node);
        setPanel('preview');
        setPreviewLoading(true);
        setPreviewError(null);
        try {
            const r = await api.syncPreview(props.serviceId, node.id, 5);
            setPreview(r);
        } catch (e) { setPreview(null); setPreviewError(e.message); }
        finally { setPreviewLoading(false); }
    };

    const showSyncForm = () => { setPanel('sync'); setCostEstimate(null); setFilters([]); };

    const estimateCost = async () => {
        const res = selectedResource();
        if (!res) return;
        try {
            const est = await api.syncEstimate(props.serviceId, {
                resource: res.id,
                source_project: authStatus()?.source_project,
                filters: filters(),
                row_limit: rowLimit()
            });
            setCostEstimate(est);
        } catch (e) { setCostEstimate({ error: e.message }); }
    };

    const startSync = async () => {
        const res = selectedResource();
        if (!res) return;
        setPanel('progress');
        setSyncProgress({ percent: 0, rowsTransferred: 0, status: 'running' });
        setSyncError(null);
        try {
            const result = await api.syncStart(props.serviceId, {
                resource: res.id,
                source_project: authStatus()?.source_project,
                filters: filters(),
                row_limit: rowLimit()
            });

            const manifestId = result.manifest_id;

            // Clear any previous polling
            if (activePolling) clearInterval(activePolling);

            // Poll progress until complete
            activePolling = setInterval(async () => {
                try {
                    const progress = await api.syncProgress(props.serviceId, res.id);

                    if (progress.status === 'running') {
                        setSyncProgress({
                            percent: progress.percent || 0,
                            rowsTransferred: progress.rows_transferred || 0,
                            bytesTransferred: progress.bytes_transferred || 0,
                            estimatedTotal: progress.estimated_total || 0,
                            elapsedMs: progress.elapsed_ms || 0,
                            status: 'running'
                        });
                    } else {
                        // Not running anymore - check manifest for final status
                        clearInterval(activePolling);
                        activePolling = null;
                        await loadManifests();

                        // Get final manifest data
                        const resp = await api.syncServiceManifests(props.serviceId);
                        const manifestList = resp?.manifests ?? (Array.isArray(resp) ? resp : []);
                        const final_ = manifestList
                            .find(m => m.id === manifestId || m.resource_path === res.id);

                        if (final_ && final_.status === 'completed') {
                            setSyncProgress({
                                percent: 100,
                                rowsSynced: final_.row_count,
                                bytesSynced: final_.bytes_synced,
                                status: 'completed'
                            });
                        } else if (final_ && final_.status === 'failed') {
                            setSyncError(final_.error_message || 'Sync failed');
                            setSyncProgress(prev => ({ ...prev, status: 'failed' }));
                        } else if (final_ && final_.status === 'cancelled') {
                            setSyncProgress(prev => ({ ...prev, status: 'cancelled' }));
                        } else {
                            setSyncProgress(prev => ({ ...prev, status: 'completed' }));
                        }
                    }
                } catch (e) {
                    // Progress endpoint might fail - keep polling
                }
            }, 2000);

            // Safety timeout - stop polling after 30 minutes
            setTimeout(() => {
                if (activePolling) {
                    clearInterval(activePolling);
                    activePolling = null;
                }
            }, 1800000);

        } catch (e) {
            setSyncError(e.message);
            setSyncProgress(prev => ({ ...prev, status: 'failed' }));
        }
    };

    const handleConnect = async () => {
        try {
            await api.syncConnect({
                source_project: connectProject(),
                auth_method: 'oauth',
                credential_data: { access_token: connectToken() }
            });
            const s = await api.syncAuthStatus();
            setAuthStatus(s);
            setConnected(true);
            loadManifests();
        } catch (e) { setSyncError(e.message); }
    };

    const handleResync = async (manifest) => {
        // Parse stored filters
        let parsedFilters = [];
        try {
            parsedFilters = JSON.parse(manifest.filters_json || '[]');
        } catch (e) { /* use empty */ }

        // Pre-populate sync form with manifest's params
        setSelectedResource({
            id: manifest.resource_path,
            name: manifest.resource_path,
            schema: selectedResource()?.schema || []
        });
        setFilters(parsedFilters);
        setRowLimit(manifest.row_count || 1000000);
        setPanel('sync');

        // Auto-estimate cost
        try {
            const est = await api.syncEstimate(props.serviceId, {
                resource: manifest.resource_path,
                source_project: manifest.source_project,
                filters: parsedFilters,
                row_limit: manifest.row_count || 1000000
            });
            setCostEstimate(est);
        } catch (e) { setCostEstimate({ error: e.message }); }
    };

    // OAuth disabled — requires client_id/client_secret configuration
    const handleOAuth = () => {};

    const handleDisconnect = async () => {
        await api.syncDisconnect();
        setConnected(false);
        setAuthStatus(null);
    };

    const deleteManifest = async (id) => {
        await api.syncDeleteManifest(id);
        await loadManifests();
        setPanel('empty');
    };

    const timeAgo = (ts) => {
        if (!ts) return '';
        const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });
        const ms = Date.now() - new Date(ts).getTime();
        if (ms < 60000) return rtf.format(-Math.round(ms / 1000), 'second');
        if (ms < 3600000) return rtf.format(-Math.round(ms / 60000), 'minute');
        if (ms < 86400000) return rtf.format(-Math.round(ms / 3600000), 'hour');
        return rtf.format(-Math.round(ms / 86400000), 'day');
    };

    const fmtNum = (n) => {
        if (n == null) return '0';
        if (n >= 1e6) return new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 }).format(n);
        return formatNumber(n);
    };

    const fmtBytes = (b) => {
        if (!b) return '0 B';
        const units = ['B', 'KB', 'MB', 'GB', 'TB'];
        const i = Math.floor(Math.log(b) / Math.log(1024));
        return new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 }).format(b / Math.pow(1024, i)) + ' ' + units[i];
    };

    const fmtCost = (c) => new Intl.NumberFormat(undefined, { style: 'currency', currency: 'USD', minimumFractionDigits: 4 }).format(c || 0);

    const fmtElapsed = (ms) => {
        if (!ms) return '0s';
        const totalSec = Math.floor(ms / 1000);
        const m = Math.floor(totalSec / 60);
        const s = totalSec % 60;
        return m > 0 ? `${m}m ${s}s` : `${s}s`;
    };

    const statusColor = (s) => s === 'completed' ? 'var(--success)' : s === 'failed' ? 'var(--error)' : 'var(--warning)';
    const statusIcon = (s) => s === 'completed' ? '\u2713' : s === 'failed' ? '\u2717' : '\u25CB';

    return (
    <Show when={connected()} fallback={
        <div class="aura-sync-wizard-shell">
            <div class="card aura-sync-wizard">
                <div class="aura-wizard-steps" aria-label="Sync setup steps">
                    <span class="active">1 Connect</span>
                    <span>2 Preview Mapping</span>
                    <span>3 Sync Local</span>
                </div>
                <div class="card-header" style="display: flex; align-items: center; gap: 10px">
                    <svg width="24" height="24" viewBox="0 0 24 24" fill="var(--primary)" aria-hidden="true" focusable="false">
                        <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z"/>
                    </svg>
                    <h3 style="margin: 0; font-size: 18px">Connect to GCP Project</h3>
                </div>
                <div class="card-body" style="display: flex; flex-direction: column; gap: 20px">
                    <div>
                        <label class="form-label" for="sync-project" style="font-size: 13px; font-weight: 600">Source Project ID</label>
                        <input id="sync-project" name="project" class="form-input"
                               placeholder="my-production-project"
                               value={connectProject()} onInput={e => setConnectProject(e.target.value)}
                               autocomplete="off" style="font-size: 14px; padding: 10px 12px" />
                    </div>
                    <div>
                        <label class="form-label" for="sync-token" style="font-size: 13px; font-weight: 600">Access Token</label>
                        <input id="sync-token" name="token" class="form-input form-input-mono"
                               type="password" placeholder="ya29.a0AfH6SM…"
                               value={connectToken()} onInput={e => setConnectToken(e.target.value)}
                               autocomplete="off" style="font-size: 14px; padding: 10px 12px" />
                        <div style="margin-top: 10px; font-size: 13px; color: var(--text-secondary); line-height: 1.7">
                            Generate with:
                            <div style="display: flex; align-items: center; gap: 6px; margin-top: 4px; margin-bottom: 6px">
                                <code style="flex: 1; background: var(--bg-subtle); padding: 8px 12px; border-radius: var(--radius-sm); font-size: 13px; font-family: var(--font-mono); user-select: all; cursor: text; border: 1px solid var(--border); display: block">gcloud auth print-access-token</code>
                                <button class="btn btn-sm" style="padding: 6px 8px; flex-shrink: 0" title="Copy command" aria-label="Copy gcloud access token command"
                                        onClick={() => { navigator.clipboard.writeText('gcloud auth print-access-token'); }}>
                                    <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false">
                                        <path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"/>
                                    </svg>
                                </button>
                            </div>
                            Required scope: <code style="background: var(--bg-subtle); padding: 2px 6px; border-radius: var(--radius-sm); font-size: 12px; font-family: var(--font-mono)">cloud-platform.read-only</code>
                            <br/>Token expires in ~1 hour. Re-paste to refresh.
                        </div>
                    </div>
                    <Show when={syncError()}>
                        <div class="alert alert-error" role="alert" style="margin: 0">{syncError()}</div>
                    </Show>
                    <div class="aura-mapping-preview">
                        <div><span>Remote dataset</span><strong>{connectProject() || 'source-project'}</strong></div>
                        <div><span>Local target</span><strong>{props.serviceId}</strong></div>
                        <div><span>Transfer mode</span><strong>read-only mirror</strong></div>
                    </div>
                    <button class="btn btn-primary" onClick={handleConnect}
                            disabled={!connectProject() || !connectToken()}
                            style="width: 100%">
                        Connect
                    </button>
                    <div style="border-top: 1px solid var(--border); padding-top: 12px; margin-top: 4px">
                        <div style="display: flex; align-items: center; gap: 8px; opacity: 0.5">
                            <svg width="16" height="16" viewBox="0 0 18 18" aria-hidden="true" focusable="false">
                                <path fill="#4285F4" d="M17.64 9.2c0-.637-.057-1.251-.164-1.84H9v3.481h4.844a4.14 4.14 0 0 1-1.796 2.716v2.259h2.908c1.702-1.567 2.684-3.875 2.684-6.615Z"/>
                                <path fill="#34A853" d="M9 18c2.43 0 4.467-.806 5.956-2.18l-2.908-2.259c-.806.54-1.837.86-3.048.86-2.344 0-4.328-1.584-5.036-3.711H.957v2.332A8.997 8.997 0 0 0 9 18Z"/>
                                <path fill="#FBBC05" d="M3.964 10.71A5.41 5.41 0 0 1 3.682 9c0-.593.102-1.17.282-1.71V4.958H.957A8.997 8.997 0 0 0 0 9c0 1.452.348 2.827.957 4.042l3.007-2.332Z"/>
                                <path fill="#EA4335" d="M9 3.58c1.321 0 2.508.454 3.44 1.345l2.582-2.58C13.463.891 11.426 0 9 0A8.997 8.997 0 0 0 .957 4.958L3.964 7.29C4.672 5.163 6.656 3.58 9 3.58Z"/>
                            </svg>
                            <span style="font-size: 12px; color: var(--text-secondary)">
                                Google Sign-In — requires OAuth client configuration
                            </span>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    }>
        <div style="display: flex; flex-direction: column; flex: 1; min-height: 0">
            {/* CONNECTION HEADER — full width */}
            <div style="display: flex; align-items: center; gap: 10px; padding: 8px 16px; border-bottom: 1px solid var(--border); background: var(--surface); flex-shrink: 0">
                <div style="display: flex; align-items: center; gap: 6px">
                    <span class="status-dot healthy" />
                    <span style="font-size: 12px; font-weight: 600; color: var(--text)">{authStatus()?.source_project}</span>
                    <span style="font-size: 10px; color: var(--text-tertiary); background: var(--surface-variant); padding: 1px 6px; border-radius: var(--radius-pill); font-weight: 500">REMOTE</span>
                </div>
                <div style="margin-left: auto; display: flex; align-items: center; gap: 8px">
                    <button class="btn btn-sm" onClick={handleDisconnect} title="Disconnect"
                            style="font-size: 11px; color: var(--text-secondary); padding: 3px 10px">
                        Disconnect
                    </button>
                </div>
            </div>

            <div style="display: flex; flex: 1; min-height: 0">
                {/* EXPLORER SIDEBAR */}
                <div style="width: 320px; border-right: 1px solid var(--border); display: flex; flex-direction: column; overflow: hidden; flex-shrink: 0; border-left: 4px solid transparent">
                    <div style="flex: 1; overflow-y: auto">
                        <SchemaExplorer source="remote" serviceId={props.serviceId}
                                        onSelect={handleSelect} syncManifests={syncManifests} />
                    </div>
                    <Show when={manifestError()}>
                        <div style="padding: 8px 12px; border-top: 1px solid var(--border)">
                            <div class="alert alert-error" role="alert" style="margin: 0; font-size: 12px">{manifestError()}</div>
                        </div>
                    </Show>
                </div>

                {/* MAIN CONTENT — vertical split: content top, history bottom */}
                <div style="flex: 1; display: flex; flex-direction: column; min-width: 0">
                    {/* Top: content area */}
                    <div style="flex: 1; overflow-y: auto; min-height: 0">

                    {/* Empty state */}
                    <Show when={panel() === 'empty'}>
                        <div style="display: flex; align-items: center; justify-content: center; flex: 1; padding: 48px">
                            <div style="text-align: center; max-width: 320px">
                                <svg width="48" height="48" viewBox="0 0 24 24" fill="var(--text-tertiary)" aria-hidden="true" focusable="false" style="opacity: 0.4; margin-bottom: 16px">
                                    <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z"/>
                                </svg>
                                <div style="font-size: 15px; font-weight: 500; color: var(--text); margin-bottom: 6px">Select a resource</div>
                                <div style="font-size: 13px; color: var(--text-secondary); line-height: 1.5">
                                    Browse the remote project in the explorer and click a table, bucket, or collection to preview its data.
                                </div>
                            </div>
                        </div>
                    </Show>

                    {/* Preview */}
                    <Show when={panel() === 'preview'}>
                        <div style="padding: 24px">
                            <Show when={selectedResource()}>
                                <div style="display: flex; align-items: center; gap: 10px; margin-bottom: 20px">
                                    <div style="flex: 1">
                                        <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 4px">
                                            <h3 style="margin: 0; font-size: 16px">{selectedResource().name}</h3>
                                            <span style="font-size: 10px; color: var(--primary); background: var(--primary-soft); padding: 1px 8px; border-radius: var(--radius-pill); font-weight: 600">REMOTE</span>
                                        </div>
                                        <Show when={selectedResource().metadata}>
                                            <div style="font-size: 13px; color: var(--text-secondary)">
                                                {fmtNum(selectedResource().metadata.rowCount)} rows &middot; {fmtBytes(selectedResource().metadata.sizeBytes)}
                                            </div>
                                        </Show>
                                    </div>
                                    <button class="btn btn-primary btn-sm" onClick={showSyncForm} style="flex-shrink: 0">
                                        Sync to Local
                                    </button>
                                </div>
                                <Show when={previewLoading()}>
                                    <div class="loading-state"><div class="loading-spinner" /></div>
                                </Show>
                                <Show when={!previewLoading() && previewError()}>
                                    <div class="alert alert-error" role="alert">{previewError()}</div>
                                </Show>
                                <Show when={!previewLoading() && preview()}>
                                    <div style="font-size: 11px; font-weight: 600; color: var(--text-tertiary); text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 8px">
                                        Sample Data ({(preview().rows || []).length} rows)
                                    </div>
                                    <div class="data-table-wrapper">
                                        <table class="data-table">
                                            <thead>
                                                <tr>
                                                    <For each={preview().columns || []}>
                                                        {(col) => <th>{col}</th>}
                                                    </For>
                                                </tr>
                                            </thead>
                                            <tbody>
                                                <For each={preview().rows || []}>
                                                    {(row) => (
                                                        <tr>
                                                            <For each={preview().columns || []}>
                                                                {(col) => <td>{String(row[col] ?? '')}</td>}
                                                            </For>
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

                    {/* Sync form */}
                    <Show when={panel() === 'sync'}>
                        <div style="padding: 24px; max-width: 640px">
                            <h3 style="margin: 0 0 20px 0; font-size: 16px">Sync to Local: {selectedResource()?.name}</h3>
                            <div style="background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius); padding: 16px; margin-bottom: 16px">
                                <div style="font-size: 12px; font-weight: 600; color: var(--text-secondary); text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 12px">Filters</div>
                                <SyncFilterBuilder schema={selectedResource()?.schema} onChange={setFilters} initialFilters={filters()} />
                            </div>
                            <div style="margin-bottom: 16px">
                                <label class="form-label" for="sync-row-limit">Row Limit</label>
                                <input id="sync-row-limit" name="sync-row-limit" autocomplete="off" class="form-input" type="number" value={rowLimit()}
                                       onInput={e => setRowLimit(parseInt(e.target.value) || 1000000)}
                                       style="width: 200px" />
                            </div>
                            <div style="display: flex; gap: 8px; margin-bottom: 16px">
                                <button class="btn btn-sm" onClick={estimateCost} style="font-size: 12px">Estimate Cost</button>
                            </div>
                            <Show when={costEstimate()}>
                                <div style="background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius); padding: 16px; margin-bottom: 16px">
                                    <Show when={costEstimate().error}>
                                        <div class="alert alert-error" role="alert">{costEstimate().error}</div>
                                    </Show>
                                    <Show when={!costEstimate().error}>
                                        <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 10px; font-size: 13px">
                                            <div><span style="color: var(--text-secondary)">Est. rows</span><br/><strong>{fmtNum(costEstimate().estimatedRows)}</strong></div>
                                            <div><span style="color: var(--text-secondary)">Scan size</span><br/><strong>{fmtBytes(costEstimate().estimatedBytes)}</strong></div>
                                            <div><span style="color: var(--text-secondary)">Est. cost</span><br/><strong>{fmtCost(costEstimate().estimatedCostUsd)}</strong></div>
                                            <div><span style="color: var(--text-secondary)">Details</span><br/><strong>{costEstimate().details}</strong></div>
                                        </div>
                                    </Show>
                                </div>
                            </Show>
                            <div style="display: flex; gap: 8px">
                                <button class="btn" onClick={() => setPanel('preview')}>Cancel</button>
                                <button class="btn btn-primary" onClick={startSync}>Start Sync</button>
                            </div>
                        </div>
                    </Show>

                    {/* Progress */}
                    <Show when={panel() === 'progress'}>
                        <div style="padding: 24px; max-width: 640px">
                            <h3 style="margin: 0 0 20px 0; font-size: 16px">
                                Syncing: {selectedResource()?.name}
                                <span style="color: var(--text-tertiary); font-weight: 400"> &rarr; Local</span>
                            </h3>
                            <div style="margin-bottom: 20px">
                                <div style="display: flex; justify-content: space-between; font-size: 12px; color: var(--text-secondary); margin-bottom: 6px">
                                    <span aria-live="polite">{syncProgress()?.status === 'running' ? 'Transferring…' : syncProgress()?.status}</span>
                                    <span>{syncProgress()?.percent || 0}%</span>
                                </div>
                                <div style="height: 4px; background: var(--border); border-radius: 2px; overflow: hidden">
                                    <div style={`height: 100%; border-radius: 2px; background: ${syncProgress()?.status === 'failed' ? 'var(--error)' : syncProgress()?.status === 'completed' ? 'var(--success)' : 'var(--primary)'}; width: ${syncProgress()?.percent || 0}%; transition: width 0.3s ease`} />
                                </div>
                            </div>
                            <Show when={syncProgress()}>
                                <div style="background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius); padding: 16px; display: grid; grid-template-columns: 1fr 1fr; gap: 12px; font-size: 13px; margin-bottom: 16px">
                                    <div><span style="color: var(--text-secondary)">Rows</span><br/><strong>{fmtNum(syncProgress().rowsTransferred || syncProgress().rowsSynced || 0)}{syncProgress().estimatedTotal > 0 ? ` / ${fmtNum(syncProgress().estimatedTotal)}` : ''}</strong></div>
                                    <div><span style="color: var(--text-secondary)">Status</span><br/><strong style={`color: ${statusColor(syncProgress().status)}`}>{syncProgress().status}</strong></div>
                                    <Show when={syncProgress().bytesTransferred || syncProgress().bytesSynced}>
                                        <div><span style="color: var(--text-secondary)">Size</span><br/><strong>{fmtBytes(syncProgress().bytesTransferred || syncProgress().bytesSynced || 0)}</strong></div>
                                    </Show>
                                    <Show when={syncProgress().elapsedMs}>
                                        <div><span style="color: var(--text-secondary)">Elapsed</span><br/><strong>{fmtElapsed(syncProgress().elapsedMs)}</strong></div>
                                    </Show>
                                </div>
                            </Show>
                            <Show when={syncProgress()?.status === 'running'}>
                                <button class="btn btn-danger btn-sm" onClick={async () => {
                                    if (activePolling) { clearInterval(activePolling); activePolling = null; }
                                    await api.syncCancel(props.serviceId, { resource: selectedResource()?.id });
                                    setSyncProgress(prev => ({ ...prev, status: 'cancelled' }));
                                    await loadManifests();
                                }}>Cancel Sync</button>
                            </Show>
                            <Show when={syncError()}>
                                <div class="alert alert-error" role="alert" style="margin-top: 16px">{syncError()}</div>
                            </Show>
                            <Show when={syncProgress()?.status === 'cancelled'}>
                                <div style="margin-top: 16px; background: var(--warning-soft); color: var(--warning); border: 1px solid var(--warning); border-radius: var(--radius); padding: 12px; font-size: 13px">
                                    Sync cancelled.
                                </div>
                            </Show>
                            <Show when={syncProgress()?.status === 'completed'}>
                                <div style="margin-top: 16px; background: var(--success-soft); color: var(--success); border: 1px solid var(--success); border-radius: var(--radius); padding: 12px; font-size: 13px">
                                    Sync complete! {fmtNum(syncProgress().rowsSynced)} rows synced.
                                </div>
                            </Show>
                        </div>
                    </Show>

                    {/* History detail */}
                    <Show when={panel() === 'detail' && selectedManifest()}>
                        <div style="padding: 24px; max-width: 640px">
                            <div style="display: flex; align-items: center; gap: 10px; margin-bottom: 20px">
                                <button class="btn btn-sm btn-icon" onClick={() => setPanel(syncManifests().length > 0 ? 'empty' : 'empty')}
                                        aria-label="Back to sync history"
                                        style="padding: 4px 6px; color: var(--text-secondary)" title="Back">
                                    <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg>
                                </button>
                                <div style="flex: 1">
                                    <h3 style="margin: 0; font-size: 16px">{selectedManifest().resource_path}</h3>
                                </div>
                                <span style={`font-size: 12px; font-weight: 600; color: ${statusColor(selectedManifest().status)}; background: ${selectedManifest().status === 'completed' ? 'var(--success-soft)' : selectedManifest().status === 'failed' ? 'var(--error-soft)' : 'var(--warning-soft)'}; padding: 2px 10px; border-radius: var(--radius-pill)`}>
                                    {selectedManifest().status}
                                </span>
                            </div>
                            <div style="background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius); padding: 16px; margin-bottom: 16px">
                                <div style="display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 16px; font-size: 13px">
                                    <div><span style="color: var(--text-secondary); font-size: 11px; text-transform: uppercase; letter-spacing: 0.3px">Synced</span><br/><strong>{timeAgo(selectedManifest().synced_at)}</strong></div>
                                    <div><span style="color: var(--text-secondary); font-size: 11px; text-transform: uppercase; letter-spacing: 0.3px">Rows</span><br/><strong>{fmtNum(selectedManifest().row_count)}</strong></div>
                                    <div><span style="color: var(--text-secondary); font-size: 11px; text-transform: uppercase; letter-spacing: 0.3px">Size</span><br/><strong>{fmtBytes(selectedManifest().bytes_synced)}</strong></div>
                                    <div><span style="color: var(--text-secondary); font-size: 11px; text-transform: uppercase; letter-spacing: 0.3px">Cost</span><br/><strong>{fmtCost(selectedManifest().estimated_cost)}</strong></div>
                                    <div><span style="color: var(--text-secondary); font-size: 11px; text-transform: uppercase; letter-spacing: 0.3px">Source</span><br/><strong>{selectedManifest().source_project}</strong></div>
                                    <div><span style="color: var(--text-secondary); font-size: 11px; text-transform: uppercase; letter-spacing: 0.3px">Status</span><br/><strong style={`color: ${statusColor(selectedManifest().status)}`}>{selectedManifest().status}</strong></div>
                                </div>
                                <Show when={selectedManifest().filters_json && selectedManifest().filters_json !== '[]'}>
                                    <div style="margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--border); font-size: 13px">
                                        <span style="color: var(--text-secondary); font-size: 11px; text-transform: uppercase; letter-spacing: 0.3px">Filters</span><br/>
                                        <code style="font-size: 12px; color: var(--text)">{selectedManifest().filters_json}</code>
                                    </div>
                                </Show>
                            </div>
                            <Show when={selectedManifest().error_message}>
                                <div class="alert alert-error" role="alert" style="margin-bottom: 16px; font-size: 12px">
                                    {selectedManifest().error_message}
                                </div>
                            </Show>
                            <div style="display: flex; gap: 8px">
                                <button class="btn btn-primary btn-sm" onClick={() => handleResync(selectedManifest())}>Resync</button>
                                <Show when={confirmDelete() === selectedManifest()?.id} fallback={
                                    <button class="btn btn-sm" style="color: var(--error)" onClick={() => setConfirmDelete(selectedManifest()?.id)}>
                                        Remove
                                    </button>
                                }>
                                    <span />
                                </Show>
                            </div>
                            <Show when={confirmDelete() === selectedManifest()?.id}>
                                <div style="margin-top: 12px; background: var(--error-soft); border: 1px solid var(--error); border-radius: var(--radius); padding: 12px">
                                    <div style="font-weight: 500; color: var(--error); margin-bottom: 4px; font-size: 13px">
                                        Remove "{selectedManifest()?.resource_path}" from local?
                                    </div>
                                    <div style="font-size: 12px; color: var(--text-secondary); margin-bottom: 10px">
                                        Deletes synced data from local emulators and removes sync history. Production data not affected.
                                    </div>
                                    <div style="display: flex; gap: 8px">
                                        <button class="btn btn-danger btn-sm" onClick={() => { deleteManifest(selectedManifest()?.id); setConfirmDelete(null); }}>
                                            Yes, Remove
                                        </button>
                                        <button class="btn btn-sm" onClick={() => setConfirmDelete(null)}>Cancel</button>
                                    </div>
                                </div>
                            </Show>
                        </div>
                    </Show>

                    </div>{/* end top content area */}

                    {/* RESIZABLE BOTTOM PANEL — Sync History */}
                    <Show when={syncManifests().length > 0}>
                        {/* Drag handle */}
                        <div onMouseDown={startDrag}
                             onKeyDown={onHistoryResizeKeyDown}
                             role="separator"
                             aria-orientation="horizontal"
                             aria-valuemin="80"
                             aria-valuemax="500"
                             aria-valuenow={historyHeight()}
                             tabIndex="0"
                             style="height: 6px; cursor: ns-resize; border-top: 1px solid var(--border); display: flex; align-items: center; justify-content: center; flex-shrink: 0; background: var(--bg-subtle)"
                             title="Drag to resize">
                            <div style="width: 32px; height: 2px; border-radius: 1px; background: var(--text-tertiary); opacity: 0.5" />
                        </div>
                        {/* History panel */}
                        <div style={`height: ${historyOpen() ? historyHeight() + 'px' : '32px'}; flex-shrink: 0; display: flex; flex-direction: column; overflow: hidden; background: var(--surface); transition: height 0.15s ease`}>
                            <button type="button" class="sync-history-toggle" style="display: flex; align-items: center; padding: 6px 12px; gap: 8px; flex-shrink: 0; cursor: pointer; border: 0; background: transparent; text-align: left; width: 100%"
                                 onClick={() => setHistoryOpen(p => !p)} aria-expanded={historyOpen()}>
                                <svg width="10" height="10" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"
                                     style={`transition: transform 0.15s; transform: rotate(${historyOpen() ? '90deg' : '0deg'}); color: var(--text-tertiary)`}>
                                    <path d="M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z"/>
                                </svg>
                                <span style="font-size: 11px; font-weight: 600; color: var(--text-secondary); text-transform: uppercase; letter-spacing: 0.5px">Sync History</span>
                                <span style="font-size: 10px; color: var(--text-tertiary); background: var(--surface-variant); padding: 0 5px; border-radius: var(--radius-pill); font-weight: 500">{syncManifests().length}</span>
                                <Show when={manifestError()}>
                                    <span style="font-size: 11px; color: var(--error); margin-left: auto">{manifestError()}</span>
                                </Show>
                            </button>
                            <Show when={historyOpen()}>
                                <div style="flex: 1; overflow-y: auto; overflow-x: hidden">
                                    <table style="width: 100%; border-collapse: collapse; font-size: 12px">
                                        <thead>
                                            <tr style="position: sticky; top: 0; background: var(--surface); border-bottom: 1px solid var(--border)">
                                                <th style="text-align: left; padding: 4px 12px; font-weight: 600; color: var(--text-secondary); font-size: 11px">Resource</th>
                                                <th style="text-align: left; padding: 4px 8px; font-weight: 600; color: var(--text-secondary); font-size: 11px">Status</th>
                                                <th style="text-align: right; padding: 4px 8px; font-weight: 600; color: var(--text-secondary); font-size: 11px">Rows</th>
                                                <th style="text-align: right; padding: 4px 8px; font-weight: 600; color: var(--text-secondary); font-size: 11px">Size</th>
                                                <th style="text-align: right; padding: 4px 8px; font-weight: 600; color: var(--text-secondary); font-size: 11px">Cost</th>
                                                <th style="text-align: right; padding: 4px 8px; font-weight: 600; color: var(--text-secondary); font-size: 11px">When</th>
                                                <th style="text-align: right; padding: 4px 12px; font-weight: 600; color: var(--text-secondary); font-size: 11px"></th>
                                            </tr>
                                        </thead>
                                        <tbody>
                                            <For each={syncManifests()}>
                                                {(m) => (
                                                    <tr style="border-bottom: 1px solid var(--border-subtle); cursor: pointer"
                                                        onClick={() => { setSelectedManifest(m); setPanel('detail'); }}
                                                        onKeyDown={onActivate(() => { setSelectedManifest(m); setPanel('detail'); })}
                                                        role="button"
                                                        tabIndex="0"
                                                        onMouseEnter={e => e.currentTarget.style.background = 'var(--surface-hover)'}
                                                        onMouseLeave={e => e.currentTarget.style.background = ''}>
                                                        <td style="padding: 6px 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 220px; font-weight: 500">
                                                            {m.resource_path}
                                                        </td>
                                                        <td style="padding: 6px 8px">
                                                            <span style={`display: inline-flex; align-items: center; gap: 4px; font-size: 11px; font-weight: 500; color: ${statusColor(m.status)}`}>
                                                                {statusIcon(m.status)} {m.status}
                                                            </span>
                                                        </td>
                                                        <td style="padding: 6px 8px; text-align: right; color: var(--text-secondary); font-variant-numeric: tabular-nums">{fmtNum(m.row_count)}</td>
                                                        <td style="padding: 6px 8px; text-align: right; color: var(--text-secondary); font-variant-numeric: tabular-nums">{fmtBytes(m.bytes_synced)}</td>
                                                        <td style="padding: 6px 8px; text-align: right; color: var(--text-secondary); font-variant-numeric: tabular-nums">{fmtCost(m.estimated_cost)}</td>
                                                        <td style="padding: 6px 8px; text-align: right; color: var(--text-tertiary)">{timeAgo(m.synced_at)}</td>
                                                        <td style="padding: 6px 12px; text-align: right">
                                                            <button class="btn btn-sm" style="font-size: 10px; padding: 1px 6px"
                                                                    onClick={(e) => { e.stopPropagation(); handleResync(m); }}>
                                                                Resync
                                                            </button>
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
                </div>
            </div>
        </div>
    </Show>
    );
}

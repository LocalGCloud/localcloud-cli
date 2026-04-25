import { createSignal, createEffect, Show, For, onCleanup } from 'solid-js';
import { api } from '../api.js';
import { SchemaExplorer } from './SchemaExplorer.jsx';
import { SyncFilterBuilder } from './SyncFilterBuilder.jsx';

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

    // Track active polling interval for cleanup on unmount
    let activePolling = null;
    onCleanup(() => { if (activePolling) clearInterval(activePolling); });

    // Check auth
    createEffect(async () => {
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
        const rtf = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });
        const ms = Date.now() - new Date(ts).getTime();
        if (ms < 60000) return rtf.format(-Math.round(ms / 1000), 'second');
        if (ms < 3600000) return rtf.format(-Math.round(ms / 60000), 'minute');
        if (ms < 86400000) return rtf.format(-Math.round(ms / 3600000), 'hour');
        return rtf.format(-Math.round(ms / 86400000), 'day');
    };

    const fmtNum = (n) => {
        if (n == null) return '0';
        if (n >= 1e6) return new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 }).format(n);
        return new Intl.NumberFormat('en').format(n);
    };

    const fmtBytes = (b) => {
        if (!b) return '0 B';
        const units = ['B', 'KB', 'MB', 'GB', 'TB'];
        const i = Math.floor(Math.log(b) / Math.log(1024));
        return new Intl.NumberFormat('en', { maximumFractionDigits: 1 }).format(b / Math.pow(1024, i)) + ' ' + units[i];
    };

    const fmtCost = (c) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 4 }).format(c || 0);

    const fmtElapsed = (ms) => {
        if (!ms) return '0s';
        const totalSec = Math.floor(ms / 1000);
        const m = Math.floor(totalSec / 60);
        const s = totalSec % 60;
        return m > 0 ? `${m}m ${s}s` : `${s}s`;
    };

    // Not connected view
    if (!connected()) {
        return (
            <div style="display: flex; align-items: center; justify-content: center; height: 100%; padding: 48px">
                <div class="card" style="max-width: 440px; width: 100%">
                    <div class="card-header" style="display: flex; align-items: center; gap: 8px">
                        <svg width="20" height="20" viewBox="0 0 24 24" fill="var(--primary)">
                            <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z"/>
                        </svg>
                        <h3 style="margin: 0">Connect to GCP Project</h3>
                    </div>
                    <div class="card-body" style="display: flex; flex-direction: column; gap: 16px">
                        <div>
                            <label class="form-label" for="sync-project">Source Project ID</label>
                            <input id="sync-project" name="project" class="form-input"
                                   placeholder="my-production-project"
                                   value={connectProject()} onInput={e => setConnectProject(e.target.value)}
                                   autocomplete="off" />
                        </div>
                        <div>
                            <label class="form-label" for="sync-token">Access Token</label>
                            <input id="sync-token" name="token" class="form-input form-input-mono"
                                   type="password" placeholder="ya29.a0AfH6SM..."
                                   value={connectToken()} onInput={e => setConnectToken(e.target.value)}
                                   autocomplete="off" />
                            <div style="margin-top: 6px; font-size: 11px; color: var(--text-secondary); line-height: 1.5">
                                Generate with: <code style="background: var(--surface-elevated, var(--border)); padding: 1px 4px; border-radius: 3px; font-size: 10px">gcloud auth print-access-token</code>
                                <br/>Required scope: <code style="background: var(--surface-elevated, var(--border)); padding: 1px 4px; border-radius: 3px; font-size: 10px">cloud-platform.read-only</code>
                                <br/>Token expires in ~1 hour. Re-paste to refresh.
                            </div>
                        </div>
                        <Show when={syncError()}>
                            <div class="alert alert-error" style="margin: 0">{syncError()}</div>
                        </Show>
                        <button class="btn btn-primary" onClick={handleConnect}
                                disabled={!connectProject() || !connectToken()}
                                style="width: 100%">
                            Connect
                        </button>
                        <div style="border-top: 1px solid var(--border); padding-top: 12px; margin-top: 4px">
                            <div style="display: flex; align-items: center; gap: 8px; opacity: 0.5">
                                <svg width="16" height="16" viewBox="0 0 18 18">
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
        );
    }

    // Connected --- split layout
    return (
        <div style="display: flex; height: 100%">
            {/* LEFT PANEL */}
            <div style="width: 300px; border-right: 1px solid var(--border); display: flex; flex-direction: column; overflow: hidden">
                <div style="padding: 8px 12px; border-bottom: 1px solid var(--border); display: flex; align-items: center; gap: 8px">
                    <span style="font-size: 11px; font-weight: 600; color: var(--primary)">{authStatus()?.source_project}</span>
                    <button class="btn btn-icon" onClick={handleDisconnect} title="Disconnect"
                            style="margin-left: auto; font-size: 11px; color: var(--text-secondary)">&times;</button>
                </div>
                <div style="flex: 1; overflow-y: auto">
                    <SchemaExplorer source="remote" serviceId={props.serviceId}
                                    onSelect={handleSelect} syncManifests={syncManifests} />
                </div>
                <Show when={manifestError()}>
                    <div style="padding: 8px 12px">
                        <div class="alert alert-error" style="margin: 0; font-size: 12px">{manifestError()}</div>
                    </div>
                </Show>
                <Show when={syncManifests().length > 0}>
                    <div style="border-top: 1px solid var(--border); max-height: 200px; overflow-y: auto">
                        <div style="padding: 8px 12px; font-size: 11px; font-weight: 600; color: var(--text-secondary); text-transform: uppercase">Sync History</div>
                        <For each={syncManifests()}>
                            {(m) => (
                                <div style="padding: 6px 12px; cursor: pointer; border-bottom: 1px solid var(--border-light, var(--border))"
                                     onClick={() => { setSelectedManifest(m); setPanel('detail'); }}
                                     class="clickable-row">
                                    <div style="font-size: 13px; font-weight: 500">{m.resource_path}</div>
                                    <div style="font-size: 11px; color: var(--text-secondary)">
                                        {fmtNum(m.row_count)} rows &middot; {timeAgo(m.synced_at)}
                                        <span style={`margin-left: 4px; color: ${m.status === 'completed' ? 'var(--success, #34a853)' : 'var(--warning, #fbbc04)'}`}>
                                            {m.status === 'completed' ? '\u2713' : m.status}
                                        </span>
                                    </div>
                                </div>
                            )}
                        </For>
                    </div>
                </Show>
            </div>

            {/* RIGHT PANEL */}
            <div style="flex: 1; padding: 16px; overflow-y: auto">
                {/* Empty state */}
                <Show when={panel() === 'empty'}>
                    <div class="empty-state" style="padding: 48px; text-align: center">
                        <div class="empty-state-title">Select a resource</div>
                        <div class="empty-state-text">Click a table or collection in the explorer to preview data.</div>
                    </div>
                </Show>

                {/* Preview */}
                <Show when={panel() === 'preview'}>
                    <Show when={selectedResource()}>
                        <div style="margin-bottom: 16px">
                            <h3 style="margin: 0 0 4px 0; display: flex; align-items: center; gap: 8px">
                                {selectedResource().name}
                                <span class="badge badge-info" style="font-size: 10px">REMOTE</span>
                            </h3>
                            <Show when={selectedResource().metadata}>
                                <p style="margin: 0; font-size: 13px; color: var(--text-secondary)">
                                    {fmtNum(selectedResource().metadata.rowCount)} rows &middot; {fmtBytes(selectedResource().metadata.sizeBytes)}
                                </p>
                            </Show>
                        </div>
                        <Show when={previewLoading()}>
                            <div class="loading-state"><div class="loading-spinner" /></div>
                        </Show>
                        <Show when={!previewLoading() && previewError()}>
                            <div class="alert alert-error" style="margin-bottom: 16px">{previewError()}</div>
                        </Show>
                        <Show when={!previewLoading() && preview()}>
                            <div class="data-table-wrapper" style="margin-bottom: 16px">
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
                            <button class="btn btn-primary" onClick={showSyncForm}>Sync to Local</button>
                        </Show>
                    </Show>
                </Show>

                {/* Sync form */}
                <Show when={panel() === 'sync'}>
                    <h3 style="margin: 0 0 16px 0">Sync to Local: {selectedResource()?.name}</h3>
                    <div class="card" style="margin-bottom: 16px">
                        <div class="card-header"><h4 style="margin: 0">Filters</h4></div>
                        <div class="card-body">
                            <SyncFilterBuilder schema={selectedResource()?.schema} onChange={setFilters} initialFilters={filters()} />
                        </div>
                    </div>
                    <div style="margin-bottom: 16px">
                        <label class="form-label">Row Limit</label>
                        <input class="form-input" type="number" value={rowLimit()}
                               onInput={e => setRowLimit(parseInt(e.target.value) || 1000000)}
                               style="width: 200px" />
                    </div>
                    <div style="display: flex; gap: 8px; margin-bottom: 16px">
                        <button class="btn btn-secondary" onClick={estimateCost}>Estimate Cost</button>
                    </div>
                    <Show when={costEstimate()}>
                        <div class="card" style="margin-bottom: 16px">
                            <div class="card-body">
                                <Show when={costEstimate().error}>
                                    <div class="alert alert-error">{costEstimate().error}</div>
                                </Show>
                                <Show when={!costEstimate().error}>
                                    <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 8px; font-size: 13px">
                                        <div><strong>Est. rows:</strong> {fmtNum(costEstimate().estimatedRows)}</div>
                                        <div><strong>Scan size:</strong> {fmtBytes(costEstimate().estimatedBytes)}</div>
                                        <div><strong>Est. cost:</strong> {fmtCost(costEstimate().estimatedCostUsd)}</div>
                                        <div><strong>Details:</strong> {costEstimate().details}</div>
                                    </div>
                                </Show>
                            </div>
                        </div>
                    </Show>
                    <div style="display: flex; gap: 8px">
                        <button class="btn" onClick={() => setPanel('preview')}>Cancel</button>
                        <button class="btn btn-primary" onClick={startSync}>Start Sync</button>
                    </div>
                </Show>

                {/* Progress */}
                <Show when={panel() === 'progress'}>
                    <h3 style="margin: 0 0 16px 0">Syncing: {selectedResource()?.name} &rarr; Local</h3>
                    <div style="margin-bottom: 16px">
                        <div style="height: 6px; background: var(--border); border-radius: 3px; overflow: hidden">
                            <div style={`height: 100%; background: var(--primary); width: ${syncProgress()?.percent || 0}%; transition: width 0.3s`} />
                        </div>
                    </div>
                    <Show when={syncProgress()}>
                        <div style="font-size: 13px; color: var(--text-secondary); display: grid; grid-template-columns: 1fr 1fr; gap: 8px">
                            <div>Rows: {fmtNum(syncProgress().rowsTransferred || syncProgress().rowsSynced || 0)}{syncProgress().estimatedTotal > 0 ? ` / ${fmtNum(syncProgress().estimatedTotal)}` : ''}</div>
                            <div>Status: {syncProgress().status}</div>
                            <Show when={syncProgress().bytesTransferred || syncProgress().bytesSynced}>
                                <div>Size: {fmtBytes(syncProgress().bytesTransferred || syncProgress().bytesSynced || 0)}</div>
                            </Show>
                            <Show when={syncProgress().elapsedMs}>
                                <div>Elapsed: {fmtElapsed(syncProgress().elapsedMs)}</div>
                            </Show>
                        </div>
                    </Show>
                    <Show when={syncProgress()?.status === 'running'}>
                        <div style="margin-top: 16px">
                            <button class="btn btn-danger" onClick={async () => {
                                if (activePolling) { clearInterval(activePolling); activePolling = null; }
                                await api.syncCancel(props.serviceId, { resource: selectedResource()?.id });
                                setSyncProgress(prev => ({ ...prev, status: 'cancelled' }));
                                await loadManifests();
                            }}>Cancel Sync</button>
                        </div>
                    </Show>
                    <Show when={syncError()}>
                        <div class="alert alert-error" style="margin-top: 16px">{syncError()}</div>
                    </Show>
                    <Show when={syncProgress()?.status === 'cancelled'}>
                        <div class="alert" style="margin-top: 16px; background: var(--warning-bg, #fef7e0); color: var(--warning, #fbbc04); border: 1px solid var(--warning, #fbbc04); border-radius: 8px; padding: 12px">
                            Sync cancelled.
                        </div>
                    </Show>
                    <Show when={syncProgress()?.status === 'completed'}>
                        <div class="alert" style="margin-top: 16px; background: var(--success-bg, #e6f4ea); color: var(--success, #34a853); border: 1px solid var(--success, #34a853); border-radius: 8px; padding: 12px">
                            Sync complete! {fmtNum(syncProgress().rowsSynced)} rows synced.
                        </div>
                    </Show>
                </Show>

                {/* History detail */}
                <Show when={panel() === 'detail' && selectedManifest()}>
                    <h3 style="margin: 0 0 16px 0">
                        {selectedManifest().resource_path}
                        <span style={`margin-left: 8px; font-size: 13px; color: ${selectedManifest().status === 'completed' ? 'var(--success, #34a853)' : 'var(--warning, #fbbc04)'}`}>
                            {selectedManifest().status === 'completed' ? '\u2713 synced' : selectedManifest().status}
                        </span>
                    </h3>
                    <div class="card">
                        <div class="card-body" style="display: grid; grid-template-columns: 1fr 1fr; gap: 12px; font-size: 13px">
                            <div><strong>Synced:</strong> {timeAgo(selectedManifest().synced_at)}</div>
                            <div><strong>Rows:</strong> {fmtNum(selectedManifest().row_count)}</div>
                            <div><strong>Size:</strong> {fmtBytes(selectedManifest().bytes_synced)}</div>
                            <div><strong>Cost:</strong> {fmtCost(selectedManifest().estimated_cost)}</div>
                            <div><strong>Source:</strong> {selectedManifest().source_project}</div>
                            <div><strong>Status:</strong> {selectedManifest().status}</div>
                            <Show when={selectedManifest().filters_json && selectedManifest().filters_json !== '[]'}>
                                <div style="grid-column: 1 / -1"><strong>Filters:</strong> {selectedManifest().filters_json}</div>
                            </Show>
                        </div>
                    </div>
                    <div style="margin-top: 16px; display: flex; flex-direction: column; gap: 8px">
                        <div style="display: flex; gap: 8px">
                            <button class="btn btn-primary" onClick={() => handleResync(selectedManifest())}>Resync</button>
                            <Show when={confirmDelete() === selectedManifest()?.id} fallback={
                                <button class="btn btn-danger" onClick={() => setConfirmDelete(selectedManifest()?.id)}>
                                    Remove Data & History
                                </button>
                            }>
                                <span />
                            </Show>
                        </div>
                        <Show when={confirmDelete() === selectedManifest()?.id}>
                            <div class="alert" style="background: var(--danger-bg, #fce8e6); border: 1px solid var(--danger, #ea4335); border-radius: 8px; padding: 12px; margin-bottom: 8px">
                                <div style="font-weight: 500; color: var(--danger, #ea4335); margin-bottom: 4px">
                                    Remove "{selectedManifest()?.resource_path}" from local?
                                </div>
                                <div style="font-size: 12px; color: var(--text-secondary); margin-bottom: 8px">
                                    This deletes synced data from local emulators and removes the sync history entry. Production data is not affected.
                                </div>
                                <div style="display: flex; gap: 8px">
                                    <button class="btn btn-danger" onClick={() => { deleteManifest(selectedManifest()?.id); setConfirmDelete(null); }}>
                                        Yes, Remove
                                    </button>
                                    <button class="btn btn-secondary" onClick={() => setConfirmDelete(null)}>
                                        Cancel
                                    </button>
                                </div>
                            </div>
                        </Show>
                    </div>
                </Show>
            </div>
        </div>
    );
}

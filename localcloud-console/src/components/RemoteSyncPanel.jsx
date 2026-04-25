import { createSignal, createEffect, Show, For } from 'solid-js';
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
            const m = await api.syncServiceManifests(props.serviceId);
            setSyncManifests(Array.isArray(m) ? m : []);
        } catch (e) { /* ignore */ }
    };

    const handleSelect = async (node) => {
        setSelectedResource(node);
        setPanel('preview');
        setPreviewLoading(true);
        try {
            const r = await api.syncPreview(props.serviceId, node.id, 5);
            setPreview(r);
        } catch (e) { setPreview(null); }
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
            setSyncProgress({ percent: 100, ...result });
            await loadManifests();
            setTimeout(() => setPanel('preview'), 2000);
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
        const ms = Date.now() - new Date(ts).getTime();
        if (ms < 3600000) return Math.round(ms / 60000) + 'm ago';
        if (ms < 86400000) return Math.round(ms / 3600000) + 'h ago';
        return Math.round(ms / 86400000) + 'd ago';
    };

    const fmtNum = (n) => {
        if (n >= 1e6) return (n / 1e6).toFixed(1) + 'M';
        if (n >= 1e3) return (n / 1e3).toFixed(1) + 'K';
        return String(n || 0);
    };

    const fmtBytes = (b) => {
        if (!b) return '0 B';
        if (b >= 1e9) return (b / 1e9).toFixed(1) + ' GB';
        if (b >= 1e6) return (b / 1e6).toFixed(1) + ' MB';
        if (b >= 1e3) return (b / 1e3).toFixed(1) + ' KB';
        return b + ' B';
    };

    // Not connected view
    if (!connected()) {
        return (
            <div style="display: flex; align-items: center; justify-content: center; height: 100%; padding: 48px">
                <div class="card" style="max-width: 400px; width: 100%">
                    <div class="card-header"><h3 style="margin: 0">Connect to GCP</h3></div>
                    <div class="card-body" style="display: flex; flex-direction: column; gap: 12px">
                        <p style="color: var(--text-secondary); margin: 0">Connect to a GCP project to browse and sync remote data.</p>
                        <label class="form-label">Source Project</label>
                        <input class="form-input" placeholder="prod-project-123"
                               value={connectProject()} onInput={e => setConnectProject(e.target.value)} />
                        <label class="form-label">Access Token</label>
                        <input class="form-input form-input-mono" type="password" placeholder="ya29...."
                               value={connectToken()} onInput={e => setConnectToken(e.target.value)} />
                        <p style="font-size: 11px; color: var(--text-secondary); margin: 0">
                            Run: gcloud auth print-access-token
                        </p>
                        <Show when={syncError()}>
                            <div class="alert alert-error">{syncError()}</div>
                        </Show>
                        <button class="btn btn-primary" onClick={handleConnect}
                                disabled={!connectProject() || !connectToken()}>Connect</button>
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
                            <SyncFilterBuilder schema={selectedResource()?.schema} onChange={setFilters} />
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
                                        <div><strong>Est. cost:</strong> ${costEstimate().estimatedCostUsd?.toFixed(4)}</div>
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
                            <div>Rows: {fmtNum(syncProgress().rowsSynced || syncProgress().rowsTransferred || 0)}</div>
                            <div>Status: {syncProgress().status}</div>
                        </div>
                    </Show>
                    <Show when={syncError()}>
                        <div class="alert alert-error" style="margin-top: 16px">{syncError()}</div>
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
                            <div><strong>Cost:</strong> ${selectedManifest().estimated_cost?.toFixed(4) || '0.0000'}</div>
                            <div><strong>Source:</strong> {selectedManifest().source_project}</div>
                            <div><strong>Status:</strong> {selectedManifest().status}</div>
                            <Show when={selectedManifest().filters_json && selectedManifest().filters_json !== '[]'}>
                                <div style="grid-column: 1 / -1"><strong>Filters:</strong> {selectedManifest().filters_json}</div>
                            </Show>
                        </div>
                    </div>
                    <div style="margin-top: 16px; display: flex; gap: 8px">
                        <button class="btn btn-primary" onClick={() => { /* resync would go here */ }}>Resync</button>
                        <button class="btn btn-danger" onClick={() => deleteManifest(selectedManifest().id)}>Remove from Local</button>
                    </div>
                </Show>
            </div>
        </div>
    );
}

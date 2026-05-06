import { createSignal, createEffect, onCleanup, Show, For } from 'solid-js';
import { api } from '../api.js';

const SERVICE_NAMES = {
    gcs: 'Cloud Storage',
    pubsub: 'Pub/Sub',
    firestore: 'Firestore',
    bigquery: 'BigQuery',
    secretmanager: 'Secret Manager',
    cloudtasks: 'Cloud Tasks',
    spanner: 'Spanner',
    bigtable: 'Bigtable',
    logging: 'Cloud Logging',
    monitoring: 'Cloud Monitoring',
    gke: 'GKE',
    compute: 'Compute Engine',
    cloudrun: 'Cloud Run',
    memorystore: 'Memorystore (Redis)',
    workflows: 'Cloud Workflows',
};

// All 15 services — ensures disabled ones still appear as greyed cards
const ALL_SERVICE_IDS = [
    { id: 'gcs', port: 4443, protocol: 'REST' },
    { id: 'pubsub', port: 8085, protocol: 'GRPC' },
    { id: 'firestore', port: 8086, protocol: 'GRPC' },
    { id: 'bigtable', port: 8087, protocol: 'GRPC' },
    { id: 'spanner', port: 9010, protocol: 'GRPC' },
    { id: 'bigquery', port: 9050, protocol: 'REST' },
    { id: 'secretmanager', port: 8080, protocol: 'GRPC' },
    { id: 'cloudtasks', port: 8080, protocol: 'GRPC' },
    { id: 'logging', port: 8080, protocol: 'GRPC' },
    { id: 'monitoring', port: 8080, protocol: 'GRPC' },
    { id: 'memorystore', port: 6379, protocol: 'REDIS' },
    { id: 'gke', port: 8080, protocol: 'GRPC' },
    { id: 'compute', port: 8080, protocol: 'REST' },
    { id: 'cloudrun', port: 8080, protocol: 'GRPC' },
    { id: 'workflows', port: 8080, protocol: 'REST' },
];

function ServiceIcon({ id, size = 20 }) {
    return <img src={`/icons/${id}.svg`} alt="" width={size} height={size} style={{ "object-fit": "contain" }} />;
}

function formatUptime(seconds) {
    if (!seconds && seconds !== 0) return '--';
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = Math.floor(seconds % 60);
    const parts = [];
    if (h > 0) parts.push(`${h}h`);
    if (m > 0) parts.push(`${m}m`);
    parts.push(`${s}s`);
    return parts.join(' ');
}

export default function Dashboard(props) {
    const [resetting, setResetting] = createSignal(false);
    const [resetMsg, setResetMsg] = createSignal(null);
    const [copyMsg, setCopyMsg] = createSignal(null);
    const [servicesData, setServicesData] = createSignal([]);
    const [confirmingReset, setConfirmingReset] = createSignal(false);
    const [fetchError, setFetchError] = createSignal(null);
    const [failCount, setFailCount] = createSignal(0);

    createEffect(() => {
        // Re-fetch when active project changes
        const _proj = typeof props.activeProject === 'function' ? props.activeProject() : props.activeProject;
        const fetchServices = async () => {
            try {
                const data = await api.services();
                if (data && data.services) {
                    setServicesData(data.services);
                    setFetchError(null);
                    setFailCount(0);
                }
            } catch (err) {
                const count = failCount() + 1;
                setFailCount(count);
                if (count >= 3) {
                    setFetchError('Cannot reach LocalCloud backend. Is the container running?');
                }
                console.error('Failed to fetch services:', err);
            }
        };
        fetchServices();
        const timer = setInterval(fetchServices, 10000);
        onCleanup(() => clearInterval(timer));
    });

    const services = () => {
        const svcList = servicesData();
        const health = props.healthData();
        const healthServices = health?.services || {};

        // Build lookup from live API data
        const liveMap = {};
        for (const svc of svcList) {
            liveMap[svc.id] = svc;
        }

        // Merge static list with live data — always show all 14 services
        return ALL_SERVICE_IDS.map(def => {
            const live = liveMap[def.id] || {};
            const healthStatus = healthServices[def.id]?.status;
            return {
                ...def,
                ...live,
                id: def.id,
                displayName: SERVICE_NAMES[def.id] || live.name || def.id,
                status: healthStatus || live.status || 'disabled',
                port: live.port || def.port,
                protocol: (live.protocol || def.protocol || '--').toUpperCase(),
                request_count: live.request_count || 0,
            };
        });
    };

    const healthyCount = () => services().filter(s => s.status === 'healthy').length;
    const enabledCount = () => services().filter(s => s.status !== 'disabled').length;
    const totalCount = () => services().length;

    const overallHealthy = () => {
        const h = props.healthData();
        return h && h.status === 'healthy';
    };

    const totalRequests = () => services().reduce((sum, s) => sum + (s.request_count || 0), 0);

    const handleResetClick = () => {
        setConfirmingReset(true);
    };

    const handleResetConfirm = async () => {
        setConfirmingReset(false);
        setResetting(true);
        setResetMsg(null);
        try {
            await api.reset();
            setResetMsg({ type: 'success', text: 'All data reset successfully.' });
        } catch (err) {
            setResetMsg({ type: 'error', text: 'Reset failed: ' + err.message });
        } finally {
            setResetting(false);
            setTimeout(() => setResetMsg(null), 5000);
        }
    };

    const handleCopyEnv = async () => {
        try {
            const envData = await api.env();
            const lines = Object.entries(envData)
                .map(([k, v]) => `export ${k}="${v}"`)
                .join('\n');
            await navigator.clipboard.writeText(lines);
            setCopyMsg('Copied!');
            setTimeout(() => setCopyMsg(null), 2000);
        } catch (err) {
            setCopyMsg('Copy failed');
            setTimeout(() => setCopyMsg(null), 2000);
        }
    };

    const handleCardClick = (serviceId) => {
        if (props.onServiceClick) {
            props.onServiceClick(serviceId);
        }
    };

    return (
        <div>
            <div class="page-header">
                <h1>Dashboard</h1>
                <p class="page-header-subtitle">
                    Overview of all emulated GCP services and system health.
                </p>
            </div>

            {/* Summary Bar */}
            <div class="summary-bar">
                <div class="stat-card stat-card-hero">
                    <div class="stat-card-label">Services</div>
                    <div class="stat-card-value">
                        {healthyCount()} / {enabledCount()}
                    </div>
                </div>
                <div class="stat-card">
                    <div class="stat-card-label">Project</div>
                    <div class="stat-card-value" style={{ "font-size": "16px" }}>
                        {props.healthData()?.project_id || '--'}
                    </div>
                </div>
                <div class="stat-card">
                    <div class="stat-card-label">Uptime</div>
                    <div class="stat-card-value" style={{ "font-size": "16px" }}>
                        {formatUptime(props.healthData()?.uptime_seconds)}
                    </div>
                </div>
                <div class="stat-card">
                    <div class="stat-card-label">Total Requests</div>
                    <div class="stat-card-value" style={{ "font-size": "16px" }}>
                        {totalRequests().toLocaleString()}
                    </div>
                </div>
                <div class="stat-card">
                    <div class="stat-card-label">Status</div>
                    <div class="stat-card-value" style={{ "font-size": "16px" }}>
                        <Show when={props.healthData()} fallback="--">
                            <span class={`badge ${overallHealthy() ? 'badge-healthy' : 'badge-unhealthy'}`}>
                                {overallHealthy() ? 'HEALTHY' : 'DEGRADED'}
                            </span>
                        </Show>
                    </div>
                </div>
            </div>

            {/* Service Grid */}
            <div class="section">
                <h2>Local Services</h2>
                <Show when={services().length > 0} fallback={
                    <Show when={fetchError()} fallback={
                        <div class="loading-state">
                            <div class="loading-spinner" />
                            Loading services...
                        </div>
                    }>
                        <div class="alert alert-error">
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z"/></svg>
                            {fetchError()}
                        </div>
                    </Show>
                }>
                    <div class="service-grid">
                        <For each={services()}>
                            {(svc) => {
                                const isHealthy = () => svc.status === 'healthy';
                                const isUnknown = () => svc.status === 'unknown';
                                const isDisabled = () => svc.status === 'disabled';
                                const statusClass = () => {
                                    if (isDisabled()) return 'disabled';
                                    if (isHealthy()) return 'healthy';
                                    if (isUnknown()) return 'warning';
                                    return 'unhealthy';
                                };
                                const statusLabel = () => {
                                    if (isDisabled()) return 'Disabled';
                                    if (isHealthy()) return 'Healthy';
                                    if (isUnknown()) return 'Unknown';
                                    return 'Unhealthy';
                                };
                                return (
                                    <div
                                        class="service-card"
                                        role="button"
                                        tabIndex={0}
                                        aria-label={`${svc.displayName} — ${statusLabel()}`}
                                        style={{ cursor: 'pointer', ...(isDisabled() ? { opacity: '0.5', "pointer-events": 'none' } : {}) }}
                                        onClick={() => handleCardClick(svc.id)}
                                        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); handleCardClick(svc.id); } }}
                                    >
                                        <div style={{ display: 'flex', "align-items": 'center', gap: '10px' }}>
                                            <ServiceIcon id={svc.id} size={24} />
                                            <div class="service-card-name">{svc.displayName}</div>
                                        </div>
                                        <div class="service-card-status">
                                            <span class={`status-dot ${statusClass()}`} />
                                            <span style={{
                                                color: isDisabled()
                                                    ? 'var(--text-tertiary)'
                                                    : isHealthy()
                                                        ? 'var(--success)'
                                                        : isUnknown()
                                                            ? 'var(--warning)'
                                                            : 'var(--error)',
                                                "font-size": '12px',
                                                "font-weight": '500',
                                            }}>
                                                {statusLabel()}
                                            </span>
                                            {(() => {
                                                const mode = props.routingData?.()?.[svc.id]?.mode;
                                                if (mode === 'remote') return <span class="badge badge-cloud" style={{ "margin-left": "auto" }}>Cloud</span>;
                                                return null;
                                            })()}
                                        </div>
                                        <div class="service-card-meta">
                                            <span style={{ "font-family": "var(--font-mono)", "font-size": "11px", "letter-spacing": "-0.02em" }}>:{svc.port || '--'}</span>
                                            <span class={`badge ${svc.protocol === 'gRPC' ? 'badge-info' : 'badge-neutral'}`}>
                                                {svc.protocol || '--'}
                                            </span>
                                        </div>
                                        <div style={{
                                            display: 'flex',
                                            "justify-content": 'space-between',
                                            "align-items": 'center',
                                            "font-size": '11px',
                                            color: 'var(--text-secondary)',
                                            "border-top": '1px solid var(--border)',
                                            "padding-top": '10px',
                                        }}>
                                            <span>Requests</span>
                                            <span style={{ "font-weight": '600', color: 'var(--text)', "font-family": "var(--font-mono)" }}>
                                                {(svc.request_count || 0).toLocaleString()}
                                            </span>
                                        </div>
                                    </div>
                                );
                            }}
                        </For>
                    </div>
                </Show>
            </div>

            {/* Confirmation Dialog */}
            <Show when={confirmingReset()}>
                <div class="alert alert-error" style={{ display: 'flex', "align-items": 'center', "justify-content": 'space-between' }}>
                    <span>Reset all emulator data? This cannot be undone.</span>
                    <div style={{ display: 'flex', gap: '8px', "flex-shrink": 0, "margin-left": '16px' }}>
                        <button class="btn btn-secondary" onClick={() => setConfirmingReset(false)}>Cancel</button>
                        <button class="btn btn-danger" onClick={handleResetConfirm}>Confirm Reset</button>
                    </div>
                </div>
            </Show>

            {/* Status Messages */}
            <Show when={resetMsg()}>
                <div class={`alert ${resetMsg().type === 'success' ? 'alert-success' : 'alert-error'}`}>
                    {resetMsg().text}
                </div>
            </Show>

            {/* Quick Actions */}
            <div class="actions-bar">
                <button class="btn btn-danger" onClick={handleResetClick} disabled={resetting() || confirmingReset()}>
                    {resetting() ? 'Resetting...' : 'Reset All Data'}
                </button>
                <button class="btn btn-secondary" onClick={handleCopyEnv}>
                    Copy Env Vars
                </button>
                <button class="btn btn-secondary" onClick={async () => {
                    try {
                        const resp = await fetch('/_localcloud/export');
                        const text = await resp.text();
                        const blob = new Blob([text], { type: 'application/yaml' });
                        const url = URL.createObjectURL(blob);
                        const a = document.createElement('a');
                        a.href = url;
                        a.download = `localcloud-state-${new Date().toISOString().slice(0,10)}.yaml`;
                        a.click();
                        URL.revokeObjectURL(url);
                    } catch (e) { console.error('Export failed:', e); }
                }}>
                    Export State
                </button>
                <Show when={copyMsg()}>
                    <span class="badge badge-healthy" style={{ "align-self": "center" }}>
                        {copyMsg()}
                    </span>
                </Show>
            </div>
        </div>
    );
}

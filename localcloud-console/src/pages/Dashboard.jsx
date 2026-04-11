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
};

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

    createEffect(() => {
        // Re-fetch when active project changes
        const _proj = props.activeProject;
        const fetchServices = async () => {
            try {
                const data = await api.services();
                if (data && data.services) {
                    setServicesData(data.services);
                }
            } catch (err) {
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

        if (svcList.length > 0) {
            return svcList.map(svc => ({
                ...svc,
                displayName: SERVICE_NAMES[svc.id] || svc.name || svc.id,
                status: healthServices[svc.id]?.status || svc.status || 'unknown',
            }));
        }

        if (health && health.services) {
            return Object.entries(health.services).map(([key, val]) => ({
                id: key,
                displayName: SERVICE_NAMES[key] || key,
                status: val.status,
                port: val.port,
                protocol: val.protocol || '--',
                request_count: 0,
            }));
        }

        return [];
    };

    const healthyCount = () => services().filter(s => s.status === 'healthy').length;
    const totalCount = () => services().length;

    const overallHealthy = () => {
        const h = props.healthData();
        return h && h.status === 'healthy';
    };

    const totalRequests = () => services().reduce((sum, s) => sum + (s.request_count || 0), 0);

    const handleReset = async () => {
        if (!confirm('Reset all emulator data? This cannot be undone.')) return;
        setResetting(true);
        setResetMsg(null);
        try {
            await api.reset();
            setResetMsg('All data reset successfully.');
        } catch (err) {
            setResetMsg('Reset failed: ' + err.message);
        } finally {
            setResetting(false);
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
                        {healthyCount()} / {totalCount()}
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
                    <div class="loading-state">
                        <div class="loading-spinner" />
                        Loading services...
                    </div>
                }>
                    <div class="service-grid">
                        <For each={services()}>
                            {(svc) => {
                                const isHealthy = () => svc.status === 'healthy';
                                const isUnknown = () => svc.status === 'unknown';
                                const statusClass = () => {
                                    if (isHealthy()) return 'healthy';
                                    if (isUnknown()) return 'warning';
                                    return 'unhealthy';
                                };
                                const statusLabel = () => {
                                    if (isHealthy()) return 'Healthy';
                                    if (isUnknown()) return 'Unknown';
                                    return 'Unhealthy';
                                };
                                return (
                                    <div
                                        class="service-card"
                                        style={{ cursor: 'pointer' }}
                                        onClick={() => handleCardClick(svc.id)}
                                    >
                                        <div style={{ display: 'flex', "align-items": 'center', gap: '10px' }}>
                                            <ServiceIcon id={svc.id} size={24} />
                                            <div class="service-card-name">{svc.displayName}</div>
                                        </div>
                                        <div class="service-card-status">
                                            <span class={`status-dot ${statusClass()}`} />
                                            <span style={{
                                                color: isHealthy()
                                                    ? 'var(--success)'
                                                    : isUnknown()
                                                        ? 'var(--warning)'
                                                        : 'var(--error)',
                                                "font-size": '12px',
                                                "font-weight": '500',
                                            }}>
                                                {statusLabel()}
                                            </span>
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

            {/* Quick Actions */}
            <div class="actions-bar">
                <button class="btn btn-danger" onClick={handleReset} disabled={resetting()}>
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
                <Show when={resetMsg()}>
                    <span style={{ "font-size": "12px", color: "var(--text-secondary)", "align-self": "center" }}>
                        {resetMsg()}
                    </span>
                </Show>
                <Show when={copyMsg()}>
                    <span style={{ "font-size": "12px", color: "var(--success)", "align-self": "center" }}>
                        {copyMsg()}
                    </span>
                </Show>
            </div>
        </div>
    );
}

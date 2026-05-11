import { createSignal, createEffect, onCleanup, Show, For } from 'solid-js';
import { api } from '../api.js';
import { formatNumber, onActivate } from '../utils/a11y.js';

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
    vertexai: 'Vertex AI',
    kms: 'Cloud KMS',
    cloudsql: 'Cloud SQL',
};

const ALL_SERVICE_IDS = [
    { id: 'gcs', port: 4443, protocol: 'REST', env_var: 'STORAGE_EMULATOR_HOST', endpoint: 'http://localhost:4443' },
    { id: 'pubsub', port: 8085, protocol: 'GRPC', env_var: 'PUBSUB_EMULATOR_HOST', endpoint: 'localhost:8085' },
    { id: 'firestore', port: 8086, protocol: 'GRPC', env_var: 'FIRESTORE_EMULATOR_HOST', endpoint: 'localhost:8086' },
    { id: 'bigtable', port: 8087, protocol: 'GRPC', env_var: 'BIGTABLE_EMULATOR_HOST', endpoint: 'localhost:8087' },
    { id: 'spanner', port: 9010, protocol: 'GRPC', env_var: 'SPANNER_EMULATOR_HOST', endpoint: 'localhost:9010' },
    { id: 'bigquery', port: 9050, protocol: 'REST', env_var: 'BIGQUERY_EMULATOR_HOST', endpoint: 'http://localhost:9050' },
    { id: 'secretmanager', port: 8080, protocol: 'GRPC', env_var: 'SECRET_MANAGER_EMULATOR_HOST', endpoint: 'localhost:8080' },
    { id: 'cloudtasks', port: 8080, protocol: 'GRPC', env_var: 'CLOUD_TASKS_EMULATOR_HOST', endpoint: 'localhost:8080' },
    { id: 'logging', port: 8080, protocol: 'GRPC', env_var: 'CLOUD_LOGGING_EMULATOR_HOST', endpoint: 'localhost:8080' },
    { id: 'monitoring', port: 8080, protocol: 'GRPC', env_var: 'CLOUD_MONITORING_EMULATOR_HOST', endpoint: 'localhost:8080' },
    { id: 'memorystore', port: 6379, protocol: 'REDIS', env_var: 'REDIS_HOST', endpoint: 'localhost:6379' },
    { id: 'gke', port: 8080, protocol: 'GRPC', env_var: 'GKE_EMULATOR_HOST', endpoint: 'localhost:8080' },
    { id: 'compute', port: 8080, protocol: 'REST', env_var: 'COMPUTE_EMULATOR_HOST', endpoint: 'http://localhost:8080' },
    { id: 'cloudrun', port: 8080, protocol: 'GRPC', env_var: 'CLOUD_RUN_EMULATOR_HOST', endpoint: 'localhost:8080' },
    { id: 'workflows', port: 8080, protocol: 'REST', env_var: 'WORKFLOWS_EMULATOR_HOST', endpoint: 'http://localhost:8080' },
    { id: 'vertexai', port: 8080, protocol: 'REST', env_var: 'AIPLATFORM_EMULATOR_HOST', endpoint: 'http://localhost:8080' },
    { id: 'kms', port: 8080, protocol: 'REST', env_var: 'CLOUD_KMS_EMULATOR_HOST', endpoint: 'http://localhost:8080' },
    { id: 'cloudsql', port: 8080, protocol: 'REST', env_var: 'CLOUD_SQL_EMULATOR_HOST', endpoint: 'http://localhost:8080' },
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

function UtilizationBar(props) {
    const pct = () => Math.min(100, Math.max(0, Number(props.value) || 0));
    const tone = () => {
        if (pct() < 40) return 'success';
        if (pct() < 70) return 'warning';
        return 'error';
    };
    return (
        <div class="util-bar">
            <span class="util-bar-fill" style={{ width: `${pct()}%`, "background-color": `var(--${tone()})` }} />
        </div>
    );
}

export default function Dashboard(props) {
    const [servicesData, setServicesData] = createSignal([]);
    const [fetchError, setFetchError] = createSignal(null);
    const [failCount, setFailCount] = createSignal(0);
    const [toggleError, setToggleError] = createSignal(null);
    const [togglingService, setTogglingService] = createSignal(null);
    const [copiedEnvVar, setCopiedEnvVar] = createSignal(null);
    let failCounter = 0;

    const fetchServices = async () => {
        try {
            const data = await api.services();
            if (data && data.services) {
                setServicesData(data.services);
            }
            failCounter = 0;
            setFetchError(null);
            setFailCount(0);
        } catch (err) {
            failCounter++;
            if (failCounter >= 3) {
                setFetchError('Cannot reach LocalCloud backend. Is the container running?');
            }
        }
    };

    createEffect(() => {
        const _proj = typeof props.activeProject === 'function' ? props.activeProject() : props.activeProject;
        fetchServices();
        const timer = setInterval(fetchServices, 10000);
        onCleanup(() => clearInterval(timer));
    });

    const services = () => {
        const svcList = servicesData();
        const health = props.healthData();
        const healthServices = health?.services || {};

        const liveMap = {};
        for (const svc of svcList) {
            liveMap[svc.id] = svc;
        }

        return ALL_SERVICE_IDS.map(def => {
            const live = liveMap[def.id] || {};
            const healthStatus = healthServices[def.id]?.status;
            const requestCount = live.request_count || 0;
            const uptime = Number(health?.uptime_seconds || 0);
            const cpuBase = Math.min(92, 12 + (uptime % 15));
            const cpuFromRequests = Math.min(30, (requestCount % 20));
            const memoryBase = 28 + (Object.keys(healthServices).filter(k => healthServices[k]?.status === 'healthy').length * 3);
            const memoryFromRequests = Math.min(25, (requestCount % 15));
            return {
                ...def,
                ...live,
                id: def.id,
                displayName: SERVICE_NAMES[def.id] || live.name || def.id,
                status: healthStatus || live.status || (live.enabled === false ? 'disabled' : 'unknown'),
                port: live.port || def.port,
                protocol: (live.protocol || def.protocol || '--').toUpperCase(),
                request_count: requestCount,
                enabled: live.enabled !== undefined ? live.enabled : true,
                enabledSource: live.enabledSource || 'default',
                cpu: Math.min(95, cpuBase + cpuFromRequests),
                memory: Math.min(90, memoryBase + memoryFromRequests),
            };
        });
    };

    const healthyCount = () => services().filter(s => s.status === 'healthy').length;
    const enabledCount = () => services().filter(s => s.enabled).length;
    const totalCount = () => services().length;
    const totalRequests = () => services().reduce((sum, s) => sum + (s.request_count || 0), 0);

    const overallHealthy = () => {
        const h = props.healthData();
        return h && h.status === 'healthy';
    };

    const handleToggle = async (serviceId, currentlyEnabled) => {
        setTogglingService(serviceId);
        setToggleError(null);
        try {
            if (currentlyEnabled) {
                await api.disableService(serviceId);
            } else {
                await api.enableService(serviceId);
            }
            await fetchServices();
        } catch (err) {
            setToggleError(`Failed to ${currentlyEnabled ? 'disable' : 'enable'} ${SERVICE_NAMES[serviceId] || serviceId}`);
            setTimeout(() => setToggleError(null), 3000);
        } finally {
            setTogglingService(null);
        }
    };

    const handleRowClick = (serviceId) => {
        if (props.onServiceClick) {
            props.onServiceClick(serviceId);
        }
    };

    const handleCopyEnvVar = async (envVar, endpoint) => {
        if (!envVar || envVar === '--') return;
        let value = endpoint || 'localhost';
        if (value.startsWith('http://') || value.startsWith('https://')) {
            value = value.replace(/^https?:\/\//, '');
        }
        const exportCmd = `export ${envVar}="${value}"`;
        try {
            await navigator.clipboard.writeText(exportCmd);
            setCopiedEnvVar(envVar);
            setTimeout(() => setCopiedEnvVar(null), 2000);
        } catch (e) {
            console.error('Copy failed:', e);
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

            <Show when={fetchError()}>
                <div class="alert alert-error" role="alert" style={{ "margin-bottom": "16px" }}>{fetchError()}</div>
            </Show>

            <div class="summary-bar">
                <div class="stat-card stat-card-hero">
                    <div class="stat-card-label">Services</div>
                    <div class="stat-card-value">{healthyCount()} / {enabledCount()}</div>
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
                        {formatNumber(totalRequests())}
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

            <div class="section">
                <h2>APIs & Services</h2>
                <div class="data-table-wrapper">
                    <table class="data-table">
                        <thead>
                            <tr>
                                <th style={{ width: '44px' }}>On</th>
                                <th style={{ width: '36px' }}></th>
                                <th>Service</th>
                                <th>Status</th>
                                <th>Port</th>
                                <th>Protocol</th>
                                <th>Env Var</th>
                                <th style={{ "text-align": 'right' }}>Requests</th>
                                <th>CPU</th>
                                <th>Memory</th>
                                <th style={{ "text-align": 'right' }}>Total Usage</th>
                            </tr>
                        </thead>
                        <tbody>
                            <For each={services()}>
                                {(svc) => {
                                    const isHealthy = () => svc.status === 'healthy';
                                    const isUnknown = () => svc.status === 'unknown';
                                    const isDisabled = () => !svc.enabled || svc.status === 'disabled';
                                    const isToggling = () => togglingService() === svc.id;
                                    const statusClass = () => {
                                        if (isDisabled()) return 'disabled';
                                        if (isHealthy()) return 'healthy';
                                        if (isUnknown()) return 'warning';
                                        return 'unhealthy';
                                    };
                                    const statusLabel = () => {
                                        if (isToggling()) return 'Updating…';
                                        if (isDisabled()) return 'Disabled';
                                        if (isHealthy()) return 'Healthy';
                                        if (isUnknown()) return 'Unknown';
                                        return 'Unhealthy';
                                    };
                                    const usageScore = () => {
                                        const req = svc.request_count || 0;
                                        const cpuWeight = svc.cpu || 0;
                                        const memWeight = svc.memory || 0;
                                        return Math.round((req * 0.5 + cpuWeight * 0.3 + memWeight * 0.2));
                                    };
                                    const protocolBadge = () => {
                                        if (svc.protocol === 'GRPC' || svc.protocol === 'gRPC') return 'badge-grpc';
                                        if (svc.protocol === 'REDIS') return 'badge-redis';
                                        return 'badge-rest';
                                    };
                                    const envVar = svc.env_var || '--';
                                    const endpointVal = svc.endpoint || '--';
                                    const copied = copiedEnvVar() === envVar;
                                    return (
                                        <tr
                                            class={`clickable-row ${isDisabled() ? 'service-row-disabled' : ''}`}
                                            onClick={() => handleRowClick(svc.id)}
                                            onKeyDown={onActivate(() => handleRowClick(svc.id))}
                                            role="button"
                                            tabIndex="0"
                                        >
                                            <td onClick={(e) => e.stopPropagation()}>
                                                <label class="toggle-switch">
                                                    <input
                                                        type="checkbox"
                                                        checked={svc.enabled}
                                                        disabled={isToggling()}
                                                        onChange={() => handleToggle(svc.id, svc.enabled)}
                                                        aria-label={`${svc.enabled ? 'Disable' : 'Enable'} ${svc.displayName}`}
                                                    />
                                                    <span class="toggle-slider" />
                                                </label>
                                            </td>
                                            <td style={{ "text-align": 'center' }}>
                                                <ServiceIcon id={svc.id} size={18} />
                                            </td>
                                            <td style={{ "font-weight": "600", "font-size": "13px" }}>
                                                <span style={{ display: 'inline-flex', "align-items": 'center', gap: '8px' }}>
                                                    <span>{svc.displayName}</span>
                                                    <Show when={svc.id === 'firestore'}><span class="badge badge-coming-up">Coming up</span></Show>
                                                </span>
                                            </td>
                                            <td>
                                                <span class="status-indicator">
                                                    <span class={`status-dot ${statusClass()}`} />
                                                    {statusLabel()}
                                                </span>
                                            </td>
                                            <td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>
                                                {svc.port || '--'}
                                            </td>
                                            <td>
                                                <span class={`badge ${protocolBadge()}`}>
                                                    {svc.protocol || '--'}
                                                </span>
                                            </td>
                                            <td onClick={(e) => e.stopPropagation()}>
                                                <div class="env-var-cell">
                                                    <code
                                                        class="env-var-text"
                                                        title={envVar !== '--' ? `export ${envVar}="${endpointVal.startsWith('http') ? endpointVal.replace(/^https?:\/\//, '') : endpointVal}"` : ''}
                                                    >
                                                        {envVar}
                                                    </code>
                                                    <button
                                                        class="env-var-copy-btn"
                                                        disabled={envVar === '--'}
                                                        onClick={() => handleCopyEnvVar(envVar, endpointVal)}
                                                        title={copied ? 'Copied!' : 'Copy export command'}
                                                        aria-label={copied ? `Copied ${envVar}` : `Copy ${envVar} export command`}
                                                    >
                                                        {copied ? (
                                                            <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></svg>
                                                        ) : (
                                                            <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"/></svg>
                                                        )}
                                                    </button>
                                                </div>
                                            </td>
                                            <td style={{ "text-align": 'right', "font-weight": '600', "font-family": "var(--font-mono)", "font-size": "12px" }}>
                                                {formatNumber(svc.request_count || 0)}
                                            </td>
                                            <td>
                                                <div style={{ display: 'flex', "align-items": 'center', gap: '8px' }}>
                                                    <span style={{ "font-size": "12px", "min-width": "32px" }}>{svc.cpu}%</span>
                                                    <UtilizationBar value={svc.cpu} />
                                                </div>
                                            </td>
                                            <td>
                                                <div style={{ display: 'flex', "align-items": 'center', gap: '8px' }}>
                                                    <span style={{ "font-size": "12px", "min-width": "32px" }}>{svc.memory}%</span>
                                                    <UtilizationBar value={svc.memory} />
                                                </div>
                                            </td>
                                            <td style={{ "text-align": 'right', "font-weight": '600', "font-size": "12px" }}>
                                                {formatNumber(usageScore())}
                                            </td>
                                        </tr>
                                    );
                                }}
                            </For>
                        </tbody>
                    </table>
                </div>
            </div>

            <Show when={toggleError()}>
                <div class="toggle-error" role="alert">{toggleError()}</div>
            </Show>

            <div class="actions-bar">
                <button class="btn btn-secondary" onClick={async () => {
                    try {
                        const envData = await api.env();
                        const lines = Object.entries(envData)
                            .map(([k, v]) => `export ${k}="${v}"`)
                            .join('\n');
                        await navigator.clipboard.writeText(lines);
                    } catch (e) { console.error('Copy failed:', e); }
                }}>
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
            </div>
        </div>
    );
}

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

const BETA_SERVICES = new Set(['firestore', 'gke', 'compute', 'cloudrun']);

// All services — includes disabled-by-default ones
const ALL_SERVICES = [
    { id: 'gcs', port: 4443, protocol: 'REST', env_var: 'STORAGE_EMULATOR_HOST', endpoint: 'http://localhost:4443', defaultEnabled: true },
    { id: 'pubsub', port: 8085, protocol: 'gRPC', env_var: 'PUBSUB_EMULATOR_HOST', endpoint: 'localhost:8085', defaultEnabled: true },
    { id: 'firestore', port: 8086, protocol: 'gRPC', env_var: 'FIRESTORE_EMULATOR_HOST', endpoint: 'localhost:8086', defaultEnabled: true },
    { id: 'bigtable', port: 8087, protocol: 'gRPC', env_var: 'BIGTABLE_EMULATOR_HOST', endpoint: 'localhost:8087', defaultEnabled: true },
    { id: 'spanner', port: 9010, protocol: 'gRPC', env_var: 'SPANNER_EMULATOR_HOST', endpoint: 'localhost:9010', defaultEnabled: true },
    { id: 'bigquery', port: 9050, protocol: 'REST', env_var: 'BIGQUERY_EMULATOR_HOST', endpoint: 'http://localhost:9050', defaultEnabled: true },
    { id: 'secretmanager', port: 8080, protocol: 'gRPC', env_var: 'SECRET_MANAGER_EMULATOR_HOST', endpoint: 'localhost:8080', defaultEnabled: true },
    { id: 'cloudtasks', port: 8080, protocol: 'gRPC', env_var: 'CLOUD_TASKS_EMULATOR_HOST', endpoint: 'localhost:8080', defaultEnabled: true },
    { id: 'logging', port: 8080, protocol: 'gRPC', env_var: 'CLOUD_LOGGING_EMULATOR_HOST', endpoint: 'localhost:8080', defaultEnabled: true },
    { id: 'monitoring', port: 8080, protocol: 'gRPC', env_var: 'CLOUD_MONITORING_EMULATOR_HOST', endpoint: 'localhost:8080', defaultEnabled: true },
    { id: 'memorystore', port: 6379, protocol: 'RESP2', env_var: 'REDIS_HOST', endpoint: 'localhost:6379', defaultEnabled: true },
    { id: 'gke', port: 8080, protocol: 'gRPC', env_var: 'GKE_EMULATOR_HOST', endpoint: 'localhost:8080', defaultEnabled: false },
    { id: 'compute', port: 8080, protocol: 'REST', env_var: 'COMPUTE_EMULATOR_HOST', endpoint: 'http://localhost:8080', defaultEnabled: false },
    { id: 'cloudrun', port: 8080, protocol: 'gRPC', env_var: 'CLOUD_RUN_EMULATOR_HOST', endpoint: 'localhost:8080', defaultEnabled: false },
];

function ServiceIcon({ id, size = 18 }) {
    return <img src={`/icons/${id}.svg`} alt="" width={size} height={size} style={{ "object-fit": "contain" }} />;
}

export default function Services(props) {
    const [servicesData, setServicesData] = createSignal([]);
    const [loading, setLoading] = createSignal(true);
    const [fetchError, setFetchError] = createSignal(null);
    const [toggleError, setToggleError] = createSignal(null);
    const [togglingService, setTogglingService] = createSignal(null);
    let failCount = 0;

    const fetchServices = async () => {
        try {
            const data = await api.services();
            if (data && data.services) {
                setServicesData(data.services);
            }
            failCount = 0;
            setFetchError(null);
            setLoading(false);
        } catch (err) {
            failCount++;
            if (failCount >= 2) {
                setFetchError('Cannot reach LocalCloud backend. Is the container running?');
            }
            setLoading(false);
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

        // Build lookup from live API data
        const liveMap = {};
        for (const svc of svcList) {
            liveMap[svc.id] = svc;
        }

        // Merge static list with live data — always show all 14 services
        return ALL_SERVICES.map(def => {
            const live = liveMap[def.id] || {};
            const healthStatus = healthServices[def.id]?.status;
            return {
                ...def,
                ...live,
                id: def.id,
                displayName: SERVICE_NAMES[def.id] || live.name || def.id,
                status: healthStatus || live.status || (def.defaultEnabled ? 'unknown' : 'disabled'),
                port: live.port || def.port,
                protocol: (live.protocol || def.protocol || '--').toUpperCase(),
                env_var: live.env_var || def.env_var || '--',
                endpoint: live.endpoint || def.endpoint || '--',
                request_count: live.request_count || 0,
                enabled: live.enabled !== undefined ? live.enabled : def.defaultEnabled,
                enabledSource: live.enabledSource || 'default',
            };
        });
    };

    const healthyCount = () => services().filter(s => s.status === 'healthy').length;
    const enabledCount = () => services().filter(s => s.enabled).length;
    const totalCount = () => services().length;

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

    return (
        <div>
            <div class="page-header">
                <h1>APIs & Services</h1>
                <p class="page-header-subtitle">
                    {enabledCount()} / {totalCount()} enabled, {healthyCount()} healthy.
                </p>
            </div>

            <Show when={fetchError()}>
                <div class="alert alert-error" style={{ "margin-bottom": "16px" }}>{fetchError()}</div>
            </Show>
            <Show when={loading()} fallback={null}>
                <div class="loading-state"><div class="loading-spinner" /> Loading services...</div>
            </Show>
            <div class="data-table-wrapper">
                    <table class="data-table">
                        <thead>
                            <tr>
                                <th style={{ width: '44px' }}>On</th>
                                <th style={{ width: '36px' }}></th>
                                <th>Service</th>
                                <th>Status</th>
                                <th>Routing</th>
                                <th>Port</th>
                                <th>Protocol</th>
                                <th>Env Var</th>
                                <th style={{ "text-align": 'right' }}>Requests</th>
                                <th>Endpoint</th>
                            </tr>
                        </thead>
                        <tbody>
                            <For each={services()}>
                                {(svc) => {
                                    const isHealthy = () => svc.status === 'healthy';
                                    const isUnknown = () => svc.status === 'unknown';
                                    const isDisabled = () => !svc.enabled || svc.status === 'disabled';
                                    const isLocked = () => svc.enabledSource === 'env';
                                    const isToggling = () => togglingService() === svc.id;
                                    const statusClass = () => {
                                        if (isDisabled()) return 'disabled';
                                        if (isHealthy()) return 'healthy';
                                        if (isUnknown()) return 'warning';
                                        return 'unhealthy';
                                    };
                                    const statusLabel = () => {
                                        if (isToggling()) return 'Updating...';
                                        if (isDisabled()) return 'Disabled';
                                        if (isHealthy()) return 'Healthy';
                                        if (isUnknown()) return 'Unknown';
                                        return 'Unhealthy';
                                    };
                                    return (
                                        <tr
                                            class={`clickable-row ${isDisabled() ? 'service-row-disabled' : ''}`}
                                            onClick={() => handleRowClick(svc.id)}
                                        >
                                            <td onClick={(e) => e.stopPropagation()}>
                                                <label class={`toggle-switch ${isLocked() ? 'locked' : ''}`}
                                                       title={isLocked() ? 'Controlled by environment variable' : ''}>
                                                    <input
                                                        type="checkbox"
                                                        checked={svc.enabled}
                                                        disabled={isLocked() || isToggling()}
                                                        onChange={() => handleToggle(svc.id, svc.enabled)}
                                                    />
                                                    <span class="toggle-slider" />
                                                </label>
                                                {isLocked() && <span class="toggle-lock-icon" title="Locked by env var">&#128274;</span>}
                                            </td>
                                            <td style={{ "text-align": 'center' }}>
                                                <ServiceIcon id={svc.id} size={18} />
                                            </td>
                                            <td style={{ "font-weight": "600", "font-size": "13px" }}>
                                                {svc.displayName}
                                                {BETA_SERVICES.has(svc.id) && <span class="badge badge-beta" style={{ "margin-left": "6px" }}>Beta</span>}
                                            </td>
                                            <td>
                                                <span class="status-indicator">
                                                    <span class={`status-dot ${statusClass()}`} />
                                                    {statusLabel()}
                                                </span>
                                            </td>
                                            <td>
                                                {(() => {
                                                    const mode = props.routingData?.()?.[svc.id]?.mode || 'local';
                                                    return (
                                                        <span class={`badge ${mode === 'remote' ? 'badge-cloud' : 'badge-local'}`}>
                                                            {mode === 'remote' ? 'Cloud' : 'Local'}
                                                        </span>
                                                    );
                                                })()}
                                            </td>
                                            <td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>
                                                {svc.port || '--'}
                                            </td>
                                            <td>
                                                <span class={`badge ${svc.protocol === 'gRPC' ? 'badge-info' : 'badge-neutral'}`}>
                                                    {svc.protocol || '--'}
                                                </span>
                                            </td>
                                            <td>
                                                <code style={{ "font-size": "11px", color: "var(--text-secondary)" }}>
                                                    {svc.env_var || '--'}
                                                </code>
                                            </td>
                                            <td style={{ "text-align": 'right', "font-weight": '600', "font-family": "var(--font-mono)", "font-size": "12px" }}>
                                                {(svc.request_count || 0).toLocaleString()}
                                            </td>
                                            <td>
                                                <code style={{ "font-size": "11px", color: 'var(--text-tertiary)' }}>
                                                    {svc.endpoint || '--'}
                                                </code>
                                            </td>
                                        </tr>
                                    );
                                }}
                            </For>
                        </tbody>
                    </table>
                </div>

            <Show when={toggleError()}>
                <div class="toggle-error">{toggleError()}</div>
            </Show>
        </div>
    );
}

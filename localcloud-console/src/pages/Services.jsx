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

const BETA_SERVICES = new Set(['firestore', 'gke', 'compute']);

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

    createEffect(() => {
        const _proj = props.activeProject; // re-fetch on project switch
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
            };
        });
    };

    const healthyCount = () => services().filter(s => s.status === 'healthy').length;
    const totalCount = () => services().length;

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
                    All {totalCount()} emulated GCP services and their connection details.
                    {' '}{healthyCount()} / {totalCount()} healthy.
                </p>
            </div>

            <div class="data-table-wrapper">
                    <table class="data-table">
                        <thead>
                            <tr>
                                <th style={{ width: '36px' }}></th>
                                <th>Service</th>
                                <th>Status</th>
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
                                    const isDisabled = () => svc.status === 'disabled';
                                    const statusClass = () => {
                                        if (isDisabled()) return 'warning';
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
                                        <tr
                                            class="clickable-row"
                                            onClick={() => handleRowClick(svc.id)}
                                        >
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
        </div>
    );
}

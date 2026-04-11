import { createSignal, createEffect, onCleanup, Show, For } from 'solid-js';
import { api } from '../api.js';

const GCP_PRICING = {
    gcs: { label: 'Cloud Storage', unit: 'per 10K operations', price: 0.05, category: 'storage' },
    pubsub: { label: 'Pub/Sub', unit: 'per 1M messages', price: 0.40, category: 'messaging' },
    firestore: { label: 'Firestore', unit: 'per 100K reads', price: 0.06, category: 'database' },
    bigquery: { label: 'BigQuery', unit: 'per TB queried', price: 5.00, category: 'analytics' },
    secretmanager: { label: 'Secret Manager', unit: 'per 10K access ops', price: 0.03, category: 'security' },
    cloudtasks: { label: 'Cloud Tasks', unit: 'per 1M operations', price: 0.40, category: 'messaging' },
    spanner: { label: 'Spanner', unit: 'per node-hour', price: 0.90, category: 'database' },
    bigtable: { label: 'Bigtable', unit: 'per node-hour', price: 0.65, category: 'database' },
    logging: { label: 'Cloud Logging', unit: 'per GiB ingested', price: 0.50, category: 'operations' },
    monitoring: { label: 'Cloud Monitoring', unit: 'per 1K API calls', price: 0.01, category: 'operations' },
    gke: { label: 'GKE', unit: 'per cluster-hour', price: 0.10, category: 'compute' },
    compute: { label: 'Compute Engine', unit: 'per vCPU-hour', price: 0.03, category: 'compute' },
    cloudrun: { label: 'Cloud Run', unit: 'per 1M requests', price: 0.40, category: 'compute' },
    memorystore: { label: 'Memorystore', unit: 'per GB-hour', price: 0.049, category: 'database' },
};

const STORAGE_PRICING = [
    { label: 'Standard Storage', unit: 'per GB/month', price: 0.020 },
    { label: 'Nearline Storage', unit: 'per GB/month', price: 0.010 },
    { label: 'Coldline Storage', unit: 'per GB/month', price: 0.004 },
    { label: 'Archive Storage', unit: 'per GB/month', price: 0.0012 },
];

const COMPUTE_PRICING = [
    { label: 'e2-micro', unit: 'per hour', price: 0.0084 },
    { label: 'e2-standard-2', unit: 'per hour', price: 0.067 },
    { label: 'e2-standard-4', unit: 'per hour', price: 0.134 },
    { label: 'e2-standard-8', unit: 'per hour', price: 0.268 },
    { label: 'n2-standard-2', unit: 'per hour', price: 0.097 },
    { label: 'n2-standard-8', unit: 'per hour', price: 0.388 },
];

const PROTOCOL_MAP = {
    gcs: 'REST',
    pubsub: 'gRPC',
    firestore: 'gRPC',
    bigquery: 'REST',
    secretmanager: 'gRPC',
    cloudtasks: 'gRPC',
    spanner: 'gRPC',
    bigtable: 'gRPC',
    logging: 'gRPC',
    monitoring: 'gRPC',
    gke: 'gRPC',
    compute: 'REST',
    cloudrun: 'gRPC',
};

function estimateCost(serviceId, requestCount) {
    const pricing = GCP_PRICING[serviceId];
    if (!pricing || !requestCount) return 0;

    switch (serviceId) {
        case 'gcs': return (requestCount / 10000) * pricing.price;
        case 'pubsub': return (requestCount / 1000000) * pricing.price;
        case 'firestore': return (requestCount / 100000) * pricing.price;
        case 'bigquery': return (requestCount / 1000) * pricing.price;
        case 'secretmanager': return (requestCount / 10000) * pricing.price;
        case 'cloudtasks': return (requestCount / 1000000) * pricing.price;
        case 'spanner': return (requestCount / 3600) * pricing.price;
        case 'bigtable': return (requestCount / 3600) * pricing.price;
        case 'logging': return (requestCount / 10000) * pricing.price;
        case 'monitoring': return (requestCount / 1000) * pricing.price;
        case 'gke': return (requestCount / 3600) * pricing.price;
        case 'compute': return (requestCount / 3600) * pricing.price;
        case 'cloudrun': return (requestCount / 1000000) * pricing.price;
        case 'memorystore': return (requestCount / 10000) * pricing.price;
        default: return 0;
    }
}

function formatCost(amount) {
    return '$' + amount.toFixed(2);
}

function formatNumber(n) {
    if (n == null) return '0';
    return Number(n).toLocaleString();
}

export default function Usage(props) {
    const [services, setServices] = createSignal([]);
    const [loading, setLoading] = createSignal(true);
    const [error, setError] = createSignal(null);

    const fetchServices = async () => {
        setLoading(true);
        setError(null);
        try {
            const result = await api.services();
            setServices(result.services || []);
        } catch (err) {
            setError('Could not load services: ' + err.message);
        } finally {
            setLoading(false);
        }
    };

    createEffect(() => {
        const _proj = props.activeProject; // re-fetch on project switch
        fetchServices();
    });

    const serviceUsage = () => {
        return services().map(svc => {
            const id = svc.id;
            const pricing = GCP_PRICING[id];
            const count = svc.request_count || 0;
            const cost = estimateCost(id, count);
            return {
                id,
                name: pricing ? pricing.label : (svc.name || id),
                requestCount: count,
                protocol: PROTOCOL_MAP[id] || svc.protocol || 'REST',
                cost,
                unit: pricing ? pricing.unit : '--',
                price: pricing ? pricing.price : 0,
            };
        });
    };

    const totalCost = () => {
        return serviceUsage().reduce((sum, svc) => sum + svc.cost, 0);
    };

    const totalRequests = () => {
        return serviceUsage().reduce((sum, svc) => sum + svc.requestCount, 0);
    };

    return (
        <div>
            <div class="page-header">
                <h1>Usage & Cost Estimates</h1>
                <p class="page-header-subtitle">
                    Track API usage per service and estimated GCP costs saved by using LocalCloud.
                </p>
            </div>

            <Show when={!loading()} fallback={
                <div class="loading-state"><div class="loading-spinner" /> Loading usage data...</div>
            }>
                <Show when={!error()} fallback={
                    <div>
                        <div class="alert alert-error">{error()}</div>
                        <button class="btn btn-secondary" onClick={fetchServices}>Retry</button>
                    </div>
                }>
                    {/* Savings Banner */}
                    <div class="card" style={{
                        "margin-bottom": "24px",
                        "text-align": "center",
                        "padding": "28px 20px",
                        "background": "linear-gradient(135deg, rgba(99,102,241,0.08) 0%, rgba(52,211,153,0.06) 100%)",
                        "border-color": "rgba(99,102,241,0.15)",
                    }}>
                        <div style={{ "font-size": "11px", "font-weight": "600", "text-transform": "uppercase", "letter-spacing": "0.06em", color: "var(--text-tertiary)", "margin-bottom": "8px" }}>
                            Estimated GCP Cost Saved
                        </div>
                        <div class="gradient-text" style={{
                            "font-size": "40px",
                            "font-weight": "700",
                            "font-family": "var(--font-display)",
                            "letter-spacing": "-0.03em",
                            "line-height": "1",
                            "margin-bottom": "8px",
                        }}>
                            {formatCost(totalCost())}
                        </div>
                        <div style={{ "font-size": "12px", color: "var(--text-secondary)" }}>
                            {formatNumber(totalRequests())} total requests
                        </div>
                    </div>

                    {/* Two-column layout */}
                    <div style={{
                        "display": "grid",
                        "grid-template-columns": "1fr 340px",
                        "gap": "20px",
                        "align-items": "start",
                    }}>
                        {/* Left: Usage Table */}
                        <div class="data-table-wrapper">
                            <table class="data-table">
                                <thead>
                                    <tr>
                                        <th>Service</th>
                                        <th style={{ "text-align": "right" }}>Requests</th>
                                        <th>Protocol</th>
                                        <th style={{ "text-align": "right" }}>Est. Cost</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    <For each={serviceUsage()}>
                                        {(svc) => (
                                            <tr>
                                                <td style={{ "font-weight": "500" }}>{svc.name}</td>
                                                <td style={{ "text-align": "right", "font-family": "var(--font-mono)", "font-size": "12px" }}>
                                                    {formatNumber(svc.requestCount)}
                                                </td>
                                                <td>
                                                    <span class="badge badge-neutral">{svc.protocol}</span>
                                                </td>
                                                <td style={{ "text-align": "right", "font-family": "var(--font-mono)", "font-size": "12px" }}>
                                                    {formatCost(svc.cost)}
                                                </td>
                                            </tr>
                                        )}
                                    </For>
                                    {/* Total row */}
                                    <tr style={{ "background": "var(--surface-active)" }}>
                                        <td style={{ "font-weight": "700" }}>Total</td>
                                        <td style={{ "text-align": "right", "font-weight": "600", "font-family": "var(--font-mono)", "font-size": "12px" }}>
                                            {formatNumber(totalRequests())}
                                        </td>
                                        <td></td>
                                        <td style={{ "text-align": "right", "font-weight": "700", "font-family": "var(--font-mono)", "font-size": "12px" }}>
                                            {formatCost(totalCost())}
                                        </td>
                                    </tr>
                                </tbody>
                            </table>
                        </div>

                        {/* Right: Pricing Reference */}
                        <div class="card" style={{ padding: "16px" }}>
                            <h3 style={{ "margin-bottom": "12px" }}>API Pricing</h3>
                            <div style={{ "display": "flex", "flex-direction": "column", "gap": "6px", "margin-bottom": "20px" }}>
                                <For each={Object.entries(GCP_PRICING)}>
                                    {([id, p]) => (
                                        <div style={{
                                            "display": "flex",
                                            "justify-content": "space-between",
                                            "font-size": "11px",
                                            "color": "var(--text-secondary)",
                                            "padding": "3px 0",
                                            "border-bottom": "1px solid var(--border-subtle)",
                                        }}>
                                            <span>{p.label}</span>
                                            <span style={{ "font-family": "var(--font-mono)", "white-space": "nowrap" }}>
                                                ${p.price.toFixed(p.price < 0.01 ? 4 : 2)} {p.unit}
                                            </span>
                                        </div>
                                    )}
                                </For>
                            </div>

                            <h3 style={{ "margin-bottom": "12px" }}>Storage Tiers</h3>
                            <div style={{ "display": "flex", "flex-direction": "column", "gap": "6px", "margin-bottom": "20px" }}>
                                <For each={STORAGE_PRICING}>
                                    {(p) => (
                                        <div style={{
                                            "display": "flex",
                                            "justify-content": "space-between",
                                            "font-size": "11px",
                                            "color": "var(--text-secondary)",
                                            "padding": "3px 0",
                                            "border-bottom": "1px solid var(--border-subtle)",
                                        }}>
                                            <span>{p.label}</span>
                                            <span style={{ "font-family": "var(--font-mono)", "white-space": "nowrap" }}>
                                                ${p.price.toFixed(p.price < 0.01 ? 4 : 3)} {p.unit}
                                            </span>
                                        </div>
                                    )}
                                </For>
                            </div>

                            <h3 style={{ "margin-bottom": "12px" }}>Compute Instances</h3>
                            <div style={{ "display": "flex", "flex-direction": "column", "gap": "6px" }}>
                                <For each={COMPUTE_PRICING}>
                                    {(p) => (
                                        <div style={{
                                            "display": "flex",
                                            "justify-content": "space-between",
                                            "font-size": "11px",
                                            "color": "var(--text-secondary)",
                                            "padding": "3px 0",
                                            "border-bottom": "1px solid var(--border-subtle)",
                                        }}>
                                            <span style={{ "font-family": "var(--font-mono)" }}>{p.label}</span>
                                            <span style={{ "font-family": "var(--font-mono)", "white-space": "nowrap" }}>
                                                ${p.price.toFixed(p.price < 0.01 ? 4 : 3)} {p.unit}
                                            </span>
                                        </div>
                                    )}
                                </For>
                            </div>
                        </div>
                    </div>
                </Show>
            </Show>
        </div>
    );
}

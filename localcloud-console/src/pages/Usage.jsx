import { createSignal, createEffect, onCleanup, Show, For } from 'solid-js';
import { api } from '../api.js';
import { formatNumber, formatTime } from '../utils/format.js';
import { setUsageRefreshInterval, usageRefreshInterval } from '../utils/refreshPreferences.js';

// Indicative pricing, last reviewed 2026-07-31 — not live GCP rates.
const PRICING_LAST_REVIEWED = '2026-07-31';
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

export default function Usage(props) {
    const [usageData, setUsageData] = createSignal([]);
    const [loading, setLoading] = createSignal(true);
    const [error, setError] = createSignal(null);
    const [lastUpdated, setLastUpdated] = createSignal(null);
    const [autoRefresh, setAutoRefresh] = createSignal((() => {
        try { return localStorage.getItem('localcloud-usage-autorefresh') !== 'false'; } catch { return true; }
    })());
    let isInitialLoad = true;

    const fetchUsage = async () => {
        if (isInitialLoad) setLoading(true);
        setError(null);
        try {
            const result = await api.usage();
            setUsageData(result.services || []);
            setLastUpdated(new Date());
        } catch (err) {
            setError('Could not load usage data: ' + err.message);
        } finally {
            if (isInitialLoad) {
                isInitialLoad = false;
                setLoading(false);
            }
        }
    };

    createEffect(() => {
        const _proj = typeof props.activeProject === 'function' ? props.activeProject() : props.activeProject;
        fetchUsage();
    });

    createEffect(() => {
        if (!autoRefresh()) return;
        const interval = usageRefreshInterval() * 1000;
        const timer = setInterval(fetchUsage, interval);
        onCleanup(() => clearInterval(timer));
    });

    const toggleAutoRefresh = (checked) => {
        setAutoRefresh(checked);
        try { localStorage.setItem('localcloud-usage-autorefresh', String(checked)); } catch {}
    };

    const applyInterval = (seconds) => {
        setUsageRefreshInterval(seconds);
    };

    const serviceUsage = () => {
        return usageData().map(svc => {
            const id = svc.id;
            const pricing = GCP_PRICING[id];
            const count = svc.request_count || 0;
            return {
                id,
                name: pricing ? pricing.label : (svc.name || id),
                requestCount: count,
                protocol: PROTOCOL_MAP[id] || 'REST',
            };
        });
    };

    const totalRequests = () => {
        return serviceUsage().reduce((sum, svc) => sum + svc.requestCount, 0);
    };

    return (
        <div>
            <div class="page-header">
                <h1>Usage &amp; Pricing</h1>
                <p class="page-header-subtitle">
                    Cumulative API request counts by service, alongside an indicative pricing reference.
                </p>
            </div>

            {/* Refresh Controls — right-aligned */}
            <div class="filter-bar" style={{ "margin-bottom": "16px", "justify-content": "flex-end" }}>
                <label for="usage-auto-refresh">
                    <input
                        id="usage-auto-refresh"
                        name="usage-auto-refresh"
                        type="checkbox"
                        checked={autoRefresh()}
                        onChange={e => toggleAutoRefresh(e.currentTarget.checked)}
                    />
                    Auto-refresh
                </label>

                <label class="refresh-interval-label" for="usage-refresh-interval">
                    Every
                    <input
                        id="usage-refresh-interval"
                        name="usage-refresh-interval"
                        autocomplete="off"
                        type="number"
                        min="1"
                        max="120"
                        value={usageRefreshInterval()}
                        onChange={e => applyInterval(parseInt(e.currentTarget.value) || 30)}
                        disabled={!autoRefresh()}
                        style="width:50px"
                    />
                    sec
                </label>

                <button class="btn btn-secondary" onClick={fetchUsage} disabled={loading()}>
                    Refresh
                </button>

                <Show when={lastUpdated()}>
                    <span style="font-size:12px;color:var(--text-secondary)">
                        Last updated: {formatTime(lastUpdated())}
                    </span>
                </Show>
            </div>

            <Show when={!loading()} fallback={
                <div class="loading-state"><div class="loading-spinner" /> Loading usage data…</div>
            }>
                <Show when={!error()} fallback={
                    <div>
                        <div class="alert alert-error" role="alert">{error()}</div>
                        <button class="btn btn-secondary" onClick={fetchUsage}>Retry</button>
                    </div>
                }>
                    {/* Summary bar: replaces hero-metric with restrained stat cards */}
                    <div class="summary-bar" style={{ "margin-bottom": "24px" }}>
                        <div class="stat-card">
                            <div class="stat-card-main">
                                <span class="stat-card-label">API requests</span>
                                <span class="stat-card-value">{formatNumber(totalRequests())}</span>
                                <span class="stat-card-sublabel">cumulative</span>
                            </div>
                        </div>
                        <div class="stat-card">
                            <div class="stat-card-main">
                                <span class="stat-card-label">Active services</span>
                                <span class="stat-card-value">{serviceUsage().filter(s => s.requestCount > 0).length}</span>
                                <span class="stat-card-sublabel">with usage</span>
                            </div>
                        </div>
                        <div class="stat-card">
                            <div class="stat-card-main">
                                <span class="stat-card-label">Last updated</span>
                                <span class="stat-card-value" style={{ "font-size": "var(--font-size-md)" }}>
                                    <Show when={lastUpdated()} fallback="--">
                                        {formatTime(lastUpdated())}
                                    </Show>
                                </span>
                            </div>
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
                        <div>
                            <h2 style={{ "margin-bottom": "14px", "font-size": "var(--font-size-lg)" }}>Service Usage</h2>
                            <div class="data-table-wrapper">
                                <table class="data-table">
                                    <thead>
                                        <tr>
                                            <th>Service</th>
                                            <th style={{ "text-align": "right" }}>Requests</th>
                                            <th>Protocol</th>
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
                                        </tr>
                                    </tbody>
                                </table>
                            </div>
                        </div>

                        {/* Right: Pricing Reference */}
                        <div>
                            <h2 style={{ "margin-bottom": "14px", "font-size": "var(--font-size-lg)" }}>Pricing Reference</h2>
                            <p style={{ "font-size": "11px", "color": "var(--text-tertiary)", "margin-bottom": "8px" }}>
                                Indicative pricing only — not live GCP rates. Last reviewed {PRICING_LAST_REVIEWED}.
                            </p>
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
                    </div>
                </Show>
            </Show>
        </div>
    );
}

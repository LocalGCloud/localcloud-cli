import { createSignal, createEffect, onCleanup, Show, For } from 'solid-js';
import { api } from '../api.js';

function formatTime(ts) {
    if (!ts) return '--';
    try {
        const d = new Date(ts);
        if (isNaN(d.getTime())) return ts;
        return d.toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
    } catch {
        return ts;
    }
}

function statusClass(code) {
    if (!code) return '';
    const n = Number(code);
    if (n >= 200 && n < 300) return 'status-2xx';
    if (n >= 300 && n < 400) return 'status-3xx';
    if (n >= 400 && n < 500) return 'status-4xx';
    if (n >= 500) return 'status-5xx';
    return '';
}

function methodClass(method) {
    if (!method) return '';
    return method.toLowerCase();
}

function matchesMethodFilter(method, filter) {
    if (filter === 'ALL') return true;
    return method === filter;
}

function matchesStatusFilter(status, filter) {
    if (filter === 'ALL') return true;
    const n = Number(status);
    switch (filter) {
        case '2xx': return n >= 200 && n < 300;
        case '4xx': return n >= 400 && n < 500;
        case '5xx': return n >= 500;
        default: return true;
    }
}

export default function Logs(props) {
    const [requests, setRequests] = createSignal([]);
    const [loading, setLoading] = createSignal(false);
    const [error, setError] = createSignal(null);
    const [methodFilter, setMethodFilter] = createSignal('ALL');
    const [statusFilter, setStatusFilter] = createSignal('ALL');
    const [autoRefresh, setAutoRefresh] = createSignal(true);

    const fetchRequests = async () => {
        setLoading(true);
        setError(null);
        try {
            const data = await api.requests();
            const list = data.requests || [];
            setRequests(list.slice(0, 100));
        } catch (err) {
            setError('Failed to load requests: ' + err.message);
        } finally {
            setLoading(false);
        }
    };

    // Re-fetch on project change
    createEffect(() => {
        const _proj = props.activeProject;
        fetchRequests();
    });

    createEffect(() => {
        if (!autoRefresh()) return;
        const timer = setInterval(fetchRequests, 3000);
        onCleanup(() => clearInterval(timer));
    });

    const filteredRequests = () => {
        return requests().filter(r =>
            matchesMethodFilter(r.method, methodFilter()) &&
            matchesStatusFilter(r.status, statusFilter())
        );
    };

    return (
        <div>
            <div class="page-header">
                <h1>Logs</h1>
                <p class="page-header-subtitle">
                    Recent API requests across all emulated services.
                </p>
            </div>

            <Show when={error()}>
                <div class="alert alert-error">{error()}</div>
            </Show>

            {/* Filter Bar */}
            <div class="filter-bar">
                <select
                    value={methodFilter()}
                    onChange={e => setMethodFilter(e.currentTarget.value)}
                >
                    <option value="ALL">All Methods</option>
                    <option value="GET">GET</option>
                    <option value="POST">POST</option>
                    <option value="PUT">PUT</option>
                    <option value="DELETE">DELETE</option>
                </select>

                <select
                    value={statusFilter()}
                    onChange={e => setStatusFilter(e.currentTarget.value)}
                >
                    <option value="ALL">All Status</option>
                    <option value="2xx">2xx Success</option>
                    <option value="4xx">4xx Client Error</option>
                    <option value="5xx">5xx Server Error</option>
                </select>

                <label>
                    <input
                        type="checkbox"
                        checked={autoRefresh()}
                        onChange={e => setAutoRefresh(e.currentTarget.checked)}
                    />
                    Auto-refresh
                </label>

                <button class="btn btn-secondary" onClick={fetchRequests} disabled={loading()}>
                    Refresh
                </button>
            </div>

            {/* Table */}
            <Show when={!loading() || requests().length > 0} fallback={
                <div class="loading-state">
                    <div class="loading-spinner" />
                    Loading requests...
                </div>
            }>
                <Show when={filteredRequests().length > 0} fallback={
                    <div class="empty-state">
                        <div class="empty-state-icon">
                            <svg width="40" height="40" viewBox="0 0 24 24" fill="currentColor"><path d="M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z"/></svg>
                        </div>
                        <div class="empty-state-title">No requests logged yet</div>
                        <div class="empty-state-text">
                            Send requests to the emulated services to see them appear here.
                        </div>
                    </div>
                }>
                    <div class="data-table-wrapper">
                        <table class="data-table">
                            <thead>
                                <tr>
                                    <th>Time</th>
                                    <th>Method</th>
                                    <th>Path</th>
                                    <th>Status</th>
                                    <th>Latency</th>
                                </tr>
                            </thead>
                            <tbody>
                                <For each={filteredRequests()}>
                                    {(req) => (
                                        <tr>
                                            <td style={{ "white-space": "nowrap", "font-family": "var(--font-mono)", "font-size": "11px", color: "var(--text-secondary)" }}>
                                                {formatTime(req.timestamp)}
                                            </td>
                                            <td>
                                                <span class={`badge-method ${methodClass(req.method)}`}>
                                                    {req.method}
                                                </span>
                                            </td>
                                            <td style={{ "font-family": "var(--font-mono)", "font-size": "11px", "max-width": "400px", overflow: "hidden", "text-overflow": "ellipsis", "white-space": "nowrap" }}>
                                                {req.path}
                                            </td>
                                            <td>
                                                <span class={statusClass(req.status)} style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>
                                                    {req.status}
                                                </span>
                                            </td>
                                            <td style={{ "font-family": "var(--font-mono)", "font-size": "11px", color: "var(--text-secondary)" }}>
                                                {req.latency_ms != null ? `${req.latency_ms}ms` : '--'}
                                            </td>
                                        </tr>
                                    )}
                                </For>
                            </tbody>
                        </table>
                    </div>
                </Show>
            </Show>
        </div>
    );
}

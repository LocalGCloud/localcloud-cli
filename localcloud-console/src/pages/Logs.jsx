import { createSignal, createEffect, createMemo, onCleanup, Show, For } from 'solid-js';
import { api } from '../api.js';
import { formatTime } from '../utils/format.js';
import { logsRefreshInterval, setLogsRefreshInterval } from '../utils/refreshPreferences.js';

const PAGE_SIZE = 50;

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

function requestKey(req, idx) {
    return req.id || req.traceId || req.trace_id || `${req.timestamp || ''}:${req.method || ''}:${req.path || ''}:${idx}`;
}

function requestStatus(req) {
    return req.status ?? req.statusCode ?? req.status_code;
}

function requestLatency(req) {
    return req.latency_ms ?? req.durationMs ?? req.duration_ms;
}

function requestMatchesSearch(req, query) {
    if (!query) return true;
    const haystack = [
        req.path,
        req.service,
        req.method,
        req.traceId,
        req.trace_id,
        req.requestBody,
        req.request_body,
        req.responseBody,
        req.response_body,
    ].filter(Boolean).join('\n').toLowerCase();
    return haystack.includes(query);
}

function prettyValue(value) {
    if (value == null || value === '') return '—';
    if (typeof value === 'object') return JSON.stringify(value, null, 2);
    const text = String(value);
    const trimmed = text.trim();
    if ((trimmed.startsWith('{') && trimmed.endsWith('}')) || (trimmed.startsWith('[') && trimmed.endsWith(']'))) {
        try { return JSON.stringify(JSON.parse(trimmed), null, 2); } catch {}
    }
    return text;
}

function LogDetailSection(props) {
    const [copied, setCopied] = createSignal(false);
    const [expanded, setExpanded] = createSignal(false);
    const maxLen = 10240; // 10 KB
    const text = () => prettyValue(props.value);
    const isLarge = () => text().length > maxLen;
    const displayText = () => isLarge() && !expanded() ? text().slice(0, maxLen) + '\n… (truncated — click to expand)' : text();
    const copy = async (e) => {
        e.stopPropagation();
        try {
            await navigator.clipboard.writeText(text());
            setCopied(true);
            setTimeout(() => setCopied(false), 1600);
        } catch {}
    };
    return (
        <div class="log-detail-section">
            <div class="log-detail-title">
                <span>{props.title}</span>
                <div style="display:flex;align-items:center;gap:6px">
                    <Show when={isLarge()}>
                        <button class="btn btn-icon log-copy-btn" onClick={() => setExpanded(!expanded())} title={expanded() ? 'Collapse' : 'Show full body'} style="font-size:11px;font-weight:600">
                            {expanded() ? 'Collapse' : 'Show full'}
                        </button>
                    </Show>
                    <button class="btn btn-icon log-copy-btn" onClick={copy} title={`Copy ${props.title}`} aria-label={`Copy ${props.title}`}>
                        {copied() ? <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path d="M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></svg> : <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"/></svg>}
                    </button>
                </div>
            </div>
            <pre style={{ "max-height": isLarge() && !expanded() ? '300px' : 'none', overflow: 'auto' }}>{displayText()}</pre>
        </div>
    );
}

export default function Logs(props) {
    const [requests, setRequests] = createSignal([]);
    const [loading, setLoading] = createSignal(true);
    const [error, setError] = createSignal(null);
    const [methodFilter, setMethodFilter] = createSignal('ALL');
    const [statusFilter, setStatusFilter] = createSignal('ALL');
    const [searchQuery, setSearchQuery] = createSignal('');
    const [expandedKey, setExpandedKey] = createSignal(null);
    const [tailMode, setTailMode] = createSignal((() => {
        try { return localStorage.getItem('localcloud-logs-tail') === 'true'; } catch { return false; }
    })());
    const [autoRefresh, setAutoRefresh] = createSignal((() => {
        try { return localStorage.getItem('localcloud-logs-autorefresh') !== 'false'; } catch { return true; }
    })());
    const [page, setPage] = createSignal(1);
    let isInitialLoad = true;
    let tableScrollRef;
    let requestSeq = 0;

    const fetchRequests = async () => {
        const requestId = ++requestSeq;
        const initialRequest = isInitialLoad;
        if (initialRequest) setLoading(true);
        setError(null);
        try {
            const data = await api.requests();
            if (requestId !== requestSeq) return;

            const scrollTarget = tableScrollRef;
            const shouldFollowTail = tailMode()
                && scrollTarget
                && scrollTarget.scrollHeight - scrollTarget.scrollTop - scrollTarget.clientHeight <= 40;
            setRequests(data.requests || []);
            if (shouldFollowTail) {
                queueMicrotask(() => {
                    if (tailMode() && scrollTarget.isConnected) {
                        scrollTarget.scrollTop = scrollTarget.scrollHeight;
                    }
                });
            }
        } catch (err) {
            if (requestId === requestSeq) {
                setError('Failed to load requests: ' + err.message);
            }
        } finally {
            if (requestId === requestSeq && initialRequest) {
                isInitialLoad = false;
                setLoading(false);
            }
        }
    };

    onCleanup(() => { requestSeq++; });

    // Re-fetch on project change
    createEffect(() => {
        const _proj = typeof props.activeProject === 'function' ? props.activeProject() : props.activeProject;
        fetchRequests();
    });

    createEffect(() => {
        if (!autoRefresh()) return;
        const interval = logsRefreshInterval() * 1000;
        const timer = setInterval(fetchRequests, interval);
        onCleanup(() => clearInterval(timer));
    });

    const toggleAutoRefresh = (checked) => {
        setAutoRefresh(checked);
        try { localStorage.setItem('localcloud-logs-autorefresh', String(checked)); } catch {}
    };

    const toggleTailMode = (checked) => {
        setTailMode(checked);
        setPage(1);
        try { localStorage.setItem('localcloud-logs-tail', String(checked)); } catch {}
    };

    const applyInterval = (seconds) => {
        setLogsRefreshInterval(seconds);
    };

    const filteredRequests = createMemo(() => {
        const q = searchQuery().trim().toLowerCase();
        return requests().filter(r =>
            matchesMethodFilter(r.method, methodFilter()) &&
            matchesStatusFilter(requestStatus(r), statusFilter()) &&
            requestMatchesSearch(r, q)
        );
    });
    const totalPages = () => Math.max(1, Math.ceil(filteredRequests().length / PAGE_SIZE));
    const pageStart = () => (page() - 1) * PAGE_SIZE;
    const pageEnd = () => Math.min(filteredRequests().length, pageStart() + PAGE_SIZE);
    const orderedRequests = createMemo(() => tailMode() ? [...filteredRequests()].reverse() : filteredRequests());
    const visibleRequests = () => tailMode() ? filteredRequests().slice(-PAGE_SIZE).reverse() : orderedRequests().slice(pageStart(), pageEnd());

    createEffect(() => {
        if (page() > totalPages()) {
            setPage(totalPages());
        }
    });

    return (
        <div>
            <div class="page-header">
                <h1>Logs</h1>
                <p class="page-header-subtitle">
                    Recent API requests across all emulated services.
                </p>
            </div>

            <Show when={error()}>
                <div class="alert alert-error" role="alert">{error()}</div>
            </Show>

            {/* Filter Bar */}
            <div class="filter-bar">
                    <input
                        id="logs-search"
                        name="logs-search"
                        type="text"
                        autocomplete="off"
                        aria-label="Search log paths and payloads"
                        placeholder="Search paths and payloads…"
                        value={searchQuery()}
                        onInput={e => { setSearchQuery(e.currentTarget.value); setPage(1); }}
                    />

                    <select
                        id="logs-method-filter"
                        name="logs-method-filter"
                        aria-label="Filter logs by method"
                        value={methodFilter()}
                        onChange={e => { setMethodFilter(e.currentTarget.value); setPage(1); }}
                    >
                    <option value="ALL">All Methods</option>
                    <option value="GET">GET</option>
                    <option value="POST">POST</option>
                    <option value="PUT">PUT</option>
                    <option value="DELETE">DELETE</option>
                </select>

                    <select
                        id="logs-status-filter"
                        name="logs-status-filter"
                        aria-label="Filter logs by status"
                        value={statusFilter()}
                        onChange={e => { setStatusFilter(e.currentTarget.value); setPage(1); }}
                    >
                    <option value="ALL">All Status</option>
                    <option value="2xx">2xx Success</option>
                    <option value="4xx">4xx Client Error</option>
                    <option value="5xx">5xx Server Error</option>
                </select>

                <label for="logs-auto-refresh">
                        <input
                            id="logs-auto-refresh"
                            name="logs-auto-refresh"
                            type="checkbox"
                            checked={autoRefresh()}
                            onChange={e => toggleAutoRefresh(e.currentTarget.checked)}
                    />
                    Auto-refresh
                </label>

                <label for="logs-tail-mode">
                    <input
                        id="logs-tail-mode"
                        name="logs-tail-mode"
                        type="checkbox"
                        checked={tailMode()}
                        onChange={e => toggleTailMode(e.currentTarget.checked)}
                    />
                    Tail mode
                </label>

                <label class="refresh-interval-label" for="logs-refresh-interval">
                    Every
                    <input
                        id="logs-refresh-interval"
                        name="logs-refresh-interval"
                        autocomplete="off"
                        type="number"
                        min="1"
                        max="60"
                        value={logsRefreshInterval()}
                        onChange={e => applyInterval(parseInt(e.currentTarget.value) || 3)}
                        disabled={!autoRefresh()}
                        style="width:50px"
                    />
                    sec
                </label>

                <button class="btn btn-secondary" onClick={fetchRequests} disabled={loading()}>
                    Refresh
                </button>
            </div>

            {/* Table */}
            <Show when={!loading() || requests().length > 0} fallback={
                <div class="loading-state">
                    <div class="loading-spinner" />
                    Loading requests…
                </div>
            }>
                <Show when={filteredRequests().length > 0} fallback={
                    <Show when={requests().length > 0} fallback={
                        <div class="empty-state">
                            <div class="empty-state-icon">
                                <svg width="40" height="40" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path d="M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z"/></svg>
                            </div>
                            <div class="empty-state-title">No requests logged yet</div>
                            <div class="empty-state-text">
                                Send requests to the emulated services to see them appear here.
                            </div>
                            <div class="empty-state-hint">
                                <code>curl http://localhost:24080/health</code>
                            </div>
                        </div>
                    }>
                        <div class="empty-state">
                            <div class="empty-state-icon">
                                <svg width="40" height="40" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path d="M9.5 3a6.5 6.5 0 0 1 5.15 10.46l4.44 4.45-1.41 1.41-4.45-4.44A6.5 6.5 0 1 1 9.5 3m0 2a4.5 4.5 0 1 0 0 9 4.5 4.5 0 0 0 0-9z"/></svg>
                            </div>
                            <div class="empty-state-title">No results match your filters</div>
                            <div class="empty-state-text">
                                {requests().length} request{requests().length !== 1 ? 's' : ''} logged, but none match the current search or method/status filters.
                            </div>
                            <div class="empty-state-hint" style="cursor:pointer" onClick={() => { setSearchQuery(''); setMethodFilter('ALL'); setStatusFilter('ALL'); }}>
                                Click here to clear all filters
                            </div>
                        </div>
                    </Show>
                }>
                    <div class={`data-table-wrapper logs-table-wrapper ${tailMode() ? 'tailing' : ''}`} ref={tableScrollRef}>
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
                                <For each={visibleRequests()}>
                                    {(req, idx) => {
                                        const key = () => requestKey(req, idx());
                                        const open = () => expandedKey() === key();
                                        const status = () => requestStatus(req);
                                        const latency = () => requestLatency(req);
                                        return (
                                        <>
                                        <tr class={`log-row ${open() ? 'expanded' : ''}`} onClick={() => setExpandedKey(open() ? null : key())} onKeyDown={(e) => {
                                            if (e.key === 'Enter' || e.key === ' ') {
                                                e.preventDefault();
                                                setExpandedKey(open() ? null : key());
                                            }
                                        }} role="button" tabIndex="0" aria-expanded={open()}>
                                            <td style={{ "white-space": "nowrap", "font-family": "var(--font-mono)", "font-size": "11px", color: "var(--text-secondary)" }}>
                                                {formatTime(req.timestamp) || '--'}
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
                                                <span class={statusClass(status())} style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>
                                                    {status()}
                                                </span>
                                            </td>
                                            <td style={{ "font-family": "var(--font-mono)", "font-size": "11px", color: "var(--text-secondary)" }}>
                                                {latency() != null ? `${latency()}ms` : '--'}
                                            </td>
                                        </tr>
                                        <Show when={open()}>
                                            <tr class="log-detail-row">
                                                <td colSpan="5">
                                                    <div class="log-detail-drawer">
                                                        <div class="log-detail-meta">
                                                            <span>{req.service || 'gateway'}</span>
                                                            <Show when={req.traceId || req.trace_id}><code>{req.traceId || req.trace_id}</code></Show>
                                                            <Show when={req.requestSize || req.request_size}><span>{req.requestSize || req.request_size} B request</span></Show>
                                                            <Show when={req.responseSize || req.response_size}><span>{req.responseSize || req.response_size} B response</span></Show>
                                                        </div>
                                                        <div class="log-detail-grid">
                                                            <LogDetailSection title="Request" value={req.requestBody || req.request_body || req.body} />
                                                            <LogDetailSection title="Response" value={req.responseBody || req.response_body} />
                                                            <Show when={req.headers || req.requestHeaders || req.request_headers}>
                                                                <LogDetailSection title="Headers" value={req.headers || req.requestHeaders || req.request_headers} />
                                                            </Show>
                                                        </div>
                                                    </div>
                                                </td>
                                            </tr>
                                        </Show>
                                        </>
                                    );}}
                                </For>
                            </tbody>
                        </table>
                    </div>
                    <Show when={!tailMode() && filteredRequests().length > PAGE_SIZE}>
                        <div class="pagination-controls" aria-label="Log pagination">
                            <span>{pageStart() + 1}-{pageEnd()} of {filteredRequests().length}</span>
                            <button class="btn btn-secondary" onClick={() => setPage(Math.max(1, page() - 1))} disabled={page() <= 1}>Previous</button>
                            <button class="btn btn-secondary" onClick={() => setPage(Math.min(totalPages(), page() + 1))} disabled={page() >= totalPages()}>Next</button>
                        </div>
                    </Show>
                </Show>
            </Show>
        </div>
    );
}

import { createSignal, createEffect, onMount, Show, For } from 'solid-js';

export function TriggerTestPanel(props) {
    const [url, setUrl] = createSignal('');
    const [method, setMethod] = createSignal('GET');
    const [headersList, setHeadersList] = createSignal([{ key: '', value: '' }]);
    const [body, setBody] = createSignal('');
    const [response, setResponse] = createSignal(null);
    const [loading, setLoading] = createSignal(false);
    const [error, setError] = createSignal(null);
    const [elapsed, setElapsed] = createSignal(null);

    const functionId = () => props.functionId || '';

    const buildTriggerUrl = () => {
        return `/functions/trigger/local-project/us-central1/${functionId()}`;
    };

    onMount(() => {
        setUrl(buildTriggerUrl());
    });

    const runTrigger = async () => {
        setLoading(true);
        setError(null);
        setResponse(null);
        const start = performance.now();
        try {
            const headersObj = {};
            headersList().forEach(({ key, value }) => {
                if (key.trim()) headersObj[key.trim()] = value;
            });
            if (!headersObj['Content-Type'] && (body() || method() !== 'GET')) {
                headersObj['Content-Type'] = 'application/json';
            }
            const opts = {
                method: method(),
                headers: headersObj,
            };
            if (body() && method() !== 'GET') {
                opts.body = body();
            }
            const res = await fetch(url(), opts);
            const elapsedMs = performance.now() - start;
            setElapsed(elapsedMs);
            let text = '';
            try { text = await res.text(); } catch {}
            setResponse({
                status: res.status,
                statusText: res.statusText,
                headers: Object.fromEntries(res.headers.entries()),
                body: text,
            });
        } catch (e) {
            setError(e.message);
        } finally {
            setLoading(false);
        }
    };

    const statusColor = () => {
        const s = response()?.status;
        if (!s) return 'var(--text-tertiary)';
        if (s >= 200 && s < 300) return 'var(--success)';
        if (s >= 400 && s < 500) return 'var(--warning)';
        return 'var(--error)';
    };

    const addHeader = () => setHeadersList(prev => [...prev, { key: '', value: '' }]);
    const removeHeader = (i) => setHeadersList(prev => prev.filter((_, idx) => idx !== i));
    const updateHeader = (i, field, val) => setHeadersList(prev => {
        const next = [...prev];
        next[i] = { ...next[i], [field]: val };
        return next;
    });

    return (
        <div class="trigger-panel">
            <div class="trigger-header">
                <div class="trigger-title">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" style={{ opacity: 0.7 }}>
                        <path d="M13 3c-4.97 0-9 4.03-9 9s4.03 9 9 9c.83 0 1.5-.67 1.5-1.5 0-.39-.15-.74-.39-1.01-.23-.26-.38-.61-.38-.99 0-.83.67-1.5 1.5-1.5H16c2.76 0 5-2.24 5-5 0-4.42-4.03-8-9-8zm-5.5 9c-.83 0-1.5-.67-1.5-1.5S6.67 9 7.5 9 9 9.67 9 10.5 8.33 12 7.5 12zm3-4C8.67 8 8 7.33 8 6.5S8.67 5 10.5 5s2.5.67 2.5 1.5S11.33 8 10.5 8zm5 0c-.83 0-1.5-.67-1.5-1.5S14.67 5 15.5 5s2.5.67 2.5 1.5S16.33 8 15.5 8zm3 4c-.83 0-1.5-.67-1.5-1.5S17.67 9 18.5 9s2.5.67 2.5 1.5-.67 1.5-1.5 1.5z"/>
                    </svg>
                    <span>HTTP Trigger Test</span>
                </div>
                <Show when={functionId()}>
                    <span class="trigger-function-badge">{functionId()}</span>
                </Show>
            </div>

            <div class="trigger-url-bar">
                <select class="trigger-method" value={method()} onChange={(e) => setMethod(e.currentTarget.value)}>
                    <option value="GET">GET</option>
                    <option value="POST">POST</option>
                    <option value="PUT">PUT</option>
                    <option value="DELETE">DELETE</option>
                    <option value="PATCH">PATCH</option>
                </select>
                <input
                    class="trigger-url-input"
                    type="text"
                    value={url()}
                    onInput={(e) => setUrl(e.currentTarget.value)}
                    placeholder="https://your-function-url"
                />
                <button class="trigger-send-btn" onClick={runTrigger} disabled={loading() || !url()}>
                    {loading() ? (
                        <div class="loading-spinner" style={{ width: 14, height: 14 }} />
                    ) : (
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
                            <path d="M2.01 21L23 12 2.01 11 2 13l11 1-11 1z"/>
                        </svg>
                    )}
                    Send
                </button>
            </div>

            <div class="trigger-headers-editor">
                <div class="trigger-headers-label">Headers</div>
                <For each={headersList()}>
                    {(header, i) => (
                        <div class="trigger-header-row">
                            <input
                                class="trigger-header-key"
                                type="text"
                                value={header.key}
                                onInput={(e) => updateHeader(i(), 'key', e.currentTarget.value)}
                                placeholder="Header-Name"
                            />
                            <input
                                class="trigger-header-val"
                                type="text"
                                value={header.value}
                                onInput={(e) => updateHeader(i(), 'value', e.currentTarget.value)}
                                placeholder="Value"
                            />
                            <button class="trigger-header-remove" onClick={() => removeHeader(i())}>
                                <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor">
                                    <path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/>
                                </svg>
                            </button>
                        </div>
                    )}
                </For>
                <button class="trigger-add-header" onClick={addHeader}>+ Add Header</button>
            </div>

            <Show when={error()}>
                <div class="trigger-error">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
                        <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z"/>
                    </svg>
                    {error()}
                </div>
            </Show>

            <Show when={response()}>
                <div class="trigger-response">
                    <div class="trigger-response-meta">
                        <span class="trigger-status" style={{ color: statusColor() }}>
                            {response().status} {response().statusText}
                        </span>
                        <Show when={elapsed()}>
                            <span class="trigger-elapsed">{elapsed().toFixed(0)}ms</span>
                        </Show>
                    </div>
                    <div class="trigger-response-headers">
                        <div class="trigger-response-section-label">Response Headers</div>
                        <div class="trigger-headers-list">
                            <For each={Object.entries(response().headers)}>
                                {([k, v]) => (
                                    <div class="trigger-header-item">
                                        <span class="trigger-header-key">{k}:</span>
                                        <span class="trigger-header-val">{v}</span>
                                    </div>
                                )}
                            </For>
                        </div>
                    </div>
                    <div class="trigger-response-body">
                        <div class="trigger-response-section-label">Body</div>
                        <pre class="trigger-body-pre">{response().body || '(empty)'}</pre>
                    </div>
                </div>
            </Show>

            <Show when={!response() && !error() && !loading()}>
                <div class="trigger-placeholder">
                    <svg width="32" height="32" viewBox="0 0 24 24" fill="currentColor" style={{ opacity: 0.12 }}>
                        <path d="M13 3c-4.97 0-9 4.03-9 9s4.03 9 9 9c.83 0 1.5-.67 1.5-1.5 0-.39-.15-.74-.39-1.01-.23-.26-.38-.61-.38-.99 0-.83.67-1.5 1.5-1.5H16c2.76 0 5-2.24 5-5 0-4.42-4.03-8-9-8z"/>
                    </svg>
                    <span>Configure request and click Send to test your trigger</span>
                </div>
            </Show>
        </div>
    );
}

export function JobOutputPanel(props) {
    const [output, setOutput] = createSignal(null);
    const [loading, setLoading] = createSignal(false);
    const [error, setError] = createSignal(null);
    const [height, setHeight] = createSignal(200);

    const loadOutput = async () => {
        if (!props.jobId) return;
        setLoading(true);
        setError(null);
        setOutput(null);
        try {
            const data = await fetch(`/browse/dataproc/a/b/c/${props.jobId}/output?project=${props.project || 'local-project'}`).then(r => r.json());
            if (data.error) {
                setError(data.message || data.error);
            } else {
                setOutput(data.output);
            }
        } catch (e) {
            setError(e.message);
        } finally {
            setLoading(false);
        }
    };

    createEffect(() => {
        if (props.jobId) loadOutput();
    });

    const onResize = (e) => {
        const startY = e.clientY;
        const startH = height();
        const onMove = (ev) => {
            const delta = startY - ev.clientY;
            setHeight(Math.max(80, Math.min(800, startH + delta)));
        };
        const onUp = () => {
            document.removeEventListener('mousemove', onMove);
            document.removeEventListener('mouseup', onUp);
        };
        document.addEventListener('mousemove', onMove);
        document.addEventListener('mouseup', onUp);
    };

    return (
        <div class="job-output-panel">
            <div class="job-output-header">
                <div class="job-output-title">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" style={{ opacity: 0.7 }}>
                        <path d="M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z"/>
                    </svg>
                    <span>Job Output</span>
                </div>
                <button class="btn btn-secondary" style={{ height: "24px", "font-size": "11px", padding: "0 8px" }} onClick={loadOutput}>
                    Refresh
                </button>
            </div>

            <div class="job-output-body" style={{ height: `${height()}px` }}>
                <Show when={loading()}>
                    <div class="job-output-loading">
                        <div class="loading-spinner" />
                        <span>Loading output...</span>
                    </div>
                </Show>

                <Show when={error()}>
                    <div class="job-output-error">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
                            <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z"/>
                        </svg>
                        {error()}
                    </div>
                </Show>

                <Show when={output()}>
                    <pre class="job-output-pre">{output()}</pre>
                </Show>

                <Show when={!output() && !loading() && !error()}>
                    <div class="job-output-placeholder">
                        <span>No output available</span>
                    </div>
                </Show>
            </div>

            <div class="job-output-resize-handle" onMouseDown={onResize}>
                <div class="resize-grip" />
            </div>
        </div>
    );
}

export function SchedulerHistoryPanel(props) {
    const [executions, setExecutions] = createSignal([]);
    const [loading, setLoading] = createSignal(false);
    const [error, setError] = createSignal(null);
    const [height, setHeight] = createSignal(200);

    const loadHistory = async () => {
        if (!props.jobName) return;
        setLoading(true);
        setError(null);
        try {
            const data = await fetch(`/browse/cloudscheduler/jobs/${encodeURIComponent(props.jobName)}/executions?project=${props.project || 'local-project'}`).then(r => r.json());
            if (data.error) {
                setError(data.message || data.error);
            } else {
                setExecutions(data.executions || []);
            }
        } catch (e) {
            setError(e.message);
        } finally {
            setLoading(false);
        }
    };

    createEffect(() => {
        if (props.jobName) loadHistory();
    });

    const statusBadge = (status) => {
        const cls = status === 'OK' ? 'badge-success' : 'badge-error';
        return <span class={`badge ${cls}`}>{status}</span>;
    };

    const onResize = (e) => {
        const startY = e.clientY;
        const startH = height();
        const onMove = (ev) => {
            const delta = startY - ev.clientY;
            setHeight(Math.max(80, Math.min(600, startH + delta)));
        };
        const onUp = () => {
            document.removeEventListener('mousemove', onMove);
            document.removeEventListener('mouseup', onUp);
        };
        document.addEventListener('mousemove', onMove);
        document.addEventListener('mouseup', onUp);
    };

    return (
        <div class="scheduler-history-panel">
            <div class="scheduler-history-header">
                <div class="scheduler-history-title">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" style={{ opacity: 0.7 }}>
                        <path d="M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 3.15.75-1.23-4.5-2.67z"/>
                    </svg>
                    <span>Execution History</span>
                </div>
                <button class="btn btn-secondary" style={{ height: "24px", "font-size": "11px", padding: "0 8px" }} onClick={loadHistory}>
                    Refresh
                </button>
            </div>

            <div class="scheduler-history-body" style={{ height: `${height()}px` }}>
                <Show when={loading()}>
                    <div class="scheduler-history-loading">
                        <div class="loading-spinner" />
                        <span>Loading...</span>
                    </div>
                </Show>

                <Show when={error()}>
                    <div class="scheduler-history-error">{error()}</div>
                </Show>

                <Show when={executions().length > 0}>
                    <div class="scheduler-executions-list">
                        <For each={executions()}>
                            {(exec) => (
                                <div class="scheduler-execution-item">
                                    <div class="scheduler-execution-meta">
                                        <span class="scheduler-execution-time">
                                            {new Date(exec.executedAt).toLocaleString()}
                                        </span>
                                        {statusBadge(exec.status)}
                                    </div>
                                    <Show when={exec.output}>
                                        <div class="scheduler-execution-output">{exec.output}</div>
                                    </Show>
                                </div>
                            )}
                        </For>
                    </div>
                </Show>

                <Show when={!loading() && executions().length === 0 && !error()}>
                    <div class="scheduler-history-placeholder">
                        <span>No execution history yet</span>
                    </div>
                </Show>
            </div>

            <div class="scheduler-history-resize-handle" onMouseDown={onResize}>
                <div class="resize-grip" />
            </div>
        </div>
    );
}

export function ConnectionInfoPanel(props) {
    const [activeTab, setActiveTab] = createSignal('clusters');
    const [clusters, setClusters] = createSignal([]);
    const [loading, setLoading] = createSignal(false);

    const loadClusters = async () => {
        setLoading(true);
        try {
            const data = await fetch(`/browse/alloydb?project=${props.project || 'local-project'}`).then(r => r.json());
            setClusters((data.clusters || []));
        } catch (e) {
            console.error(e);
        } finally {
            setLoading(false);
        }
    };

    createEffect(() => {
        loadClusters();
    });

    return (
        <div class="connection-info-panel">
            <div class="connection-info-header">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" style={{ opacity: 0.7 }}>
                    <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z"/>
                </svg>
                <span>Connection Info</span>
            </div>

            <div class="connection-tabs">
                <button class={`connection-tab ${activeTab() === 'clusters' ? 'active' : ''}`} onClick={() => setActiveTab('clusters')}>
                    Clusters
                </button>
                <button class={`connection-tab ${activeTab() === 'instances' ? 'active' : ''}`} onClick={() => setActiveTab('instances')}>
                    Instances
                </button>
            </div>

            <Show when={loading()}>
                <div class="connection-loading">
                    <div class="loading-spinner" />
                </div>
            </Show>

            <Show when={!loading() && clusters().length > 0}>
                <div class="connection-list">
                    <For each={clusters()}>
                        {(cluster) => (
                            <div class="connection-item">
                                <div class="connection-item-header">
                                    <span class="connection-item-name">{cluster.clusterId || cluster.cluster_id}</span>
                                    <span class="connection-item-location">{cluster.locationId || cluster.location_id}</span>
                                </div>
                                <div class="connection-details">
                                    <div class="connection-detail-row">
                                        <span class="connection-detail-label">Host</span>
                                        <code class="connection-detail-value">localhost</code>
                                    </div>
                                    <div class="connection-detail-row">
                                        <span class="connection-detail-label">Port</span>
                                        <code class="connection-detail-value">24090</code>
                                    </div>
                                    <div class="connection-detail-row">
                                        <span class="connection-detail-label">Database</span>
                                        <code class="connection-detail-value">{cluster.databaseName || cluster.database_name}</code>
                                    </div>
                                    <div class="connection-detail-row">
                                        <span class="connection-detail-label">Connection</span>
                                        <code class="connection-detail-value" style={{ "font-size": "10px" }}>
                                            postgresql://postgres@localhost:24090/{cluster.databaseName || cluster.database_name}
                                        </code>
                                    </div>
                                </div>
                                <button class="connection-copy-btn" onClick={async () => {
                                    const connStr = `postgresql://postgres@localhost:24090/${cluster.databaseName || cluster.database_name}`;
                                    await navigator.clipboard.writeText(connStr);
                                }}>
                                    <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor">
                                        <path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"/>
                                    </svg>
                                    Copy
                                </button>
                            </div>
                        )}
                    </For>
                </div>
            </Show>
        </div>
    );
}
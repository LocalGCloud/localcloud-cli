import { createSignal, createEffect, onCleanup, Show, For } from 'solid-js';
import { api } from '../api.js';

// --- Helpers ---

const STATE_COLORS = {
    ACTIVE: 'var(--success)',
    SUCCEEDED: 'var(--success)',
    FAILED: 'var(--error)',
    CANCELLED: 'var(--text-tertiary)',
    QUEUED: 'var(--warning)',
    DELETED: 'var(--text-tertiary)',
};

function formatTimestamp(raw) {
    if (!raw) return '—';
    try {
        const d = new Date(raw.replace(' ', 'T'));
        if (isNaN(d.getTime())) return raw;
        return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' }) +
            ', ' + d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true });
    } catch { return raw; }
}

function StateBadge(props) {
    const color = () => STATE_COLORS[props.state] || 'var(--text-secondary)';
    return (
        <span style={{
            display: 'inline-flex', 'align-items': 'center', gap: '4px',
            'font-size': '11px', 'font-weight': '600', color: color(),
            background: color() + '18', padding: '2px 8px', 'border-radius': '4px',
            'text-transform': 'uppercase', 'letter-spacing': '0.03em',
        }}>
            <span style={{ width: '6px', height: '6px', 'border-radius': '50%', background: color() }} />
            {props.state}
        </span>
    );
}

function YamlHighlight(props) {
    const lines = () => {
        const src = props.source || '';
        return src.split('\n').map((line, idx) => {
            // Simple YAML syntax highlighting
            let html = line
                .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
                // Comments
                .replace(/(#.*)$/, '<span style="color:var(--sql-comment,#6a9955)">$1</span>')
                // Strings (quoted)
                .replace(/"([^"]*)"/g, '<span style="color:var(--sql-string,#ce9178)">"$1"</span>')
                .replace(/'([^']*)'/g, '<span style="color:var(--sql-string,#ce9178)">\'$1\'</span>')
                // Keys (word followed by colon)
                .replace(/^(\s*)([\w_-]+)(:)/gm, '$1<span style="color:var(--sql-keyword,#569cd6)">$2</span>$3')
                // Boolean / null
                .replace(/\b(true|false|null)\b/g, '<span style="color:var(--sql-number,#b5cea8)">$1</span>')
                // Numbers
                // Note: Sequential regex replacements may double-wrap content in edge cases
                // (e.g., numbers inside string values). This is cosmetic only.
                .replace(/\b(\d+\.?\d*)\b/g, '<span style="color:var(--sql-number,#b5cea8)">$1</span>')
                // ${...} expressions
                .replace(/(\$\{[^}]+\})/g, '<span style="color:var(--sql-function,#dcdcaa)">$1</span>');
            return { num: idx + 1, html };
        });
    };

    return (
        <div style={{
            background: 'var(--sql-editor-bg, var(--bg-subtle))',
            border: '1px solid var(--border)',
            'border-radius': 'var(--radius-sm)',
            'max-height': '600px', 'overflow-y': 'auto', 'font-size': '12px',
            'font-family': 'var(--font-mono)', 'line-height': '1.7',
        }}>
            <table style={{ 'border-collapse': 'collapse', width: '100%' }}>
                <tbody>
                    <For each={lines()}>
                        {(line) => (
                            <tr>
                                <td style={{
                                    'text-align': 'right', padding: '0 12px 0 16px',
                                    color: 'var(--text-tertiary)', 'user-select': 'none',
                                    'font-size': '11px', 'min-width': '36px',
                                    'border-right': '1px solid var(--border-subtle, var(--border))',
                                    background: 'var(--sql-editor-gutter, var(--surface-variant))',
                                }}>
                                    {line.num}
                                </td>
                                <td style={{ padding: '0 16px', 'white-space': 'pre' }}
                                    innerHTML={line.html} />
                            </tr>
                        )}
                    </For>
                </tbody>
            </table>
        </div>
    );
}

// --- Breadcrumb ---
function Breadcrumb(props) {
    return (
        <div style={{
            display: 'flex', 'align-items': 'center', gap: '6px',
            'font-size': '13px', 'margin-bottom': '16px', color: 'var(--text-secondary)',
        }}>
            <For each={props.items}>
                {(item, idx) => (
                    <>
                        {idx() > 0 && <span style={{ color: 'var(--text-tertiary)' }}>/</span>}
                        {item.onClick ? (
                            <span onClick={item.onClick} style={{
                                color: 'var(--primary)', cursor: 'pointer',
                                'text-decoration': 'none',
                            }}
                                onMouseEnter={(e) => e.target.style.textDecoration = 'underline'}
                                onMouseLeave={(e) => e.target.style.textDecoration = 'none'}
                            >{item.label}</span>
                        ) : (
                            <span style={{ color: 'var(--text)', 'font-weight': '500' }}>{item.label}</span>
                        )}
                    </>
                )}
            </For>
        </div>
    );
}

// --- Main Component ---

export default function Workflows(props) {
    const [workflows, setWorkflows] = createSignal([]);
    const [loading, setLoading] = createSignal(true);
    const [error, setError] = createSignal(null);
    const [selectedWorkflow, setSelectedWorkflow] = createSignal(null);
    const [workflowDetail, setWorkflowDetail] = createSignal(null);
    const [executions, setExecutions] = createSignal([]);
    const [selectedExecution, setSelectedExecution] = createSignal(null);
    const [activeTab, setActiveTab] = createSignal('definition');
    const [showCreateExec, setShowCreateExec] = createSignal(false);
    const [execArgument, setExecArgument] = createSignal('{}');
    const [creating, setCreating] = createSignal(false);

    const projectId = () => {
        const p = typeof props.activeProject === 'function' ? props.activeProject() : props.activeProject;
        return p || 'local-project';
    };

    const fetchWorkflows = async () => {
        try {
            const data = await api.browse('workflows');
            setWorkflows(Array.isArray(data) ? data : (data.workflows || []));
            setError(null);
        } catch (err) {
            setError('Failed to load workflows: ' + err.message);
        } finally {
            setLoading(false);
        }
    };

    createEffect(() => {
        const _p = projectId();
        fetchWorkflows();
    });

    const selectWorkflow = async (wfId) => {
        setSelectedWorkflow(wfId);
        setSelectedExecution(null);
        setActiveTab('definition');
        try {
            const detail = await api.browse('workflows/' + wfId);
            setWorkflowDetail(detail);
            const execs = await api.browse('workflows/' + wfId + '/executions');
            setExecutions(Array.isArray(execs) ? execs : (execs.executions || []));
        } catch (err) {
            setError('Failed to load workflow: ' + err.message);
        }
    };

    createEffect(() => {
        // Solid.js cleans up the previous effect's registrations even if
        // the new run returns early, so no timer leak occurs here.
        if (!selectedWorkflow() || activeTab() !== 'executions') return;
        const hasActive = executions().some(e => e.state === 'ACTIVE' || e.state === 'QUEUED');
        if (!hasActive) return;
        const timer = setInterval(async () => {
            try {
                const execs = await api.browse('workflows/' + selectedWorkflow() + '/executions');
                setExecutions(Array.isArray(execs) ? execs : (execs.executions || []));
            } catch {}
        }, 3000);
        onCleanup(() => clearInterval(timer));
    });

    const handleCreateExecution = async () => {
        setCreating(true);
        try {
            await fetch('/_localcloud/mutate/workflows/execute', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ workflow_id: selectedWorkflow(), argument: execArgument() }),
            });
            setShowCreateExec(false);
            setExecArgument('{}');
            setActiveTab('executions');
            const execs = await api.browse('workflows/' + selectedWorkflow() + '/executions');
            setExecutions(Array.isArray(execs) ? execs : (execs.executions || []));
        } catch (err) {
            setError('Failed to create execution: ' + err.message);
        } finally {
            setCreating(false);
        }
    };

    // --- Execution Detail View ---
    const renderExecutionDetail = () => {
        const exec = selectedExecution();
        if (!exec) return null;
        const execId = exec.execution_id || exec.name?.split('/').pop() || 'execution';
        return (
            <div>
                <Breadcrumb items={[
                    { label: 'Workflows', onClick: () => { setSelectedWorkflow(null); setWorkflowDetail(null); setSelectedExecution(null); } },
                    { label: selectedWorkflow(), onClick: () => setSelectedExecution(null) },
                    { label: execId },
                ]} />

                <div style={{ display: 'flex', 'align-items': 'center', gap: '12px', 'margin-bottom': '20px' }}>
                    <h2 style={{ margin: 0, 'font-size': '18px' }}>Execution {execId}</h2>
                    <StateBadge state={exec.state} />
                </div>

                <div style={{ display: 'grid', 'grid-template-columns': '1fr 1fr 1fr', gap: '12px', 'margin-bottom': '20px' }}>
                    <div class="card" style={{ padding: '12px' }}>
                        <div style={{ 'font-size': '11px', color: 'var(--text-tertiary)', 'margin-bottom': '4px', 'text-transform': 'uppercase', 'letter-spacing': '0.04em' }}>Start Time</div>
                        <div style={{ 'font-size': '13px' }}>{formatTimestamp(exec.startTime || exec.start_time)}</div>
                    </div>
                    <div class="card" style={{ padding: '12px' }}>
                        <div style={{ 'font-size': '11px', color: 'var(--text-tertiary)', 'margin-bottom': '4px', 'text-transform': 'uppercase', 'letter-spacing': '0.04em' }}>End Time</div>
                        <div style={{ 'font-size': '13px' }}>{formatTimestamp(exec.endTime || exec.end_time)}</div>
                    </div>
                    <div class="card" style={{ padding: '12px' }}>
                        <div style={{ 'font-size': '11px', color: 'var(--text-tertiary)', 'margin-bottom': '4px', 'text-transform': 'uppercase', 'letter-spacing': '0.04em' }}>Revision</div>
                        <div style={{ 'font-size': '13px' }}>{exec.workflowRevisionId || exec.workflow_revision_id || '—'}</div>
                    </div>
                </div>

                <Show when={exec.argument && exec.argument !== 'null'}>
                    <div style={{ 'margin-bottom': '16px' }}>
                        <h3 style={{ 'font-size': '13px', 'font-weight': '600', 'margin-bottom': '8px' }}>Input</h3>
                        <div class="code-block" style={{ 'max-height': '200px', 'overflow-y': 'auto' }}>
                            {typeof exec.argument === 'string' ? exec.argument : JSON.stringify(exec.argument, null, 2)}
                        </div>
                    </div>
                </Show>

                <Show when={exec.result && exec.result !== 'null'}>
                    <div style={{ 'margin-bottom': '16px' }}>
                        <h3 style={{ 'font-size': '13px', 'font-weight': '600', 'margin-bottom': '8px', color: 'var(--success)' }}>Output</h3>
                        <div class="code-block" style={{ 'max-height': '300px', 'overflow-y': 'auto' }}>
                            {typeof exec.result === 'string' ? exec.result : JSON.stringify(exec.result, null, 2)}
                        </div>
                    </div>
                </Show>

                <Show when={exec.error && exec.error !== 'null'}>
                    <div style={{ 'margin-bottom': '16px' }}>
                        <h3 style={{ 'font-size': '13px', 'font-weight': '600', 'margin-bottom': '8px', color: 'var(--error)' }}>Error</h3>
                        <div class="alert alert-error" style={{ 'font-family': 'var(--font-mono)', 'font-size': '12px', 'white-space': 'pre-wrap' }}>
                            {typeof exec.error === 'string' ? exec.error : JSON.stringify(exec.error, null, 2)}
                        </div>
                    </div>
                </Show>
            </div>
        );
    };

    // --- Workflow Detail View ---
    const renderWorkflowDetail = () => {
        const detail = workflowDetail();
        if (!detail) return <div class="loading-state"><div class="loading-spinner" /> Loading...</div>;

        return (
            <div>
                <Breadcrumb items={[
                    { label: 'Workflows', onClick: () => { setSelectedWorkflow(null); setWorkflowDetail(null); } },
                    { label: selectedWorkflow() },
                ]} />

                <div style={{ display: 'flex', 'align-items': 'center', 'justify-content': 'space-between', 'margin-bottom': '8px' }}>
                    <div style={{ display: 'flex', 'align-items': 'center', gap: '12px' }}>
                        <h2 style={{ margin: 0, 'font-size': '20px', 'font-weight': '600' }}>{selectedWorkflow()}</h2>
                        <StateBadge state={detail.state || 'ACTIVE'} />
                    </div>
                    <button class="btn btn-primary" onClick={() => setShowCreateExec(true)} style={{ height: '32px', 'font-size': '12px' }}>
                        Execute
                    </button>
                </div>
                <div style={{ 'font-size': '12px', color: 'var(--text-secondary)', 'margin-bottom': '20px', display: 'flex', gap: '16px' }}>
                    <span>Revision {detail.revisionId || detail.revision_id || 1}</span>
                    <span>Region: us-central1</span>
                    <span>Updated {formatTimestamp(detail.updateTime || detail.updated_at)}</span>
                </div>

                {/* Tabs — matching se-mode-tab pattern */}
                <div class="se-mode-bar" style={{ 'margin-bottom': '16px' }}>
                    <div class="se-mode-tabs">
                        <button class={`se-mode-tab ${activeTab() === 'definition' ? 'active' : ''}`}
                            onClick={() => setActiveTab('definition')}>
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
                                <path d="M9.4 16.6L4.8 12l4.6-4.6L8 6l-6 6 6 6 1.4-1.4zm5.2 0l4.6-4.6-4.6-4.6L16 6l6 6-6 6-1.4-1.4z"/>
                            </svg>
                            Source
                        </button>
                        <button class={`se-mode-tab ${activeTab() === 'executions' ? 'active' : ''}`}
                            onClick={() => setActiveTab('executions')}>
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
                                <path d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-7 14l-5-5 1.41-1.41L12 14.17l7.59-7.59L21 8l-9 9z"/>
                            </svg>
                            Executions ({executions().length})
                        </button>
                    </div>
                </div>

                <Show when={activeTab() === 'definition'}>
                    <YamlHighlight source={detail.sourceContents || detail.source_contents || '(no source)'} />
                </Show>

                <Show when={activeTab() === 'executions'}>
                    <Show when={selectedExecution()} fallback={
                        <>
                            <Show when={executions().length === 0}>
                                <div class="loading-state" style={{ padding: '40px' }}>
                                    <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="var(--text-tertiary)" stroke-width="1.5" style={{ 'margin-bottom': '12px' }}>
                                        <path d="M12 8v4l3 3m6-3a9 9 0 1 1-18 0 9 9 0 0 1 18 0z"/>
                                    </svg>
                                    <p style={{ 'font-weight': '600', 'margin-bottom': '4px' }}>No executions yet</p>
                                    <p style={{ color: 'var(--text-secondary)', 'font-size': '13px', 'margin-bottom': '12px' }}>
                                        Click "Execute" to run this workflow.
                                    </p>
                                </div>
                            </Show>

                            <Show when={executions().length > 0}>
                                <div class="data-table-wrapper">
                                    <table class="data-table">
                                        <thead>
                                            <tr>
                                                <th>Execution ID</th>
                                                <th>State</th>
                                                <th>Start Time</th>
                                                <th>End Time</th>
                                                <th>Revision</th>
                                            </tr>
                                        </thead>
                                        <tbody>
                                            <For each={executions()}>
                                                {(exec) => (
                                                    <tr class="clickable-row" onClick={() => setSelectedExecution(exec)}>
                                                        <td style={{ 'font-family': 'var(--font-mono)', 'font-size': '12px' }}>
                                                            {exec.execution_id || exec.name?.split('/').pop() || '—'}
                                                        </td>
                                                        <td><StateBadge state={exec.state} /></td>
                                                        <td style={{ 'font-size': '12px' }}>{formatTimestamp(exec.startTime || exec.start_time)}</td>
                                                        <td style={{ 'font-size': '12px' }}>{formatTimestamp(exec.endTime || exec.end_time)}</td>
                                                        <td style={{ 'font-size': '12px' }}>{exec.workflowRevisionId || exec.workflow_revision_id || '—'}</td>
                                                    </tr>
                                                )}
                                            </For>
                                        </tbody>
                                    </table>
                                </div>
                            </Show>
                        </>
                    }>
                        {renderExecutionDetail()}
                    </Show>
                </Show>

                {/* Create Execution Modal */}
                <Show when={showCreateExec()}>
                    <div class="modal-overlay" role="dialog" aria-modal="true"
                         onClick={(e) => { if (e.target === e.currentTarget) setShowCreateExec(false); }}
                         onKeyDown={(e) => { if (e.key === 'Escape') setShowCreateExec(false); }}>
                        <div class="card modal-card" onClick={(e) => e.stopPropagation()}>
                            <h2 style={{ 'margin-bottom': '4px', 'font-size': '16px' }}>Execute Workflow</h2>
                            <p style={{ 'font-size': '12px', color: 'var(--text-secondary)', 'margin-bottom': '16px' }}>
                                Run <strong>{selectedWorkflow()}</strong> with the following input argument.
                            </p>
                            <div style={{ 'margin-bottom': '12px' }}>
                                <label class="form-label">Input (JSON)</label>
                                <textarea class="form-input form-input-mono"
                                    style={{ 'min-height': '120px', resize: 'vertical', 'font-size': '12px' }}
                                    value={execArgument()}
                                    onInput={(e) => setExecArgument(e.currentTarget.value)}
                                    placeholder='{"key": "value"}'
                                />
                            </div>
                            <div style={{ display: 'flex', gap: '8px', 'justify-content': 'flex-end' }}>
                                <button class="btn btn-secondary" onClick={() => setShowCreateExec(false)}>Cancel</button>
                                <button class="btn btn-primary" onClick={handleCreateExecution} disabled={creating()}>
                                    {creating() ? 'Starting...' : 'Execute'}
                                </button>
                            </div>
                        </div>
                    </div>
                </Show>
            </div>
        );
    };

    // --- List View ---
    return (
        <div>
            <Show when={!selectedWorkflow()} fallback={renderWorkflowDetail()}>
                <Show when={error()}>
                    <div class="alert alert-error" style={{ 'margin-bottom': '16px' }}>{error()}</div>
                </Show>

                <Show when={loading()}>
                    <div class="loading-state"><div class="loading-spinner" /> Loading workflows...</div>
                </Show>

                <Show when={!loading() && workflows().length === 0}>
                    <div class="loading-state" style={{ padding: '60px' }}>
                        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--text-tertiary)" stroke-width="1.5" style={{ 'margin-bottom': '16px' }}>
                            <path d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2M9 5a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2M9 5h6"/>
                            <path d="M9 14l2 2 4-4"/>
                        </svg>
                        <p style={{ 'font-weight': '600', 'margin-bottom': '4px' }}>No workflows deployed</p>
                        <p style={{ color: 'var(--text-secondary)', 'font-size': '13px' }}>
                            Add workflows to your <code style={{ 'font-size': '12px' }}>seed.yaml</code> or use the Workflows API.
                        </p>
                    </div>
                </Show>

                <Show when={!loading() && workflows().length > 0}>
                    <div class="data-table-wrapper">
                        <table class="data-table">
                            <thead>
                                <tr>
                                    <th>Name</th>
                                    <th>Region</th>
                                    <th>State</th>
                                    <th>Revision</th>
                                    <th>Last Updated</th>
                                </tr>
                            </thead>
                            <tbody>
                                <For each={workflows()}>
                                    {(wf) => (
                                        <tr class="clickable-row" onClick={() => selectWorkflow(wf.workflow_id || wf.name?.split('/').pop())}>
                                            <td style={{ 'font-weight': '600' }}>
                                                {wf.workflow_id || wf.name?.split('/').pop() || '—'}
                                            </td>
                                            <td style={{ 'font-size': '12px', color: 'var(--text-secondary)' }}>
                                                {wf.location_id || 'us-central1'}
                                            </td>
                                            <td><StateBadge state={wf.state || 'ACTIVE'} /></td>
                                            <td style={{ 'font-family': 'var(--font-mono)', 'font-size': '12px' }}>
                                                {wf.revisionId || wf.revision_id || 1}
                                            </td>
                                            <td style={{ 'font-size': '12px', color: 'var(--text-secondary)' }}>
                                                {formatTimestamp(wf.updateTime || wf.updated_at)}
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

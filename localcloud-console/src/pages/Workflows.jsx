import { createSignal, createEffect, onCleanup, Show, For } from 'solid-js';
import { api } from '../api.js';

const STATE_COLORS = {
    ACTIVE: 'var(--success)',
    SUCCEEDED: 'var(--success)',
    FAILED: 'var(--error)',
    CANCELLED: 'var(--text-tertiary)',
    QUEUED: 'var(--warning)',
    DELETED: 'var(--text-tertiary)',
};

function StateBadge(props) {
    const color = () => STATE_COLORS[props.state] || 'var(--text-secondary)';
    return (
        <span style={{
            display: 'inline-flex',
            'align-items': 'center',
            gap: '4px',
            'font-size': '11px',
            'font-weight': '600',
            color: color(),
            background: color() + '18',
            padding: '2px 8px',
            'border-radius': '4px',
            'text-transform': 'uppercase',
        }}>
            <span style={{
                width: '6px', height: '6px', 'border-radius': '50%',
                background: color(),
            }} />
            {props.state}
        </span>
    );
}

export default function Workflows(props) {
    const [workflows, setWorkflows] = createSignal([]);
    const [loading, setLoading] = createSignal(true);
    const [error, setError] = createSignal(null);
    const [selectedWorkflow, setSelectedWorkflow] = createSignal(null);
    const [workflowDetail, setWorkflowDetail] = createSignal(null);
    const [executions, setExecutions] = createSignal([]);
    const [selectedExecution, setSelectedExecution] = createSignal(null);
    const [activeTab, setActiveTab] = createSignal('definition'); // 'definition' | 'executions'
    const [showCreateExec, setShowCreateExec] = createSignal(false);
    const [execArgument, setExecArgument] = createSignal('{}');
    const [creating, setCreating] = createSignal(false);

    const projectId = () => {
        const p = typeof props.activeProject === 'function' ? props.activeProject() : props.activeProject;
        return p || 'local-project';
    };

    // Fetch workflow list
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

    // Fetch workflow detail + executions
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

    // Auto-refresh executions when viewing
    createEffect(() => {
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

    // Create execution
    const handleCreateExecution = async () => {
        setCreating(true);
        try {
            await fetch('/_localcloud/workflows/' + projectId() + '/us-central1/' + selectedWorkflow() + '/executions', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ argument: execArgument() }),
            });
            setShowCreateExec(false);
            setExecArgument('{}');
            setActiveTab('executions');
            // Refresh executions
            const execs = await api.browse('workflows/' + selectedWorkflow() + '/executions');
            setExecutions(Array.isArray(execs) ? execs : (execs.executions || []));
        } catch (err) {
            setError('Failed to create execution: ' + err.message);
        } finally {
            setCreating(false);
        }
    };

    const goBack = () => {
        if (selectedExecution()) {
            setSelectedExecution(null);
        } else {
            setSelectedWorkflow(null);
            setWorkflowDetail(null);
        }
    };

    // --- Execution Detail View ---
    const renderExecutionDetail = () => {
        const exec = selectedExecution();
        if (!exec) return null;
        return (
            <div>
                <button class="btn btn-secondary" onClick={() => setSelectedExecution(null)} style={{ 'margin-bottom': '16px' }}>
                    ← Back to Executions
                </button>
                <div style={{ display: 'flex', 'align-items': 'center', gap: '12px', 'margin-bottom': '20px' }}>
                    <h2 style={{ margin: 0 }}>Execution</h2>
                    <StateBadge state={exec.state} />
                </div>

                <div style={{ display: 'grid', 'grid-template-columns': '1fr 1fr', gap: '12px', 'margin-bottom': '20px' }}>
                    <div class="card" style={{ padding: '12px' }}>
                        <div style={{ 'font-size': '11px', color: 'var(--text-tertiary)', 'margin-bottom': '4px' }}>Start Time</div>
                        <div style={{ 'font-size': '13px' }}>{exec.startTime || exec.start_time || '—'}</div>
                    </div>
                    <div class="card" style={{ padding: '12px' }}>
                        <div style={{ 'font-size': '11px', color: 'var(--text-tertiary)', 'margin-bottom': '4px' }}>End Time</div>
                        <div style={{ 'font-size': '13px' }}>{exec.endTime || exec.end_time || '—'}</div>
                    </div>
                </div>

                <Show when={exec.argument}>
                    <div style={{ 'margin-bottom': '16px' }}>
                        <h3>Input Argument</h3>
                        <div class="code-block" style={{ 'max-height': '200px', 'overflow-y': 'auto' }}>
                            {typeof exec.argument === 'string' ? exec.argument : JSON.stringify(exec.argument, null, 2)}
                        </div>
                    </div>
                </Show>

                <Show when={exec.result}>
                    <div style={{ 'margin-bottom': '16px' }}>
                        <h3>Result</h3>
                        <div class="code-block" style={{ 'max-height': '300px', 'overflow-y': 'auto' }}>
                            {typeof exec.result === 'string' ? exec.result : JSON.stringify(exec.result, null, 2)}
                        </div>
                    </div>
                </Show>

                <Show when={exec.error}>
                    <div style={{ 'margin-bottom': '16px' }}>
                        <h3 style={{ color: 'var(--error)' }}>Error</h3>
                        <div class="alert alert-error">
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
                <button class="btn btn-secondary" onClick={goBack} style={{ 'margin-bottom': '16px' }}>
                    ← Back to Workflows
                </button>

                <div style={{ display: 'flex', 'align-items': 'center', gap: '12px', 'margin-bottom': '8px' }}>
                    <h1 style={{ margin: 0 }}>{selectedWorkflow()}</h1>
                    <StateBadge state={detail.state || 'ACTIVE'} />
                </div>
                <p class="page-header-subtitle" style={{ 'margin-bottom': '20px' }}>
                    Revision {detail.revisionId || detail.revision_id || 1}
                    {detail.updateTime || detail.updated_at ? ` · Updated ${detail.updateTime || detail.updated_at}` : ''}
                </p>

                {/* Tabs */}
                <div style={{ display: 'flex', gap: '0', 'border-bottom': '2px solid var(--border)', 'margin-bottom': '20px' }}>
                    <button
                        class={`segmented-toggle-btn ${activeTab() === 'definition' ? 'active' : ''}`}
                        onClick={() => setActiveTab('definition')}
                        style={{ 'border-bottom': activeTab() === 'definition' ? '2px solid var(--primary)' : '2px solid transparent', 'margin-bottom': '-2px', background: 'none', border: 'none', padding: '8px 16px', cursor: 'pointer', color: activeTab() === 'definition' ? 'var(--primary)' : 'var(--text-secondary)', 'font-weight': activeTab() === 'definition' ? '600' : '400' }}
                    >
                        Definition
                    </button>
                    <button
                        class={`segmented-toggle-btn ${activeTab() === 'executions' ? 'active' : ''}`}
                        onClick={() => setActiveTab('executions')}
                        style={{ 'border-bottom': activeTab() === 'executions' ? '2px solid var(--primary)' : '2px solid transparent', 'margin-bottom': '-2px', background: 'none', border: 'none', padding: '8px 16px', cursor: 'pointer', color: activeTab() === 'executions' ? 'var(--primary)' : 'var(--text-secondary)', 'font-weight': activeTab() === 'executions' ? '600' : '400' }}
                    >
                        Executions ({executions().length})
                    </button>
                </div>

                <Show when={activeTab() === 'definition'}>
                    <div class="code-block" style={{ 'max-height': '600px', 'overflow-y': 'auto', 'line-height': '1.6' }}>
                        {detail.sourceContents || detail.source_contents || '(no source)'}
                    </div>
                </Show>

                <Show when={activeTab() === 'executions'}>
                    <Show when={selectedExecution()} fallback={
                        <>
                            <div style={{ display: 'flex', 'justify-content': 'flex-end', 'margin-bottom': '12px' }}>
                                <button class="btn btn-primary" onClick={() => setShowCreateExec(true)}>
                                    + Execute
                                </button>
                            </div>

                            <Show when={executions().length === 0}>
                                <div class="loading-state" style={{ padding: '40px' }}>
                                    <p style={{ color: 'var(--text-secondary)' }}>No executions yet. Click "+ Execute" to run this workflow.</p>
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
                                                        <td style={{ 'font-size': '12px' }}>{exec.startTime || exec.start_time || '—'}</td>
                                                        <td style={{ 'font-size': '12px' }}>{exec.endTime || exec.end_time || '—'}</td>
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
                         onClick={(e) => { if (e.target === e.currentTarget) setShowCreateExec(false); }}>
                        <div class="card modal-card" onClick={(e) => e.stopPropagation()}>
                            <h2 style={{ 'margin-bottom': '16px' }}>Execute Workflow</h2>
                            <div style={{ 'margin-bottom': '12px' }}>
                                <label class="form-label">Argument (JSON)</label>
                                <textarea class="form-input form-input-mono"
                                    style={{ 'min-height': '120px', resize: 'vertical' }}
                                    value={execArgument()}
                                    onInput={(e) => setExecArgument(e.currentTarget.value)}
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

    // --- Main render ---
    return (
        <div>
            <Show when={!selectedWorkflow()} fallback={renderWorkflowDetail()}>
                <div class="page-header">
                    <h1>Workflows</h1>
                    <p class="page-header-subtitle">Cloud Workflows definitions and execution history.</p>
                </div>

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
                            Deploy workflows via seed data or the Workflows API.
                        </p>
                    </div>
                </Show>

                <Show when={!loading() && workflows().length > 0}>
                    <div class="data-table-wrapper">
                        <table class="data-table">
                            <thead>
                                <tr>
                                    <th>Name</th>
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
                                            <td><StateBadge state={wf.state || 'ACTIVE'} /></td>
                                            <td style={{ 'font-family': 'var(--font-mono)', 'font-size': '12px' }}>
                                                {wf.revisionId || wf.revision_id || 1}
                                            </td>
                                            <td style={{ 'font-size': '12px', color: 'var(--text-secondary)' }}>
                                                {wf.updateTime || wf.updated_at || '—'}
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

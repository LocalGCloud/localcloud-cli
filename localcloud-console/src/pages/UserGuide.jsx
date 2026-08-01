import { createSignal, createEffect, Show, For } from 'solid-js';
import { api } from '../api.js';
import { SERVICE_META, SDK_ORDER, SAMPLE_CODE, CLI_COMMANDS, DATABASE_EXAMPLES } from './settings-data.js';
import { onActivate } from '../utils/a11y.js';
import { createUrlBackedTab } from '../utils/urlTabs.js';

// --- SVG Icons ---
const CopyIcon = () => (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
);
const CheckIcon = () => (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><polyline points="20 6 9 17 4 12"/></svg>
);
const ChevronIcon = (props) => (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"
        aria-hidden="true" focusable="false"
        style={{ transition: "transform 150ms ease", transform: props.open ? "rotate(90deg)" : "rotate(0deg)" }}>
        <polyline points="9 18 15 12 9 6"/>
    </svg>
);

// --- CopyableCodeBlock component ---
function CopyableCodeBlock(props) {
    const [copied, setCopied] = createSignal(false);
    const handleCopy = async () => {
        const ok = await copyToClipboard(props.children?.toString() || props.code || '');
        if (ok) { setCopied(true); setTimeout(() => setCopied(false), 2000); }
    };
    return (
        <div class="code-block-copyable">
            {props.label && <div class="code-block-label">{props.label}</div>}
            <div class="code-block" style={{ margin: "0", ...(props.style || {}) }}>
                {props.children || props.code}
            </div>
            <button class="code-block-copy-btn" onClick={handleCopy} title="Copy to clipboard" aria-label="Copy to clipboard">
                <Show when={copied()} fallback={<CopyIcon />}><CheckIcon /></Show>
            </button>
        </div>
    );
}

// --- Categorization helper ---
function categorizeEnvVars(vars) {
    const common = {};
    const sdk = {};
    const gcloud = {};
    for (const [k, v] of Object.entries(vars)) {
        if (k === 'GOOGLE_CLOUD_PROJECT' || k === 'GCLOUD_PROJECT' || k === 'CLOUDSDK_AUTH_ACCESS_TOKEN') {
            common[k] = v;
        } else if (k.startsWith('CLOUDSDK_')) {
            gcloud[k] = v;
        } else {
            sdk[k] = v;
        }
    }
    return { common, sdk, gcloud };
}

// --- Clipboard helper ---
async function copyToClipboard(text) {
    try {
        await navigator.clipboard.writeText(text);
        return true;
    } catch {
        return false;
    }
}

// --- CopyableEnvVar component ---
function CopyableEnvVar(props) {
    const [copied, setCopied] = createSignal(false);
    const handleCopy = async () => {
        const ok = await copyToClipboard(`export ${props.name}="${props.value}"`);
        if (ok) {
            setCopied(true);
            setTimeout(() => setCopied(false), 2000);
        }
    };
    return (
        <div class="env-var-row">
            <span class="env-var-key">{props.name}</span>
            <span class="env-var-value">{props.value}</span>
            <button class="env-var-copy" onClick={handleCopy} title="Copy to clipboard" aria-label={`Copy ${props.name} export command`}>
                <Show when={copied()} fallback={<CopyIcon />}>
                    <CheckIcon />
                </Show>
            </button>
            <Show when={copied()}>
                <span class="env-var-copied">Copied!</span>
            </Show>
        </div>
    );
}

// --- ServiceEnvCard component ---
function ServiceEnvCard(props) {
    const [expanded, setExpanded] = createSignal(false);
    const [activeTab, setActiveTab] = createSignal('python');
    const meta = () => SERVICE_META[props.envKey];
    const snippets = () => meta() ? SAMPLE_CODE[meta().id] : null;

    const tabs = () => {
        const s = snippets();
        if (!s) return [];
        const t = [];
        if (s.python) t.push({ id: 'python', label: 'Python' });
        if (s.nodejs) t.push({ id: 'nodejs', label: 'Node.js' });
        if (s.go) t.push({ id: 'go', label: 'Go' });
        if (s.java) t.push({ id: 'java', label: 'Java' });
        if (s.gcloud && meta()?.hasGcloud) t.push({ id: 'gcloud', label: 'gcloud CLI' });
        return t;
    };

    return (
        <div class="env-service-card">
            <div class="env-service-header">
                <span class="env-service-name">{meta()?.displayName || props.envKey}</span>
            </div>
            <CopyableEnvVar name={props.envKey} value={props.value} />
            <Show when={snippets()}>
                <button class="env-sample-toggle" onClick={() => setExpanded(!expanded())} aria-expanded={expanded()}>
                    <ChevronIcon open={expanded()} />
                    <span>Sample Code</span>
                </button>
                <Show when={expanded()}>
                    <div class="env-sample-content">
                        <div class="env-sample-tabs">
                            <For each={tabs()}>
                                {(tab) => (
                                    <button
                                        classList={{ "env-sample-tab": true, "active": activeTab() === tab.id }}
                                        onClick={() => setActiveTab(tab.id)}
                                    >
                                        {tab.label}
                                    </button>
                                )}
                            </For>
                        </div>
                        <CopyableCodeBlock style={{ "border-top-left-radius": "0", "border-top-right-radius": "0" }}>
                            {snippets()?.[activeTab()] || 'No sample available for this language.'}
                        </CopyableCodeBlock>
                    </div>
                </Show>
            </Show>
        </div>
    );
}

// --- CopyAll button ---
function CopyAllButton(props) {
    const [copied, setCopied] = createSignal(false);
    const handleCopy = async () => {
        const text = Object.entries(props.vars)
            .map(([k, v]) => `export ${k}="${v}"`)
            .join('\n');
        const ok = await copyToClipboard(text);
        if (ok) {
            setCopied(true);
            setTimeout(() => setCopied(false), 2000);
        }
    };
    return (
        <div style={{ "margin-top": "12px", display: "flex", "align-items": "center", gap: "10px" }}>
            <button class="btn btn-secondary" onClick={handleCopy}>
                <CopyIcon />
                {copied() ? 'Copied!' : props.label}
            </button>
        </div>
    );
}

// --- EnvTabs component (Shell / CLI / SDK / Examples / Compatibility) ---
function EnvTabs(props) {
    const [_activeTab, _setActiveTab] = createSignal(props.tab || 'shell');
    const activeTab = () => props.tab || _activeTab();
    const setActiveTab = props.tab ? () => {} : _setActiveTab;
    const [copiedBlock, setCopiedBlock] = createSignal(null);
    const [sdkLang, setSdkLang] = createUrlBackedTab('sdk', ['python', 'nodejs', 'go', 'java'], 'python', { history: 'replace' });
    const [expandedServices, setExpandedServices] = createSignal(new Set());
    const [allExpanded, setAllExpanded] = createSignal(false);
    const [coverageData, setCoverageData] = createSignal(null);
    const [coverageError, setCoverageError] = createSignal(null);
    const [coverageLoading, setCoverageLoading] = createSignal(false);

    const fetchCoverageData = async () => {
        if (coverageLoading()) return;
        setCoverageLoading(true);
        setCoverageError(null);
        try {
            const data = await api.coverage();
            setCoverageData(data);
        } catch (e) {
            const msg = e.message || '';
            if (msg.includes('text/html') || msg.includes('<!DOCTYPE') || msg.includes('HTML instead of JSON')) {
                setCoverageError('Coverage API not available — ensure the server is running the latest version.');
            } else {
                setCoverageError(msg || 'Failed to load coverage data');
            }
        } finally {
            setCoverageLoading(false);
        }
    };

    // Auto-fetch coverage data when Compatibility tab becomes active
    createEffect(() => {
        if (activeTab() === 'compatibility' && !coverageData() && !coverageError()) {
            fetchCoverageData();
        }
    });

    const STATUS_COLORS = {
        supported: { bg: 'rgba(52,168,83,0.1)', fg: '#34a853', label: 'Supported' },
        partial: { bg: 'rgba(251,188,4,0.1)', fg: '#f9ab00', label: 'Partial' },
        unsupported: { bg: 'rgba(234,67,53,0.06)', fg: '#ea4335', label: 'Unsupported' },
        unverified: { bg: 'rgba(128,128,128,0.1)', fg: '#2408080', label: 'Unverified' },
        planned: { bg: 'rgba(66,133,244,0.1)', fg: '#4285f4', label: 'Planned' },
        prod_only: { bg: 'rgba(138,180,248,0.1)', fg: '#8ab4f8', label: 'Prod Only' },
    };

    const toggleService = (id) => {
        const s = new Set(expandedServices());
        if (s.has(id)) s.delete(id); else s.add(id);
        setExpandedServices(s);
    };
    const toggleAll = () => {
        if (allExpanded()) {
            setExpandedServices(new Set());
            setAllExpanded(false);
        } else {
            const all = new Set(Object.entries(CLI_COMMANDS).map(([id]) => id));
            sdkServices().forEach(s => all.add(s.id));
            setExpandedServices(all);
            setAllExpanded(true);
        }
    };

    const shellBlock = (vars, comment) => {
        const lines = Object.entries(vars).map(([k, v]) => `export ${k}="${v}"`);
        return comment ? `# ${comment}\n${lines.join('\n')}` : lines.join('\n');
    };

    const handleCopyBlock = async (blockName, text) => {
        const ok = await copyToClipboard(text);
        if (ok) {
            setCopiedBlock(blockName);
            setTimeout(() => setCopiedBlock(null), 2000);
        }
    };

    const sdkServices = () => {
        return props.sdkEntries().map(e => {
            const meta = SERVICE_META[e.key];
            return { envKey: e.key, value: e.value, id: meta?.id, label: meta?.displayName || e.key };
        }).filter(s => SAMPLE_CODE[s.id]);
    };

    return (
        <div>
            <Show when={!props.tab}>
                <div class="tab-bar">
                    <button classList={{ "tab-item": true, "active": activeTab() === 'shell' }} onClick={() => setActiveTab('shell')}>
                        Environment Variables
                    </button>
                    <button classList={{ "tab-item": true, "active": activeTab() === 'cli' }} onClick={() => setActiveTab('cli')}>
                        CLI
                    </button>
                    <button classList={{ "tab-item": true, "active": activeTab() === 'sdk' }} onClick={() => setActiveTab('sdk')}>
                        SDK
                    </button>
                    <button classList={{ "tab-item": true, "active": activeTab() === 'examples' }} onClick={() => setActiveTab('examples')}>
                        Examples
                    </button>
                    <button classList={{ "tab-item": true, "active": activeTab() === 'compatibility' }} onClick={() => { setActiveTab('compatibility'); if (!coverageData()) fetchCoverageData(); }}>
                        Compatibility
                    </button>
                </div>
            </Show>

            {/* Shell Export Tab */}
            <Show when={activeTab() === 'shell'}>
                <div style={{ "margin-top": "4px" }}>
                    <p style={{ "margin-bottom": "12px", "font-size": "13px", color: "var(--text-secondary)" }}>
                        Copy and paste these export statements into your terminal. All Google Cloud SDKs will route to LocalCloud.
                    </p>

                    <Show when={Object.keys(props.categorized().common).length > 0}>
                        <div class="code-block-header" style={{ "margin-bottom": "4px" }}>
                            <span style={{ "font-size": "12px", "font-weight": "600", color: "var(--text-secondary)" }}>Project & Auth Setup</span>
                        </div>
                        <p style={{ "margin-top": "0", "margin-bottom": "8px", "font-size": "12px", color: "var(--text-tertiary)" }}>
                            Set this for all technology emulator usage.
                        </p>
                        <CopyableCodeBlock style={{ "margin-bottom": "16px" }}>
                            {shellBlock(props.categorized().common)}
                        </CopyableCodeBlock>
                    </Show>

                    <Show when={Object.keys(props.categorized().sdk).length > 0}>
                        <div class="code-block-header">
                            <span style={{ "font-size": "12px", "font-weight": "600", color: "var(--text-secondary)" }}>SDK Emulator Hosts</span>
                        </div>
                        <CopyableCodeBlock style={{ "margin-bottom": "16px" }}>
                            {shellBlock(props.categorized().sdk)}
                        </CopyableCodeBlock>
                    </Show>

                    <Show when={Object.keys(props.categorized().gcloud).length > 0}>
                        <div class="code-block-header">
                            <span style={{ "font-size": "12px", "font-weight": "600", color: "var(--text-secondary)" }}>gcloud CLI Overrides</span>
                        </div>
                        <CopyableCodeBlock>
                            {shellBlock(props.categorized().gcloud)}
                        </CopyableCodeBlock>
                    </Show>
                </div>
            </Show>

            {/* CLI Tab */}
            <Show when={activeTab() === 'cli'}>
                <div style={{ "margin-top": "4px" }}>
                    <p style={{ "margin-bottom": "12px", "font-size": "13px", color: "var(--text-secondary)" }}>
                        gcloud CLI commands for each service. Set the env vars from the Shell tab first.
                    </p>
                    <div style={{ display: "flex", "justify-content": "flex-end", "margin-bottom": "8px" }}>
                        <button class="btn btn-secondary" style={{ height: "28px", "font-size": "11px", padding: "0 10px" }} onClick={toggleAll}>
                            {allExpanded() ? 'Collapse All' : 'Expand All'}
                        </button>
                    </div>
                    <For each={Object.entries(CLI_COMMANDS)}>
                        {([svcId, svc]) => {
                            const envKey = () => props.sdkEntries().find(e => SERVICE_META[e.key]?.id === svcId);
                            return (
                                <div class="env-service-card" style={{ "margin-bottom": "8px" }}>
                                    <div style={{ display: "flex", "align-items": "center", gap: "10px", cursor: "pointer" }}
                                        onClick={() => toggleService(svcId)}
                                        onKeyDown={onActivate(() => toggleService(svcId))}
                                        role="button"
                                        tabIndex="0"
                                        aria-expanded={expandedServices().has(svcId)}>
                                        <ChevronIcon open={expandedServices().has(svcId)} />
                                        <img src={`/icons/${svcId}.svg`} alt="" width="20" height="20" style={{ "object-fit": "contain" }} />
                                        <span class="env-service-name">{svc.label}</span>
                                    </div>
                                    <Show when={expandedServices().has(svcId)}>
                                        <div style={{ "margin-top": "8px" }}>
                                            <Show when={envKey()}>
                                                <CopyableCodeBlock style={{ "margin-bottom": "8px", "font-size": "11px", "line-height": "1.5", "padding": "8px 12px" }}>
                                                    {`export ${envKey().key}="${envKey().value}"`}
                                                </CopyableCodeBlock>
                                            </Show>
                                            <div class="code-block-header" style={{ "margin-bottom": "0" }}>
                                                <span style={{ "font-size": "11px", color: "var(--text-tertiary)" }}>Commands</span>
                                            </div>
                                            <CopyableCodeBlock style={{ "border-top-left-radius": "0", "border-top-right-radius": "0" }}>
                                                {svc.commands}
                                            </CopyableCodeBlock>
                                        </div>
                                    </Show>
                                </div>
                            );
                        }}
                    </For>
                </div>
            </Show>

            {/* SDK Tab */}
            <Show when={activeTab() === 'sdk'}>
                <div style={{ "margin-top": "4px" }}>
                    <p style={{ "margin-bottom": "12px", "font-size": "13px", color: "var(--text-secondary)" }}>
                        SDK code samples per service. Each card shows the env var export and sample code in your chosen language.
                    </p>

                    <div style={{ display: "flex", gap: "0", "margin-bottom": "16px", "border-bottom": "1px solid var(--border)" }}>
                        {[{ id: 'python', label: 'Python' }, { id: 'nodejs', label: 'Node.js' }, { id: 'go', label: 'Go' }, { id: 'java', label: 'Java' }].map(lang => (
                            <button
                                classList={{ "tab-item": true, "active": sdkLang() === lang.id }}
                                onClick={() => setSdkLang(lang.id)}
                            >
                                {lang.label}
                            </button>
                        ))}
                    </div>

                    <div style={{ display: "flex", "justify-content": "flex-end", "margin-bottom": "8px" }}>
                        <button class="btn btn-secondary" style={{ height: "28px", "font-size": "11px", padding: "0 10px" }} onClick={toggleAll}>
                            {allExpanded() ? 'Collapse All' : 'Expand All'}
                        </button>
                    </div>

                    <For each={sdkServices()}>
                        {(svc) => {
                            const snippet = () => SAMPLE_CODE[svc.id]?.[sdkLang()];
                            return (
                                <Show when={snippet()}>
                                    <div class="env-service-card" style={{ "margin-bottom": "8px" }}>
                                        <div style={{ display: "flex", "align-items": "center", gap: "10px", cursor: "pointer" }}
                                            onClick={() => toggleService(svc.id)}>
                                            <ChevronIcon open={expandedServices().has(svc.id)} />
                                            <img src={`/icons/${svc.id}.svg`} alt="" width="20" height="20" style={{ "object-fit": "contain" }} />
                                            <span class="env-service-name">{svc.label}</span>
                                        </div>
                                        <Show when={expandedServices().has(svc.id)}>
                                            <div style={{ "margin-top": "8px" }}>
                                                <CopyableCodeBlock style={{ "margin-bottom": "8px", "font-size": "11px", "line-height": "1.5", "padding": "8px 12px" }}>
                                                    {`export ${svc.envKey}="${svc.value}"`}
                                                </CopyableCodeBlock>
                                                <div class="code-block-header" style={{ "margin-bottom": "0" }}>
                                                    <span style={{ "font-size": "11px", color: "var(--text-tertiary)" }}>Sample Code</span>
                                                </div>
                                                <CopyableCodeBlock style={{ "border-top-left-radius": "0", "border-top-right-radius": "0" }}>
                                                    {snippet()}
                                                </CopyableCodeBlock>
                                            </div>
                                        </Show>
                                    </div>
                                </Show>
                            );
                        }}
                    </For>
                </div>
            </Show>

            {/* Examples Tab */}
            <Show when={activeTab() === 'examples'}>
                <div style={{ "margin-top": "4px" }}>
                    <p style={{ "margin-bottom": "16px", "font-size": "13px", color: "var(--text-secondary)" }}>
                        CREATE TABLE and schema examples for each database emulator. Covers supported operations and known emulator limitations.
                    </p>
                    <DatabaseExamples />
                </div>
            </Show>

            {/* Compatibility Tab */}
            <Show when={activeTab() === 'compatibility'}>
                <div style={{ "margin-top": "4px" }}>
                    <p style={{ "margin-bottom": "16px", "font-size": "13px", color: "var(--text-secondary)", "line-height": "1.5" }}>
                        Each service is continuously verified across SDKs, Terraform providers, gcloud CLI paths, and console surfaces.
                        A service graduates to <strong style="color:#34a853">supported</strong> only when all paths share one verified state source.
                    </p>

                    <Show when={!coverageData()}
                        fallback={() => {
                            const data = coverageData();
                            const services = data?.services || [];
                            const summary = data?.summary || {};
                            return (
                                <>
                                    <div style={{ display: "flex", gap: "12px", "margin-bottom": "20px", "flex-wrap": "wrap" }}>
                                        <For each={Object.entries(summary.by_coverage_status || {})}>
                                            {([status, count]) => {
                                                const c = STATUS_COLORS[status] || { bg: 'rgba(128,128,128,0.1)', fg: '#2408080', label: status };
                                                return (
                                                    <div style={{
                                                        padding: "6px 14px", "border-radius": "8px",
                                                        background: c.bg, color: c.fg,
                                                        "font-size": "13px", "font-weight": "600",
                                                        border: `1px solid ${c.fg}22`,
                                                    }}>
                                                        {c.label}: {count}
                                                    </div>
                                                );
                                            }}
                                        </For>
                                        <div style={{ "font-size": "12px", color: "var(--text-tertiary)", display: "flex", "align-items": "center", "margin-left": "auto" }}>
                                            {summary.total_services} services total
                                        </div>
                                    </div>

                                    <div style={{
                                        "margin-bottom": "16px", padding: "10px 14px",
                                        background: "var(--accent-soft, rgba(66,133,244,0.06))",
                                        border: "1px solid var(--border)", "border-radius": "8px",
                                        "font-size": "12px", color: "var(--text-secondary)", "line-height": "1.5",
                                    }}>
                                        <strong style={{ color: "var(--text)" }}>Contract:</strong> {summary.contract}
                                    </div>

                                    <div style={{ display: "flex", "flex-direction": "column", gap: "10px" }}>
                                        <For each={services}>
                                            {(svc) => {
                                                const statusStyle = STATUS_COLORS[svc.coverage_status] || STATUS_COLORS.unverified;
                                                const [expanded, setExpanded] = createSignal(false);
                                                const ops = svc.operations || [];
                                                const limitations = svc.limitations || [];
                                                const tf = svc.terraform_resources || {};
                                                const gcloudPaths = svc.gcloud_paths || [];
                                                const hasInfo = ops.length > 0 || limitations.length > 0 || tf.resources?.length > 0;
                                                return (
                                                    <div style={{
                                                        border: "1px solid var(--border)", "border-radius": "10px",
                                                        overflow: "hidden", background: "var(--surface)",
                                                    }}>
                                                        <div
                                                            onClick={() => hasInfo && setExpanded(!expanded())}
                                                            onKeyDown={onActivate(() => hasInfo && setExpanded(!expanded()))}
                                                            role="button"
                                                            tabIndex={0}
                                                            aria-expanded={expanded()}
                                                            style={{
                                                                display: "flex", "align-items": "center", gap: "10px",
                                                                padding: "10px 16px",
                                                                cursor: hasInfo ? "pointer" : "default",
                                                                "user-select": "none",
                                                            }}
                                                        >
                                                            {hasInfo && (
                                                                <svg width="12" height="12" viewBox="0 0 24 24" fill="var(--text-secondary)" aria-hidden="true" style={{ transition: "transform 0.15s", transform: expanded() ? "rotate(90deg)" : "rotate(0deg)", "flex-shrink": "0" }}><path d="M8 5v14l11-7z"/></svg>
                                                            )}
                                                            {!hasInfo && <span style={{ width: "12px", "flex-shrink": "0" }} />}
                                                            <img src={`/icons/${svc.service_id}.svg`} alt="" width="18" height="18" style={{ "flex-shrink": "0", opacity: "0.7" }} />
                                                            <span style={{ "font-size": "13px", "font-weight": "600", flex: "1", color: "var(--text)" }}>
                                                                {svc.display_name || svc.service_id}
                                                            </span>
                                                            <span style={{
                                                                "font-size": "11px", "font-weight": "700", "letter-spacing": "0.3px",
                                                                padding: "3px 10px", "border-radius": "6px",
                                                                background: statusStyle.bg, color: statusStyle.fg,
                                                            }}>
                                                                {statusStyle.label.toUpperCase()}
                                                            </span>
                                                            <span style={{
                                                                "font-size": "11px", color: "var(--text-tertiary)",
                                                                padding: "2px 8px", "border-radius": "4px",
                                                                background: "var(--surface-variant)",
                                                            }}>
                                                                {svc.ci_recommendation ? 'Has guidance' : ''}
                                                            </span>
                                                        </div>

                                                        <Show when={expanded()}>
                                                            <div style={{ "border-top": "1px solid var(--border)", padding: "14px 16px", display: "flex", "flex-direction": "column", gap: "14px" }}>
                                                                <Show when={svc.ci_recommendation}>
                                                                    <div>
                                                                        <div style={{ "font-size": "11px", "font-weight": "600", color: "var(--text-tertiary)", "margin-bottom": "4px", "text-transform": "uppercase", "letter-spacing": "0.5px" }}>CI Recommendation</div>
                                                                        <div style={{ "font-size": "12px", color: "var(--text-secondary)", "line-height": "1.5" }}>{svc.ci_recommendation}</div>
                                                                    </div>
                                                                </Show>
                                                                <Show when={ops.length > 0}>
                                                                    <div>
                                                                        <div style={{ "font-size": "11px", "font-weight": "600", color: "var(--text-tertiary)", "margin-bottom": "6px", "text-transform": "uppercase", "letter-spacing": "0.5px" }}>Operations</div>
                                                                        <div style={{ display: "flex", "flex-direction": "column", gap: "6px" }}>
                                                                            <For each={ops}>
                                                                                {(op) => {
                                                                                    const os = STATUS_COLORS[op.status] || STATUS_COLORS.unverified;
                                                                                    return (
                                                                                        <div style={{ display: "flex", "align-items": "flex-start", gap: "8px", "font-size": "12px" }}>
                                                                                            <span style={{
                                                                                                "font-size": "10px", "font-weight": "700", "letter-spacing": "0.3px",
                                                                                                padding: "1px 7px", "border-radius": "4px", "flex-shrink": "0",
                                                                                                background: os.bg, color: os.fg,
                                                                                            }}>{os.label.toUpperCase()}</span>
                                                                                            <div style={{ flex: "1" }}>
                                                                                                <div style={{ color: "var(--text)", "font-weight": "500" }}>{op.operation}</div>
                                                                                                <Show when={op.notes}><div style={{ color: "var(--text-tertiary)", "margin-top": "2px" }}>{op.notes}</div></Show>
                                                                                            </div>
                                                                                        </div>
                                                                                    );
                                                                                }}
                                                                            </For>
                                                                        </div>
                                                                    </div>
                                                                </Show>
                                                                <Show when={tf.resources?.length > 0 || tf.status}>
                                                                    <div>
                                                                        <div style={{ "font-size": "11px", "font-weight": "600", color: "var(--text-tertiary)", "margin-bottom": "6px", "text-transform": "uppercase", "letter-spacing": "0.5px" }}>Terraform</div>
                                                                        <div style={{ display: "flex", "align-items": "center", gap: "8px", "flex-wrap": "wrap" }}>
                                                                            <Show when={tf.status}>
                                                                                {(() => {
                                                                                    const ts = STATUS_COLORS[tf.status] || STATUS_COLORS.unverified;
                                                                                    return (
                                                                                        <span style={{
                                                                                            "font-size": "10px", "font-weight": "700", "letter-spacing": "0.3px",
                                                                                            padding: "2px 8px", "border-radius": "4px",
                                                                                            background: ts.bg, color: ts.fg,
                                                                                        }}>{ts.label.toUpperCase()}</span>
                                                                                    );
                                                                                })()}
                                                                            </Show>
                                                                            <For each={tf.resources || []}>
                                                                                {(r) => (
                                                                                    <code style={{ "font-size": "11px", padding: "2px 8px", "border-radius": "4px", background: "var(--surface-variant)", "font-family": "var(--font-mono)", color: "var(--text-secondary)" }}>{r}</code>
                                                                                )}
                                                                            </For>
                                                                        </div>
                                                                    </div>
                                                                </Show>
                                                                <Show when={gcloudPaths.length > 0}>
                                                                    <div>
                                                                        <div style={{ "font-size": "11px", "font-weight": "600", color: "var(--text-tertiary)", "margin-bottom": "4px", "text-transform": "uppercase", "letter-spacing": "0.5px" }}>gcloud CLI</div>
                                                                        <div style={{ display: "flex", gap: "6px", "flex-wrap": "wrap" }}>
                                                                            <For each={gcloudPaths}>
                                                                                {(p) => {
                                                                                    const ps = STATUS_COLORS[p.status] || STATUS_COLORS.unverified;
                                                                                    return (
                                                                                        <span style={{ "font-size": "11px", padding: "2px 8px", "border-radius": "4px", background: ps.bg, color: ps.fg, "font-weight": "500" }}>
                                                                                            {p.id} — {ps.label}
                                                                                        </span>
                                                                                    );
                                                                                }}
                                                                            </For>
                                                                        </div>
                                                                    </div>
                                                                </Show>
                                                                <Show when={limitations.length > 0}>
                                                                    <div>
                                                                        <div style={{ "font-size": "11px", "font-weight": "600", color: "var(--text-tertiary)", "margin-bottom": "4px", "text-transform": "uppercase", "letter-spacing": "0.5px" }}>Limitations</div>
                                                                        <ul style={{ margin: "0", "padding-left": "18px", display: "flex", "flex-direction": "column", gap: "3px" }}>
                                                                            <For each={limitations}>
                                                                                {(l) => <li style={{ "font-size": "12px", color: "var(--text-secondary)", "line-height": "1.4" }}>{l}</li>}
                                                                            </For>
                                                                        </ul>
                                                                    </div>
                                                                </Show>
                                                            </div>
                                                        </Show>
                                                    </div>
                                                );
                                            }}
                                        </For>
                                    </div>
                                </>
                            );
                        }}
                    >
                        <Show when={coverageError()}>
                            <div class="alert alert-error" role="alert" style={{ "margin-bottom": "12px" }}>
                                {coverageError()}
                            </div>
                            <button class="btn btn-secondary" style={{ "font-size": "12px", padding: "4px 14px" }} onClick={fetchCoverageData}>
                                Retry
                            </button>
                        </Show>
                        <Show when={!coverageData() && !coverageError()}>
                            <div style={{ display: "flex", "align-items": "center", "justify-content": "center", padding: "60px 0" }}>
                                <div class="loading-spinner" style={{ width: "20px", height: "20px", "border-width": "2px" }} />
                                <span style={{ "margin-left": "12px", color: "var(--text-secondary)", "font-size": "14px" }}>Loading compatibility data…</span>
                            </div>
                        </Show>
                    </Show>
                </div>
            </Show>
        </div>
    );
}

// --- Database Examples Component ---
function DatabaseExamples() {
    const [activeDb, setActiveDb] = createUrlBackedTab('db', Object.keys(DATABASE_EXAMPLES), 'spanner', { history: 'replace' });
    const [copiedIdx, setCopiedIdx] = createSignal(null);
    const [expandedExamples, setExpandedExamples] = createSignal(new Set([0]));

    const toggleExample = (idx) => {
        const s = new Set(expandedExamples());
        if (s.has(idx)) s.delete(idx); else s.add(idx);
        setExpandedExamples(s);
    };

    const expandAll = () => {
        const db = DATABASE_EXAMPLES[activeDb()];
        if (!db) return;
        const all = new Set(db.examples.map((_, i) => i));
        setExpandedExamples(all);
    };

    const collapseAll = () => setExpandedExamples(new Set());

    const handleCopy = async (idx, sql) => {
        try {
            await navigator.clipboard.writeText(sql);
            setCopiedIdx(idx);
            setTimeout(() => setCopiedIdx(null), 2000);
        } catch {}
    };

    createEffect((prev) => {
        const db = activeDb();
        if (prev && prev !== db) setExpandedExamples(new Set([0]));
        return db;
    });

    return (
        <div>
            <div style={{ display: "flex", gap: "6px", "flex-wrap": "wrap", "margin-bottom": "16px" }}>
                <For each={Object.entries(DATABASE_EXAMPLES)}>
                    {([id, db]) => (
                        <button
                            classList={{ "env-sample-tab": true, "active": activeDb() === id }}
                            onClick={() => setActiveDb(id)}
                            style={{ "border-radius": "6px", padding: "5px 14px", "font-size": "12px", "font-weight": "500", border: "1px solid var(--border)", cursor: "pointer", background: activeDb() === id ? "var(--primary)" : "var(--surface)", color: activeDb() === id ? "#fff" : "var(--text-primary)", transition: "background 0.15s, border-color 0.15s, color 0.15s" }}
                        >
                            {db.label}
                        </button>
                    )}
                </For>
            </div>

            <Show when={DATABASE_EXAMPLES[activeDb()]}>
                {(_) => {
                    const db = () => DATABASE_EXAMPLES[activeDb()];
                    return (
                        <div>
                            <div style={{ display: "flex", "align-items": "center", "justify-content": "space-between", "margin-bottom": "12px" }}>
                                <span style={{ "font-size": "11px", "font-weight": "600", "letter-spacing": "0.5px", padding: "3px 10px", "border-radius": "4px", background: "var(--surface-variant, var(--bg-subtle))", color: "var(--text-secondary)" }}>
                                    {db().dialect}
                                </span>
                                <div style={{ display: "flex", gap: "6px" }}>
                                    <button class="btn btn-secondary" style={{ height: "26px", "font-size": "11px", padding: "0 10px" }} onClick={expandAll}>Expand All</button>
                                    <button class="btn btn-secondary" style={{ height: "26px", "font-size": "11px", padding: "0 10px" }} onClick={collapseAll}>Collapse All</button>
                                </div>
                            </div>

                            <div style={{ display: "flex", "flex-direction": "column", gap: "8px" }}>
                                <For each={db().examples}>
                                    {(example, idx) => (
                                        <div style={{ border: "1px solid var(--border)", "border-radius": "8px", overflow: "hidden", background: "var(--surface)" }}>
                                            <div
                                                onClick={() => toggleExample(idx())}
                                                onKeyDown={onActivate(() => toggleExample(idx()))}
                                                role="button"
                                                tabIndex="0"
                                                aria-expanded={expandedExamples().has(idx())}
                                                style={{ display: "flex", "align-items": "center", gap: "8px", padding: "10px 14px", cursor: "pointer", "user-select": "none", background: !example.supported ? "rgba(234,67,53,0.04)" : "transparent" }}
                                            >
                                                <svg width="12" height="12" viewBox="0 0 24 24" fill="var(--text-secondary)" aria-hidden="true" focusable="false" style={{ transition: "transform 0.15s", transform: expandedExamples().has(idx()) ? "rotate(90deg)" : "rotate(0deg)", "flex-shrink": "0" }}>
                                                    <path d="M8 5v14l11-7z"/>
                                                </svg>
                                                <span style={{ "font-size": "13px", "font-weight": "600", flex: "1" }}>{example.title}</span>
                                                <span style={{
                                                    "font-size": "10px", "font-weight": "700", "letter-spacing": "0.5px",
                                                    padding: "2px 8px", "border-radius": "4px",
                                                    background: example.supported ? "rgba(52,168,83,0.1)" : "rgba(234,67,53,0.1)",
                                                    color: example.supported ? "#34a853" : "#ea4335",
                                                }}>
                                                    {example.supported ? 'SUPPORTED' : 'UNSUPPORTED'}
                                                </span>
                                            </div>
                                            <Show when={expandedExamples().has(idx())}>
                                                <div style={{ "border-top": "1px solid var(--border)" }}>
                                                    <Show when={!example.supported && example.note}>
                                                        <div style={{ padding: "8px 14px", "font-size": "12px", color: "#ea4335", background: "rgba(234,67,53,0.04)", "border-bottom": "1px solid var(--border)", display: "flex", gap: "6px", "align-items": "flex-start" }}>
                                                            <svg width="14" height="14" viewBox="0 0 24 24" fill="#ea4335" aria-hidden="true" focusable="false" style={{ "flex-shrink": "0", "margin-top": "1px" }}><path d="M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z"/></svg>
                                                            <span>{example.note}</span>
                                                        </div>
                                                    </Show>
                                                    <div style={{ position: "relative" }}>
                                                        <button
                                                            class="btn btn-secondary"
                                                            style={{ position: "absolute", top: "8px", right: "8px", height: "26px", "font-size": "10px", padding: "0 8px", "z-index": "1" }}
                                                            onClick={(e) => { e.stopPropagation(); handleCopy(idx(), example.sql); }}
                                                        >
                                                            {copiedIdx() === idx() ? 'Copied!' : 'Copy'}
                                                        </button>
                                                        <pre style={{ margin: "0", padding: "12px 14px", "font-size": "12px", "line-height": "1.5", overflow: "auto", "max-height": "400px", background: "var(--code-bg, var(--bg-subtle))", "font-family": "'SF Mono', 'Fira Code', 'Cascadia Code', monospace", color: "var(--text-primary)", "white-space": "pre", "tab-size": "2" }}>
                                                            {example.sql}
                                                        </pre>
                                                    </div>
                                                </div>
                                            </Show>
                                        </div>
                                    )}
                                </For>
                            </div>
                        </div>
                    );
                }}
            </Show>
        </div>
    );
}

// --- Guide content helpers ---
const GuideSection = (p) => (
    <div style={{ "margin-bottom": "24px" }}>
        <h3 style={{ "font-size": "14px", "font-weight": "500", "margin-bottom": "8px", color: "var(--text)" }}>{p.title}</h3>
        {p.children}
    </div>
);
const GuideText = (p) => (
    <p style={{ "font-size": "13px", color: "var(--text-secondary)", "margin-bottom": "10px", "line-height": "1.6" }}>{p.children}</p>
);

// ===== MAIN USER GUIDE PAGE =====
export default function UserGuide(props) {
    const USER_GUIDE_TABS = [
        { id: 'quickstart', label: 'Quick Start' },
        { id: 'cli', label: 'CLI' },
        { id: 'sdk', label: 'SDK' },
        { id: 'examples', label: 'Examples' },
        { id: 'compatibility', label: 'Compatibility' },
        { id: 'revert', label: 'Revert to GCP' },
        { id: 'seed', label: 'Seed Data' },
        { id: 'api', label: 'Admin API' },
    ];
    const USER_GUIDE_TAB_IDS = USER_GUIDE_TABS.map(tab => tab.id);
    const [activeTab, setActiveTab] = createUrlBackedTab('tab', USER_GUIDE_TAB_IDS, 'quickstart', { history: 'replace' });
    // Guide sub-tabs
    const [guideTab, setGuideTab] = createSignal('quickstart');

    // --- Environment tab state ---
    const [envVars, setEnvVars] = createSignal(null);
    const [envLoading, setEnvLoading] = createSignal(false);
    const [envError, setEnvError] = createSignal(null);
    const [envVarsExpanded, setEnvVarsExpanded] = createSignal(false);
    const [quickCopied, setQuickCopied] = createSignal(false);

    const fetchEnv = async () => {
        setEnvLoading(true);
        setEnvError(null);
        try {
            const data = await api.env();
            setEnvVars(data);
        } catch (err) {
            setEnvError('Failed to load environment variables: ' + err.message);
        } finally {
            setEnvLoading(false);
        }
    };

    fetchEnv();

    const categorized = () => {
        const vars = envVars();
        if (!vars) return null;
        return categorizeEnvVars(vars);
    };

    const sdkEntries = () => {
        const cat = categorized();
        if (!cat) return [];
        return SDK_ORDER
            .filter(k => k in cat.sdk)
            .map(k => ({ key: k, value: cat.sdk[k] }));
    };

    const autoConfigCmd = `eval "$(curl -s http://localhost:24080/env?format=shell)"`;

    const handleQuickCopy = async () => {
        const ok = await copyToClipboard(autoConfigCmd);
        if (ok) {
            setQuickCopied(true);
            setTimeout(() => setQuickCopied(false), 2000);
        }
    };

    return (
        <div>
            <div class="page-header">
                <h1>User Guide</h1>
            </div>

            {/* Top-level tab bar */}
            <div class="tab-bar" style={{ "margin-bottom": "24px" }}>
                <For each={USER_GUIDE_TABS}>
                    {(tab) => (
                        <button
                            classList={{ "tab-item": true, "active": activeTab() === tab.id }}
                            onClick={() => setActiveTab(tab.id)}
                        >
                            {tab.label}
                        </button>
                    )}
                </For>
            </div>

            {/* ═══════ Quick Start ═══════ */}
            <Show when={activeTab() === 'quickstart'}>
                <div class="section">
                    <GuideSection title="Step 1: Configure your shell">
                        <GuideText>Choose <strong>one</strong> method — both set the same environment variables. You only need to run <strong>one</strong> of these:</GuideText>
                        <div style={{ display: "flex", gap: "0", "align-items": "stretch", "margin-bottom": "4px" }}>
                            {/* Option 1: eval one-liner */}
                            <div style={{ flex: "1", "min-width": "0", display: "flex", "flex-direction": "column" }}>
                                <span style={{ "font-size": "12px", "font-weight": "600", "margin-bottom": "8px", color: "var(--text-secondary)" }}>
                                    Option 1 — One-liner eval
                                </span>
                                <div style={{ flex: "1" }}>
                                    <CopyableCodeBlock>{`eval "$(curl -s 'http://localhost:24080/env?format=shell')"`}</CopyableCodeBlock>
                                </div>
                            </div>
                            {/* OR divider */}
                            <div style={{ display: "flex", "align-items": "center", "justify-content": "center", padding: "0 20px", "flex-shrink": "0" }}>
                                <span style={{ "font-size": "11px", "font-weight": "700", color: "var(--text-tertiary)", "text-transform": "uppercase", "letter-spacing": "1.5px", "white-space": "nowrap" }}>
                                    — or —
                                </span>
                            </div>
                            {/* Option 2: all env vars by category */}
                            <div style={{ flex: "1", "min-width": "0", display: "flex", "flex-direction": "column" }}>
                                <span style={{ "font-size": "12px", "font-weight": "600", "margin-bottom": "8px", color: "var(--text-secondary)" }}>
                                    Option 2 — Export all env vars
                                </span>
                                <div style={{ flex: "1", display: "flex", "flex-direction": "column", "min-height": "0" }}>
                                    <Show when={categorized()} fallback={
                                        <div style={{ padding: "24px", "font-size": "13px", color: "var(--text-tertiary)", "text-align": "center", border: "1px solid var(--border)", "border-radius": "6px", background: "var(--surface)" }}>
                                            Fetching variables from LocalCloud…
                                        </div>
                                    }>
                                        <div style={{
                                            flex: "1",
                                            maxHeight: envVarsExpanded() ? "none" : "120px",
                                            overflowY: "auto",
                                            minHeight: "0"
                                        }}>
                                            <Show when={Object.keys(categorized().common).length > 0}>
                                                <div class="code-block-header" style={{ "margin-bottom": "4px" }}>
                                                    <span style={{ "font-size": "11px", "font-weight": "600", color: "var(--text-secondary)" }}>Project & Auth Setup</span>
                                                </div>
                                                <p style={{ "margin-top": "0", "margin-bottom": "6px", "font-size": "11px", color: "var(--text-tertiary)" }}>
                                                    Required for all services.
                                                </p>
                                                <CopyableCodeBlock style={{ "margin-bottom": "12px" }}>
                                                    {Object.entries(categorized().common).map(([k, v]) => `export ${k}="${v}"`).join('\n')}
                                                </CopyableCodeBlock>
                                            </Show>
                                            <Show when={Object.keys(categorized().sdk).length > 0}>
                                                <div class="code-block-header">
                                                    <span style={{ "font-size": "11px", "font-weight": "600", color: "var(--text-secondary)" }}>SDK Emulator Hosts</span>
                                                </div>
                                                <CopyableCodeBlock style={{ "margin-bottom": "12px" }}>
                                                    {Object.entries(categorized().sdk).map(([k, v]) => `export ${k}="${v}"`).join('\n')}
                                                </CopyableCodeBlock>
                                            </Show>
                                            <Show when={Object.keys(categorized().gcloud).length > 0}>
                                                <div class="code-block-header">
                                                    <span style={{ "font-size": "11px", "font-weight": "600", color: "var(--text-secondary)" }}>gcloud CLI Overrides</span>
                                                </div>
                                                <CopyableCodeBlock>
                                                    {Object.entries(categorized().gcloud).map(([k, v]) => `export ${k}="${v}"`).join('\n')}
                                                </CopyableCodeBlock>
                                            </Show>
                                        </div>
                                        {/*<button*/}
                                        {/*    class="btn btn-secondary"*/}
                                        {/*    style={{ "margin-top": "8px", "font-size": "11px", padding: "4px 12px", "align-self": "flex-start", "flex-shrink": "0" }}*/}
                                        {/*    onClick={() => setEnvVarsExpanded(!envVarsExpanded())}*/}
                                        {/*>*/}
                                        {/*    {envVarsExpanded() ? 'Collapse' : 'Show all sections'}*/}
                                        {/*</button>*/}
                                    </Show>
                                </div>
                            </div>
                        </div>
                    </GuideSection>
                    <GuideSection title="Step 2: Test it">
                        <GuideText>Verify your setup with a quick test:</GuideText>
                        <CopyableCodeBlock>{`echo "Hello, LocalCloud!" > /tmp/test.txt && \\
gsutil mb gs://quick-test && \\
gsutil cp /tmp/test.txt gs://quick-test/ && \\
gsutil ls gs://quick-test/`}</CopyableCodeBlock>
                    </GuideSection>
                </div>
            </Show>
            
            {/* ═══════ Revert to GCP ═══════ */}
            <Show when={activeTab() === 'revert'}>
                <div class="section">
                    <GuideSection title="Switch back to real GCP">
                        <GuideText>To stop using LocalCloud and point your SDKs back to real Google Cloud, unset all emulator environment variables:</GuideText>
                        <CopyableCodeBlock>{`unset STORAGE_EMULATOR_HOST PUBSUB_EMULATOR_HOST \\
  FIRESTORE_EMULATOR_HOST BIGTABLE_EMULATOR_HOST \\
  SPANNER_EMULATOR_HOST BIGQUERY_EMULATOR_HOST \\
  SECRET_MANAGER_EMULATOR_HOST CLOUD_TASKS_EMULATOR_HOST \\
  CLOUD_LOGGING_EMULATOR_HOST CLOUD_MONITORING_EMULATOR_HOST \\
  REDIS_HOST`}</CopyableCodeBlock>
                    </GuideSection>
                    <GuideSection title="Revert gcloud CLI">
                        <GuideText>To revert gcloud CLI overrides:</GuideText>
                        <CopyableCodeBlock>{`unset CLOUDSDK_CORE_PROJECT
unset CLOUDSDK_AUTH_ACCESS_TOKEN
unset CLOUDSDK_API_ENDPOINT_OVERRIDES_PUBSUB
unset CLOUDSDK_API_ENDPOINT_OVERRIDES_SPANNER
  # … or simply open a new terminal session`}</CopyableCodeBlock>
                        <GuideText>Opening a new terminal window is the simplest way to revert, since all LocalCloud variables are session-scoped.</GuideText>
                    </GuideSection>
                    <GuideSection title="Zero code changes">
                        <GuideText>Your application code does not need any changes to switch between LocalCloud and real GCP. The SDKs automatically detect the emulator environment variables and route traffic accordingly. Remove the variables, and traffic goes to real GCP.</GuideText>
                    </GuideSection>
                </div>
            </Show>

            {/* ═══════ Seed Data ═══════ */}
            <Show when={activeTab() === 'seed'}>
                <div class="section">
                    <GuideSection title="Loading seed data">
                        <GuideText>Seed files define initial state for services using YAML. Load them on startup or into a running instance:</GuideText>
                        <CopyableCodeBlock>{`# Load into a running instance
curl -X POST http://localhost:24080/seed \\
  -H "Content-Type: application/yaml" --data-binary @seed.yaml

# Or mount on startup (docker-compose.yml)
volumes:
  - ./seed.yaml:/etc/localcloud/seed.yaml:ro`}</CopyableCodeBlock>
                    </GuideSection>
                    <GuideSection title="Seed file format">
                        <CopyableCodeBlock>{`version: "1.0"
project: "local-project"

services:
  gcs:
    buckets:
      - name: "my-bucket"
        objects:
          - key: "config.json"
            content: '{"debug": true}'
  secretmanager:
    secrets:
      - name: "api-key"
        value: "my-secret-value"
  pubsub:
    topics:
      - name: "events"
        subscriptions:
          - name: "worker"`}</CopyableCodeBlock>
                    </GuideSection>
                    <GuideSection title="Reset data">
                        <GuideText>Reset all emulator data or restore the last loaded seed:</GuideText>
                        <CopyableCodeBlock>{`# Reset all data
curl -X POST http://localhost:24080/reset

# Reset and restore last seed
curl -X POST http://localhost:24080/reset \\
  -H "Content-Type: application/json" \\
  -d '{"restore_seed": true}'`}</CopyableCodeBlock>
                    </GuideSection>
                </div>
            </Show>

            {/* ═══════ Admin API ═══════ */}
            <Show when={activeTab() === 'api'}>
                <div class="section">
                    <GuideSection title="Admin API endpoints">
                        <GuideText>The gateway at port 24080 exposes admin endpoints for managing LocalCloud:</GuideText>
                        <div class="data-table-wrapper" style={{ "margin-bottom": "16px" }}>
                            <table class="data-table">
                                <thead><tr><th>Method</th><th>Path</th><th>Description</th></tr></thead>
                                <tbody>
                                    <tr><td>GET</td><td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>/health</td><td>Health status of all services</td></tr>
                                    <tr><td>GET</td><td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>/services</td><td>List services with ports and status</td></tr>
                                    <tr><td>GET</td><td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>/env?format=shell</td><td>Environment variables</td></tr>
                                    <tr><td>GET</td><td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>/projects</td><td>List all projects</td></tr>
                                    <tr><td>POST</td><td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>/projects</td><td>Create a project</td></tr>
                                    <tr><td>GET</td><td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>/browse/{'{'} service {'}'}</td><td>Browse service data</td></tr>
                                    <tr><td>POST</td><td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>/mutate/secretmanager/secrets</td><td>Create or delete a secret</td></tr>
                                    <tr><td>POST</td><td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>/mutate/secretmanager/versions/add</td><td>Add a new version to a secret</td></tr>
                                    <tr><td>POST</td><td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>/seed</td><td>Load seed data (YAML)</td></tr>
                                    <tr><td>POST</td><td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>/reset</td><td>Reset all data</td></tr>
                                    <tr><td>GET</td><td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>/requests</td><td>Recent request log</td></tr>
                                </tbody>
                            </table>
                        </div>
                    </GuideSection>
                    <GuideSection title="Secret Manager browse API">
                        <GuideText>Browse endpoints for inspecting secrets and their versions:</GuideText>
                        <CopyableCodeBlock>{`# List all secrets (with version counts)
curl http://localhost:24080/browse/secretmanager

# List versions for a specific secret
curl http://localhost:24080/browse/secretmanager/versions/api-key

# View a specific version's value
curl http://localhost:24080/browse/secretmanager/versions/api-key/3

# Get stats (counts by state)
curl http://localhost:24080/browse/secretmanager/stats`}</CopyableCodeBlock>
                    </GuideSection>
                    <GuideSection title="Project-scoped queries">
                        <GuideText>Add ?project= to browse, env, and reset endpoints to scope to a specific project:</GuideText>
                        <CopyableCodeBlock>{`# Browse secrets for a specific project
curl http://localhost:24080/browse/secretmanager?project=staging

# Get env vars for a specific project
curl "http://localhost:24080/env?format=json&project=dev"

# Reset only one project's data
curl -X POST http://localhost:24080/reset?project=staging`}</CopyableCodeBlock>
                    </GuideSection>
                </div>
            </Show>

            {/* ═══════ CLI / SDK / Examples / Compatibility ═══════ */}
            <Show when={['cli','sdk','examples','compatibility'].includes(activeTab())}>
                <div class="section">
                    <Show when={envError()}>
                        <div class="alert alert-error" role="alert">{envError()}</div>
                    </Show>
                    <Show when={envLoading()}>
                        <div class="loading-state"><div class="loading-spinner" /> Loading…</div>
                    </Show>
                    <Show when={categorized()}>
                        <EnvTabs tab={activeTab()} categorized={categorized} sdkEntries={sdkEntries} envVars={envVars} />
                    </Show>
                </div>
            </Show>
        </div>
    );
}

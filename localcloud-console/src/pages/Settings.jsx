import { createSignal, createEffect, Show, For } from 'solid-js';
import { api } from '../api.js';
import { SERVICE_META, SDK_ORDER, SAMPLE_CODE, CLI_COMMANDS, DOCKER_RUN_PORTS, DATABASE_EXAMPLES } from './settings-data.js';

// --- SVG Icons ---
const CopyIcon = () => (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
);
const CheckIcon = () => (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
);
const ChevronIcon = (props) => (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"
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
        if (k === 'GOOGLE_CLOUD_PROJECT' || k === 'GCLOUD_PROJECT') {
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
            <button class="env-var-copy" onClick={handleCopy} title="Copy to clipboard">
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
                <button class="env-sample-toggle" onClick={() => setExpanded(!expanded())}>
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

// --- EnvTabs component (Shell / CLI / SDK) ---
function EnvTabs(props) {
    const [activeTab, setActiveTab] = createSignal('shell');
    const [copiedBlock, setCopiedBlock] = createSignal(null);
    const [sdkLang, setSdkLang] = createSignal('python');
    const [expandedServices, setExpandedServices] = createSignal(new Set());
    const [allExpanded, setAllExpanded] = createSignal(false);

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

    // Build export blocks
    const shellBlock = (vars, comment) => {
        const lines = Object.entries(vars).map(([k, v]) => `export ${k}="${v}"`);
        return comment ? `# ${comment}\n${lines.join('\n')}` : lines.join('\n');
    };

    const allExports = () => {
        const cat = props.categorized();
        if (!cat) return '';
        const parts = [];
        if (Object.keys(cat.common).length > 0) parts.push(shellBlock(cat.common, 'Project'));
        if (Object.keys(cat.sdk).length > 0) parts.push(shellBlock(cat.sdk, 'SDK emulator hosts'));
        if (Object.keys(cat.gcloud).length > 0) parts.push(shellBlock(cat.gcloud, 'gcloud CLI overrides'));
        return parts.join('\n\n');
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
            <div class="tab-bar">
                <button classList={{ "tab-item": true, "active": activeTab() === 'shell' }} onClick={() => setActiveTab('shell')}>
                    Shell Export
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
            </div>

            {/* Shell Export Tab */}
            <Show when={activeTab() === 'shell'}>
                <div style={{ "margin-top": "4px" }}>
                    <p style={{ "margin-bottom": "12px", "font-size": "13px", color: "var(--text-secondary)" }}>
                        Copy and paste these export statements into your terminal. All Google Cloud SDKs will route to LocalCloud.
                    </p>

                    {/* All-in-one block */}
                    <div class="code-block-header">
                        <span style={{ "font-size": "12px", "font-weight": "600", color: "var(--text-secondary)" }}>All Variables</span>
                        <button class="btn btn-secondary" style={{ height: "28px", "font-size": "11px", padding: "0 10px" }}
                            onClick={() => handleCopyBlock('all', allExports())}>
                            <Show when={copiedBlock() === 'all'} fallback={<><CopyIcon /> Copy All</>}>
                                <CheckIcon /> Copied!
                            </Show>
                        </button>
                    </div>
                    <CopyableCodeBlock style={{ "margin-bottom": "16px" }}>
                        {allExports()}
                    </CopyableCodeBlock>

                    {/* Per-category blocks */}
                    <Show when={Object.keys(props.categorized().common).length > 0}>
                        <div class="code-block-header">
                            <span style={{ "font-size": "12px", "font-weight": "600", color: "var(--text-secondary)" }}>Project</span>
                            <button class="btn btn-secondary" style={{ height: "28px", "font-size": "11px", padding: "0 10px" }}
                                onClick={() => handleCopyBlock('common', shellBlock(props.categorized().common))}>
                                <Show when={copiedBlock() === 'common'} fallback={<><CopyIcon /> Copy</>}>
                                    <CheckIcon /> Copied!
                                </Show>
                            </button>
                        </div>
                        <CopyableCodeBlock style={{ "margin-bottom": "16px" }}>
                            {shellBlock(props.categorized().common)}
                        </CopyableCodeBlock>
                    </Show>

                    <Show when={Object.keys(props.categorized().sdk).length > 0}>
                        <div class="code-block-header">
                            <span style={{ "font-size": "12px", "font-weight": "600", color: "var(--text-secondary)" }}>SDK Emulator Hosts</span>
                            <button class="btn btn-secondary" style={{ height: "28px", "font-size": "11px", padding: "0 10px" }}
                                onClick={() => handleCopyBlock('sdk', shellBlock(props.categorized().sdk))}>
                                <Show when={copiedBlock() === 'sdk'} fallback={<><CopyIcon /> Copy</>}>
                                    <CheckIcon /> Copied!
                                </Show>
                            </button>
                        </div>
                        <CopyableCodeBlock style={{ "margin-bottom": "16px" }}>
                            {shellBlock(props.categorized().sdk)}
                        </CopyableCodeBlock>
                    </Show>

                    <Show when={Object.keys(props.categorized().gcloud).length > 0}>
                        <div class="code-block-header">
                            <span style={{ "font-size": "12px", "font-weight": "600", color: "var(--text-secondary)" }}>gcloud CLI Overrides</span>
                            <button class="btn btn-secondary" style={{ height: "28px", "font-size": "11px", padding: "0 10px" }}
                                onClick={() => handleCopyBlock('gcloud', shellBlock(props.categorized().gcloud))}>
                                <Show when={copiedBlock() === 'gcloud'} fallback={<><CopyIcon /> Copy</>}>
                                    <CheckIcon /> Copied!
                                </Show>
                            </button>
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
                                        onClick={() => toggleService(svcId)}>
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
                                                <button class="btn btn-secondary" style={{ height: "24px", "font-size": "10px", padding: "0 8px" }}
                                                    onClick={() => handleCopyBlock(`cli-${svcId}`, svc.commands)}>
                                                    <Show when={copiedBlock() === `cli-${svcId}`} fallback={<><CopyIcon /> Copy</>}>
                                                        <CheckIcon /> Copied!
                                                    </Show>
                                                </button>
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

                    {/* Language selector */}
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
                                                    <button class="btn btn-secondary" style={{ height: "24px", "font-size": "10px", padding: "0 8px" }}
                                                        onClick={() => handleCopyBlock(`sdk-${svc.id}`, `export ${svc.envKey}="${svc.value}"\n\n${snippet()}`)}>
                                                        <Show when={copiedBlock() === `sdk-${svc.id}`} fallback={<><CopyIcon /> Copy</>}>
                                                            <CheckIcon /> Copied!
                                                        </Show>
                                                    </button>
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

            {/* Examples Tab — Database DDL examples per emulator */}
            <Show when={activeTab() === 'examples'}>
                <div style={{ "margin-top": "4px" }}>
                    <p style={{ "margin-bottom": "16px", "font-size": "13px", color: "var(--text-secondary)" }}>
                        CREATE TABLE and schema examples for each database emulator. Covers supported operations and known emulator limitations.
                    </p>
                    <DatabaseExamples />
                </div>
            </Show>
        </div>
    );
}

// --- Database Examples Component ---
function DatabaseExamples() {
    const [activeDb, setActiveDb] = createSignal('spanner');
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

    // Reset expanded state when switching databases
    createEffect((prev) => {
        const db = activeDb();
        if (prev && prev !== db) setExpandedExamples(new Set([0]));
        return db;
    });

    return (
        <div>
            {/* Database selector pills */}
            <div style={{ display: "flex", gap: "6px", "flex-wrap": "wrap", "margin-bottom": "16px" }}>
                <For each={Object.entries(DATABASE_EXAMPLES)}>
                    {([id, db]) => (
                        <button
                            classList={{ "env-sample-tab": true, "active": activeDb() === id }}
                            onClick={() => setActiveDb(id)}
                            style={{ "border-radius": "6px", padding: "5px 14px", "font-size": "12px", "font-weight": "500", border: "1px solid var(--border)", cursor: "pointer", background: activeDb() === id ? "var(--primary)" : "var(--surface)", color: activeDb() === id ? "#fff" : "var(--text-primary)", transition: "all 0.15s" }}
                        >
                            {db.label}
                        </button>
                    )}
                </For>
            </div>

            {/* Dialect badge + expand/collapse */}
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

                            {/* Example cards */}
                            <div style={{ display: "flex", "flex-direction": "column", gap: "8px" }}>
                                <For each={db().examples}>
                                    {(example, idx) => (
                                        <div style={{ border: "1px solid var(--border)", "border-radius": "8px", overflow: "hidden", background: "var(--surface)" }}>
                                            {/* Header */}
                                            <div
                                                onClick={() => toggleExample(idx())}
                                                style={{ display: "flex", "align-items": "center", gap: "8px", padding: "10px 14px", cursor: "pointer", "user-select": "none", background: !example.supported ? "rgba(234,67,53,0.04)" : "transparent" }}
                                            >
                                                <svg width="12" height="12" viewBox="0 0 24 24" fill="var(--text-secondary)" style={{ transition: "transform 0.15s", transform: expandedExamples().has(idx()) ? "rotate(90deg)" : "rotate(0deg)", "flex-shrink": "0" }}>
                                                    <path d="M8 5v14l11-7z"/>
                                                </svg>
                                                <span style={{ "font-size": "13px", "font-weight": "600", flex: "1" }}>{example.title}</span>
                                                <span style={{
                                                    "font-size": "10px",
                                                    "font-weight": "700",
                                                    "letter-spacing": "0.5px",
                                                    padding: "2px 8px",
                                                    "border-radius": "4px",
                                                    background: example.supported ? "rgba(52,168,83,0.1)" : "rgba(234,67,53,0.1)",
                                                    color: example.supported ? "#34a853" : "#ea4335",
                                                }}>
                                                    {example.supported ? 'SUPPORTED' : 'UNSUPPORTED'}
                                                </span>
                                            </div>

                                            {/* Body */}
                                            <Show when={expandedExamples().has(idx())}>
                                                <div style={{ "border-top": "1px solid var(--border)" }}>
                                                    <Show when={!example.supported && example.note}>
                                                        <div style={{ padding: "8px 14px", "font-size": "12px", color: "#ea4335", background: "rgba(234,67,53,0.04)", "border-bottom": "1px solid var(--border)", display: "flex", gap: "6px", "align-items": "flex-start" }}>
                                                            <svg width="14" height="14" viewBox="0 0 24 24" fill="#ea4335" style={{ "flex-shrink": "0", "margin-top": "1px" }}><path d="M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z"/></svg>
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

// --- User Guide Modal ---
function UserGuideModal(props) {
    const [activeTab, setActiveTab] = createSignal('quickstart');

    const tabs = [
        { id: 'quickstart', label: 'Quick Start' },
        { id: 'sdk', label: 'SDK Setup' },
        { id: 'gcloud', label: 'gcloud CLI' },
        { id: 'revert', label: 'Revert to GCP' },
        { id: 'seed', label: 'Seed Data' },
        { id: 'api', label: 'Admin API' },
    ];

    const Section = (p) => (
        <div style={{ "margin-bottom": "24px" }}>
            <h3 style={{ "font-size": "14px", "font-weight": "500", "margin-bottom": "8px", color: "var(--text)" }}>{p.title}</h3>
            {p.children}
        </div>
    );

    const Text = (p) => (
        <p style={{ "font-size": "13px", color: "var(--text-secondary)", "margin-bottom": "10px", "line-height": "1.6" }}>
            {p.children}
        </p>
    );

    return (
        <div style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,0.5)", "z-index": 300, display: "flex", "align-items": "stretch", "justify-content": "center", "padding": "24px" }}
            onClick={(e) => { if (e.target === e.currentTarget) props.onClose(); }}>
            <div style={{ background: "var(--surface)", "border-radius": "var(--radius)", width: "100%", "max-width": "860px", display: "flex", "flex-direction": "column", overflow: "hidden", "box-shadow": "var(--shadow-hover)" }}
                onClick={(e) => e.stopPropagation()}>
                {/* Header */}
                <div style={{ display: "flex", "align-items": "center", "justify-content": "space-between", padding: "16px 24px", "border-bottom": "1px solid var(--border)" }}>
                    <h2 style={{ margin: 0, "font-size": "18px", "font-weight": "400" }}>User Guide</h2>
                    <button class="btn btn-icon" onClick={props.onClose} style={{ "font-size": "20px" }}>
                        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                    </button>
                </div>

                {/* Tab bar */}
                <div class="tab-bar" style={{ padding: "0 24px", "margin-bottom": "0" }}>
                    <For each={tabs}>
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

                {/* Content */}
                <div style={{ flex: 1, overflow: "auto", padding: "24px" }}>
                    <Show when={activeTab() === 'quickstart'}>
                        <Section title="1. Start LocalCloud">
                            <Text>Start all emulated GCP services with a single command:</Text>
                            <CopyableCodeBlock>docker compose up -d</CopyableCodeBlock>
                            <Text>Wait for the health check to pass, then configure your shell:</Text>
                            <CopyableCodeBlock>{`eval "$(curl -s http://localhost:8080/_localcloud/env?format=shell)"`}</CopyableCodeBlock>
                        </Section>
                        <Section title="2. Use GCP SDKs as normal">
                            <Text>Your application code works against LocalCloud with zero changes. The environment variables tell Google Cloud SDKs to connect to localhost instead of real GCP.</Text>
                            <CopyableCodeBlock>{`from google.cloud import storage
client = storage.Client()
bucket = client.create_bucket("my-bucket")
print(f"Created: {bucket.name}")`}</CopyableCodeBlock>
                        </Section>
                        <Section title="3. View data in the console">
                            <Text>Use the Data Browser in the left sidebar to browse buckets, topics, secrets, databases, and more. Use this Settings page to copy environment variables or export state.</Text>
                        </Section>
                    </Show>

                    <Show when={activeTab() === 'sdk'}>
                        <Section title="Environment Variables">
                            <Text>Set these variables in your shell or application environment. Each one tells the corresponding Google Cloud SDK to connect to LocalCloud instead of real GCP:</Text>
                            <CopyableCodeBlock>{`export STORAGE_EMULATOR_HOST="http://localhost:4443"
export PUBSUB_EMULATOR_HOST="localhost:8085"
export FIRESTORE_EMULATOR_HOST="localhost:8086"
export BIGTABLE_EMULATOR_HOST="localhost:8087"
export SPANNER_EMULATOR_HOST="localhost:9010"
export BIGQUERY_EMULATOR_HOST="http://localhost:9050"
export SECRET_MANAGER_EMULATOR_HOST="localhost:8080"
export CLOUD_TASKS_EMULATOR_HOST="localhost:8080"
export GOOGLE_CLOUD_PROJECT="local-project"`}</CopyableCodeBlock>
                        </Section>
                        <Section title="Auto-configure (recommended)">
                            <Text>Instead of setting variables manually, use the auto-configure endpoint to set all variables at once:</Text>
                            <CopyableCodeBlock>{`eval "$(curl -s http://localhost:8080/_localcloud/env?format=shell)"`}</CopyableCodeBlock>
                            <Text>This sets all required variables for every enabled service, including the project ID.</Text>
                        </Section>
                        <Section title="Docker Compose apps">
                            <Text>For multi-container apps, use the service name "localcloud" as the hostname instead of "localhost":</Text>
                            <CopyableCodeBlock>{`# docker-compose.yml
services:
  my-app:
    environment:
      STORAGE_EMULATOR_HOST: "http://localcloud:4443"
      PUBSUB_EMULATOR_HOST: "localcloud:8085"
      FIRESTORE_EMULATOR_HOST: "localcloud:8086"
      GOOGLE_CLOUD_PROJECT: "local-project"
    depends_on:
      localcloud:
        condition: service_healthy`}</CopyableCodeBlock>
                        </Section>
                    </Show>

                    <Show when={activeTab() === 'gcloud'}>
                        <Section title="Configure gcloud CLI to use LocalCloud">
                            <Text>The gcloud CLI can be pointed to LocalCloud so commands like "gcloud pubsub topics list" query your local emulator instead of real GCP:</Text>
                            <CopyableCodeBlock>{`# Set the auto-configure endpoint
eval "$(curl -s http://localhost:8080/_localcloud/env?format=shell)"

# Now gcloud commands hit LocalCloud:
gcloud pubsub topics list
gcloud secrets list
gcloud spanner instances list`}</CopyableCodeBlock>
                        </Section>
                        <Section title="How it works">
                            <Text>LocalCloud sets CLOUDSDK_* environment variables that override gcloud CLI's default endpoints:</Text>
                            <CopyableCodeBlock>{`CLOUDSDK_CORE_PROJECT="local-project"
CLOUDSDK_API_ENDPOINT_OVERRIDES_PUBSUB="http://localhost:8085"
CLOUDSDK_API_ENDPOINT_OVERRIDES_SPANNER="http://localhost:9020"
CLOUDSDK_AUTH_ACCESS_TOKEN="localcloud-dev-token"`}</CopyableCodeBlock>
                            <Text>The access token bypasses authentication since LocalCloud runs in permissive IAM mode by default.</Text>
                        </Section>
                        <Section title="Per-project gcloud commands">
                            <Text>If you have multiple projects, specify the project in your gcloud commands:</Text>
                            <CopyableCodeBlock>{`gcloud pubsub topics list --project=staging
gcloud secrets list --project=dev`}</CopyableCodeBlock>
                        </Section>
                    </Show>

                    <Show when={activeTab() === 'revert'}>
                        <Section title="Switch back to real GCP">
                            <Text>To stop using LocalCloud and point your SDKs back to real Google Cloud, unset all emulator environment variables:</Text>
                            <CopyableCodeBlock>{`unset STORAGE_EMULATOR_HOST PUBSUB_EMULATOR_HOST \\
  FIRESTORE_EMULATOR_HOST BIGTABLE_EMULATOR_HOST \\
  SPANNER_EMULATOR_HOST BIGQUERY_EMULATOR_HOST \\
  SECRET_MANAGER_EMULATOR_HOST CLOUD_TASKS_EMULATOR_HOST \\
  CLOUD_LOGGING_EMULATOR_HOST CLOUD_MONITORING_EMULATOR_HOST \\
  REDIS_HOST`}</CopyableCodeBlock>
                        </Section>
                        <Section title="Revert gcloud CLI">
                            <Text>To revert gcloud CLI overrides:</Text>
                            <CopyableCodeBlock>{`unset CLOUDSDK_CORE_PROJECT
unset CLOUDSDK_AUTH_ACCESS_TOKEN
unset CLOUDSDK_API_ENDPOINT_OVERRIDES_PUBSUB
unset CLOUDSDK_API_ENDPOINT_OVERRIDES_SPANNER
# ... or simply open a new terminal session`}</CopyableCodeBlock>
                            <Text>Opening a new terminal window is the simplest way to revert, since all LocalCloud variables are session-scoped.</Text>
                        </Section>
                        <Section title="Zero code changes">
                            <Text>Your application code does not need any changes to switch between LocalCloud and real GCP. The SDKs automatically detect the emulator environment variables and route traffic accordingly. Remove the variables, and traffic goes to real GCP.</Text>
                        </Section>
                    </Show>

                    <Show when={activeTab() === 'seed'}>
                        <Section title="Loading seed data">
                            <Text>Seed files define initial state for services using YAML. Load them on startup or into a running instance:</Text>
                            <CopyableCodeBlock>{`# Load into a running instance
curl -X POST http://localhost:8080/_localcloud/seed \\
  -H "Content-Type: application/yaml" --data-binary @seed.yaml

# Or mount on startup (docker-compose.yml)
volumes:
  - ./seed.yaml:/etc/localcloud/seed.yaml:ro`}</CopyableCodeBlock>
                        </Section>
                        <Section title="Seed file format">
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
                        </Section>
                        <Section title="Reset data">
                            <Text>Reset all emulator data or restore the last loaded seed:</Text>
                            <CopyableCodeBlock>{`# Reset all data
curl -X POST http://localhost:8080/_localcloud/reset

# Reset and restore last seed
curl -X POST http://localhost:8080/_localcloud/reset \\
  -H "Content-Type: application/json" \\
  -d '{"restore_seed": true}'`}</CopyableCodeBlock>
                        </Section>
                    </Show>

                    <Show when={activeTab() === 'api'}>
                        <Section title="Admin API endpoints">
                            <Text>The gateway at port 8080 exposes admin endpoints for managing LocalCloud:</Text>
                            <div class="data-table-wrapper" style={{ "margin-bottom": "16px" }}>
                                <table class="data-table">
                                    <thead><tr><th>Method</th><th>Path</th><th>Description</th></tr></thead>
                                    <tbody>
                                        <tr><td>GET</td><td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>/_localcloud/health</td><td>Health status of all services</td></tr>
                                        <tr><td>GET</td><td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>/_localcloud/services</td><td>List services with ports and status</td></tr>
                                        <tr><td>GET</td><td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>/_localcloud/env?format=shell</td><td>Environment variables</td></tr>
                                        <tr><td>GET</td><td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>/_localcloud/projects</td><td>List all projects</td></tr>
                                        <tr><td>POST</td><td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>/_localcloud/projects</td><td>Create a project</td></tr>
                                        <tr><td>GET</td><td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>/_localcloud/browse/{'{'} service {'}'}</td><td>Browse service data</td></tr>
                                        <tr><td>POST</td><td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>/_localcloud/seed</td><td>Load seed data (YAML)</td></tr>
                                        <tr><td>POST</td><td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>/_localcloud/reset</td><td>Reset all data</td></tr>
                                        <tr><td>GET</td><td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>/_localcloud/requests</td><td>Recent request log</td></tr>
                                    </tbody>
                                </table>
                            </div>
                        </Section>
                        <Section title="Project-scoped queries">
                            <Text>Add ?project= to browse, env, and reset endpoints to scope to a specific project:</Text>
                            <CopyableCodeBlock>{`# Browse secrets for a specific project
curl http://localhost:8080/_localcloud/browse/secretmanager?project=staging

# Get env vars for a specific project
curl "http://localhost:8080/_localcloud/env?format=json&project=dev"

# Reset only one project's data
curl -X POST http://localhost:8080/_localcloud/reset?project=staging`}</CopyableCodeBlock>
                        </Section>
                    </Show>
                </div>
            </div>
        </div>
    );
}

export default function Settings(props) {
    const [envVars, setEnvVars] = createSignal(null);
    const [envLoading, setEnvLoading] = createSignal(false);
    const [envError, setEnvError] = createSignal(null);
    const [intervalInput, setIntervalInput] = createSignal(Math.floor(props.refreshInterval() / 1000));
    const [intervalMsg, setIntervalMsg] = createSignal(null);
    const [quickCopied, setQuickCopied] = createSignal(false);

    // Remote connection state (Data Mirror)
    const [remoteAuth, setRemoteAuth] = createSignal(null);
    const [remoteAuthLoading, setRemoteAuthLoading] = createSignal(false);
    const [remoteDisconnecting, setRemoteDisconnecting] = createSignal(false);

    const fetchRemoteAuth = async () => {
        setRemoteAuthLoading(true);
        try {
            const s = await api.syncAuthStatus();
            setRemoteAuth(s);
        } catch (e) { setRemoteAuth(null); }
        finally { setRemoteAuthLoading(false); }
    };

    const handleRemoteDisconnect = async () => {
        setRemoteDisconnecting(true);
        try {
            await api.syncDisconnect();
            setRemoteAuth(null);
        } catch (e) { /* ignore */ }
        finally { setRemoteDisconnecting(false); }
    };

    fetchRemoteAuth();

    // Tab navigation with localStorage persistence
    const savedTab = (() => { try { return localStorage.getItem('localcloud-settings-tab'); } catch { return null; } })();
    const [settingsTab, setSettingsTab] = createSignal(savedTab || 'environment');
    const switchTab = (tabId) => {
        setSettingsTab(tabId);
        try { localStorage.setItem('localcloud-settings-tab', tabId); } catch {}
    };
    const SETTINGS_TABS = [
        { id: 'environment', label: 'Environment' },
        { id: 'cloud', label: 'Cloud & Routing' },
        { id: 'preferences', label: 'Preferences' },
        { id: 'help', label: 'Help & About' },
    ];
    // Guide sub-tabs (inline in Help tab)
    const [guideTab, setGuideTab] = createSignal('quickstart');
    const [dockerComposeOpen, setDockerComposeOpen] = createSignal(false);

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

    // Ordered SDK vars present in current env
    const sdkEntries = () => {
        const cat = categorized();
        if (!cat) return [];
        return SDK_ORDER
            .filter(k => k in cat.sdk)
            .map(k => ({ key: k, value: cat.sdk[k] }));
    };

    const autoConfigCmd = `eval "$(curl -s http://localhost:8080/_localcloud/env?format=shell)"`;

    const handleQuickCopy = async () => {
        const ok = await copyToClipboard(autoConfigCmd);
        if (ok) {
            setQuickCopied(true);
            setTimeout(() => setQuickCopied(false), 2000);
        }
    };

    const handleApplyInterval = () => {
        const val = intervalInput();
        if (val < 1 || val > 60) {
            setIntervalMsg('Value must be between 1 and 60 seconds.');
            setTimeout(() => setIntervalMsg(null), 3000);
            return;
        }
        props.setRefreshInterval(val * 1000);
        setIntervalMsg('Applied.');
        setTimeout(() => setIntervalMsg(null), 2000);
    };

    // Reusable helpers for guide content (shared with inline help tab)
    const GuideSection = (p) => (
        <div style={{ "margin-bottom": "24px" }}>
            <h3 style={{ "font-size": "14px", "font-weight": "500", "margin-bottom": "8px", color: "var(--text)" }}>{p.title}</h3>
            {p.children}
        </div>
    );
    const GuideText = (p) => (
        <p style={{ "font-size": "13px", color: "var(--text-secondary)", "margin-bottom": "10px", "line-height": "1.6" }}>{p.children}</p>
    );

    return (
        <div>
            <div class="page-header">
                <h1>Settings</h1>
            </div>

            {/* Top-level tab bar */}
            <div class="tab-bar" style={{ "margin-bottom": "24px" }}>
                <For each={SETTINGS_TABS}>
                    {(tab) => (
                        <button
                            classList={{ "tab-item": true, "active": settingsTab() === tab.id }}
                            onClick={() => switchTab(tab.id)}
                        >
                            {tab.label}
                        </button>
                    )}
                </For>
            </div>

            {/* ===== ENVIRONMENT TAB ===== */}
            <Show when={settingsTab() === 'environment'}>
            <div class="section">
                <div class="section-title">Environment Variables</div>
                <p style={{ "margin-bottom": "16px" }}>
                    Configure your shell or application to connect to LocalCloud emulators.
                </p>

                <Show when={envError()}>
                    <div class="alert alert-error">{envError()}</div>
                </Show>
                <Show when={envLoading()}>
                    <div class="loading-state"><div class="loading-spinner" /> Loading...</div>
                </Show>

                <Show when={categorized()}>
                    {/* Quick Setup */}
                    <div class="env-quick-setup">
                        <div class="env-quick-setup-label">Quick Setup</div>
                        <div class="env-quick-setup-desc">Run this command to auto-configure all environment variables:</div>
                        <div class="env-quick-setup-cmd">
                            <code>{autoConfigCmd}</code>
                            <button class="env-var-copy" onClick={handleQuickCopy} title="Copy to clipboard" style={{ "flex-shrink": "0" }}>
                                <Show when={quickCopied()} fallback={<CopyIcon />}>
                                    <CheckIcon />
                                </Show>
                            </button>
                        </div>
                    </div>

                    {/* Switch to Google Cloud callout */}
                    <div style={{ background: "var(--bg-subtle)", border: "1px solid var(--border)", "border-radius": "var(--radius-sm)", padding: "12px 16px", "margin-bottom": "16px" }}>
                        <div style={{ "font-size": "12px", "font-weight": "500", color: "var(--text-secondary)", "margin-bottom": "6px" }}>Switch to Google Cloud</div>
                        <CopyableCodeBlock>
                            {`unset STORAGE_EMULATOR_HOST PUBSUB_EMULATOR_HOST \\
  FIRESTORE_EMULATOR_HOST BIGTABLE_EMULATOR_HOST \\
  SPANNER_EMULATOR_HOST BIGQUERY_EMULATOR_HOST \\
  SECRET_MANAGER_EMULATOR_HOST CLOUD_TASKS_EMULATOR_HOST \\
  CLOUD_LOGGING_EMULATOR_HOST CLOUD_MONITORING_EMULATOR_HOST \\
  REDIS_HOST`}
                        </CopyableCodeBlock>
                        <div style={{ "font-size": "11px", color: "var(--text-tertiary)", "margin-top": "6px" }}>Or open a new terminal session. No code changes needed.</div>
                    </div>

                    {/* Shell / CLI / SDK Tabs */}
                    <EnvTabs categorized={categorized} sdkEntries={sdkEntries} envVars={envVars} />
                </Show>
            </div>
            </Show>

            {/* ===== CLOUD & ROUTING TAB ===== */}
            <Show when={settingsTab() === 'cloud'}>

            {/* GCP Credentials */}
            <div class="section">
                <div class="section-title">GCP Credentials</div>
                <p style={{ "margin-bottom": "12px" }}>
                    Connect to real Google Cloud resources for hybrid local-cloud development.
                </p>
                <Show when={props.credentialData?.()} fallback={
                    <div class="card" style={{ padding: "16px 20px" }}>
                        <div style={{ "font-size": "13px", color: "var(--text-secondary)" }}>
                            Credential status unavailable — upgrade the server to enable hybrid cloud connectivity.
                        </div>
                    </div>
                }>
                    {(() => {
                        const creds = () => props.credentialData?.();
                        return (
                            <div class="card" style={{ padding: "16px 20px" }}>
                                <div style={{ display: "flex", "align-items": "center", gap: "10px", "margin-bottom": "12px" }}>
                                    <span classList={{
                                        "status-dot": true,
                                        "healthy": creds()?.valid === true,
                                        "unhealthy": creds()?.source !== 'none' && !creds()?.valid,
                                    }} style={{ width: "8px", height: "8px" }} />
                                    <span style={{ "font-size": "13px", "font-weight": "500" }}>
                                        {creds()?.valid ? 'Connected' : creds()?.source === 'none' ? 'Not configured' : 'Invalid credentials'}
                                    </span>
                                </div>
                                <Show when={creds()?.valid}>
                                    <div class="env-var-row">
                                        <span class="env-var-key">Source</span>
                                        <span class="env-var-value">{creds()?.source === 'adc' ? 'Application Default Credentials' : 'Service Account Key'}</span>
                                    </div>
                                    <div class="env-var-row">
                                        <span class="env-var-key">Identity</span>
                                        <span class="env-var-value">{creds()?.identity || 'Unknown'}</span>
                                    </div>
                                    <div class="env-var-row">
                                        <span class="env-var-key">Project</span>
                                        <span class="env-var-value">{creds()?.project || 'Not set'}</span>
                                    </div>
                                </Show>
                                <Show when={creds()?.source === 'none'}>
                                    <div style={{ "margin-top": "8px", "font-size": "12px", color: "var(--text-secondary)", "line-height": "1.6" }}>
                                        To enable hybrid cloud, add credential volume mounts to docker-compose.yml:
                                    </div>
                                    <CopyableCodeBlock style={{ "margin-top": "8px", "font-size": "11px" }}>
                                        {`# docker-compose.yml
volumes:
  - "\${HOME}/.config/gcloud:/credentials/adc:ro"
environment:
  LOCALCLOUD_GCP_CREDENTIAL_SOURCE: "adc"`}
                                    </CopyableCodeBlock>
                                </Show>
                                <Show when={creds()?.error}>
                                    <div class="alert alert-error" style={{ "margin-top": "8px", "margin-bottom": "0" }}>
                                        {creds()?.error}
                                    </div>
                                </Show>
                            </div>
                        );
                    })()}
                </Show>
            </div>

            {/* Service Routing */}
            <div class="section">
                <div class="section-title">Service Routing</div>
                <p style={{ "margin-bottom": "12px" }}>
                    Switch individual services between local emulator and remote Google Cloud.
                </p>
                <Show when={props.routingData?.()} fallback={
                    <div class="card" style={{ padding: "16px 20px" }}>
                        <div style={{ "font-size": "13px", color: "var(--text-secondary)" }}>
                            Routing data unavailable.
                        </div>
                    </div>
                }>
                    <div class="data-table-wrapper">
                        <table class="data-table">
                            <thead>
                                <tr>
                                    <th>Service</th>
                                    <th>Mode</th>
                                    <th>Status</th>
                                    <th>Remote Config</th>
                                </tr>
                            </thead>
                            <tbody>
                                <For each={Object.entries(props.routingData?.() || {})}>
                                    {([serviceId, info]) => {
                                        const hasCredentials = () => props.credentialData?.()?.valid === true;
                                        const [saving, setSaving] = createSignal(false);
                                        const handleModeChange = async (newMode) => {
                                            if (newMode === 'remote' && !hasCredentials()) return;
                                            setSaving(true);
                                            try {
                                                await api.setRouting(serviceId, newMode,
                                                    newMode === 'remote' ? info.remote_project : null,
                                                    newMode === 'remote' ? info.remote_region : null);
                                            } catch (e) {
                                                console.error('Failed to set routing:', e);
                                            } finally {
                                                setSaving(false);
                                            }
                                        };
                                        return (
                                            <tr>
                                                <td style={{ "font-weight": "500" }}>{serviceId}</td>
                                                <td>
                                                    <div class="segmented-toggle">
                                                        <button
                                                            classList={{ "segmented-toggle-btn": true, "active": (info.mode || 'local') === 'local' }}
                                                            onClick={() => handleModeChange('local')}
                                                            disabled={saving()}
                                                        >Local</button>
                                                        <button
                                                            classList={{ "segmented-toggle-btn": true, "active": info.mode === 'remote', "disabled-segment": !hasCredentials() }}
                                                            onClick={() => handleModeChange('remote')}
                                                            disabled={saving() || !hasCredentials()}
                                                            title={!hasCredentials() ? 'Configure GCP credentials to enable remote mode' : ''}
                                                        >Remote</button>
                                                    </div>
                                                </td>
                                                <td>
                                                    <span classList={{
                                                        "status-indicator": true,
                                                        "healthy": info.healthy,
                                                        "unhealthy": !info.healthy,
                                                    }}>
                                                        <span classList={{
                                                            "status-dot": true,
                                                            "healthy": info.healthy,
                                                            "unhealthy": !info.healthy,
                                                        }} />
                                                        {info.healthy ? 'Healthy' : info.emulatorRunning ? 'Unhealthy' : 'Stopped'}
                                                    </span>
                                                </td>
                                                <td style={{ "font-size": "11px", color: "var(--text-secondary)" }}>
                                                    {info.mode === 'remote'
                                                        ? `${info.remote_project || '—'} / ${info.remote_region || '—'}`
                                                        : <span style={{ color: "var(--text-tertiary)" }}>Port {info.port}</span>}
                                                </td>
                                            </tr>
                                        );
                                    }}
                                </For>
                            </tbody>
                        </table>
                    </div>
                </Show>
            </div>

            {/* Remote Connection (Data Mirror) */}
            <div class="section">
                <div class="section-title">Remote Connection</div>
                <p style={{ "margin-bottom": "12px" }}>
                    Data Mirror connection for browsing and syncing remote GCP data into local emulators.
                </p>
                <div class="card" style={{ padding: "16px 20px" }}>
                    <Show when={remoteAuthLoading()}>
                        <div class="loading-state"><div class="loading-spinner" /> Checking connection...</div>
                    </Show>
                    <Show when={!remoteAuthLoading()}>
                        <div style={{ display: "flex", "align-items": "center", gap: "10px", "margin-bottom": "12px" }}>
                            <span classList={{
                                "status-dot": true,
                                "healthy": remoteAuth()?.connected === true || remoteAuth()?.connected === 'true',
                                "unhealthy": remoteAuth() && remoteAuth()?.connected !== true && remoteAuth()?.connected !== 'true',
                            }} style={{ width: "8px", height: "8px" }} />
                            <span style={{ "font-size": "13px", "font-weight": "500" }}>
                                {remoteAuth()?.connected === true || remoteAuth()?.connected === 'true' ? 'Connected' : 'Not connected'}
                            </span>
                        </div>
                        <Show when={remoteAuth()?.connected === true || remoteAuth()?.connected === 'true'}>
                            <div class="env-var-row">
                                <span class="env-var-key">Source Project</span>
                                <span class="env-var-value">{remoteAuth()?.source_project || 'Unknown'}</span>
                            </div>
                            <div class="env-var-row">
                                <span class="env-var-key">Auth Method</span>
                                <span class="env-var-value">{remoteAuth()?.auth_method || 'OAuth'}</span>
                            </div>
                            <div style={{ "margin-top": "12px" }}>
                                <button class="btn btn-danger" onClick={handleRemoteDisconnect} disabled={remoteDisconnecting()}>
                                    {remoteDisconnecting() ? 'Disconnecting...' : 'Disconnect'}
                                </button>
                            </div>
                        </Show>
                        <Show when={!(remoteAuth()?.connected === true || remoteAuth()?.connected === 'true')}>
                            <div style={{ "font-size": "13px", color: "var(--text-secondary)", "line-height": "1.6" }}>
                                Navigate to any service's <strong>Remote Sync</strong> tab to connect to a GCP project and sync data.
                            </div>
                        </Show>
                    </Show>
                </div>
            </div>
            </Show>

            {/* ===== PREFERENCES TAB ===== */}
            <Show when={settingsTab() === 'preferences'}>

            {/* Auto-Refresh */}
            <div class="section">
                <div class="section-title">Auto-Refresh</div>
                <div class="card" style={{ padding: "0" }}>
                    <div class="settings-row" style={{ padding: "16px 20px" }}>
                        <div class="settings-row-info">
                            <div class="settings-row-label">Refresh Interval</div>
                            <div class="settings-row-desc">How often to poll for health data (1-60 seconds).</div>
                        </div>
                        <div class="settings-row-action">
                            <div class="input-group">
                                <input
                                    type="number"
                                    min="1"
                                    max="60"
                                    value={intervalInput()}
                                    onInput={e => setIntervalInput(parseInt(e.currentTarget.value) || 1)}
                                />
                                <span class="input-group-suffix">sec</span>
                                <button class="btn btn-primary" onClick={handleApplyInterval}>Apply</button>
                            </div>
                        </div>
                    </div>
                    <Show when={intervalMsg()}>
                        <div style={{ padding: "0 20px 12px", "font-size": "12px", color: "var(--success)" }}>
                            {intervalMsg()}
                        </div>
                    </Show>
                </div>
            </div>

            {/* Export */}
            <div class="section">
                <div class="section-title">Export</div>
                <p style={{ "margin-bottom": "12px" }}>
                    Download the current state of all emulated services as a YAML seed file.
                </p>
                <button class="btn btn-secondary" onClick={async () => {
                    try {
                        const resp = await fetch('/_localcloud/export');
                        const text = await resp.text();
                        const blob = new Blob([text], { type: 'application/yaml' });
                        const url = URL.createObjectURL(blob);
                        const a = document.createElement('a');
                        a.href = url;
                        a.download = `localcloud-state-${new Date().toISOString().slice(0,10)}.yaml`;
                        a.click();
                        URL.revokeObjectURL(url);
                    } catch (e) { console.error('Export failed:', e); }
                }}>
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
                    Export State
                </button>
            </div>

            {/* Seed Data */}
            <div class="section">
                <div class="section-title">Seed Data</div>
                <p style={{ "margin-bottom": "12px" }}>
                    Re-load the default seed data into all services. This uses UPSERT semantics — existing data is updated, not duplicated.
                    Seed data persists across container restarts and is only auto-loaded on first run.
                </p>
                <div style={{ display: "flex", gap: "8px", "align-items": "center" }}>
                    <button class="btn btn-secondary" onClick={async () => {
                        if (!confirm('Re-seed all services with default data? Existing data will be preserved (UPSERT).')) return;
                        try {
                            const resp = await fetch('/_localcloud/reseed', { method: 'POST' });
                            const data = await resp.json();
                            if (data.error) { alert('Seed failed: ' + data.message); return; }
                            alert('Seed complete: ' + (data.total_records || 0) + ' records loaded across ' + Object.keys(data.services || {}).length + ' services');
                        } catch (e) { alert('Seed failed: ' + e.message); }
                    }}>
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4"/></svg>
                        Re-seed Data
                    </button>
                </div>
            </div>
            </Show>

            {/* ===== HELP & ABOUT TAB ===== */}
            <Show when={settingsTab() === 'help'}>

            {/* User Guide — inline, not modal */}
            <div class="section">
                <div class="section-title">User Guide</div>
                <div class="tab-bar" style={{ "margin-bottom": "16px" }}>
                    {[
                        { id: 'quickstart', label: 'Quick Start' },
                        { id: 'sdk', label: 'SDK Setup' },
                        { id: 'gcloud', label: 'gcloud CLI' },
                        { id: 'revert', label: 'Revert to GCP' },
                        { id: 'seed', label: 'Seed Data' },
                        { id: 'api', label: 'Admin API' },
                    ].map(tab => (
                        <button
                            classList={{ "tab-item": true, "active": guideTab() === tab.id }}
                            onClick={() => setGuideTab(tab.id)}
                        >
                            {tab.label}
                        </button>
                    ))}
                </div>

                {/* Quick Start */}
                <Show when={guideTab() === 'quickstart'}>
                    <GuideSection title="Step 1: Start LocalCloud">
                        <GuideText>Start all emulated GCP services:</GuideText>
                        <CopyableCodeBlock>{`docker run -d --name localcloud \\
  -p 8080:8080 -p 4443:4443 -p 8085:8085 \\
  -p 8086:8086 -p 8087:8087 -p 9010:9010 \\
  -p 9020:9020 -p 9050:9050 -p 6379:6379 \\
  localcloud/localcloud:latest`}</CopyableCodeBlock>
                    </GuideSection>
                    <GuideSection title="Step 2: Configure your shell">
                        <GuideText>Auto-configure all environment variables:</GuideText>
                        <CopyableCodeBlock>{`eval "$(curl -s http://localhost:8080/_localcloud/env?format=shell)"`}</CopyableCodeBlock>
                    </GuideSection>
                    <GuideSection title="Step 3: Test it">
                        <GuideText>Verify your setup with a quick test:</GuideText>
                        <CopyableCodeBlock>{`python3 -c "
from google.cloud import storage
client = storage.Client()
print('Buckets:', list(client.list_buckets()))
"`}</CopyableCodeBlock>
                    </GuideSection>
                    <div style={{ "margin-top": "8px" }}>
                        <button class="env-sample-toggle" onClick={() => setDockerComposeOpen(!dockerComposeOpen())} style={{ "margin-bottom": "8px" }}>
                            <ChevronIcon open={dockerComposeOpen()} />
                            <span>Using Docker Compose</span>
                        </button>
                        <Show when={dockerComposeOpen()}>
                            <CopyableCodeBlock>docker compose up -d</CopyableCodeBlock>
                        </Show>
                    </div>
                </Show>

                {/* SDK Setup */}
                <Show when={guideTab() === 'sdk'}>
                    <GuideSection title="Auto-configure (recommended)">
                        <GuideText>Run the auto-configure command in the Environment tab, or set variables individually.</GuideText>
                        <button class="btn btn-secondary" style={{ "margin-bottom": "12px" }} onClick={() => switchTab('environment')}>
                            Go to Environment tab
                        </button>
                    </GuideSection>
                    <GuideSection title="Docker Compose apps">
                        <GuideText>For multi-container apps, use the service name "localcloud" as the hostname:</GuideText>
                        <CopyableCodeBlock>{`# docker-compose.yml
services:
  my-app:
    environment:
      STORAGE_EMULATOR_HOST: "http://localcloud:4443"
      PUBSUB_EMULATOR_HOST: "localcloud:8085"
      GOOGLE_CLOUD_PROJECT: "local-project"
    depends_on:
      localcloud:
        condition: service_healthy`}</CopyableCodeBlock>
                    </GuideSection>
                </Show>

                {/* gcloud CLI */}
                <Show when={guideTab() === 'gcloud'}>
                    <GuideSection title="Configure gcloud CLI">
                        <GuideText>After running the auto-configure command (Environment tab), gcloud commands will route to LocalCloud:</GuideText>
                        <CopyableCodeBlock>{`gcloud pubsub topics list
gcloud secrets list
gcloud spanner instances list`}</CopyableCodeBlock>
                    </GuideSection>
                    <GuideSection title="How it works">
                        <GuideText>CLOUDSDK_* environment variables override gcloud CLI default endpoints. The access token bypasses authentication.</GuideText>
                    </GuideSection>
                </Show>

                {/* Revert to GCP */}
                <Show when={guideTab() === 'revert'}>
                    <GuideSection title="Switch back to real GCP">
                        <GuideText>Unset all emulator environment variables:</GuideText>
                        <CopyableCodeBlock>{`unset STORAGE_EMULATOR_HOST PUBSUB_EMULATOR_HOST \\
  FIRESTORE_EMULATOR_HOST BIGTABLE_EMULATOR_HOST \\
  SPANNER_EMULATOR_HOST BIGQUERY_EMULATOR_HOST \\
  SECRET_MANAGER_EMULATOR_HOST CLOUD_TASKS_EMULATOR_HOST \\
  CLOUD_LOGGING_EMULATOR_HOST CLOUD_MONITORING_EMULATOR_HOST \\
  REDIS_HOST`}</CopyableCodeBlock>
                        <GuideText>Or simply open a new terminal session. Your application code does not need any changes to switch between LocalCloud and real GCP.</GuideText>
                    </GuideSection>
                </Show>

                {/* Seed Data */}
                <Show when={guideTab() === 'seed'}>
                    <GuideSection title="Loading seed data">
                        <CopyableCodeBlock>{`curl -X POST http://localhost:8080/_localcloud/seed \\
  -H "Content-Type: application/yaml" --data-binary @seed.yaml`}</CopyableCodeBlock>
                    </GuideSection>
                    <GuideSection title="Reset data">
                        <CopyableCodeBlock>{`# Reset all data
curl -X POST http://localhost:8080/_localcloud/reset

# Reset and restore last seed
curl -X POST http://localhost:8080/_localcloud/reset \\
  -H "Content-Type: application/json" -d '{"restore_seed": true}'`}</CopyableCodeBlock>
                    </GuideSection>
                </Show>

                {/* Admin API */}
                <Show when={guideTab() === 'api'}>
                    <GuideSection title="Admin API endpoints">
                        <div class="data-table-wrapper" style={{ "margin-bottom": "16px" }}>
                            <table class="data-table">
                                <thead><tr><th>Method</th><th>Path</th><th>Description</th></tr></thead>
                                <tbody>
                                    <tr><td>GET</td><td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>/_localcloud/health</td><td>Health status</td></tr>
                                    <tr><td>GET</td><td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>/_localcloud/services</td><td>List services</td></tr>
                                    <tr><td>GET</td><td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>/_localcloud/env?format=shell</td><td>Environment variables</td></tr>
                                    <tr><td>GET</td><td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>/_localcloud/routing</td><td>Service routing status</td></tr>
                                    <tr><td>GET</td><td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>/_localcloud/credentials</td><td>GCP credential status</td></tr>
                                    <tr><td>POST</td><td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>/_localcloud/seed</td><td>Load seed data</td></tr>
                                    <tr><td>POST</td><td style={{ "font-family": "var(--font-mono)", "font-size": "12px" }}>/_localcloud/reset</td><td>Reset all data</td></tr>
                                </tbody>
                            </table>
                        </div>
                    </GuideSection>
                </Show>
            </div>

            {/* About */}
            <div class="section">
                <div class="section-title">About</div>
                <div class="card">
                    <div style={{ "margin-bottom": "8px", "font-weight": "600", "font-size": "14px" }}>LocalCloud Console v0.1.0</div>
                    <p style={{ "margin-bottom": "8px" }}>
                        A lightweight Google Cloud Console-style UI for managing LocalCloud emulated GCP services.
                    </p>
                    <p style={{ "font-size": "11px", color: "var(--text-tertiary)" }}>
                        Author: Jay Sen &lt;jaysen@apache.org&gt; | License: Apache-2.0
                    </p>
                </div>
            </div>
            </Show>
        </div>
    );
}

import { createSignal, Show, For } from 'solid-js';
import { api } from '../api.js';
import { createUrlBackedTab } from '../utils/urlTabs.js';
import { shortVersion } from '../utils/version.js';
import {
    logsRefreshInterval,
    setLogsRefreshInterval,
    setUsageRefreshInterval,
    usageRefreshInterval,
} from '../utils/refreshPreferences.js';

// --- SVG Icons ---
const CopyIcon = () => (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
);
const CheckIcon = () => (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><polyline points="20 6 9 17 4 12"/></svg>
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

// --- Clipboard helper ---
async function copyToClipboard(text) {
    try {
        await navigator.clipboard.writeText(text);
        return true;
    } catch {
        return false;
    }
}

// ===== ABOUT PAGE =====
function AboutPage(props) {
    const versionDisplay = () => shortVersion(props.healthData?.());
    return (
        <div style={{ "max-width": "780px" }}>
            {/* Project Hero */}
            <div style={{
                position: "relative",
                "border-radius": "16px",
                overflow: "hidden",
                "margin-bottom": "28px",
                background: "linear-gradient(135deg, #1a73e8 0%, #0d47a1 40%, #01579b 100%)",
                padding: "40px 36px 36px",
                color: "#fff",
            }}>
                <div style={{
                    position: "absolute", top: "0", right: "0", width: "200px", height: "200px",
                    opacity: "0.06",
                    "background-image": "linear-gradient(#fff 1px, transparent 1px), linear-gradient(90deg, #fff 1px, transparent 1px)",
                    "background-size": "20px 20px",
                }} />
                <div style={{ position: "relative", "z-index": "1" }}>
                    <div style={{ display: "flex", "align-items": "center", gap: "14px", "margin-bottom": "16px" }}>
                        <img src="/icons/localcloud-mark.svg" alt="LocalCloud" width="48" height="48" style={{ "border-radius": "12px" }} />
                        <div>
                            <div style={{ "font-size": "24px", "font-weight": "700", "letter-spacing": "-0.5px" }}>LocalCloud</div>
                            <div style={{ "font-size": "12px", opacity: "0.7", "letter-spacing": "0.3px" }}>{versionDisplay()} &middot; <a href="/LICENSE" target="_blank" style={{ color: "inherit", "text-decoration": "underline" }}>Proprietary</a></div>
                        </div>
                    </div>
                    <p style={{ "font-size": "15px", "line-height": "1.65", color: "#fff", "max-width": "560px" }}>
                        A fully offline Cloud platform that runs many GCP services in a single Docker container.
                        Build and test cloud-native applications without cloud resources, cost, internet or even access credentials.
                    </p>
                    <div style={{ display: "flex", gap: "10px", "margin-top": "20px", "flex-wrap": "wrap" }}>
                        <a href="https://localgcloud.github.io/" target="_blank" rel="noopener noreferrer"
                            style={{
                                display: "inline-flex", "align-items": "center", gap: "6px",
                                padding: "8px 18px", "border-radius": "8px",
                                background: "rgba(255,255,255,0.18)", "backdrop-filter": "blur(4px)",
                                color: "#fff", "text-decoration": "none", "font-size": "13px", "font-weight": "600",
                                border: "1px solid rgba(255,255,255,0.2)", transition: "background 0.2s",
                            }}>
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z"/></svg>
                            Website
                        </a>
                        <a href="https://github.com/LocalStack-Google/localstack-google.github.io" target="_blank" rel="noopener noreferrer"
                            style={{
                                display: "inline-flex", "align-items": "center", gap: "6px",
                                padding: "8px 18px", "border-radius": "8px",
                                background: "rgba(255,255,255,0.1)",
                                color: "#fff", "text-decoration": "none", "font-size": "13px", "font-weight": "500",
                                border: "1px solid rgba(255,255,255,0.12)", transition: "background 0.2s",
                            }}>
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M12 0c-6.626 0-12 5.373-12 12 0 5.302 3.438 9.8 8.207 11.387.599.111.793-.261.793-.577v-2.234c-3.338.726-4.033-1.416-4.033-1.416-.546-1.387-1.333-1.756-1.333-1.756-1.089-.745.083-.729.083-.729 1.205.084 1.839 1.237 1.839 1.237 1.07 1.834 2.807 1.304 3.492.997.107-.775.418-1.305.762-1.604-2.665-.305-5.467-1.334-5.467-5.931 0-1.311.469-2.381 1.236-3.221-.124-.303-.535-1.524.117-3.176 0 0 1.008-.322 3.301 1.23A11.509 11.509 0 0112 5.803c1.02.005 2.047.138 3.006.404 2.291-1.552 3.297-1.23 3.297-1.23.653 1.653.242 2.874.118 3.176.77.84 1.235 1.911 1.235 3.221 0 4.609-2.807 5.624-5.479 5.921.43.372.823 1.102.823 2.222v3.293c0 .319.192.694.801.576C20.566 21.797 24 17.3 24 12c0-6.627-5.373-12-12-12z"/></svg>
                            GitHub
                        </a>
                    </div>
                </div>
            </div>

            {/* Author Card */}
            <div style={{
                "border-radius": "16px", overflow: "hidden",
                border: "1px solid var(--border)",
                background: "var(--surface)",
            }}>
                <div style={{
                    padding: "32px 36px 28px",
                    background: "linear-gradient(135deg, var(--surface) 0%, var(--bg-subtle, #f8f9fa) 100%)",
                    "border-bottom": "1px solid var(--border)",
                }}>
                    <div style={{ display: "flex", gap: "20px", "align-items": "flex-start" }}>
                        <div style={{
                            width: "72px", height: "72px", "border-radius": "50%", "flex-shrink": "0",
                            background: "linear-gradient(135deg, #1a73e8, #0d47a1)",
                            display: "flex", "align-items": "center", "justify-content": "center",
                            color: "#fff", "font-size": "26px", "font-weight": "700", "letter-spacing": "-1px",
                            "box-shadow": "0 4px 12px rgba(26,115,232,0.3)",
                        }}>JS</div>
                        <div style={{ flex: "1" }}>
                            <div style={{ "font-size": "22px", "font-weight": "700", "letter-spacing": "-0.3px", "margin-bottom": "4px", color: "var(--text-primary)" }}>
                                Jay Sen
                            </div>
                            <div style={{ "font-size": "13px", color: "var(--text-secondary)", "margin-bottom": "12px", "line-height": "1.5" }}>
                                Engineer - Architect & Builder
                            </div>
                            <div style={{ display: "flex", gap: "8px", "flex-wrap": "wrap" }}>
                                <a href="mailto:jaysen@apache.org"
                                    style={{
                                        display: "inline-flex", "align-items": "center", gap: "5px",
                                        padding: "5px 14px", "border-radius": "6px",
                                        "font-size": "12px", "font-weight": "500",
                                        background: "var(--bg-subtle, #f1f3f4)", color: "var(--text-secondary)",
                                        "text-decoration": "none", border: "1px solid var(--border)",
                                        transition: "background 0.15s, border-color 0.15s, color 0.15s",
                                    }}>
                                    <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor"><path d="M20 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 4l-8 5-8-5V6l8 5 8-5v2z"/></svg>
                                    jaysen@apache.org
                                </a>
                                <a href="https://www.linkedin.com/in/jaysen2/" target="_blank" rel="noopener noreferrer"
                                    style={{
                                        display: "inline-flex", "align-items": "center", gap: "5px",
                                        padding: "5px 14px", "border-radius": "6px",
                                        "font-size": "12px", "font-weight": "500",
                                        background: "#0077b5", color: "#fff",
                                        "text-decoration": "none", border: "none",
                                        transition: "opacity 0.15s",
                                    }}>
                                    <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor"><path d="M19 3a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h14m-.5 15.5v-5.3a3.26 3.26 0 0 0-3.26-3.26c-.85 0-1.84.52-2.32 1.3v-1.11h-2.79v8.37h2.79v-4.93c0-.77.62-1.4 1.39-1.4a1.4 1.4 0 0 1 1.4 1.4v4.93h2.79M6.88 8.56a1.68 1.68 0 0 0 1.68-1.68c0-.93-.75-1.69-1.68-1.69a1.69 1.69 0 0 0-1.69 1.69c0 .93.76 1.68 1.69 1.68m1.39 9.94v-8.37H5.5v8.37h2.77z"/></svg>
                                    LinkedIn
                                </a>
                                <a href="https://github.com/jhsenjaliya" target="_blank" rel="noopener noreferrer"
                                   style={{
                                       display: "inline-flex", "align-items": "center", gap: "6px",
                                       padding: "5px 14px", "border-radius": "6px",
                                       "font-size": "12px", "font-weight": "500",
                                       background: "#24292f", color: "#fff",
                                       "text-decoration": "none", border: "none",
                                       transition: "opacity 0.15s",
                                   }}>
                                    <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M12 0c-6.626 0-12 5.373-12 12 0 5.302 3.438 9.8 8.207 11.387.599.111.793-.261.793-.577v-2.234c-3.338.726-4.033-1.416-4.033-1.416-.546-1.387-1.333-1.756-1.333-1.756-1.089-.745.083-.729.083-.729 1.205.084 1.839 1.237 1.839 1.237 1.07 1.834 2.807 1.304 3.492.997.107-.775.418-1.305.762-1.604-2.665-.305-5.467-1.334-5.467-5.931 0-1.311.469-2.381 1.236-3.221-.124-.303-.535-1.524.117-3.176 0 0 1.008-.322 3.301 1.23A11.509 11.509 0 0112 5.803c1.02.005 2.047.138 3.006.404 2.291-1.552 3.297-1.23 3.297-1.23.653 1.653.242 2.874.118 3.176.77.84 1.235 1.911 1.235 3.221 0 4.609-2.807 5.624-5.479 5.921.43.372.823 1.102.823 2.222v3.293c0 .319.192.694.801.576C20.566 21.797 24 17.3 24 12c0-6.627-5.373-12-12-12z"/></svg>
                                    GitHub
                                </a>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
}

// ===== MAIN SETTINGS PAGE =====
export default function Settings(props) {
    const [intervalInput, setIntervalInput] = createSignal(Math.floor(props.refreshInterval() / 1000));
    const [intervalMsg, setIntervalMsg] = createSignal(null);
    const [logsInterval, setLogsInterval] = createSignal(logsRefreshInterval());
    const [usageInterval, setUsageInterval] = createSignal(usageRefreshInterval());
    const [prefsMsg, setPrefsMsg] = createSignal(null);
    const [iamLogWarnings, setIamLogWarnings] = createSignal(true);
    const [iamLogMsg, setIamLogMsg] = createSignal(null);

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

    const SETTINGS_TABS = [
        { id: 'preferences', label: 'Preferences' },
        { id: 'cloud', label: 'Cloud & Routing' },
        { id: 'about', label: 'About' },
    ];
    const SETTINGS_TAB_IDS = SETTINGS_TABS.map(tab => tab.id);
    const [settingsTab, setSettingsTab] = createUrlBackedTab('tab', SETTINGS_TAB_IDS, 'preferences', { history: 'replace' });
    const switchTab = (tabId) => setSettingsTab(tabId);

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

    const applyLogsInterval = () => {
        const val = logsInterval();
        if (val < 1 || val > 60) { setPrefsMsg('Logs interval must be 1-60 seconds.'); setTimeout(() => setPrefsMsg(null), 3000); return; }
        setLogsRefreshInterval(val);
        setPrefsMsg('Applied.'); setTimeout(() => setPrefsMsg(null), 2000);
    };

    const applyIamLogWarnings = async () => {
        const newVal = !iamLogWarnings();
        try {
            const resp = await fetch('/config/iam', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ logWarnings: newVal })
            });
            if (resp.ok) {
                setIamLogWarnings(newVal);
                setIamLogMsg('Applied.');
            } else {
                setIamLogMsg('Failed to update.');
            }
        } catch (e) {
            setIamLogMsg('Error: ' + e.message);
        }
        setTimeout(() => setIamLogMsg(null), 2000);
    };

    const applyUsageInterval = () => {
        const val = usageInterval();
        if (val < 1 || val > 120) { setPrefsMsg('Usage interval must be 1-120 seconds.'); setTimeout(() => setPrefsMsg(null), 3000); return; }
        setUsageRefreshInterval(val);
        setPrefsMsg('Applied.'); setTimeout(() => setPrefsMsg(null), 2000);
    };

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

            {/* ===== PREFERENCES TAB ===== */}
            <Show when={settingsTab() === 'preferences'}>

            {/* Appearance */}
            <div class="section">
                <div class="section-title">Appearance</div>
                <div class="card" style={{ padding: "0" }}>
                    <div class="settings-row" style={{ padding: "16px 20px" }}>
                        <div class="settings-row-info">
                            <label class="settings-row-label">Dark Mode</label>
                            <div class="settings-row-desc">Toggle between light and dark theme.</div>
                        </div>
                        <div class="settings-row-action">
                            <label class="toggle-switch">
                                <input type="checkbox" checked={props.darkMode?.()} onChange={props.toggleDarkMode} aria-label="Toggle dark mode" />
                                <span class="toggle-slider" />
                            </label>
                        </div>
                    </div>
                </div>
            </div>

            {/* Auto-Refresh */}
            <div class="section">
                <div class="section-title">Auto-Refresh</div>
                <div class="card" style={{ padding: "0" }}>
                    <div class="settings-row" style={{ padding: "16px 20px" }}>
                        <div class="settings-row-info">
                            <label class="settings-row-label" for="settings-health-refresh">Health Refresh</label>
                            <div class="settings-row-desc">How often to poll for health data (1-60 seconds).</div>
                        </div>
                        <div class="settings-row-action">
                            <div class="input-group">
                                <input
                                    id="settings-health-refresh"
                                    name="settings-health-refresh"
                                    autocomplete="off"
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
                    <div class="settings-row" style={{ padding: "16px 20px", "border-top": "1px solid var(--border)" }}>
                        <div class="settings-row-info">
                            <label class="settings-row-label" for="settings-logs-refresh">Logs Auto-Refresh</label>
                            <div class="settings-row-desc">Polling interval for the Logs page (1-60 seconds). Toggle on/off in the Logs page.</div>
                        </div>
                        <div class="settings-row-action">
                            <div class="input-group">
                                <input
                                    id="settings-logs-refresh"
                                    name="settings-logs-refresh"
                                    autocomplete="off"
                                    type="number"
                                    min="1"
                                    max="60"
                                    value={logsInterval()}
                                    onInput={e => setLogsInterval(parseInt(e.currentTarget.value) || 3)}
                                />
                                <span class="input-group-suffix">sec</span>
                                <button class="btn btn-primary" onClick={applyLogsInterval}>Apply</button>
                            </div>
                        </div>
                    </div>
                    <div class="settings-row" style={{ padding: "16px 20px", "border-top": "1px solid var(--border)" }}>
                        <div class="settings-row-info">
                            <label class="settings-row-label" for="settings-usage-refresh">Usage Auto-Refresh</label>
                            <div class="settings-row-desc">Polling interval for the Usage &amp; Pricing page (1-120 seconds). Toggle on/off in the Usage page.</div>
                        </div>
                        <div class="settings-row-action">
                            <div class="input-group">
                                <input
                                    id="settings-usage-refresh"
                                    name="settings-usage-refresh"
                                    autocomplete="off"
                                    type="number"
                                    min="1"
                                    max="120"
                                    value={usageInterval()}
                                    onInput={e => setUsageInterval(parseInt(e.currentTarget.value) || 30)}
                                />
                                <span class="input-group-suffix">sec</span>
                                <button class="btn btn-primary" onClick={applyUsageInterval}>Apply</button>
                            </div>
                        </div>
                    </div>
                    <div class="settings-row" style={{ padding: "16px 20px", "border-top": "1px solid var(--border)" }}>
                        <div class="settings-row-info">
                            <label class="settings-row-label" for="settings-iam-log-warnings">IAM Warning Logging</label>
                            <div class="settings-row-desc">Log a warning for each IAM operation (setIamPolicy, getIamPolicy, testIamPermissions) indicating policies are stored but NOT enforced.</div>
                        </div>
                        <div class="settings-row-action">
                            <label class="toggle-switch">
                                <input type="checkbox" checked={iamLogWarnings()}
                                    onChange={applyIamLogWarnings}
                                    aria-label="Toggle IAM warning logging" />
                                <span class="toggle-slider" />
                            </label>
                        </div>
                    </div>
                    <Show when={intervalMsg() || prefsMsg() || iamLogMsg()}>
                        <div style={{ padding: "0 20px 12px", "font-size": "12px", color: "var(--success)" }} aria-live="polite">
                            {intervalMsg() || prefsMsg() || iamLogMsg()}
                        </div>
                    </Show>
                </div>
            </div>


            {/* Keyboard Shortcuts */}
            <div class="section">
                <div class="section-title">Keyboard Shortcuts</div>
                <p style={{ "margin-bottom": "14px", "font-size": "13px", color: "var(--text-secondary)" }}>
                    Available keyboard shortcuts in the LocalCloud Console.
                </p>
                <div class="card" style={{ padding: "16px 24px" }}>
                    <div style={{ display: "flex", "flex-direction": "column", gap: "12px" }}>
                        {[
                            { keys: typeof navigator !== 'undefined' && navigator.platform?.includes('Mac') ? '⌘K' : 'Ctrl+K', desc: 'Command palette — search services, tables, logs' },
                            { keys: '⌘Enter', desc: 'Run current SQL query' },
                            { keys: 'Esc', desc: 'Close command palette / modals' },
                        ].map(s => (
                            <div style={{ display: "flex", "align-items": "center", gap: "14px" }}>
                                <kbd style={{
                                    "min-width": "68px",
                                    display: "inline-flex",
                                    "align-items": "center",
                                    "justify-content": "center",
                                    padding: "4px 10px",
                                    "font-family": "var(--font-mono)",
                                    "font-size": "12px",
                                    "font-weight": "700",
                                    "border-radius": "6px",
                                    border: "1px solid var(--border)",
                                    background: "var(--surface-variant)",
                                    color: "var(--text)",
                                }}>{s.keys}</kbd>
                                <span style={{ "font-size": "13px", color: "var(--text-secondary)" }}>{s.desc}</span>
                            </div>
                        ))}
                    </div>
                </div>
            </div>
            {/* Export */}
            <div class="section">
                <div class="section-title">Export</div>
                <p style={{ "margin-bottom": "12px" }}>
                    Download a lightweight state manifest (schema &amp; structure only, no row data). Actual data persists in the mounted volume.
                </p>
                <button class="btn btn-secondary" onClick={async () => {
                    try {
                        const text = await api.export();
                        const blob = new Blob([text], { type: 'application/yaml' });
                        const url = URL.createObjectURL(blob);
                        const a = document.createElement('a');
                        a.href = url;
                        a.download = `localcloud-state-${new Date().toISOString().slice(0,10)}.yaml`;
                        a.click();
                        URL.revokeObjectURL(url);
                    } catch (e) { console.error('Export failed:', e); }
                }}>
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
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
                            const resp = await fetch('/reseed', { method: 'POST' });
                            const data = await resp.json();
                            if (data.error) { alert('Seed failed: ' + data.message); return; }
                            alert('Seed complete: ' + (data.total_records || 0) + ' records loaded across ' + Object.keys(data.services || {}).length + ' services');
                        } catch (e) { alert('Seed failed: ' + e.message); }
                    }}>
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4"/></svg>
                        Re-seed Data
                    </button>
                </div>
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
                                    <div class="alert alert-error" role="alert" style={{ "margin-top": "8px", "margin-bottom": "0" }}>
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
                                                if (props.onRoutingChanged) props.onRoutingChanged();
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
                        <div class="loading-state"><div class="loading-spinner" /> Checking connection…</div>
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
                                    {remoteDisconnecting() ? 'Disconnecting…' : 'Disconnect'}
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
            {/* ===== ABOUT TAB ===== */}
            <Show when={settingsTab() === 'about'}>
                <AboutPage healthData={props.healthData} />
            </Show>
        </div>
    );
}

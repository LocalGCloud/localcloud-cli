import { createSignal, createEffect, Show, For } from 'solid-js';
import { api } from '../api.js';

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

    const Code = (p) => (
        <div class="code-block" style={{ "margin-bottom": "12px", "font-size": "12px", "line-height": "1.7" }}>
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
                            <Code>docker compose up -d</Code>
                            <Text>Wait for the health check to pass, then configure your shell:</Text>
                            <Code>{`eval "$(curl -s http://localhost:8080/_localcloud/env?format=shell)"`}</Code>
                        </Section>
                        <Section title="2. Use GCP SDKs as normal">
                            <Text>Your application code works against LocalCloud with zero changes. The environment variables tell Google Cloud SDKs to connect to localhost instead of real GCP.</Text>
                            <Code>{`from google.cloud import storage
client = storage.Client()
bucket = client.create_bucket("my-bucket")
print(f"Created: {bucket.name}")`}</Code>
                        </Section>
                        <Section title="3. View data in the console">
                            <Text>Use the Data Browser in the left sidebar to browse buckets, topics, secrets, databases, and more. Use this Settings page to copy environment variables or export state.</Text>
                        </Section>
                    </Show>

                    <Show when={activeTab() === 'sdk'}>
                        <Section title="Environment Variables">
                            <Text>Set these variables in your shell or application environment. Each one tells the corresponding Google Cloud SDK to connect to LocalCloud instead of real GCP:</Text>
                            <Code>{`export STORAGE_EMULATOR_HOST="http://localhost:4443"
export PUBSUB_EMULATOR_HOST="localhost:8085"
export FIRESTORE_EMULATOR_HOST="localhost:8086"
export BIGTABLE_EMULATOR_HOST="localhost:8087"
export SPANNER_EMULATOR_HOST="localhost:9010"
export BIGQUERY_EMULATOR_HOST="http://localhost:9050"
export SECRET_MANAGER_EMULATOR_HOST="localhost:8080"
export CLOUD_TASKS_EMULATOR_HOST="localhost:8080"
export GOOGLE_CLOUD_PROJECT="local-project"`}</Code>
                        </Section>
                        <Section title="Auto-configure (recommended)">
                            <Text>Instead of setting variables manually, use the auto-configure endpoint to set all variables at once:</Text>
                            <Code>{`eval "$(curl -s http://localhost:8080/_localcloud/env?format=shell)"`}</Code>
                            <Text>This sets all required variables for every enabled service, including the project ID.</Text>
                        </Section>
                        <Section title="Docker Compose apps">
                            <Text>For multi-container apps, use the service name "localcloud" as the hostname instead of "localhost":</Text>
                            <Code>{`# docker-compose.yml
services:
  my-app:
    environment:
      STORAGE_EMULATOR_HOST: "http://localcloud:4443"
      PUBSUB_EMULATOR_HOST: "localcloud:8085"
      FIRESTORE_EMULATOR_HOST: "localcloud:8086"
      GOOGLE_CLOUD_PROJECT: "local-project"
    depends_on:
      localcloud:
        condition: service_healthy`}</Code>
                        </Section>
                    </Show>

                    <Show when={activeTab() === 'gcloud'}>
                        <Section title="Configure gcloud CLI to use LocalCloud">
                            <Text>The gcloud CLI can be pointed to LocalCloud so commands like "gcloud pubsub topics list" query your local emulator instead of real GCP:</Text>
                            <Code>{`# Set the auto-configure endpoint
eval "$(curl -s http://localhost:8080/_localcloud/env?format=shell)"

# Now gcloud commands hit LocalCloud:
gcloud pubsub topics list
gcloud secrets list
gcloud spanner instances list`}</Code>
                        </Section>
                        <Section title="How it works">
                            <Text>LocalCloud sets CLOUDSDK_* environment variables that override gcloud CLI's default endpoints:</Text>
                            <Code>{`CLOUDSDK_CORE_PROJECT="local-project"
CLOUDSDK_API_ENDPOINT_OVERRIDES_PUBSUB="http://localhost:8085"
CLOUDSDK_API_ENDPOINT_OVERRIDES_SPANNER="http://localhost:9020"
CLOUDSDK_AUTH_ACCESS_TOKEN="localcloud-dev-token"`}</Code>
                            <Text>The access token bypasses authentication since LocalCloud runs in permissive IAM mode by default.</Text>
                        </Section>
                        <Section title="Per-project gcloud commands">
                            <Text>If you have multiple projects, specify the project in your gcloud commands:</Text>
                            <Code>{`gcloud pubsub topics list --project=staging
gcloud secrets list --project=dev`}</Code>
                        </Section>
                    </Show>

                    <Show when={activeTab() === 'revert'}>
                        <Section title="Switch back to real GCP">
                            <Text>To stop using LocalCloud and point your SDKs back to real Google Cloud, unset all emulator environment variables:</Text>
                            <Code>{`unset STORAGE_EMULATOR_HOST
unset PUBSUB_EMULATOR_HOST
unset FIRESTORE_EMULATOR_HOST
unset BIGTABLE_EMULATOR_HOST
unset SPANNER_EMULATOR_HOST
unset BIGQUERY_EMULATOR_HOST
unset SECRET_MANAGER_EMULATOR_HOST
unset CLOUD_TASKS_EMULATOR_HOST
unset CLOUD_LOGGING_EMULATOR_HOST
unset CLOUD_MONITORING_EMULATOR_HOST
unset REDIS_HOST`}</Code>
                        </Section>
                        <Section title="Revert gcloud CLI">
                            <Text>To revert gcloud CLI overrides:</Text>
                            <Code>{`unset CLOUDSDK_CORE_PROJECT
unset CLOUDSDK_AUTH_ACCESS_TOKEN
unset CLOUDSDK_API_ENDPOINT_OVERRIDES_PUBSUB
unset CLOUDSDK_API_ENDPOINT_OVERRIDES_SPANNER
# ... or simply open a new terminal session`}</Code>
                            <Text>Opening a new terminal window is the simplest way to revert, since all LocalCloud variables are session-scoped.</Text>
                        </Section>
                        <Section title="Zero code changes">
                            <Text>Your application code does not need any changes to switch between LocalCloud and real GCP. The SDKs automatically detect the emulator environment variables and route traffic accordingly. Remove the variables, and traffic goes to real GCP.</Text>
                        </Section>
                    </Show>

                    <Show when={activeTab() === 'seed'}>
                        <Section title="Loading seed data">
                            <Text>Seed files define initial state for services using YAML. Load them on startup or into a running instance:</Text>
                            <Code>{`# Load into a running instance
curl -X POST http://localhost:8080/_localcloud/seed \\
  -H "Content-Type: application/yaml" --data-binary @seed.yaml

# Or mount on startup (docker-compose.yml)
volumes:
  - ./seed.yaml:/etc/localcloud/seed.yaml:ro`}</Code>
                        </Section>
                        <Section title="Seed file format">
                            <Code>{`version: "1.0"
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
          - name: "worker"`}</Code>
                        </Section>
                        <Section title="Reset data">
                            <Text>Reset all emulator data or restore the last loaded seed:</Text>
                            <Code>{`# Reset all data
curl -X POST http://localhost:8080/_localcloud/reset

# Reset and restore last seed
curl -X POST http://localhost:8080/_localcloud/reset \\
  -H "Content-Type: application/json" \\
  -d '{"restore_seed": true}'`}</Code>
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
                            <Code>{`# Browse secrets for a specific project
curl http://localhost:8080/_localcloud/browse/secretmanager?project=staging

# Get env vars for a specific project
curl "http://localhost:8080/_localcloud/env?format=json&project=dev"

# Reset only one project's data
curl -X POST http://localhost:8080/_localcloud/reset?project=staging`}</Code>
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
    const [copyMsg, setCopyMsg] = createSignal(null);
    const [intervalInput, setIntervalInput] = createSignal(Math.floor(props.refreshInterval() / 1000));
    const [intervalMsg, setIntervalMsg] = createSignal(null);
    const [showGuide, setShowGuide] = createSignal(false);

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

    const envText = () => {
        const vars = envVars();
        if (!vars) return '';
        return Object.entries(vars)
            .map(([k, v]) => `export ${k}="${v}"`)
            .join('\n');
    };

    const handleCopy = async () => {
        try {
            await navigator.clipboard.writeText(envText());
            setCopyMsg('Copied to clipboard!');
            setTimeout(() => setCopyMsg(null), 2000);
        } catch {
            setCopyMsg('Copy failed');
            setTimeout(() => setCopyMsg(null), 2000);
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

    return (
        <div>
            <div class="page-header">
                <h1>Settings</h1>
            </div>

            {/* Environment Variables */}
            <div class="section">
                <div class="section-title">Environment Variables</div>
                <p style={{ "margin-bottom": "12px" }}>
                    Copy these variables into your shell to connect client libraries to LocalCloud.
                </p>
                <Show when={envError()}>
                    <div class="alert alert-error">{envError()}</div>
                </Show>
                <Show when={envLoading()}>
                    <div class="loading-state"><div class="loading-spinner" /> Loading...</div>
                </Show>
                <Show when={envVars()}>
                    <div class="code-block">{envText()}</div>
                    <div style={{ "margin-top": "12px", display: "flex", "align-items": "center", gap: "10px" }}>
                        <button class="btn btn-secondary" onClick={handleCopy}>
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                            Copy to Clipboard
                        </button>
                        <Show when={copyMsg()}>
                            <span style={{ "font-size": "12px", color: "var(--success)" }}>{copyMsg()}</span>
                        </Show>
                    </div>
                </Show>
            </div>

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

            {/* User Guide */}
            <div class="section">
                <div class="section-title">User Guide</div>
                <div class="card" style={{ padding: "0" }}>
                    <div class="settings-row" style={{ padding: "16px 20px" }}>
                        <div class="settings-row-info">
                            <div class="settings-row-label">Setup & Configuration Guide</div>
                            <div class="settings-row-desc">How to connect SDKs, configure gcloud CLI, load seed data, and revert to real GCP.</div>
                        </div>
                        <div class="settings-row-action">
                            <button class="btn btn-primary" onClick={() => setShowGuide(true)}>
                                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"/><path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"/></svg>
                                Open Guide
                            </button>
                        </div>
                    </div>
                </div>
            </div>

            {/* Guide Modal */}
            <Show when={showGuide()}>
                <UserGuideModal onClose={() => setShowGuide(false)} />
            </Show>

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
        </div>
    );
}

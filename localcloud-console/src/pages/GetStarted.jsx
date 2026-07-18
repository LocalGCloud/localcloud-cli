import { createSignal, Show, For } from 'solid-js';
import { GCP_REGIONS, getZonesForRegion } from '../data/gcpLocations.js';
import { createUrlBackedTab } from '../utils/urlTabs.js';

const SDK_SNIPPETS = [
    {
        lang: 'Python',
        code: `from google.cloud import storage
from google.cloud import pubsub_v1

# Use GCS
client = storage.Client()
bucket = client.create_bucket("my-bucket")
print(f"Created: {bucket.name}")

# Use Pub/Sub
publisher = pubsub_v1.PublisherClient()
topic_path = publisher.topic_path("local-project", "my-topic")
topic = publisher.create_topic(name=topic_path)
print(f"Created topic: {topic.name}")`,
    },
    {
        lang: 'gcloud CLI',
        code: `# List GCS buckets
gsutil ls

# List Pub/Sub topics
gcloud pubsub topics list

# List Secrets
gcloud secrets list

# List Spanner instances
gcloud spanner instances list

# Run BigQuery queries
bq query "SELECT 1 AS test"`,
    },
    {
        lang: 'Go',
        code: `import (
    "cloud.google.com/go/storage"
    "cloud.google.com/go/pubsub"
)

func main() {
    ctx := context.Background()

    // Use GCS
    client, _ := storage.NewClient(ctx)
    bucket := client.Bucket("my-bucket")
    bucket.Create(ctx, "local-project", nil)

    // Use Pub/Sub
    pubClient, _ := pubsub.NewClient(ctx, "local-project")
    topic, _ := pubClient.CreateTopic(ctx, "my-topic")
    topic.Stop()
}`,
    },
    {
        lang: 'Node.js',
        code: `const {Storage} = require('@google-cloud/storage');
const {PubSub} = require('@google-cloud/pubsub');

// Use GCS
const storage = new Storage();
const [bucket] = await storage.createBucket('my-bucket');
console.log('Created:', bucket.name);

// Use Pub/Sub
const pubsub = new PubSub();
const [topic] = await pubsub.createTopic('my-topic');
console.log('Created topic:', topic.name);`,
    },
    {
        lang: 'Java',
        code: `import com.google.cloud.storage.*;
import com.google.cloud.pubsub.v1.*;

// Use GCS
Storage storage = StorageOptions.getDefaultInstance().getService();
Bucket bucket = storage.create(
    BucketInfo.of("my-bucket"));
System.out.println("Created: " + bucket.getName());

// Use Pub/Sub
TopicAdminClient pubsub = TopicAdminClient.create();
TopicName topic = TopicName.of("local-project", "my-topic");
pubsub.createTopic(topic);
System.out.println("Topic created: " + topic.getTopic());`,
    },
    {
        lang: 'Terraform',
        code: `# LocalCloud emulates GCP APIs — Terraform works natively
# Configure the provider to use LocalCloud
provider "google" {
  project = "local-project"
  region  = "us-central1"

  # Override endpoints to use LocalCloud
  storage_custom_endpoint    = "http://localhost:4443"
  secret_manager_custom_endpoint = "http://localhost:8080"
  cloud_tasks_custom_endpoint = "http://localhost:8080"
}

resource "google_storage_bucket" "example" {
  name     = "my-terraform-bucket"
  location = "US"
}

resource "google_secret_manager_secret" "example" {
  secret_id = "my-secret"
  replication {
    auto {}
  }
}`,
    },
];

export default function GetStarted(props) {
    const [copiedEval, setCopiedEval] = createSignal(false);
    const [activeSdkTab, setActiveSdkTab] = createUrlBackedTab('sdk', SDK_SNIPPETS.map(s => s.lang), 'Python', { history: 'replace' });
    const [copiedSdk, setCopiedSdk] = createSignal(false);

    const healthData = () => props.healthData?.();
    const services = () => healthData()?.services || {};
    const healthyCount = () => Object.values(services()).filter(s => s.status === 'healthy').length;
    const totalCount = () => Object.keys(services()).length;
    const shortVersion = () => {
        const raw = String(healthData()?.version || '0.1.0').trim();
        const base = raw.split('+')[0]?.trim() || raw;
        return base.startsWith('v') ? base : `v${base}`;
    };

    const autoConfigCmd = `eval "$(curl -s http://localhost:8080/env?format=shell)"`;

    const copyEval = async () => {
        try {
            await navigator.clipboard.writeText(autoConfigCmd);
            setCopiedEval(true);
            setTimeout(() => setCopiedEval(false), 2000);
        } catch {}
    };

    const copySdk = async () => {
        try {
            await navigator.clipboard.writeText(SDK_SNIPPETS.find(s => s.lang === activeSdkTab())?.code || '');
            setCopiedSdk(true);
            setTimeout(() => setCopiedSdk(false), 2000);
        } catch {}
    };

    const recentServiceItems = () => {
        const recent = props.recentServices?.() || [];
        if (recent.length === 0) return [];
        const allServices = {
            gcs: { label: 'Cloud Storage', icon: 'gcs', desc: 'Object storage (buckets, blobs)' },
            pubsub: { label: 'Pub/Sub', icon: 'pubsub', desc: 'Async messaging & event ingestion' },
            spanner: { label: 'Spanner', icon: 'spanner', desc: 'Globally distributed SQL database' },
            bigquery: { label: 'BigQuery', icon: 'bigquery', desc: 'Serverless data warehouse & SQL analytics' },
            bigtable: { label: 'Bigtable', icon: 'bigtable', desc: 'Wide-column NoSQL for large analytical workloads' },
            memorystore: { label: 'Memorystore', icon: 'memorystore', desc: 'Managed Redis / Valkey' },
            cloudsql: { label: 'Cloud SQL', icon: 'cloudsql', desc: 'Managed PostgreSQL / MySQL' },
            alloydb: { label: 'AlloyDB', icon: 'alloydb', desc: 'PostgreSQL-compatible for enterprise workloads' },
            secretmanager: { label: 'Secret Manager', icon: 'secretmanager', desc: 'Store and manage secrets' },
            kms: { label: 'Cloud KMS', icon: 'kms', desc: 'Manage encryption keys' },
            cloudiam: { label: 'Cloud IAM', icon: 'cloudiam', desc: 'Identity & access management' },
            cloudrun: { label: 'Cloud Run', icon: 'cloudrun', desc: 'Serverless container platform' },
            gke: { label: 'GKE', icon: 'gke', desc: 'Managed Kubernetes clusters' },
            compute: { label: 'Compute Engine', icon: 'compute', desc: 'Virtual machine instances' },
            dataproc: { label: 'Dataproc', icon: 'dataproc', desc: 'Managed Spark & Hadoop' },
            cloudtasks: { label: 'Cloud Tasks', icon: 'cloudtasks', desc: 'Distributed task queues' },
            workflows: { label: 'Workflows', icon: 'workflows', desc: 'Orchestrate services with YAML workflows' },
            cloudscheduler: { label: 'Cloud Scheduler', icon: 'cloudscheduler', desc: 'Managed cron job scheduler' },
            cloudfunctions: { label: 'Cloud Functions', icon: 'cloudfunctions', desc: 'Event-driven serverless functions' },
            logging: { label: 'Logging', icon: 'logging', desc: 'Real-time log management & analysis' },
            monitoring: { label: 'Monitoring', icon: 'monitoring', desc: 'Metrics, dashboards & alerting' },
        };
        return recent.map(id => ({ id, ...(allServices[id] || { label: id, icon: 'gcs', desc: '' }) }));
    };

    return (
        <div class="get-started">
            {/* ── Hero ── */}
            <div class="gs-hero">
                <div class="gs-hero-left">
                    <h1 class="gs-hero-title">Welcome to LocalCloud</h1>
                    <p class="gs-hero-subtitle">
                        Run Google Cloud services locally for development and testing.
                        Zero config, zero cost, one command.
                    </p>
                    <div class="gs-hero-cmd">
                        <code>{autoConfigCmd}</code>
                        <button class="gs-hero-copy" onClick={copyEval}>
                            <Show when={copiedEval()} fallback={
                                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                            }>
                                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><polyline points="20 6 9 17 4 12"/></svg>
                            </Show>
                            {copiedEval() ? 'Copied!' : 'Copy'}
                        </button>
                    </div>
                    <p class="gs-hero-hint">Run this in your terminal, then use any GCP SDK as normal. All traffic routes to LocalCloud.</p>
                </div>
                <div class="gs-hero-right">
                    <div class="gs-hero-stat">
                        <span class="gs-hero-stat-value">{totalCount() || '—'}</span>
                        <span class="gs-hero-stat-label">Services</span>
                    </div>
                    <div class="gs-hero-stat">
                        <span class="gs-hero-stat-value" style={{ color: healthyCount() > 0 ? 'var(--success, #34a853)' : 'var(--text-tertiary)' }}>{healthyCount() || '—'}</span>
                        <span class="gs-hero-stat-label">Healthy</span>
                    </div>
                    <div class="gs-hero-stat">
                        <span class="gs-hero-stat-value">{shortVersion()}</span>
                        <span class="gs-hero-stat-label">Version</span>
                    </div>
                </div>
            </div>

            {/* ── Two-column: quick actions + recently used ── */}
            <div class="gs-grid">
                {/* Quick Start */}
                <div class="gs-card">
                    <h2 class="gs-card-title">
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" style="opacity:0.7"><path d="M13 3c-4.97 0-9 4.03-9 9s4.03 9 9 9c.83 0 1.5-.67 1.5-1.5 0-.39-.15-.74-.39-1.01-.23-.26-.38-.61-.38-.99 0-.83.67-1.5 1.5-1.5H16c2.76 0 5-2.24 5-5 0-4.42-4.03-8-9-8zm-5.5 9c-.83 0-1.5-.67-1.5-1.5S6.67 9 7.5 9 9 9.67 9 10.5 8.33 12 7.5 12zm3-4C8.67 8 8 7.33 8 6.5S8.67 5 10.5 5s2.5.67 2.5 1.5S11.33 8 10.5 8zm5 0c-.83 0-1.5-.67-1.5-1.5S14.67 5 15.5 5s2.5.67 2.5 1.5S16.33 8 15.5 8zm3 4c-.83 0-1.5-.67-1.5-1.5S17.67 9 18.5 9s2.5.67 2.5 1.5-.67 1.5-1.5 1.5z"/></svg>
                        Quick Start
                    </h2>
                    <div class="gs-quick-items">
                        <button class="gs-quick-item" onClick={() => props.onServiceClick('gcs')}>
                            <img src="/icons/gcs.svg" alt="" width="22" height="22" />
                            <div>
                                <strong>Cloud Storage</strong>
                                <small>Create a bucket, upload files</small>
                            </div>
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="opacity:0.5"><path d="M9 18l6-6-6-6"/></svg>
                        </button>
                        <button class="gs-quick-item" onClick={() => props.onServiceClick('bigquery')}>
                            <img src="/icons/bigquery.svg" alt="" width="22" height="22" />
                            <div>
                                <strong>BigQuery</strong>
                                <small>Run SQL queries, explore datasets</small>
                            </div>
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="opacity:0.5"><path d="M9 18l6-6-6-6"/></svg>
                        </button>
                        <button class="gs-quick-item" onClick={() => props.onServiceClick('pubsub')}>
                            <img src="/icons/pubsub.svg" alt="" width="22" height="22" />
                            <div>
                                <strong>Pub/Sub</strong>
                                <small>Create topics, publish messages</small>
                            </div>
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="opacity:0.5"><path d="M9 18l6-6-6-6"/></svg>
                        </button>
                        <button class="gs-quick-item" onClick={() => props.onServiceClick('spanner')}>
                            <img src="/icons/spanner.svg" alt="" width="22" height="22" />
                            <div>
                                <strong>Spanner</strong>
                                <small>Create instances, run SQL</small>
                            </div>
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="opacity:0.5"><path d="M9 18l6-6-6-6"/></svg>
                        </button>
                    </div>
                </div>

                {/* Recently Used */}
                <Show when={recentServiceItems().length > 0}>
                    <div class="gs-card">
                        <h2 class="gs-card-title">
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" style="opacity:0.7"><path d="M13 3a9 9 0 0 0-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42A8.954 8.954 0 0 0 13 21a9 9 0 0 0 0-18zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z"/></svg>
                            Recently Used
                        </h2>
                        <div class="gs-quick-items">
                            <For each={recentServiceItems()}>
                                {(svc) => (
                                    <button class="gs-quick-item" onClick={() => props.onServiceClick(svc.id)}>
                                        <img src={`/icons/${svc.icon}.svg`} alt="" width="22" height="22" />
                                        <div>
                                            <strong>{svc.label}</strong>
                                            <small>{svc.desc}</small>
                                        </div>
                                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="opacity:0.5"><path d="M9 18l6-6-6-6"/></svg>
                                    </button>
                                )}
                            </For>
                        </div>
                    </div>
                </Show>
            </div>

            {/* ── SDK Code Samples ── */}
            <div class="gs-card" style={{ 'margin-top': '24px' }}>
                <h2 class="gs-card-title">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" style="opacity:0.7"><path d="M9.4 16.6L4.8 12l4.6-4.6L8 6l-6 6 6 6 1.4-1.4zm5.2 0l4.6-4.6-4.6-4.6L16 6l6 6-6 6-1.4-1.4z"/></svg>
                    SDK Code Samples
                </h2>
                <p style="font-size:13px;color:var(--text-secondary);margin-bottom:12px">
                    After running the auto-configure command, use any GCP SDK as normal. All traffic routes to LocalCloud.
                </p>
                {/* Language tabs */}
                <div class="gs-sdk-tabs">
                    <For each={SDK_SNIPPETS.map(s => s.lang)}>
                        {(lang) => (
                            <button
                                classList={{ 'gs-sdk-tab': true, 'active': activeSdkTab() === lang }}
                                onClick={() => { setActiveSdkTab(lang); setCopiedSdk(false); }}
                            >
                                {lang}
                            </button>
                        )}
                    </For>
                </div>
                {/* Code block */}
                <div class="gs-code-block">
                    <button class="gs-code-copy" onClick={copySdk}>
                        <Show when={copiedSdk()} fallback={
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                        }>
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><polyline points="20 6 9 17 4 12"/></svg>
                        </Show>
                        {copiedSdk() ? 'Copied!' : 'Copy'}
                    </button>
                    <pre class="gs-code-pre">{SDK_SNIPPETS.find(s => s.lang === activeSdkTab())?.code || ''}</pre>
                </div>
            </div>

            {/* ── More Resources ── */}
            <div class="gs-card" style={{ 'margin-top': '24px' }}>
                <h2 class="gs-card-title">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" style="opacity:0.7"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z"/></svg>
                    More Resources
                </h2>
                <div class="gs-resources">
                    <a href="https://github.com/localcloud" target="_blank" rel="noopener noreferrer" class="gs-resource-item">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M12 0c-6.626 0-12 5.373-12 12 0 5.302 3.438 9.8 8.207 11.387.599.111.793-.261.793-.577v-2.234c-3.338.726-4.033-1.416-4.033-1.416-.546-1.387-1.333-1.756-1.333-1.756-1.089-.745.083-.729.083-.729 1.205.084 1.839 1.237 1.839 1.237 1.07 1.834 2.807 1.304 3.492.997.107-.775.418-1.305.762-1.604-2.665-.305-5.467-1.334-5.467-5.931 0-1.311.469-2.381 1.236-3.221-.124-.303-.535-1.524.117-3.176 0 0 1.008-.322 3.301 1.23.957-.266 1.983-.399 3.003-.404 1.02.005 2.047.138 3.006.404 2.291-1.552 3.297-1.23 3.297-1.23.653 1.653.242 2.874.118 3.176.77.84 1.235 1.911 1.235 3.221 0 4.609-2.807 5.624-5.479 5.921.43.372.823 1.102.823 2.222v3.293c0 .319.192.694.801.576 4.765-1.589 8.199-6.086 8.199-11.386 0-6.627-5.373-12-12-12z"/></svg>
                        <span>GitHub Repository</span>
                        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="opacity:0.4"><path d="M7 17L17 7M7 7h10v10"/></svg>
                    </a>
                    <a href="/docs" target="_blank" rel="noopener noreferrer" class="gs-resource-item">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><circle cx="12" cy="12" r="10"/><path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>
                        <span>API Documentation</span>
                        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="opacity:0.4"><path d="M7 17L17 7M7 7h10v10"/></svg>
                    </a>
                    <button onClick={() => window.location.href = '/settings'} class="gs-resource-item">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>
                        <span>Setup & SDKs — all env vars and code samples</span>
                        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="opacity:0.4"><path d="M9 18l6-6-6-6"/></svg>
                    </button>
                </div>
            </div>
        </div>
    );
}

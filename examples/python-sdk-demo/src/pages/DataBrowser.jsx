/**
 * DataBrowser Page
 * Tabbed interface for browsing data in emulated services
 * Supports Firestore, BigQuery, Cloud Storage, and Spanner preview
 * Read-only view of data stored in each service
 */

import { createSignal, Show, For } from 'solid-js';

export function DataBrowser(props) {
  // Service tabs available for browsing
  const serviceTabs = [
    { id: 'gcs', label: 'Cloud Storage' },
    { id: 'firestore', label: 'Firestore' },
    { id: 'bigquery', label: 'BigQuery' },
    { id: 'pubsub', label: 'Pub/Sub' },
    { id: 'spanner', label: 'Spanner' },
    { id: 'secretmanager', label: 'Secret Manager' }
  ];

  const [activeTab, setActiveTab] = createSignal('gcs');
  const [browseResult, setBrowseResult] = createSignal(null);
  const [loading, setLoading] = createSignal(false);
  const [error, setError] = createSignal(null);
  const [browsePath, setBrowsePath] = createSignal('');

  // Fetch data for active tab
  const fetchData = async (path = '') => {
    setLoading(true);
    setError(null);
    try {
      const data = await props.onBrowse(activeTab(), path);
      setBrowseResult(data);
    } catch (err) {
      setError(`Failed to fetch data: ${err.message}`);
      setBrowseResult(null);
    } finally {
      setLoading(false);
    }
  };

  // Handle tab change
  const switchTab = (tabId) => {
    setActiveTab(tabId);
    setBrowsePath('');
    setBrowseResult(null);
    fetchData();
  };

  // Handle navigating deeper into data
  const navigateTo = (path) => {
    setBrowsePath(path);
    fetchData(path);
  };

  // Initial data load
  fetchData();

  return (
    <div class="page databrowser-page">
      <div class="page-header">
        <h2>Data Browser</h2>
        <p class="subtitle">Browse data stored in emulated services (read-only)</p>
      </div>

      {/* Service Tabs */}
      <div class="browser-tabs">
        <For each={serviceTabs}>
          {(tab) => (
            <button
              class={`tab-btn ${activeTab() === tab.id ? 'active' : ''}`}
              onclick={() => switchTab(tab.id)}
            >
              {tab.label}
            </button>
          )}
        </For>
      </div>

      {/* Breadcrumb path for navigation */}
      <Show when={browsePath()}>
        <div class="breadcrumb">
          <button class="breadcrumb-link" onclick={() => navigateTo('')}>
            {activeTab()}
          </button>
          <For each={browsePath().split('/')}>
            {(segment, idx) => (
              <>
                <span class="breadcrumb-separator">/</span>
                <button
                  class="breadcrumb-link"
                  onclick={() =>
                    navigateTo(
                      browsePath()
                        .split('/')
                        .slice(0, idx() + 1)
                        .join('/')
                    )
                  }
                >
                  {segment}
                </button>
              </>
            )}
          </For>
        </div>
      </Show>

      {/* Data Display */}
      <div class="browse-content">
        <Show when={loading()}>
          <p class="loading-text">Loading...</p>
        </Show>

        <Show when={error()}>
          <p class="error-text">{error()}</p>
        </Show>

        <Show when={!loading() && !error() && browseResult()}>
          <ServiceDataView
            service={activeTab()}
            data={browseResult()}
            onNavigate={navigateTo}
          />
        </Show>
      </div>
    </div>
  );
}

/**
 * ServiceDataView
 * Renders service-specific data based on the active tab
 */
function ServiceDataView(props) {
  return (
    <div class="data-view">
      <Show when={props.service === 'gcs'}>
        <GCSView data={props.data} onNavigate={props.onNavigate} />
      </Show>

      <Show when={props.service === 'firestore'}>
        <FirestoreView data={props.data} onNavigate={props.onNavigate} />
      </Show>

      <Show when={props.service === 'bigquery'}>
        <BigQueryView data={props.data} onNavigate={props.onNavigate} />
      </Show>

      <Show when={props.service === 'pubsub'}>
        <PubSubView data={props.data} />
      </Show>

      <Show when={props.service === 'spanner'}>
        <SpannerView data={props.data} onNavigate={props.onNavigate} />
      </Show>

      <Show when={props.service === 'secretmanager'}>
        <SecretManagerView data={props.data} />
      </Show>
    </div>
  );
}

/**
 * GCS Data View - Shows buckets and objects
 */
function GCSView(props) {
  return (
    <div class="gcs-view">
      {/* Display buckets */}
      <Show when={props.data.buckets}>
        <h4>Buckets ({props.data.buckets.length})</h4>
        <table class="data-table">
          <thead>
            <tr>
              <th>Name</th>
              <th>Created</th>
              <th>Location</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            <For each={props.data.buckets}>
              {(bucket) => (
                <tr>
                  <td>{bucket.name}</td>
                  <td>{bucket.timeCreated || 'N/A'}</td>
                  <td>{bucket.location || 'US'}</td>
                  <td>
                    <button
                      class="btn btn-sm btn-link"
                      onclick={() => props.onNavigate(`buckets/${bucket.name}`)}
                    >
                      Browse
                    </button>
                  </td>
                </tr>
              )}
            </For>
          </tbody>
        </table>
      </Show>

      {/* Display objects */}
      <Show when={props.data.objects}>
        <h4>Objects ({props.data.objects.length})</h4>
        <table class="data-table">
          <thead>
            <tr>
              <th>Name</th>
              <th>Size</th>
              <th>Content Type</th>
              <th>Updated</th>
            </tr>
          </thead>
          <tbody>
            <For each={props.data.objects}>
              {(obj) => (
                <tr>
                  <td>{obj.name}</td>
                  <td>{obj.size} bytes</td>
                  <td>{obj.contentType || 'application/octet-stream'}</td>
                  <td>{obj.updated || 'N/A'}</td>
                </tr>
              )}
            </For>
          </tbody>
        </table>
      </Show>

      {/* Fallback if neither buckets nor objects */}
      <Show when={!props.data.buckets && !props.data.objects}>
        <JSONView data={props.data} />
      </Show>
    </div>
  );
}

/**
 * Firestore Data View - Shows collections and documents
 */
function FirestoreView(props) {
  return (
    <div class="firestore-view">
      <Show when={props.data.collections}>
        <h4>Collections ({props.data.collections.length})</h4>
        <div class="collection-list">
          <For each={props.data.collections}>
            {(collection) => (
              <div
                class="collection-item clickable"
                onclick={() => props.onNavigate(`documents/${collection.name || collection}`)}
              >
                <span class="icon">📁</span>
                {typeof collection === 'string' ? collection : collection.name}
              </div>
            )}
          </For>
        </div>
      </Show>

      <Show when={props.data.documents}>
        <h4>Documents ({props.data.documents.length})</h4>
        <div class="document-list">
          <For each={props.data.documents}>
            {(doc) => (
              <div class="document-item">
                <h5 class="doc-path">{doc.name || doc.path}</h5>
                <pre class="doc-data">{JSON.stringify(doc.fields || doc, null, 2)}</pre>
              </div>
            )}
          </For>
        </div>
      </Show>

      <Show when={!props.data.collections && !props.data.documents}>
        <JSONView data={props.data} />
      </Show>
    </div>
  );
}

/**
 * BigQuery Data View - Shows datasets and tables
 */
function BigQueryView(props) {
  return (
    <div class="bigquery-view">
      <Show when={props.data.datasets}>
        <h4>Datasets ({props.data.datasets.length})</h4>
        <table class="data-table">
          <thead>
            <tr>
              <th>Dataset ID</th>
              <th>Location</th>
              <th>Created</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            <For each={props.data.datasets}>
              {(ds) => (
                <tr>
                  <td>{ds.datasetId || ds.id}</td>
                  <td>{ds.location || 'US'}</td>
                  <td>{ds.creationTime || 'N/A'}</td>
                  <td>
                    <button
                      class="btn btn-sm btn-link"
                      onclick={() =>
                        props.onNavigate(`datasets/${ds.datasetId || ds.id}`)
                      }
                    >
                      Browse Tables
                    </button>
                  </td>
                </tr>
              )}
            </For>
          </tbody>
        </table>
      </Show>

      <Show when={props.data.tables}>
        <h4>Tables ({props.data.tables.length})</h4>
        <table class="data-table">
          <thead>
            <tr>
              <th>Table ID</th>
              <th>Type</th>
              <th>Row Count</th>
            </tr>
          </thead>
          <tbody>
            <For each={props.data.tables}>
              {(table) => (
                <tr>
                  <td>{table.tableId || table.id}</td>
                  <td>{table.type || 'TABLE'}</td>
                  <td>{table.numRows || 0}</td>
                </tr>
              )}
            </For>
          </tbody>
        </table>
      </Show>

      <Show when={!props.data.datasets && !props.data.tables}>
        <JSONView data={props.data} />
      </Show>
    </div>
  );
}

/**
 * Pub/Sub Data View - Shows topics and subscriptions
 */
function PubSubView(props) {
  return (
    <div class="pubsub-view">
      <Show when={props.data.topics}>
        <h4>Topics ({props.data.topics.length})</h4>
        <table class="data-table">
          <thead>
            <tr>
              <th>Topic Name</th>
            </tr>
          </thead>
          <tbody>
            <For each={props.data.topics}>
              {(topic) => (
                <tr>
                  <td>{topic.name || topic}</td>
                </tr>
              )}
            </For>
          </tbody>
        </table>
      </Show>

      <Show when={props.data.subscriptions}>
        <h4>Subscriptions ({props.data.subscriptions.length})</h4>
        <table class="data-table">
          <thead>
            <tr>
              <th>Subscription Name</th>
              <th>Topic</th>
              <th>Ack Deadline</th>
            </tr>
          </thead>
          <tbody>
            <For each={props.data.subscriptions}>
              {(sub) => (
                <tr>
                  <td>{sub.name || sub}</td>
                  <td>{sub.topic || 'N/A'}</td>
                  <td>{sub.ackDeadlineSeconds || 10}s</td>
                </tr>
              )}
            </For>
          </tbody>
        </table>
      </Show>

      <Show when={!props.data.topics && !props.data.subscriptions}>
        <JSONView data={props.data} />
      </Show>
    </div>
  );
}

/**
 * Spanner Data View - Shows instances and databases
 */
function SpannerView(props) {
  return (
    <div class="spanner-view">
      <Show when={props.data.instances}>
        <h4>Instances ({props.data.instances.length})</h4>
        <table class="data-table">
          <thead>
            <tr>
              <th>Instance Name</th>
              <th>State</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            <For each={props.data.instances}>
              {(inst) => (
                <tr>
                  <td>{inst.name || inst}</td>
                  <td>{inst.state || 'READY'}</td>
                  <td>
                    <button
                      class="btn btn-sm btn-link"
                      onclick={() => props.onNavigate(`instances/${inst.name || inst}`)}
                    >
                      Browse DBs
                    </button>
                  </td>
                </tr>
              )}
            </For>
          </tbody>
        </table>
      </Show>

      <Show when={props.data.databases}>
        <h4>Databases ({props.data.databases.length})</h4>
        <table class="data-table">
          <thead>
            <tr>
              <th>Database Name</th>
              <th>State</th>
            </tr>
          </thead>
          <tbody>
            <For each={props.data.databases}>
              {(db) => (
                <tr>
                  <td>{db.name || db}</td>
                  <td>{db.state || 'READY'}</td>
                </tr>
              )}
            </For>
          </tbody>
        </table>
      </Show>

      <Show when={!props.data.instances && !props.data.databases}>
        <JSONView data={props.data} />
      </Show>
    </div>
  );
}

/**
 * Secret Manager Data View - Shows secrets (values redacted)
 */
function SecretManagerView(props) {
  return (
    <div class="secretmanager-view">
      <Show when={props.data.secrets}>
        <h4>Secrets ({props.data.secrets.length})</h4>
        <table class="data-table">
          <thead>
            <tr>
              <th>Secret Name</th>
              <th>Versions</th>
              <th>Created</th>
            </tr>
          </thead>
          <tbody>
            <For each={props.data.secrets}>
              {(secret) => (
                <tr>
                  <td>{secret.name}</td>
                  <td>{secret.versionCount || 'N/A'}</td>
                  <td>{secret.createTime || 'N/A'}</td>
                </tr>
              )}
            </For>
          </tbody>
        </table>
        <p class="info-text">Secret values are redacted for security.</p>
      </Show>

      <Show when={!props.data.secrets}>
        <JSONView data={props.data} />
      </Show>
    </div>
  );
}

/**
 * Generic JSON viewer as fallback for unknown data shapes
 */
function JSONView(props) {
  return (
    <div class="json-view">
      <pre class="json-display">{JSON.stringify(props.data, null, 2)}</pre>
    </div>
  );
}

/**
 * Logs Page
 * Displays request logs with filtering and auto-tail functionality
 * Shows HTTP method, path, status code, duration, and sizes
 */

import { createSignal, Show, For, onCleanup } from 'solid-js';

export function Logs(props) {
  const [requests, setRequests] = createSignal([]);
  const [filter, setFilter] = createSignal('');
  const [limit, setLimit] = createSignal(50);
  const [autoTail, setAutoTail] = createSignal(true);
  const [loading, setLoading] = createSignal(false);

  // Fetch logs
  const fetchLogs = async () => {
    setLoading(true);
    try {
      const data = await props.onFetchLogs({
        limit: limit(),
        service: filter() || undefined
      });
      setRequests(data.requests || []);
    } catch (error) {
      console.error('Failed to fetch logs:', error);
    } finally {
      setLoading(false);
    }
  };

  // Auto-refresh logs
  const setupAutoRefresh = () => {
    if (!autoTail()) return;

    const interval = setInterval(() => {
      fetchLogs();
    }, props.refreshInterval * 1000);

    onCleanup(() => clearInterval(interval));
  };

  // Initial load and setup
  fetchLogs();
  setupAutoRefresh();

  const formatTime = (isoString) => {
    if (!isoString) return 'N/A';
    const date = new Date(isoString);
    return date.toLocaleTimeString();
  };

  const formatDuration = (ms) => {
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(2)}s`;
  };

  const formatSize = (bytes) => {
    if (!bytes) return '0 B';
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(2)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
  };

  const getStatusClass = (code) => {
    if (code < 300) return 'status-success';
    if (code < 400) return 'status-redirect';
    if (code < 500) return 'status-client-error';
    return 'status-server-error';
  };

  return (
    <div class="page logs-page">
      <div class="page-header">
        <h2>Request Logs</h2>
        <p class="subtitle">API Request History & Activity</p>
      </div>

      {/* Controls */}
      <div class="logs-controls">
        <div class="control-group">
          <label for="service-filter">Service Filter:</label>
          <select
            id="service-filter"
            value={filter()}
            onchange={(e) => setFilter(e.target.value)}
          >
            <option value="">All Services</option>
            <option value="gcs">Cloud Storage (GCS)</option>
            <option value="pubsub">Pub/Sub</option>
            <option value="firestore">Firestore</option>
            <option value="bigquery">BigQuery</option>
            <option value="secretmanager">Secret Manager</option>
            <option value="cloudtasks">Cloud Tasks</option>
            <option value="logging">Logging</option>
            <option value="monitoring">Monitoring</option>
          </select>
        </div>

        <div class="control-group">
          <label for="log-limit">Limit:</label>
          <select
            id="log-limit"
            value={limit()}
            onchange={(e) => setLimit(parseInt(e.target.value))}
          >
            <option value="25">25</option>
            <option value="50" selected>
              50
            </option>
            <option value="100">100</option>
            <option value="200">200</option>
          </select>
        </div>

        <label class="checkbox-label">
          <input
            type="checkbox"
            checked={autoTail()}
            onchange={(e) => setAutoTail(e.target.checked)}
          />
          Auto-tail
        </label>

        <button class="btn btn-primary" onclick={fetchLogs} disabled={loading()}>
          {loading() ? 'Loading...' : 'Refresh'}
        </button>
      </div>

      {/* Logs Table */}
      <div class="logs-table-wrapper">
        <Show
          when={requests().length > 0}
          fallback={<p class="empty-state">No requests logged yet.</p>}
        >
          <table class="logs-table">
            <thead>
              <tr>
                <th>Time</th>
                <th>Service</th>
                <th>Method</th>
                <th>Path</th>
                <th>Status</th>
                <th>Duration</th>
                <th>Request Size</th>
                <th>Response Size</th>
              </tr>
            </thead>
            <tbody>
              <For each={requests()}>
                {(request) => (
                  <tr>
                    <td class="time">{formatTime(request.timestamp)}</td>
                    <td class="service">
                      <code>{request.service}</code>
                    </td>
                    <td class="method">
                      <code class={`method-${request.method.toLowerCase()}`}>
                        {request.method}
                      </code>
                    </td>
                    <td class="path">
                      <code class="path-code">{request.path}</code>
                    </td>
                    <td class="status">
                      <span class={`status-code ${getStatusClass(request.status_code)}`}>
                        {request.status_code}
                      </span>
                    </td>
                    <td class="duration">{formatDuration(request.duration_ms)}</td>
                    <td class="size">{formatSize(request.request_size)}</td>
                    <td class="size">{formatSize(request.response_size)}</td>
                  </tr>
                )}
              </For>
            </tbody>
          </table>
        </Show>
      </div>

      {/* Info Panel */}
      <div class="logs-info-panel">
        <h3>About Request Logs</h3>
        <p>
          Request logs are stored in a ring buffer of the last 1000 requests. Logs are automatically
          cleared when the platform restarts unless persistent storage is enabled.
        </p>
      </div>
    </div>
  );
}

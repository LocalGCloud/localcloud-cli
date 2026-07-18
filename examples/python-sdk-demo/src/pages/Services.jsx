/**
 * Services Page
 * Displays all services in a table format with controls
 * Shows detailed information about each service
 */

import { createSignal, Show, For } from 'solid-js';
import { StatusBadge } from '../components/StatusBadge';

export function Services(props) {
  const [filterStatus, setFilterStatus] = createSignal('all');

  const filteredServices = () => {
    const status = filterStatus();
    if (status === 'all') return props.services || [];
    return (props.services || []).filter((s) => s.status === status);
  };

  const copyToClipboard = (text) => {
    navigator.clipboard.writeText(text);
  };

  return (
    <div class="page services-page">
      <div class="page-header">
        <h2>Services</h2>
        <p class="subtitle">All Emulated GCP Services</p>
      </div>

      {/* Filter Controls */}
      <div class="services-controls">
        <div class="filter-group">
          <label for="status-filter">Filter by Status:</label>
          <select
            id="status-filter"
            value={filterStatus()}
            onchange={(e) => setFilterStatus(e.target.value)}
          >
            <option value="all">All Services</option>
            <option value="running">Running Only</option>
            <option value="stopped">Stopped Only</option>
            <option value="error">Error Only</option>
          </select>
        </div>

        <div class="service-count">
          {filteredServices().length} service{filteredServices().length !== 1 ? 's' : ''}
        </div>
      </div>

      {/* Services Table */}
      <div class="services-table-wrapper">
        <Show
          when={filteredServices().length > 0}
          fallback={<p class="empty-state">No services match the filter.</p>}
        >
          <table class="services-table">
            <thead>
              <tr>
                <th>Service Name</th>
                <th>Status</th>
                <th>Protocol</th>
                <th>Port</th>
                <th>Endpoint</th>
                <th>Environment Variable</th>
                <th>Requests</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              <For each={filteredServices()}>
                {(service) => (
                  <tr>
                    <td class="service-name">{service.name}</td>
                    <td class="status-cell">
                      <StatusBadge status={service.status} />
                    </td>
                    <td class="protocol">
                      <code>{service.protocol?.toUpperCase() || 'REST'}</code>
                    </td>
                    <td class="port">
                      <code>{service.port}</code>
                    </td>
                    <td class="endpoint">
                      <code>{service.endpoint || `http://localhost:${service.port}`}</code>
                    </td>
                    <td class="env-var">
                      <Show when={service.env_var}>
                        <code>{service.env_var}</code>
                      </Show>
                    </td>
                    <td class="request-count">{service.request_count || 0}</td>
                    <td class="actions">
                      <button
                        class="btn btn-sm btn-outline"
                        onclick={() => {
                          if (service.env_var && service.env_value) {
                            copyToClipboard(`${service.env_var}=${service.env_value}`);
                          }
                        }}
                        title="Copy environment variable"
                      >
                        Copy Env
                      </button>
                    </td>
                  </tr>
                )}
              </For>
            </tbody>
          </table>
        </Show>
      </div>

      {/* Service Details Section */}
      <div class="service-info-panel">
        <h3>Service Configuration Reference</h3>
        <p class="info-text">
          Use the environment variables above to configure your application. Example:
        </p>
        <pre class="code-block">
          <code>export STORAGE_EMULATOR_HOST=http://localhost:8080</code>
        </pre>
        <p class="info-text">
          Or run <code>eval "$(curl -s http://localhost:8080/env?format=shell)"</code> to export all variables at once.
        </p>
      </div>
    </div>
  );
}

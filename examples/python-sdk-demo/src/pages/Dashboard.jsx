/**
 * Dashboard Page
 * Main landing page showing:
 * - Overall platform health status
 * - 3-column grid of service cards
 * - Quick stats (uptime, total requests, etc.)
 */

import { createSignal, Show, For } from 'solid-js';
import { ServiceCard } from '../components/ServiceCard';
import { StatusBadge } from '../components/StatusBadge';

export function Dashboard(props) {
  const formatUptime = (seconds) => {
    if (!seconds) return 'N/A';
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    const secs = seconds % 60;

    if (hours > 0) return `${hours}h ${minutes}m`;
    if (minutes > 0) return `${minutes}m ${secs}s`;
    return `${secs}s`;
  };

  const getTotalRequests = () => {
    return props.services?.reduce((sum, service) => {
      return sum + (service.request_count || 0);
    }, 0) || 0;
  };

  return (
    <div class="page dashboard-page">
      <div class="page-header">
        <h2>Dashboard</h2>
        <p class="subtitle">Platform Status & Service Overview</p>
      </div>

      {/* Health Status Bar */}
      <Show when={props.health}>
        {(health) => (
          <div class="status-overview">
            <div class="status-item">
              <label>Platform Status:</label>
              <StatusBadge status={health().status} />
            </div>
            <div class="status-item">
              <label>Uptime:</label>
              <span class="uptime">{formatUptime(health().uptime_seconds)}</span>
            </div>
            <div class="status-item">
              <label>Total Requests:</label>
              <span class="request-count">{getTotalRequests()}</span>
            </div>
            <div class="status-item">
              <label>Data Directory:</label>
              <code class="data-dir">{health().data_dir || '/var/lib/localcloud'}</code>
            </div>
          </div>
        )}
      </Show>

      {/* Service Cards Grid - 3 columns */}
      <div class="services-grid">
        <Show
          when={props.services && props.services.length > 0}
          fallback={<p class="empty-state">Loading services...</p>}
        >
          <For each={props.services}>
            {(service) => (
              <ServiceCard
                service={service}
                onRefresh={props.onRefreshService}
              />
            )}
          </For>
        </Show>
      </div>

      {/* Quick Actions */}
      <div class="dashboard-actions">
        <h3>Quick Actions</h3>
        <div class="action-buttons">
          <button class="btn btn-primary" onclick={props.onResetServices}>
            Reset All Services
          </button>
          <button class="btn btn-secondary" onclick={props.onExportEnv}>
            Export Environment
          </button>
        </div>
      </div>
    </div>
  );
}

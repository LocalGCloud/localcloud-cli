import { createSignal, createEffect, Show } from 'solid-js';
import { api } from '../api.js';

export function Settings() {
  const [health, setHealth] = createSignal(null);
  const [error, setError] = createSignal('');

  createEffect(async () => {
    try {
      const h = await api.health();
      setHealth(h);
    } catch (e) {
      setError(e.message);
    }
  });

  return (
    <div>
      <div class="page-header">
        <div>
          <h2>System</h2>
          <p>Server configuration and health status</p>
        </div>
      </div>

      <div class="detail-card">
        <h3>Database Health</h3>
        <Show when={health()}>
          <div class="detail-grid">
            <div class="field">
              <label>Status</label>
              <span>
                <span class={`status-dot ${health().status === 'ok' ? 'online' : 'offline'}`}></span>
                {health().status === 'ok' ? 'Online' : health().status}
              </span>
            </div>
            <div class="field">
              <label>Database</label>
              <span>{health().database ?? '-'}</span>
            </div>
            <div class="field">
              <label>Offline Keys</label>
              <span class="mono">{health().offline_keys_enabled ? 'Enabled' : 'Disabled (set LOCALCLOUD_LICENSE_OFFLINE_PRIVATE_KEY)'}</span>
            </div>
          </div>
        </Show>
        <Show when={!health() && !error()}>
          <div class="loading">Loading...</div>
        </Show>
        <Show when={error()}>
          <div class="error-msg">{error()}</div>
        </Show>
      </div>

      <div class="detail-card">
        <h3>Configuration</h3>
        <div class="detail-grid">
          <div class="field"><label>Admin UI</label><span>Served at /admin/</span></div>
          <div class="field"><label>API</label><span>REST at /admin/api/*</span></div>
          <div class="field"><label>Auth</label><span>Session-based (ADMIN_PASSWORD)</span></div>
          <div class="field"><label>Version</label><span>0.1.0</span></div>
        </div>
      </div>
    </div>
  );
}

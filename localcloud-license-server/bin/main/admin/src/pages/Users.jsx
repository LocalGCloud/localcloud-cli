import { createSignal, createEffect, For, Show } from 'solid-js';
import { api } from '../api.js';

export function Users() {
  const [users, setUsers] = createSignal([]);
  const [query, setQuery] = createSignal('');
  const [selected, setSelected] = createSignal(null);
  const [detail, setDetail] = createSignal(null);
  const [error, setError] = createSignal('');
  const [loading, setLoading] = createSignal(false);

  const load = async () => {
    setError('');
    if (selected()) return;
    try {
      const d = await api.listUsers(query());
      setUsers(d);
    } catch (e) { setError(e.message); }
  };

  createEffect(load);

  const openDetail = async (id) => {
    setSelected(id);
    setLoading(true);
    try {
      const d = await api.getUser(id);
      setDetail(d);
    } catch (e) { setError(e.message); }
    setLoading(false);
  };

  return (
    <div>
      <div class="page-header">
        <div>
          <h2>Users</h2>
          <p>View and manage registered users</p>
        </div>
        <Show when={selected()}>
          <button class="secondary" onClick={() => { setSelected(null); setDetail(null); }}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <line x1="19" y1="12" x2="5" y2="12" /><polyline points="12 19 5 12 12 5" />
            </svg>
            Back
          </button>
        </Show>
      </div>

      <Show when={!selected()}>
        <div class="filters">
          <label for="user-search" class="sr-only">Search users</label>
          <input id="user-search" placeholder="Search by email..." value={query()}
                 onInput={(e) => setQuery(e.target.value)} />
        </div>
        <Show when={error()}><div class="error-msg">{error()}</div></Show>
        <div class="table-wrap">
          <div class="table-header">
            <h3>All Users</h3>
            <span class="count">{users().length} users</span>
          </div>
          <div class="data-table-wrapper">
            <table class="data-table">
              <thead><tr>
                <th>Email</th><th>Verified</th><th>Status</th><th>Keys</th><th>Trials</th><th>Devices</th><th>Created</th>
              </tr></thead>
              <tbody>
                <For each={users()}>{(u) => (
                  <tr class="clickable" onClick={() => openDetail(u.id)}>
                    <td>{u.email}</td>
                    <td><span class={`badge ${u.email_verified ? 'active' : 'expired'}`}>{u.email_verified ? 'Yes' : 'No'}</span></td>
                    <td><span class="badge active">{u.status}</span></td>
                    <td><span class="mono">{u.key_count}</span></td>
                    <td><span class="mono">{u.trial_count}</span></td>
                    <td><span class="mono">{u.device_count}</span></td>
                    <td>{new Date(u.created_at * 1000).toLocaleDateString()}</td>
                  </tr>
                )}</For>
                <Show when={users().length === 0}>
                  <tr><td colspan="7"><div class="empty-state">No users found</div></td></tr>
                </Show>
              </tbody>
            </table>
          </div>
        </div>
      </Show>

      <Show when={selected() && detail()}>
        <div class="detail-card">
          <h3>{detail().email}</h3>
          <div class="detail-grid">
            <div class="field"><label>ID</label><span class="mono">{detail().id}</span></div>
            <div class="field"><label>Verified</label><span class={`badge ${detail().email_verified ? 'active' : 'expired'}`}>{detail().email_verified ? 'Yes' : 'No'}</span></div>
            <div class="field"><label>Status</label><span class="badge active">{detail().status}</span></div>
            <div class="field"><label>Created</label><span>{new Date(detail().created_at * 1000).toLocaleString()}</span></div>
          </div>
        </div>

        <Show when={detail().keys && detail().keys.length > 0}>
          <div class="detail-card">
            <h3>Keys ({detail().keys.length})</h3>
            <div class="data-table-wrapper">
              <table class="data-table">
                <thead><tr><th>Prefix</th><th>Tier</th><th>Status</th><th>Created</th></tr></thead>
                <tbody>
                  <For each={detail().keys}>{(k) => (
                    <tr>
                      <td><span class="mono">{k.key_prefix}...</span></td>
                      <td><span class={`badge ${k.tier}`}>{k.tier}</span></td>
                      <td><span class={`badge ${k.revoked_at ? 'revoked' : k.expires_at && k.expires_at * 1000 < Date.now() ? 'expired' : 'active'}`}>
                        {k.revoked_at ? 'Revoked' : k.expires_at && k.expires_at * 1000 < Date.now() ? 'Expired' : 'Active'}
                      </span></td>
                      <td>{new Date(k.created_at * 1000).toLocaleDateString()}</td>
                    </tr>
                  )}</For>
                </tbody>
              </table>
            </div>
          </div>
        </Show>

        <Show when={detail().devices && detail().devices.length > 0}>
          <div class="detail-card">
            <h3>Devices ({detail().devices.length})</h3>
            <div class="data-table-wrapper">
              <table class="data-table">
                <thead><tr><th>Fingerprint</th><th>First Seen</th><th>Last Seen</th></tr></thead>
                <tbody>
                  <For each={detail().devices}>{(d) => (
                    <tr>
                      <td><span class="mono">{d.device_fingerprint}</span></td>
                      <td>{new Date(d.first_seen * 1000).toLocaleString()}</td>
                      <td>{new Date(d.last_seen * 1000).toLocaleString()}</td>
                    </tr>
                  )}</For>
                </tbody>
              </table>
            </div>
          </div>
        </Show>

        <Show when={detail().trials && detail().trials.length > 0}>
          <div class="detail-card">
            <h3>Trials ({detail().trials.length})</h3>
            <div class="data-table-wrapper">
              <table class="data-table">
                <thead><tr><th>Started</th><th>Expires</th><th>Status</th></tr></thead>
                <tbody>
                  <For each={detail().trials}>{(t) => (
                    <tr>
                      <td>{new Date(t.started_at * 1000).toLocaleDateString()}</td>
                      <td>{new Date(t.expires_at * 1000).toLocaleDateString()}</td>
                      <td><span class={`badge ${t.expires_at * 1000 > Date.now() ? 'active' : 'expired'}`}>
                        {t.expires_at * 1000 > Date.now() ? 'Active' : 'Expired'}
                      </span></td>
                    </tr>
                  )}</For>
                </tbody>
              </table>
            </div>
          </div>
        </Show>
      </Show>
    </div>
  );
}

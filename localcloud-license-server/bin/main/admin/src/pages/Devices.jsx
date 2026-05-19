import { createSignal, createEffect, For, Show } from 'solid-js';
import { api } from '../api.js';

export function Devices() {
  const [devices, setDevices] = createSignal([]);
  const [query, setQuery] = createSignal('');
  const [error, setError] = createSignal('');

  createEffect(async () => {
    try {
      const d = await api.listDevices(query());
      setDevices(d);
    } catch (e) { setError(e.message); }
  });

  return (
    <div>
      <div class="page-header">
        <div>
          <h2>Devices</h2>
          <p>Track registered devices and activity</p>
        </div>
      </div>
      <div class="filters">
        <label for="device-search" class="sr-only">Search devices</label>
        <input id="device-search" placeholder="Search by fingerprint or email..." value={query()}
               onInput={(e) => setQuery(e.target.value)} />
      </div>
      <Show when={error()}><div class="error-msg">{error()}</div></Show>
      <div class="table-wrap">
        <div class="table-header">
          <h3>All Devices</h3>
          <span class="count">{devices().length} devices</span>
        </div>
        <div class="data-table-wrapper">
          <table class="data-table">
            <thead><tr>
              <th>Email</th><th>Fingerprint</th><th>First Seen</th><th>Last Seen</th>
            </tr></thead>
            <tbody>
              <For each={devices()}>{(d) => (
                <tr>
                  <td>{d.email}</td>
                  <td><span class="mono">{d.device_fingerprint}</span></td>
                  <td>{new Date(d.first_seen * 1000).toLocaleString()}</td>
                  <td>{new Date(d.last_seen * 1000).toLocaleString()}</td>
                </tr>
              )}</For>
              <Show when={devices().length === 0}>
                <tr><td colspan="4"><div class="empty-state">No devices found</div></td></tr>
              </Show>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}

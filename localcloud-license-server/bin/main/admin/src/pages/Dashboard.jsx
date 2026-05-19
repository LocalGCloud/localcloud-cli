import { createSignal, createEffect, For, Show } from 'solid-js';
import { api } from '../api.js';

export function Dashboard() {
  const [data, setData] = createSignal(null);
  const [error, setError] = createSignal('');

  createEffect(async () => {
    try {
      setError('');
      const s = await api.stats();
      setData(s);
    } catch (e) {
      setError(e.message);
    }
  });

  const cards = [
    { label: 'Total Keys', key: 'total_keys', color: '' },
    { label: 'Active Keys', key: 'active_keys', color: 'green' },
    { label: 'Online Keys', key: 'keys_online', color: 'blue' },
    { label: 'Offline Keys', key: 'keys_offline', color: 'orange' },
    { label: 'Expired Keys', key: 'expired_keys', color: 'red' },
    { label: 'Total Users', key: 'total_users', color: '' },
    { label: 'Verified Users', key: 'verified_users', color: 'green' },
    { label: 'Total Devices', key: 'total_devices', color: '' },
    { label: 'Active Trials', key: 'active_trials', color: 'yellow' },
    { label: 'Expired Trials', key: 'expired_trials', color: 'orange' },
  ];

  return (
    <div>
      <div class="page-header">
        <div>
          <h2>Dashboard</h2>
          <p>Overview of license server activity</p>
        </div>
      </div>
      <Show when={error()}><div class="error-msg">{error()}</div></Show>
      <Show when={data()}>
        <div class="stats-grid">
          <For each={cards}>
            {(c) => (
              <div class="stat-card">
                <div class="label">{c.label}</div>
                <div class={`value ${c.color}`}>{data()[c.key] ?? '-'}</div>
              </div>
            )}
          </For>
        </div>

        <div class="tier-card">
          <h3>Tier Distribution</h3>
          <Show when={data()}>
            <TierBar
              pro={data().keys_pro || 0}
              trial={data().keys_trial || 0}
              community={data().keys_community || 0}
            />
            <div class="tier-legend">
              <div class="tier-row">
                <span class="tier-label"><span class="tier-dot pro"></span>Pro</span>
                <span class="num">{data().keys_pro || 0}</span>
              </div>
              <div class="tier-row">
                <span class="tier-label"><span class="tier-dot trial"></span>Trial</span>
                <span class="num">{data().keys_trial || 0}</span>
              </div>
              <div class="tier-row">
                <span class="tier-label"><span class="tier-dot community"></span>Community</span>
                <span class="num">{data().keys_community || 0}</span>
              </div>
            </div>
          </Show>
        </div>
      </Show>
      <Show when={!data() && !error()}>
        <div class="loading">Loading dashboard data...</div>
      </Show>
    </div>
  );
}

function TierBar(props) {
  const total = () => props.pro + props.trial + props.community || 1;
  return (
    <div class="tier-bar">
      <Show when={props.pro > 0}>
        <div class="segment pro" style={{ width: (props.pro / total() * 100) + '%' }}></div>
      </Show>
      <Show when={props.trial > 0}>
        <div class="segment trial" style={{ width: (props.trial / total() * 100) + '%' }}></div>
      </Show>
      <Show when={props.community > 0}>
        <div class="segment community" style={{ width: (props.community / total() * 100) + '%' }}></div>
      </Show>
    </div>
  );
}

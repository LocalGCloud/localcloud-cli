import { createSignal, createEffect, For, Show } from 'solid-js';
import { api } from '../api.js';

function ChevronIcon(props) {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"
         style={{ transform: props.open ? 'rotate(180deg)' : 'rotate(0deg)', transition: 'transform 0.2s ease' }}>
      <polyline points="6 9 12 15 18 9" />
    </svg>
  );
}

function CopyButton(props) {
  const [copied, setCopied] = createSignal(false);

  const copy = async () => {
    await navigator.clipboard.writeText(props.text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <button class="icon-btn" onClick={copy} title={copied() ? 'Copied!' : 'Copy to clipboard'}>
      {copied()
        ? <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12" /></svg>
        : <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2" /><path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1" /></svg>
      }
    </button>
  );
}

function KeyCard(props) {
  const [showPrivate, setShowPrivate] = createSignal(false);
  const [confirmRotate, setConfirmRotate] = createSignal(false);

  const isOnline = props.keyType === 'online';
  const icon = isOnline ? '🔗' : '📴';
  const color = isOnline ? 'var(--green)' : 'var(--red)';
  const colorBg = isOnline ? 'var(--green-bg)' : 'var(--red-bg)';
  const algorithm = isOnline ? 'RSA-2048' : 'Ed25519';
  const description = isOnline
    ? 'Signs JWT tokens for online license keys (lc_on_*). Required for license validation.'
    : 'Signs offline license keys (lc_of_*). Required for offline key generation.';

  return (
    <div class="key-card">
      <div class="key-card-header">
        <div class="key-card-title">
          <span class="key-icon" style={{ background: colorBg, color }}>{icon}</span>
          <div>
            <h3>{isOnline ? 'Online Signing Key' : 'Offline Signing Key'}</h3>
            <p>{description}</p>
          </div>
        </div>
        <Show when={props.active}>
          <span class="badge active">active</span>
        </Show>
      </div>

      <Show when={props.active}
            fallback={
              <div class="key-card-empty">
                <div class="key-card-empty-icon" style={{ color }}>
                  {isOnline
                    ? <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" /></svg>
                    : <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" /></svg>
                  }
                </div>
                <p class="key-card-empty-text">No {props.keyType} signing key configured</p>
                <p class="key-card-empty-sub">
                  {isOnline ? 'Clients cannot validate licenses until this key is set.' : 'Offline key generation is disabled until this key is set.'}
                </p>
                <button onClick={() => props.onGenerate(props.keyType)} disabled={props.generating}>
                  {props.generating ? 'Generating...' : `Generate ${algorithm} Key`}
                </button>
              </div>
            }>
        <div class="key-card-details">
          <div class="detail-row">
            <label>Algorithm</label>
            <span class="mono">{algorithm}</span>
          </div>
          <div class="detail-row">
            <label>Created</label>
            <span>{new Date(props.active.created_at * 1000).toLocaleString()}</span>
          </div>
          <Show when={props.active.kid}>
            <div class="detail-row">
              <label>KID</label>
              <span class="mono">{props.active.kid}</span>
            </div>
          </Show>
          <div class="detail-row">
            <label>Public Key</label>
            <div class="key-value-row">
              <span class="mono key-value-text">{props.active.public_key.substring(0, 48)}...</span>
              <CopyButton text={props.active.public_key} />
            </div>
          </div>

          <div class="detail-row collapsible">
            <label class="collapsible-label" onClick={() => setShowPrivate(!showPrivate())}>
              Private Key
              <ChevronIcon open={showPrivate()} />
            </label>
            <Show when={showPrivate()}>
              <div class="key-value-block">
                <div class="key-value-row">
                  <span class="mono key-value-text-full">{props.active.private_key}</span>
                  <CopyButton text={props.active.private_key} />
                </div>
              </div>
            </Show>
          </div>
        </div>

        <div class="key-card-actions">
          <Show when={!confirmRotate()}
                fallback={
                  <div class="rotate-confirm">
                    <span>Rotate this key? The old key will be deactivated.</span>
                    <div class="rotate-confirm-actions">
                      <button class="danger" onClick={() => { props.onRotate(props.keyType); setConfirmRotate(false); }}>
                        Confirm Rotate
                      </button>
                      <button class="secondary" onClick={() => setConfirmRotate(false)}>Cancel</button>
                    </div>
                  </div>
                }>
            <button class="secondary" onClick={() => setConfirmRotate(true)} disabled={props.generating}>
              {props.generating ? 'Generating...' : 'Rotate Key'}
            </button>
          </Show>
        </div>
      </Show>
    </div>
  );
}

export function KeyPairs() {
  const [keyPairs, setKeyPairs] = createSignal([]);
  const [error, setError] = createSignal('');
  const [toast, setToast] = createSignal('');
  const [generating, setGenerating] = createSignal(null);
  const [health, setHealth] = createSignal(null);

  const load = async () => {
    setError('');
    try {
      const d = await api.listKeyPairs();
      setKeyPairs(d);
    } catch (e) { setError(e.message); }
  };

  const loadHealth = async () => {
    try { setHealth(await api.health()); } catch (_) {}
  };

  createEffect(() => { load(); loadHealth(); });

  const doGenerate = async (type) => {
    setGenerating(type);
    setError('');
    try {
      const res = type === 'online'
        ? await api.generateOnlineKeyPair()
        : await api.generateOfflineKeyPair();
      setToast(`New ${type} key pair generated and activated`);
      load();
      loadHealth();
      setTimeout(() => setToast(''), 3000);
    } catch (e) { setError(e.message); }
    setGenerating(null);
  };

  const online = () => keyPairs().filter(k => k.key_type === 'online');
  const offline = () => keyPairs().filter(k => k.key_type === 'offline');
  const activeOnline = () => online().find(k => k.status === 'active');
  const activeOffline = () => offline().find(k => k.status === 'active');

  return (
    <div>
      <div class="page-header">
        <div>
          <h2>Signing Keys</h2>
          <p>Manage the server's signing key pairs for license token generation</p>
        </div>
      </div>

      <Show when={error()}><div class="error-msg">{error()}</div></Show>

      <Show when={health() && !health().online_key_configured}>
        <div class="alert-banner warning">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z" /><line x1="12" y1="9" x2="12" y2="13" /><line x1="12" y1="17" x2="12.01" y2="17" /></svg>
          No online signing key configured — clients cannot validate licenses.
        </div>
      </Show>

      <div class="key-cards-grid">
        <KeyCard keyType="online" active={activeOnline()} generating={generating() === 'online'}
                 onGenerate={doGenerate} onRotate={doGenerate} />
        <KeyCard keyType="offline" active={activeOffline()} generating={generating() === 'offline'}
                 onGenerate={doGenerate} onRotate={doGenerate} />
      </div>

      <Show when={keyPairs().length > 0}>
        <div class="table-wrap" style="margin-top:24px;">
          <div class="table-header">
            <h3>Key History</h3>
            <span class="count">{keyPairs().length} keys</span>
          </div>
          <div class="data-table-wrapper">
            <table class="data-table">
              <thead><tr>
                <th>Type</th><th>Algorithm</th><th>KID</th><th>Status</th><th>Created</th><th>Rotated</th>
              </tr></thead>
              <tbody>
                <For each={keyPairs()}>{(k) => (
                  <tr>
                    <td><span class={`badge ${k.key_type}`}>{k.key_type}</span></td>
                    <td><span class="mono">{k.algorithm}</span></td>
                    <td><span class="mono">{k.kid || '-'}</span></td>
                    <td><span class={`badge ${k.status}`}>{k.status}</span></td>
                    <td>{new Date(k.created_at * 1000).toLocaleDateString()}</td>
                    <td>{k.rotated_at ? new Date(k.rotated_at * 1000).toLocaleDateString() : '-'}</td>
                  </tr>
                )}</For>
              </tbody>
            </table>
          </div>
        </div>
      </Show>

      <Show when={toast()}>
        <div class="toast">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12" /></svg>
          {toast()}
        </div>
      </Show>
    </div>
  );
}

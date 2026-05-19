import { createSignal, createEffect, For, Show } from 'solid-js';
import { api } from '../api.js';

export function Keys() {
  const [keys, setKeys] = createSignal([]);
  const [query, setQuery] = createSignal('');
  const [tierFilter, setTierFilter] = createSignal('');
  const [statusFilter, setStatusFilter] = createSignal('');
  const [sortConfig, setSortConfig] = createSignal({ key: 'created_at', direction: 'desc' });
  const [selected, setSelected] = createSignal(null);
  const [detail, setDetail] = createSignal(null);
  const [error, setError] = createSignal('');
  const [showGenerate, setShowGenerate] = createSignal(false);
  const [genEmail, setGenEmail] = createSignal('');
  const [genTier, setGenTier] = createSignal('pro');
  const [genMode, setGenMode] = createSignal('online');
  const [genResult, setGenResult] = createSignal(null);
  const [genSaving, setGenSaving] = createSignal(false);
  const [tiers, setTiers] = createSignal([]);
  const [toast, setToast] = createSignal('');
  const [copied, setCopied] = createSignal(false);
  const [genError, setGenError] = createSignal('');
  const [health, setHealth] = createSignal(null);
  const [keyPairs, setKeyPairs] = createSignal([]);

  const load = async () => {
    setError('');
    if (selected()) return;
    try {
      const d = await api.listKeys(query(), tierFilter(), statusFilter());
      setKeys(d);
    } catch (e) { setError(e.message); }
  };

  createEffect(load);

  createEffect(async () => {
    try { setTiers(await api.listTiers()); } catch (e) { /* non-critical */ }
  });

  createEffect(async () => {
    try {
      const h = await api.health();
      setHealth(h);
    } catch (e) { /* non-critical */ }
  });

  createEffect(async () => {
    try { setKeyPairs(await api.listKeyPairs()); } catch (e) { /* non-critical */ }
  });

  const onlineKeyExists = () => keyPairs().some(k => k.key_type === 'online' && k.status === 'active');
  const offlineKeyExists = () => keyPairs().some(k => k.key_type === 'offline' && k.status === 'active');

  const sortedKeys = () => {
    let sortableItems = [...keys()];
    const { key, direction } = sortConfig();
    if (key !== null) {
      sortableItems.sort((a, b) => {
        let aValue = a[key];
        let bValue = b[key];
        
        // special handling for computed columns
        if (key === 'status') {
          aValue = a.revoked_at ? 'revoked' : a.expires_at && a.expires_at * 1000 < Date.now() ? 'expired' : 'active';
          bValue = b.revoked_at ? 'revoked' : b.expires_at && b.expires_at * 1000 < Date.now() ? 'expired' : 'active';
        }

        if (aValue === null || aValue === undefined) return direction === 'asc' ? 1 : -1;
        if (bValue === null || bValue === undefined) return direction === 'asc' ? -1 : 1;

        if (aValue < bValue) return direction === 'asc' ? -1 : 1;
        if (aValue > bValue) return direction === 'asc' ? 1 : -1;
        return 0;
      });
    }
    return sortableItems;
  };

  const handleSort = (key) => {
    let direction = 'asc';
    if (sortConfig().key === key && sortConfig().direction === 'asc') {
      direction = 'desc';
    }
    setSortConfig({ key, direction });
  };

  const SortIcon = (props) => (
    <span style="margin-left:4px; font-size:10px; opacity:0.6;">
      {sortConfig().key === props.column
        ? (sortConfig().direction === 'asc' ? '▲' : '▼')
        : '↕'}
    </span>
  );

  const copyToClipboard = async (text) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setToast('Key copied to clipboard');
      setTimeout(() => { setCopied(false); setToast(''); }, 2000);
    } catch (e) {
      const el = document.querySelector('#gen-key-value');
      if (el) { el.select(); document.execCommand('copy'); }
    }
  };

  const openDetail = async (id) => {
    setSelected(id);
    try {
      const d = await api.getKey(id);
      setDetail(d);
    } catch (e) { setError(e.message); }
  };

  const doRevoke = async (id) => {
    if (!confirm('Revoke this key? This cannot be undone.')) return;
    try {
      await api.revokeKey(id);
      setToast('Key revoked');
      setDetail(null);
      setSelected(null);
      setTimeout(() => setToast(''), 3000);
    } catch (e) { setError(e.message); }
  };

  const doGenerate = async () => {
    const email = genEmail().trim().toLowerCase();
    if (!email || !email.includes('@')) return;
    setGenSaving(true);
    setCopied(false);
    setGenError('');
    try {
      const res = await api.generateKey(email, genTier(), genMode());
      setGenResult(res);
      setToast('Key generated');
    } catch (e) { setGenError(e.message); }
    setGenSaving(false);
  };

  const closeGenerate = () => {
    setShowGenerate(false);
    setGenResult(null);
    setGenEmail('');
    setGenError('');
    setError('');
    setCopied(false);
    load();
    setTimeout(() => setToast(''), 2000);
  };

  return (
    <div>
      <div class="page-header">
        <div>
          <h2>License Keys</h2>
          <p>Manage and generate license keys for your users</p>
        </div>
        <div class="actions">
          <Show when={!selected()}>
            <button onClick={() => { setShowGenerate(true); }}>
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <line x1="12" y1="5" x2="12" y2="19" /><line x1="5" y1="12" x2="19" y2="12" />
              </svg>
              Generate Key
            </button>
          </Show>
          <Show when={selected()}>
            <button class="secondary" onClick={() => { setSelected(null); setDetail(null); }}>
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <line x1="19" y1="12" x2="5" y2="12" /><polyline points="12 19 5 12 12 5" />
              </svg>
              Back
            </button>
          </Show>
        </div>
      </div>

      <Show when={!onlineKeyExists()}>
        <div class="alert-banner warning">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z" /><line x1="12" y1="9" x2="12" y2="13" /><line x1="12" y1="17" x2="12.01" y2="17" />
          </svg>
          <span>No online signing key configured. <a href="/admin/keypairs" onClick={(e) => { e.preventDefault(); localStorage.setItem('admin_page', 'keypairs'); window.location.reload(); }}>Go to Signing Keys</a> to generate one before creating online license keys.</span>
        </div>
      </Show>

      <Show when={!offlineKeyExists()}>
        <div class="alert-banner info">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <circle cx="12" cy="12" r="10" /><line x1="12" y1="16" x2="12" y2="12" /><line x1="12" y1="8" x2="12.01" y2="8" />
          </svg>
          <span>Offline signing key not configured. <a href="/admin/keypairs" onClick={(e) => { e.preventDefault(); localStorage.setItem('admin_page', 'keypairs'); window.location.reload(); }}>Go to Signing Keys</a> to generate one. Offline key generation will be unavailable until then.</span>
        </div>
      </Show>

      <Show when={!selected()}>
        <div class="filters">
          <label for="key-search" class="sr-only">Search keys</label>
          <input id="key-search" placeholder="Search by prefix or email..." value={query()}
                 onInput={(e) => setQuery(e.target.value)} />
          <label for="key-tier" class="sr-only">Filter by tier</label>
          <select id="key-tier" value={tierFilter()} onChange={(e) => setTierFilter(e.target.value)}>
            <option value="">All tiers</option>
            <option value="community">Community</option>
            <option value="pro">Pro</option>
            <option value="trial">Trial</option>
          </select>
          <label for="key-status" class="sr-only">Filter by status</label>
          <select id="key-status" value={statusFilter()} onChange={(e) => setStatusFilter(e.target.value)}>
            <option value="">All status</option>
            <option value="active">Active</option>
            <option value="revoked">Revoked</option>
            <option value="expired">Expired</option>
          </select>
        </div>
        <Show when={error()}><div class="error-msg">{error()}</div></Show>
        <div class="table-wrap">
          <div class="table-header">
            <h3>All Keys</h3>
            <span class="count">{keys().length} keys</span>
          </div>
          <div class="data-table-wrapper">
            <table class="data-table">
              <thead><tr>
                <th style="cursor:pointer;" onClick={() => handleSort('key_prefix')}>Prefix<SortIcon column="key_prefix" /></th>
                <th style="cursor:pointer;" onClick={() => handleSort('email')}>User<SortIcon column="email" /></th>
                <th style="cursor:pointer;" onClick={() => handleSort('tier')}>Tier<SortIcon column="tier" /></th>
                <th style="cursor:pointer;" onClick={() => handleSort('mode')}>Mode<SortIcon column="mode" /></th>
                <th style="cursor:pointer;" onClick={() => handleSort('status')}>Status<SortIcon column="status" /></th>
                <th style="cursor:pointer;" onClick={() => handleSort('created_at')}>Created<SortIcon column="created_at" /></th>
              </tr></thead>
              <tbody>
                <For each={sortedKeys()}>{(k) => (
                  <tr class="clickable" onClick={() => openDetail(k.id)}>
                    <td><span class="mono">{k.key_prefix}</span></td>
                    <td>{k.email}</td>
                    <td><span class={`badge ${k.tier}`}>{k.tier}</span></td>
                    <td><span class={`badge ${k.mode === 'offline' ? 'trial' : 'pro'}`}>{k.mode === 'offline' ? 'Offline' : 'Online'}</span></td>
                    <td><span class={`badge ${k.revoked_at ? 'revoked' : k.expires_at && k.expires_at * 1000 < Date.now() ? 'expired' : 'active'}`}>
                      {k.revoked_at ? 'Revoked' : k.expires_at && k.expires_at * 1000 < Date.now() ? 'Expired' : 'Active'}
                    </span></td>
                    <td>{new Date(k.created_at * 1000).toLocaleDateString()}</td>
                  </tr>
                )}</For>
                <Show when={keys().length === 0}>
                  <tr><td colspan="6"><div class="empty-state">No keys found</div></td></tr>
                </Show>
              </tbody>
            </table>
          </div>
        </div>
      </Show>

      <Show when={selected() && detail()}>
        <div class="detail-card">
          <div class="card-header">
            <h3>Key {detail().key_prefix}</h3>
            <Show when={!detail().revoked_at}>
              <button class="danger" onClick={() => doRevoke(detail().id)}>Revoke Key</button>
            </Show>
          </div>
          <div class="detail-grid">
            <div class="field"><label>ID</label><span class="mono">{detail().id}</span></div>
            <div class="field"><label>User</label><span>{detail().email}</span></div>
            <div class="field"><label>Tier</label><span class={`badge ${detail().tier}`}>{detail().tier}</span></div>
            <div class="field"><label>Mode</label><span class={`badge ${detail().mode === 'offline' ? 'trial' : 'pro'}`}>{detail().mode === 'offline' ? 'Offline (self-validating)' : 'Online (server)'}</span></div>
            <div class="field"><label>Status</label>
              <span class={`badge ${detail().revoked_at ? 'revoked' : detail().expires_at && detail().expires_at * 1000 < Date.now() ? 'expired' : 'active'}`}>
                {detail().revoked_at ? 'Revoked' : detail().expires_at && detail().expires_at * 1000 < Date.now() ? 'Expired' : 'Active'}
              </span>
            </div>
            <div class="field"><label>Created</label><span>{new Date(detail().created_at * 1000).toLocaleString()}</span></div>
            <Show when={detail().revoked_at}>
              <div class="field"><label>Revoked</label><span>{new Date(detail().revoked_at * 1000).toLocaleString()}</span></div>
            </Show>
            <Show when={detail().expires_at}>
              <div class="field"><label>Expires</label><span>{new Date(detail().expires_at * 1000).toLocaleString()}</span></div>
            </Show>
          </div>
        </div>

        <Show when={detail().devices && detail().devices.length > 0}>
          <div class="detail-card">
            <h3>Linked Devices ({detail().devices.length})</h3>
            <div class="data-table-wrapper">
              <table class="data-table">
                <thead><tr><th>Fingerprint</th><th>Last Seen</th></tr></thead>
                <tbody>
                  <For each={detail().devices}>{(d) => (
                    <tr>
                      <td><span class="mono">{d.device_fingerprint}</span></td>
                      <td>{new Date(d.last_seen * 1000).toLocaleString()}</td>
                    </tr>
                  )}</For>
                </tbody>
              </table>
            </div>
          </div>
        </Show>
      </Show>

      <Show when={showGenerate()}>
        <div class="modal-overlay" onClick={closeGenerate}>
          <div class="modal" onClick={(e) => e.stopPropagation()}>
            <h3>Generate License Key</h3>
            <Show when={!genResult()}>
              <div class="field">
                <label for="gen-email">User Email</label>
                <input id="gen-email" type="email" placeholder="user@example.com"
                       value={genEmail()} onInput={(e) => setGenEmail(e.target.value)}
                       autofocus />
              </div>
              <div class="field">
                <label for="gen-tier">Tier</label>
                <select id="gen-tier" value={genTier()} onChange={(e) => setGenTier(e.target.value)}>
                  <For each={tiers()}>{(t) => (
                    <option value={t.id}>{t.name} ({t.id})</option>
                  )}</For>
                </select>
                <Show when={genTier()}>
                  <div style="font-size:12px;color:var(--text-muted);margin-top:4px;">
                    {tiers().find(t => t.id === genTier())?.description}
                  </div>
                </Show>
              </div>
              <div class="field">
                <label for="gen-mode">Key Type</label>
                <select id="gen-mode" value={genMode()} onChange={(e) => setGenMode(e.target.value)}>
                  <option value="online" disabled={!onlineKeyExists()}>
                    Online (lc_on_) — {onlineKeyExists() ? 'requires license server' : 'UNAVAILABLE — configure online signing key first'}
                  </option>
                  <option value="offline" disabled={!offlineKeyExists()}>
                    Offline (lc_of_) — {offlineKeyExists() ? 'self-validating, no server needed' : 'UNAVAILABLE — configure offline signing key first'}
                  </option>
                </select>
                <div style="font-size:12px;color:var(--text-muted);margin-top:4px;">
                  {!onlineKeyExists() && genMode() === 'online'
                    ? 'Set up an online signing key in Signing Keys first.'
                    : !offlineKeyExists() && genMode() === 'offline'
                    ? 'Set up an offline signing key in Signing Keys first.'
                    : genMode() === 'online'
                    ? 'Validated against the license server each startup. Supports revocation.'
                    : 'Signed with Ed25519, validated locally. No server dependency. Set LOCALCLOUD_LICENSE_PUBLIC_KEY in the client.'}
                </div>
              </div>
              <Show when={genError()}><div class="error-msg">{genError()}</div></Show>
              <div class="actions">
                <button class="secondary" onClick={closeGenerate}>Cancel</button>
                <button onClick={doGenerate} disabled={!genEmail().trim() || genSaving()}>
                  {genSaving() ? 'Generating...' : 'Generate'}
                </button>
              </div>
            </Show>
            <Show when={genResult()}>
              <div class="field">
                <label>Generated Key</label>
                <div style="position:relative;">
                  <input id="gen-key-value" type="text" readonly value={genResult().key} class="mono"
                         style="width:100%;padding:12px 50px 12px 14px;font-size:13px;"
                         onClick={(e) => e.target.select()} />
                  <button onClick={() => copyToClipboard(genResult().key)}
                          style="position:absolute;right:4px;top:4px;bottom:4px;padding:4px 12px;font-size:12px;"
                          title="Copy to clipboard">
                    {copied ? 'Copied!' : 'Copy'}
                  </button>
                </div>
                <div style="font-size:12px;color:var(--text-muted);margin-top:6px;">
                  Click the input to select all, or click Copy.
                </div>
              </div>
              <div class="detail-grid" style="margin-top:16px;">
                <div class="field"><label>Email</label><span>{genResult().email}</span></div>
                <div class="field"><label>Tier</label><span class={`badge ${genResult().tier}`}>{genResult().tier}</span></div>
                <div class="field"><label>Type</label><span>{genResult().mode === 'offline' ? 'Offline (self-validating)' : 'Online (server)'}</span></div>
                <div class="field"><label>User</label><span>{genResult().user_created ? 'Newly created' : 'Existing'}</span></div>
              </div>
              <div class="actions">
                <button class="secondary" onClick={closeGenerate}>Close</button>
              </div>
            </Show>
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

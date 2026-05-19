const BASE = '/admin/api';

function headers() {
  const token = localStorage.getItem('admin_token');
  const h = { 'Content-Type': 'application/json' };
  if (token) h['Authorization'] = `Bearer ${token}`;
  return h;
}

async function request(method, path, body) {
  const token = localStorage.getItem('admin_token');
  const res = await fetch(BASE + path, {
    method,
    headers: headers(),
    body: body ? JSON.stringify(body) : undefined,
  });
  if (res.status === 401 && token) {
    localStorage.removeItem('admin_token');
    localStorage.removeItem('admin_page');
    window.location.reload();
    throw new Error('Session expired');
  }
  const data = await res.json();
  if (!res.ok && data.error) throw new Error(data.error);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return data;
}

export const api = {
  login: (password) => request('POST', '/login', { password }),
  logout: () => request('POST', '/logout'),
  stats: () => request('GET', '/stats'),
  listUsers: (q) => request('GET', '/users' + (q ? `?q=${encodeURIComponent(q)}` : '')),
  getUser: (id) => request('GET', `/users/${id}`),
  listKeys: (q, tier, status) => {
    const p = new URLSearchParams();
    if (q) p.set('q', q);
    if (tier) p.set('tier', tier);
    if (status) p.set('status', status);
    return request('GET', '/keys?' + p.toString());
  },
  getKey: (id) => request('GET', `/keys/${id}`),
  generateKey: (email, tier, mode) => request('POST', '/keys', { email, tier, mode }),
  listTiers: () => request('GET', '/tiers'),
  revokeKey: (id) => request('POST', `/keys/${id}/revoke`),
  listTrials: (status) => request('GET', '/trials' + (status ? `?status=${status}` : '')),
  listDevices: (q) => request('GET', '/devices' + (q ? `?q=${encodeURIComponent(q)}` : '')),
  health: () => request('GET', '/health'),
  listKeyPairs: () => request('GET', '/key-pairs'),
  generateOnlineKeyPair: () => request('POST', '/key-pairs/generate-online'),
  generateOfflineKeyPair: () => request('POST', '/key-pairs/generate-offline'),
};

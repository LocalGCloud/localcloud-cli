import { createSignal, createEffect, Show, For, onMount, onCleanup } from 'solid-js';
import { render } from 'solid-js/web';
import { Login } from './pages/Login.jsx';
import { Dashboard } from './pages/Dashboard.jsx';
import { Users } from './pages/Users.jsx';
import { Keys } from './pages/Keys.jsx';
import { KeyPairs } from './pages/KeyPairs.jsx';
import { Devices } from './pages/Devices.jsx';
import { Settings } from './pages/Settings.jsx';

function Icon(props) {
  const icons = {
    dashboard: 'M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6',
    users: 'M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z',
    keys: 'M15 7a2 2 0 012 2m4 0a6 6 0 01-7.743 5.743L11 17H9v2H7v2H4a1 1 0 01-1-1v-2.586a1 1 0 01.293-.707l5.964-5.964A6 6 0 1121 9z',
    devices: 'M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z',
    settings: 'M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z M15 12a3 3 0 11-6 0 3 3 0 016 0z',
    keypairs: 'M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z',
  };
  return (
    <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
      <path d={icons[props.name]} />
    </svg>
  );
}

function App() {
  const [token, setToken] = createSignal(localStorage.getItem('admin_token'));
  const [page, setPage] = createSignal('dashboard');

  const navigate = (p) => {
    setPage(p);
    window.location.hash = p;
    localStorage.setItem('admin_page', p);
  };

  const handleHashChange = () => {
    const hash = window.location.hash.replace('#', '');
    const valid = ['dashboard', 'users', 'keys', 'keypairs', 'devices', 'settings'];
    if (valid.includes(hash)) setPage(hash);
  };

  onMount(() => {
    const saved = localStorage.getItem('admin_page');
    const hash = window.location.hash.replace('#', '');
    const valid = ['dashboard', 'users', 'keys', 'keypairs', 'devices', 'settings'];
    if (valid.includes(hash)) setPage(hash);
    else if (saved && valid.includes(saved)) { setPage(saved); window.location.hash = saved; }
    window.addEventListener('hashchange', handleHashChange);
  });

  onCleanup(() => window.removeEventListener('hashchange', handleHashChange));

  const getInitialTheme = () => {
    const saved = localStorage.getItem('admin_theme');
    if (saved) return saved === 'light';
    return true;
  };
  const [isLight, setIsLight] = createSignal(getInitialTheme());

  createEffect(() => {
    const theme = isLight() ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('admin_theme', theme);
  });

  createEffect(() => {
    const t = token();
    if (t) localStorage.setItem('admin_token', t);
    else localStorage.removeItem('admin_token');
  });

  const handleLogin = (t) => { setToken(t); navigate('dashboard'); };
  const handleLogout = () => {
    fetch('/admin/api/logout', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${token()}` },
    }).catch(() => {});
    setToken(null);
    localStorage.removeItem('admin_token');
    localStorage.removeItem('admin_page');
    window.location.hash = '';
  };

  const nav = [
    { id: 'dashboard', label: 'Dashboard' },
    { id: 'users', label: 'Users' },
    { id: 'keys', label: 'License Keys' },
    { id: 'keypairs', label: 'Signing Keys' },
    { id: 'devices', label: 'Devices' },
    { id: 'settings', label: 'System' },
  ];

  return (
    <Show when={token()} fallback={<Login onLogin={handleLogin} />}>
      <div class="app">
        <aside class="sidebar">
          <div class="logo">
            <div class="logo-icon">LC</div>
            <div>
              <div class="logo-text">LocalCloud</div>
              <div class="logo-sub">License Admin</div>
            </div>
          </div>
          <nav role="navigation" aria-label="Admin navigation">
            <For each={nav}>
              {(item) => (
                <a class={page() === item.id ? 'active' : ''}
                   onClick={() => navigate(item.id)}
                   role="button"
                   tabindex="0"
                   onKeyDown={(e) => e.key === 'Enter' && navigate(item.id)}>
                  <Icon name={item.id} />
                  <span>{item.label}</span>
                </a>
              )}
            </For>
          </nav>
          <div style="margin-top:auto;">
            <div class="version">v0.1.0</div>
            <button class="theme-toggle" onClick={() => setIsLight(!isLight())}
                    aria-label={isLight() ? 'Switch to dark mode' : 'Switch to light mode'}>
              {isLight()
                ? <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                    <circle cx="12" cy="12" r="5" />
                    <line x1="12" y1="1" x2="12" y2="3" /><line x1="12" y1="21" x2="12" y2="23" />
                    <line x1="4.22" y1="4.22" x2="5.64" y2="5.64" /><line x1="18.36" y1="18.36" x2="19.78" y2="19.78" />
                    <line x1="1" y1="12" x2="3" y2="12" /><line x1="21" y1="12" x2="23" y2="12" />
                    <line x1="4.22" y1="19.78" x2="5.64" y2="18.36" /><line x1="18.36" y1="5.64" x2="19.78" y2="4.22" />
                  </svg>
                : <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                    <path d="M21 12.79A9 9 0 1111.21 3 7 7 0 0021 12.79z" />
                  </svg>
              }
              <span>{isLight() ? 'Dark Mode' : 'Light Mode'}</span>
            </button>
            <div class="logout-link" onClick={handleLogout}
                 role="button" tabindex="0"
                 onKeyDown={(e) => e.key === 'Enter' && handleLogout()}>
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4" />
                <polyline points="16 17 21 12 16 7" />
                <line x1="21" y1="12" x2="9" y2="12" />
              </svg>
              <span>Sign Out</span>
            </div>
          </div>
        </aside>
        <main class="main" role="main">
          <div class="content">
            <Show when={page() === 'dashboard'}><Dashboard /></Show>
            <Show when={page() === 'users'}><Users /></Show>
            <Show when={page() === 'keys'}><Keys /></Show>
            <Show when={page() === 'keypairs'}><KeyPairs /></Show>
            <Show when={page() === 'devices'}><Devices /></Show>
            <Show when={page() === 'settings'}><Settings /></Show>
          </div>
        </main>
      </div>
    </Show>
  );
}

render(() => <App />, document.getElementById('root'));

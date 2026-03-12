/**
 * Sidebar Component
 * Navigation menu with links to different pages
 * Highlights the current active page
 */

export function Sidebar(props) {
  const navItems = [
    { id: 'dashboard', label: '📊 Dashboard', path: '/' },
    { id: 'services', label: '⚙️ Services', path: '/services' },
    { id: 'logs', label: '📝 Logs', path: '/logs' },
    { id: 'databrowser', label: '📂 Data Browser', path: '/data-browser' },
    { id: 'settings', label: '⚡ Settings', path: '/settings' }
  ];

  return (
    <aside class="sidebar">
      <nav class="sidebar-nav">
        {navItems.map((item) => (
          <a
            href={item.path}
            class={`nav-link ${props.currentPage === item.id ? 'active' : ''}`}
            onclick={(e) => {
              e.preventDefault();
              props.onNavigate(item.id);
            }}
          >
            {item.label}
          </a>
        ))}
      </nav>

      <div class="sidebar-footer">
        <div class="footer-info">
          <p class="text-sm">Auto-refresh: {props.refreshInterval}s</p>
        </div>
      </div>
    </aside>
  );
}

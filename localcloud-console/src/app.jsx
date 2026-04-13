import { render } from 'solid-js/web';
import { createSignal, createEffect, onCleanup, Show, For } from 'solid-js';
import { api, setActiveProject, getActiveProject } from './api.js';
import Dashboard from './pages/Dashboard.jsx';
import Services from './pages/Services.jsx';
import Logs from './pages/Logs.jsx';
import ServiceExplorer from './pages/ServiceExplorer.jsx';
import Settings from './pages/Settings.jsx';
import Usage from './pages/Usage.jsx';

// --- SVG Icon component ---
const ICON_PATHS = {
    dashboard: 'M4 13h6a1 1 0 0 0 1-1V4a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1v8a1 1 0 0 0 1 1zm-1 7h6a1 1 0 0 0 1-1v-4a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1v4a1 1 0 0 0 1 1zm10 0h6a1 1 0 0 0 1-1v-8a1 1 0 0 0-1-1h-6a1 1 0 0 0-1 1v8a1 1 0 0 0 1 1zM13 4v4a1 1 0 0 0 1 1h6a1 1 0 0 0 1-1V4a1 1 0 0 0-1-1h-6a1 1 0 0 0-1 1z',
    services: 'M12 15.5A3.5 3.5 0 0 1 8.5 12 3.5 3.5 0 0 1 12 8.5a3.5 3.5 0 0 1 3.5 3.5 3.5 3.5 0 0 1-3.5 3.5m7.43-2.53c.04-.32.07-.64.07-.97s-.03-.66-.07-1l2.11-1.63c.19-.15.24-.42.12-.64l-2-3.46c-.12-.22-.39-.31-.61-.22l-2.49 1c-.52-.4-1.08-.73-1.69-.98l-.38-2.65A.49.49 0 0 0 14 2h-4c-.25 0-.46.18-.5.42l-.38 2.65c-.61.25-1.17.58-1.69.98l-2.49-1c-.22-.09-.49 0-.61.22l-2 3.46c-.13.22-.07.49.12.64L4.57 11c-.04.34-.07.67-.07 1s.03.65.07.97l-2.11 1.66c-.19.15-.25.42-.12.64l2 3.46c.12.22.39.3.61.22l2.49-1.01c.52.4 1.08.73 1.69.98l.38 2.65c.04.24.25.42.5.42h4c.25 0 .46-.18.5-.42l.38-2.65c.61-.25 1.17-.58 1.69-.98l2.49 1.01c.22.08.49 0 .61-.22l2-3.46c.12-.22.07-.49-.12-.64z',
    logs: 'M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z',
    data: 'M20 6H12L10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm0 12H4V8h16v10z',
    usage: 'M11.8 10.9c-2.27-.59-3-1.2-3-2.15 0-1.09 1.01-1.85 2.7-1.85 1.78 0 2.44.85 2.5 2.1h2.21c-.07-1.72-1.12-3.3-3.21-3.81V3h-3v2.16c-1.94.42-3.5 1.68-3.5 3.61 0 2.31 1.91 3.46 4.7 4.13 2.5.6 3 1.48 3 2.41 0 .69-.49 1.79-2.7 1.79-2.06 0-2.87-.92-2.98-2.1h-2.2c.12 2.19 1.76 3.42 3.68 3.83V21h3v-2.15c1.95-.37 3.5-1.5 3.5-3.55 0-2.84-2.43-3.81-4.7-4.4z',
    settings: 'M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.49.49 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.484.484 0 0 0-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96a.49.49 0 0 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z',
    sun: 'M12 7c-2.76 0-5 2.24-5 5s2.24 5 5 5 5-2.24 5-5-2.24-5-5-5zM2 13h2c.55 0 1-.45 1-1s-.45-1-1-1H2c-.55 0-1 .45-1 1s.45 1 1 1zm18 0h2c.55 0 1-.45 1-1s-.45-1-1-1h-2c-.55 0-1 .45-1 1s.45 1 1 1zM11 2v2c0 .55.45 1 1 1s1-.45 1-1V2c0-.55-.45-1-1-1s-1 .45-1 1zm0 18v2c0 .55.45 1 1 1s1-.45 1-1v-2c0-.55-.45-1-1-1s-1 .45-1 1zM5.99 4.58a.996.996 0 0 0-1.41 0 .996.996 0 0 0 0 1.41l1.06 1.06c.39.39 1.03.39 1.41 0s.39-1.03 0-1.41zm12.37 12.37a.996.996 0 0 0-1.41 0 .996.996 0 0 0 0 1.41l1.06 1.06c.39.39 1.03.39 1.41 0a.996.996 0 0 0 0-1.41zm1.06-10.96a.996.996 0 0 0 0-1.41.996.996 0 0 0-1.41 0l-1.06 1.06c-.39.39-.39 1.03 0 1.41s1.03.39 1.41 0zM7.05 18.36a.996.996 0 0 0 0-1.41.996.996 0 0 0-1.41 0l-1.06 1.06c-.39.39-.39 1.03 0 1.41s1.03.39 1.41 0z',
    moon: 'M9.37 5.51A7.35 7.35 0 0 0 9.1 7.5c0 4.08 3.32 7.4 7.4 7.4.68 0 1.35-.09 1.99-.27A7.014 7.014 0 0 1 12 19c-3.86 0-7-3.14-7-7 0-2.93 1.81-5.45 4.37-6.49zM12 3a9 9 0 1 0 9 9c0-.46-.04-.92-.1-1.36a5.389 5.389 0 0 1-4.4 2.26 5.403 5.403 0 0 1-3.14-9.8c-.44-.06-.9-.1-1.36-.1z',
};

function Icon({ name, size = 18 }) {
    const path = ICON_PATHS[name];
    if (!path) return null;
    return (
        <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor" xmlns="http://www.w3.org/2000/svg">
            <path d={path} />
        </svg>
    );
}

const NAV_ITEMS = [
    { id: 'dashboard',  label: 'Dashboard',        icon: 'dashboard' },
    { id: 'services',   label: 'APIs & Services',  icon: 'services' },
    { id: 'logs',       label: 'Logs',              icon: 'logs' },
    { id: 'data',       label: 'Service Explorer',   icon: 'data', expandable: true },
    { id: 'usage',      label: 'Usage',             icon: 'usage' },
    { id: 'settings',   label: 'Settings',          icon: 'settings' },
];

const DATA_SERVICES = [
    { id: 'gcs', label: 'Cloud Storage' },
    { id: 'pubsub', label: 'Pub/Sub' },
    { id: 'firestore', label: 'Firestore', beta: true },
    { id: 'bigquery', label: 'BigQuery' },
    { id: 'secretmanager', label: 'Secret Manager' },
    { id: 'cloudtasks', label: 'Cloud Tasks' },
    { id: 'spanner', label: 'Spanner' },
    { id: 'bigtable', label: 'Bigtable' },
    { id: 'logging', label: 'Logging' },
    { id: 'monitoring', label: 'Monitoring' },
    { id: 'gke', label: 'GKE', beta: true },
    { id: 'compute', label: 'Compute', beta: true },
    { id: 'cloudrun', label: 'Cloud Run' },
    { id: 'memorystore', label: 'Memorystore' },
];

// --- Hash-based routing ---

function parseHash() {
    const hash = window.location.hash.replace(/^#\/?/, '');
    if (!hash) return { page: 'dashboard', service: null };
    const parts = hash.split('/');
    const page = parts[0] || 'dashboard';
    const service = parts[1] || null;
    return { page, service };
}

function setHash(page, service) {
    const path = service ? `${page}/${service}` : page;
    const newHash = `#/${path}`;
    if (window.location.hash !== newHash) {
        window.location.hash = newHash;
    }
}

// ---

function App() {
    const initial = parseHash();
    const [currentPage, setCurrentPage] = createSignal(initial.page);
    const [selectedService, setSelectedService] = createSignal(initial.service);
    const [darkMode, setDarkMode] = createSignal(false);
    const [healthData, setHealthData] = createSignal(null);
    const [routingData, setRoutingData] = createSignal(null);
    const [credentialData, setCredentialData] = createSignal(null);
    const [refreshInterval, setRefreshInterval] = createSignal(5000);
    const [connectionStatus, setConnectionStatus] = createSignal('connecting'); // 'connected' | 'connecting' | 'offline'
    let healthFailCount = 0;

    // Project management
    const [projects, setProjects] = createSignal([]);
    const [activeProject, setActiveProjectState] = createSignal(null);
    const [projectDropdownOpen, setProjectDropdownOpen] = createSignal(false);
    const [showNewProjectDialog, setShowNewProjectDialog] = createSignal(false);
    const [newProjectId, setNewProjectId] = createSignal('');
    const [newProjectName, setNewProjectName] = createSignal('');
    const [projectError, setProjectError] = createSignal(null);

    // Initialize active project from localStorage
    const initProject = (() => {
        try {
            return localStorage.getItem('localcloud-active-project') || null;
        } catch { return null; }
    })();

    const switchProject = (projectId) => {
        // IMPORTANT: Set the api.js module variable BEFORE the Solid.js signal.
        // Solid.js may synchronously flush effects when the signal is set,
        // and those effects call api.browse() which reads _activeProject.
        setActiveProject(projectId);
        setActiveProjectState(projectId);
        setProjectDropdownOpen(false);
        try { localStorage.setItem('localcloud-active-project', projectId); } catch {}
    };

    // Fetch projects list
    const fetchProjects = async () => {
        try {
            const data = await api.projects();
            const list = data.projects || data || [];
            setProjects(Array.isArray(list) ? list : []);
            // Set initial project if not set
            if (!activeProject() && list.length > 0) {
                const stored = initProject;
                const match = list.find(p => p.project_id === stored);
                switchProject(match ? match.project_id : list[0].project_id);
            }
        } catch (err) {
            console.error('Failed to fetch projects:', err);
        }
    };
    fetchProjects();

    const handleCreateProject = async () => {
        const id = newProjectId().trim();
        const name = newProjectName().trim();
        if (!id) return;
        try {
            await api.createProject(id, name || id);
            setNewProjectId('');
            setNewProjectName('');
            setShowNewProjectDialog(false);
            await fetchProjects();
            switchProject(id);
        } catch (err) {
            setProjectError('Failed to create project: ' + err.message);
            setTimeout(() => setProjectError(null), 5000);
        }
    };

    // Initialize dark mode from localStorage
    const initDark = (() => {
        try {
            const stored = localStorage.getItem('localcloud-dark-mode');
            if (stored !== null) return stored === 'true';
            return false; // default to light (GCP Console default)
        } catch {
            return false;
        }
    })();
    setDarkMode(initDark);

    // Apply theme to body
    createEffect(() => {
        document.body.dataset.theme = darkMode() ? 'dark' : 'light';
    });

    const toggleDarkMode = () => {
        const next = !darkMode();
        setDarkMode(next);
        try {
            localStorage.setItem('localcloud-dark-mode', String(next));
        } catch { /* ignore */ }
    };

    // Sync URL hash when page or service changes
    createEffect(() => {
        const page = currentPage();
        const svc = selectedService();
        if (page === 'data' && svc) {
            setHash(page, svc);
        } else {
            setHash(page);
        }
    });

    // Listen for browser back/forward
    const onHashChange = () => {
        const { page, service } = parseHash();
        setCurrentPage(page);
        if (service) setSelectedService(service);
    };
    window.addEventListener('hashchange', onHashChange);
    onCleanup(() => window.removeEventListener('hashchange', onHashChange));

    // Auto-refresh health data
    createEffect(() => {
        const interval = refreshInterval();
        let timer;

        const fetchHealth = async () => {
            try {
                const [health, routing, creds] = await Promise.all([
                    api.health(),
                    api.routing().catch(() => null),
                    api.credentials().catch(() => null),
                ]);
                setHealthData(health);
                if (routing) setRoutingData(routing);
                if (creds) setCredentialData(creds);
                healthFailCount = 0;
                setConnectionStatus('connected');
            } catch (err) {
                healthFailCount++;
                if (healthFailCount >= 2) {
                    setConnectionStatus('offline');
                    setHealthData(null);
                }
            }
        };

        fetchHealth();
        timer = setInterval(fetchHealth, interval);

        onCleanup(() => clearInterval(timer));
    });

    const projectId = () => {
        const h = healthData();
        return h && h.project_id ? h.project_id : 'local-project';
    };

    // Navigate to page (updates URL)
    const navigateTo = (page) => {
        if (page !== 'data') setSelectedService(null);
        setCurrentPage(page);
    };

    // Navigate to Data Browser with a pre-selected service
    const handleServiceClick = (serviceId) => {
        setSelectedService(serviceId);
        setCurrentPage('data');
    };

    // Called by DataBrowser when user switches tabs
    const handleTabChange = (tabId) => {
        setSelectedService(tabId);
    };

    // Derived signal: true once a project has loaded (prevents null-project API calls)
    const projectReady = () => !!activeProject();

    const renderPage = () => {
        const page = currentPage();
        // Don't render data-dependent pages until project is resolved
        if (!projectReady()) {
            return <div class="loading-state"><div class="loading-spinner" /> Loading project...</div>;
        }
        // Pass activeProject as a signal accessor so children can track changes reactively
        // NOTE: renderPage must NOT read activeProject() here — only pass the accessor.
        // Reading it would cause full component tree re-creation on every project switch.
        switch (page) {
            case 'dashboard':
                return <Dashboard healthData={healthData} routingData={routingData} onServiceClick={handleServiceClick} activeProject={activeProject} />;
            case 'services':
                return <Services healthData={healthData} routingData={routingData} onServiceClick={handleServiceClick} activeProject={activeProject} />;
            case 'logs':
                return <Logs activeProject={activeProject} />;
            case 'data':
                return <ServiceExplorer selectedService={selectedService} onTabChange={handleTabChange} activeProject={activeProject} />;
            case 'usage':
                return <Usage activeProject={activeProject} />;
            case 'settings':
                return (
                    <Settings
                        darkMode={darkMode}
                        toggleDarkMode={toggleDarkMode}
                        refreshInterval={refreshInterval}
                        setRefreshInterval={setRefreshInterval}
                        routingData={routingData}
                        credentialData={credentialData}
                    />
                );
            default:
                return <Dashboard healthData={healthData} routingData={routingData} onServiceClick={handleServiceClick} activeProject={proj} />;
        }
    };

    return (
        <>
            <a class="skip-link" href="#main-content">Skip to content</a>
            {/* Top Bar */}
            <header class="topbar">
                <div class="topbar-left">
                    <div class="topbar-logo">
                        <div class="topbar-logo-icon">LC</div>
                        <span>LocalCloud</span>
                    </div>
                </div>
                <div class="topbar-center" style={{ position: 'relative' }}>
                    <button
                        class="topbar-project-chip"
                        onClick={() => setProjectDropdownOpen(!projectDropdownOpen())}
                        style={{ cursor: 'pointer' }}
                    >
                        <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor" style={{ color: 'var(--primary)', "flex-shrink": '0' }}>
                            <path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                        </svg>
                        <span>{activeProject() || projectId()}</span>
                        <svg width="10" height="10" viewBox="0 0 24 24" fill="currentColor" style={{ opacity: 0.5 }}>
                            <path d="M7 10l5 5 5-5z"/>
                        </svg>
                    </button>
                    <Show when={projectDropdownOpen()}>
                        <div class="project-dropdown">
                            <div class="project-dropdown-label">Projects</div>
                            <For each={projects()}>
                                {(p) => (
                                    <button
                                        class={`project-dropdown-item ${activeProject() === p.project_id ? 'active' : ''}`}
                                        onClick={() => switchProject(p.project_id)}
                                    >
                                        <span>{p.project_id}</span>
                                        <Show when={p.display_name && p.display_name !== p.project_id}>
                                            <span style={{ "font-size": "10px", color: "var(--text-tertiary)" }}>{p.display_name}</span>
                                        </Show>
                                    </button>
                                )}
                            </For>
                            <div style={{ "border-top": "1px solid var(--border)", "margin-top": "4px", "padding-top": "4px" }}>
                                <button
                                    class="project-dropdown-item"
                                    onClick={() => { setProjectDropdownOpen(false); setShowNewProjectDialog(true); }}
                                    style={{ color: "var(--primary)" }}
                                >
                                    + New Project
                                </button>
                            </div>
                        </div>
                    </Show>
                    {/* New Project Dialog */}
                    <Show when={showNewProjectDialog()}>
                        <div
                            class="modal-overlay"
                            role="dialog"
                            aria-modal="true"
                            aria-labelledby="new-project-title"
                            onClick={(e) => { if (e.target === e.currentTarget) { setShowNewProjectDialog(false); setProjectError(null); } }}
                            onKeyDown={(e) => { if (e.key === 'Escape') { setShowNewProjectDialog(false); setProjectError(null); } }}
                        >
                            <div class="card modal-card" onClick={(e) => e.stopPropagation()}>
                                <h2 id="new-project-title" style={{ "margin-bottom": "16px" }}>New Project</h2>
                                <Show when={projectError()}>
                                    <div class="alert alert-error" style={{ "margin-bottom": "12px" }}>{projectError()}</div>
                                </Show>
                                <div style={{ "margin-bottom": "12px" }}>
                                    <label for="project-id-input" class="form-label">Project ID</label>
                                    <input id="project-id-input" type="text" class="form-input form-input-mono" value={newProjectId()} onInput={(e) => setNewProjectId(e.currentTarget.value)}
                                        placeholder="my-project" />
                                </div>
                                <div style={{ "margin-bottom": "16px" }}>
                                    <label for="project-name-input" class="form-label">Display Name</label>
                                    <input id="project-name-input" type="text" class="form-input" value={newProjectName()} onInput={(e) => setNewProjectName(e.currentTarget.value)}
                                        placeholder="My Project" />
                                </div>
                                <div style={{ display: "flex", gap: "8px", "justify-content": "flex-end" }}>
                                    <button class="btn btn-secondary" onClick={() => { setShowNewProjectDialog(false); setProjectError(null); }}>Cancel</button>
                                    <button class="btn btn-primary" onClick={handleCreateProject} disabled={!newProjectId().trim()}>Create</button>
                                </div>
                            </div>
                        </div>
                    </Show>
                </div>
                <div class="topbar-right">
                    <button
                        class="topbar-toggle"
                        onClick={toggleDarkMode}
                        title={darkMode() ? 'Switch to light mode' : 'Switch to dark mode'}
                    >
                        <Icon name={darkMode() ? 'sun' : 'moon'} size={18} />
                    </button>
                </div>
            </header>

            {/* Offline Banner */}
            <Show when={connectionStatus() === 'offline'}>
                <div class="offline-banner" role="alert">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" style={{ "flex-shrink": "0" }}>
                        <path d="M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z"/>
                    </svg>
                    <span>Cannot reach LocalCloud backend. Is the container running?</span>
                </div>
            </Show>
            <Show when={connectionStatus() === 'connecting'}>
                <div class="offline-banner connecting" role="status">
                    <div class="loading-spinner" style={{ width: "14px", height: "14px" }} />
                    <span>Connecting to LocalCloud...</span>
                </div>
            </Show>

            {/* Sidebar */}
            <nav class="sidebar" aria-label="Main navigation">
                <div class="sidebar-section-label">Navigation</div>
                <For each={NAV_ITEMS}>
                    {(item) => (
                        <>
                            <button
                                class={`sidebar-item ${currentPage() === item.id ? 'active' : ''}`}
                                onClick={() => {
                                    if (item.id === 'data') {
                                        navigateTo('data');
                                        if (!selectedService()) setSelectedService('gcs');
                                    } else {
                                        navigateTo(item.id);
                                    }
                                }}
                            >
                                <span class="sidebar-item-icon">
                                    <Icon name={item.icon} size={18} />
                                </span>
                                <span class="sidebar-item-label">{item.label}</span>
                                {item.expandable && (
                                    <svg class={`sidebar-item-chevron ${currentPage() === 'data' ? 'expanded' : ''}`} width="12" height="12" viewBox="0 0 24 24" fill="currentColor">
                                        <path d="M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z"/>
                                    </svg>
                                )}
                            </button>
                            {item.expandable && (
                                <div class={`sidebar-sub-items ${currentPage() === 'data' ? 'expanded' : ''}`}>
                                    <For each={DATA_SERVICES}>
                                        {(svc) => {
                                            const healthStatus = () => connectionStatus() === 'offline' ? 'offline' : healthData()?.services?.[svc.id]?.status;
                                            const routingMode = () => routingData()?.[svc.id]?.mode || 'local';
                                            const isDisabled = () => healthStatus() === 'disabled' || healthStatus() === 'offline';
                                            return (
                                                <button
                                                    class={`sidebar-sub-item ${selectedService() === svc.id && currentPage() === 'data' ? 'active' : ''}`}
                                                    onClick={() => handleServiceClick(svc.id)}
                                                    style={isDisabled() ? { opacity: "0.5" } : {}}
                                                >
                                                    <img src={`/icons/${svc.id}.svg`} alt="" class="sidebar-sub-item-icon" />
                                                    <span class="sidebar-sub-item-label">{svc.label}</span>
                                                    {svc.beta && <span class="badge badge-beta">Beta</span>}
                                                    {routingMode() === 'remote' && <span class="badge badge-cloud" title="Routed to Google Cloud">Cloud</span>}
                                                    <span classList={{
                                                        "sidebar-sub-item-dot": true,
                                                        "healthy": healthStatus() === 'healthy',
                                                        "unhealthy": healthStatus() === 'unhealthy' || isDisabled(),
                                                    }} />
                                                </button>
                                            );
                                        }}
                                    </For>
                                </div>
                            )}
                        </>
                    )}
                </For>
            </nav>

            {/* Content */}
            <main id="main-content" class="main-content">
                {renderPage()}
            </main>
        </>
    );
}

render(() => <App />, document.getElementById('root'));

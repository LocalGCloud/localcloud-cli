import { render } from 'solid-js/web';
import { batch, createSignal, createEffect, createMemo, onCleanup, Show, For } from 'solid-js';
import { api, setActiveProject } from './api.js';
import Dashboard from './pages/Dashboard.jsx';
import Logs from './pages/Logs.jsx';
import ServiceExplorer from './pages/ServiceExplorer.jsx';
import Settings from './pages/Settings.jsx';
import Usage from './pages/Usage.jsx';
import { trapFocus } from './utils/a11y.js';
import { normalizeLocalSchema, normalizeRemoteBrowse } from './components/SchemaExplorer.jsx';
import Onboarding from './components/Onboarding.jsx';
import GetStarted from './pages/GetStarted.jsx';
import ComboBox from './components/ComboBox.jsx';
import { GCP_REGIONS, getZonesForRegion } from './data/gcpLocations.js';

const ICON_PATHS = {
    dashboard: 'M4 13h6a1 1 0 0 0 1-1V4a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1v8a1 1 0 0 0 1 1zm-1 7h6a1 1 0 0 0 1-1v-4a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1v4a1 1 0 0 0 1 1zm10 0h6a1 1 0 0 0 1-1v-8a1 1 0 0 0-1-1h-6a1 1 0 0 0-1 1v8a1 1 0 0 0 1 1zM13 4v4a1 1 0 0 0 1 1h6a1 1 0 0 0 1-1V4a1 1 0 0 0-1-1h-6a1 1 0 0 0-1 1z',
    services: 'M12 15.5A3.5 3.5 0 0 1 8.5 12 3.5 3.5 0 0 1 12 8.5a3.5 3.5 0 0 1 3.5 3.5 3.5 3.5 0 0 1-3.5 3.5m7.43-2.53c.04-.32.07-.64.07-.97s-.03-.66-.07-1l2.11-1.63c.19-.15.24-.42.12-.64l-2-3.46c-.12-.22-.39-.31-.61-.22l-2.49 1c-.52-.4-1.08-.73-1.69-.98l-.38-2.65A.49.49 0 0 0 14 2h-4c-.25 0-.46.18-.5.42l-.38 2.65c-.61.25-1.17.58-1.69.98l-2.49-1c-.22-.09-.49 0-.61.22l-2 3.46c-.13.22-.07.49.12.64L4.57 11c-.04.34-.07.67-.07 1s.03.65.07.97l-2.11 1.66c-.19.15-.25.42-.12.64l2 3.46c.12.22.39.3.61.22l2.49-1.01c.52.4 1.08.73 1.69.98l.38 2.65c.04.24.25.42.5.42h4c.25 0 .46-.18.5-.42l.38-2.65c.61-.25 1.17-.58 1.69-.98l2.49 1.01c.22.08.49 0 .61-.22l2-3.46c.12-.22.07-.49-.12-.64z',
    logs: 'M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z',
    data: 'M20 6H12L10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm0 12H4V8h16v10z',
    usage: 'M11.8 10.9c-2.27-.59-3-1.2-3-2.15 0-1.09 1.01-1.85 2.7-1.85 1.78 0 2.44.85 2.5 2.1h2.21c-.07-1.72-1.12-3.3-3.21-3.81V3h-3v2.16c-1.94.42-3.5 1.68-3.5 3.61 0 2.31 1.91 3.46 4.7 4.13 2.5.6 3 1.48 3 2.41 0 .69-.49 1.79-2.7 1.79-2.06 0-2.87-.92-2.98-2.1h-2.2c.12 2.19 1.76 3.42 3.68 3.83V21h3v-2.15c1.95-.37 3.5-1.5 3.5-3.55 0-2.84-2.43-3.81-4.7-4.4z',
    settings: 'M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.49.49 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.484.484 0 0 0-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96a.49.49 0 0 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z',
    docs: 'M5 3h11a3 3 0 0 1 3 3v15H8a3 3 0 0 1-3-3V3zm3 14h9V6a1 1 0 0 0-1-1H7v13a1 1 0 0 0 1 1v-2zm1-9h6v2H9V8zm0 4h6v2H9v-2z',
    chevron: 'M8.59 16.59 13.17 12 8.59 7.41 10 6l6 6-6 6-1.41-1.41z',
    home: 'M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8h5z',
    search: 'M9.5 3a6.5 6.5 0 0 1 5.15 10.46l4.44 4.45-1.41 1.41-4.45-4.44A6.5 6.5 0 1 1 9.5 3m0 2a4.5 4.5 0 1 0 0 9 4.5 4.5 0 0 0 0-9z',
    spark: 'M3 17h3.6l3.1-8.2 3.2 5.1 2.2-3.2H21v2h-4.8l-3.4 5-2.7-4.4L8 19H3v-2z',
    sun: 'M12 7c-2.76 0-5 2.24-5 5s2.24 5 5 5 5-2.24 5-5-2.24-5-5-5zM2 13h2c.55 0 1-.45 1-1s-.45-1-1-1H2c-.55 0-1 .45-1 1s.45 1 1 1zm18 0h2c.55 0 1-.45 1-1s-.45-1-1-1h-2c-.55 0-1 .45-1 1s.45 1 1 1zM11 2v2c0 .55.45 1 1 1s1-.45 1-1V2c0-.55-.45-1-1-1s-1 .45-1 1zm0 18v2c0 .55.45 1 1 1s1-.45 1-1v-2c0-.55-.45-1-1-1s-1 .45-1 1zM5.99 4.58a.996.996 0 0 0-1.41 0 .996.996 0 0 0 0 1.41l1.06 1.06c.39.39 1.03.39 1.41 0s.39-1.03 0-1.41zm12.37 12.37a.996.996 0 0 0-1.41 0 .996.996 0 0 0 0 1.41l1.06 1.06c.39.39 1.03.39 1.41 0a.996.996 0 0 0 0-1.41zm1.06-10.96a.996.996 0 0 0 0-1.41.996.996 0 0 0-1.41 0l-1.06 1.06c-.39.39-.39 1.03 0 1.41s1.03.39 1.41 0zM7.05 18.36a.996.996 0 0 0 0-1.41.996.996 0 0 0-1.41 0l-1.06 1.06c-.39.39-.39 1.03 0 1.41s1.03.39 1.41 0z',
    moon: 'M9.37 5.51A7.35 7.35 0 0 0 9.1 7.5c0 4.08 3.32 7.4 7.4 7.4.68 0 1.35-.09 1.99-.27A7.014 7.014 0 0 1 12 19c-3.86 0-7-3.14-7-7 0-2.93 1.81-5.45 4.37-6.49zM12 3a9 9 0 1 0 9 9c0-.46-.04-.92-.1-1.36a5.389 5.389 0 0 1-4.4 2.26 5.403 5.403 0 0 1-3.14-9.8c-.44-.06-.9-.1-1.36-.1z',
    pin: 'M16 9V4h1V2H7v2h1v5l-2 2v2h5v7l1 1 1-1v-7h5v-2l-2-2z',
    user: 'M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z',
};

function Icon(props) {
    const path = ICON_PATHS[props.name];
    if (!path) return null;
    return <svg width={props.size || 18} height={props.size || 18} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path d={path} /></svg>;
}

const NAV_ITEMS = [
    { id: 'dashboard', label: 'Dashboard', icon: 'dashboard' },
    { id: 'getstarted', label: 'Get Started', icon: 'spark' },
    { id: 'logs', label: 'Logs', icon: 'logs' },
    { id: 'usage', label: 'Cost Analysis', icon: 'usage' },
    { id: 'settings', label: 'Setup & SDKs', icon: 'docs' },
];

const SERVICE_GROUPS = [
    { name: 'Storage', tone: 'green', services: [
        { id: 'gcs', label: 'Cloud Storage', desc: 'Object storage — buckets, blobs, lifecycle' },
    ]},
    { name: 'Streaming', tone: 'amber', services: [
        { id: 'pubsub', label: 'Pub/Sub', desc: 'Async messaging — topics, subscriptions, pull/push' },
    ]},
    { name: 'Databases', tone: 'blue', services: [
        { id: 'spanner', label: 'Spanner', desc: 'OLTP — globally distributed, strongly consistent SQL' },
        { id: 'bigtable', label: 'Bigtable', desc: 'Wide-column NoSQL — petabyte-scale analytical workloads' },
        { id: 'memorystore', label: 'Memorystore', desc: 'In-memory — managed Redis / Valkey, sub-ms latency' },
        { id: 'cloudsql', label: 'Cloud SQL', desc: 'OLTP — managed PostgreSQL, MySQL, SQL Server' },
        { id: 'alloydb', label: 'AlloyDB', desc: 'OLTP — PostgreSQL-compatible, enterprise-grade' },
        { id: 'bigquery', label: 'BigQuery', desc: 'OLAP / Analytics — serverless data warehouse, petabyte SQL' },
        { id: 'firestore', label: 'Firestore', desc: 'Document DB — serverless, real-time, mobile-ready', tag: 'Coming up' },
    ]},
    { name: 'Compute', tone: 'red', services: [
        { id: 'cloudrun', label: 'Cloud Run', desc: 'Serverless containers — scale-to-zero, HTTP/gRPC' },
        { id: 'gke', label: 'GKE', desc: 'Managed Kubernetes — clusters, node pools, autopilot' },
        { id: 'compute', label: 'Compute Engine', desc: 'VMs — instances, disks, snapshots, networking' },
        { id: 'dataproc', label: 'Dataproc', desc: 'Managed Spark & Hadoop — batch, streaming, ML' },
        { id: 'cloudtasks', label: 'Cloud Tasks', desc: 'Distributed task queues — async, rate-limited dispatch' },
        { id: 'workflows', label: 'Workflows', desc: 'Orchestration — YAML-based service chaining' },
        { id: 'cloudscheduler', label: 'Cloud Scheduler', desc: 'Managed cron — HTTP/PubSub/App Engine targets' },
        { id: 'cloudfunctions', label: 'Cloud Functions', desc: 'FaaS — event-driven, serverless, zero ops' },
    ]},
    { name: 'Security', tone: 'teal', services: [
        { id: 'secretmanager', label: 'Secret Manager', desc: 'Secrets — API keys, passwords, certificates' },
        { id: 'kms', label: 'Cloud KMS', desc: 'Key management — symmetric/asymmetric, HSM, rotation' },
        { id: 'cloudiam', label: 'Cloud IAM', desc: 'Identity & access — policies, roles, service accounts' },
    ]},
    { name: 'Operations', tone: 'violet', services: [
        { id: 'logging', label: 'Logging', desc: 'Logs — ingestion, storage, query, export' },
        { id: 'monitoring', label: 'Monitoring', desc: 'Metrics — dashboards, alerts, uptime checks' },
        { id: 'cloudbilling', label: 'Billing', desc: 'Cost — budgets, alerts, cost breakdown' },
        { id: 'serviceusage', label: 'Service Usage', desc: 'API enablement — service activation & quotas' },
    ]},
    { name: 'AI/ML', tone: 'purple', services: [
        { id: 'vertexai', label: 'Vertex AI', desc: 'ML platform — training, prediction, model registry', tag: 'Coming up' },
    ]},
];
const FLAT_SERVICES = SERVICE_GROUPS.flatMap(group => group.services.map(svc => ({ ...svc, group: group.name, tone: group.tone })));
const DOCKERHUB_IMAGE = 'localcloud/localcloud';
const DOCKERHUB_LATEST_TAG_URL = `https://hub.docker.com/v2/repositories/${DOCKERHUB_IMAGE}/tags/latest`;

function shortVersion(health) {
    const raw = String(health?.version || '0.1.0').trim();
    const base = (raw.split('+')[0] || raw).trim();
    if (!base) return 'v0.1.0';
    return base.startsWith('v') ? base : `v${base}`;
}

function parseBuildDate(health) {
    const raw = String(health?.version || '').trim();
    const buildPart = raw.includes('+') ? raw.split('+').slice(1).join('+') : '';
    const dateText = buildPart.includes('.') ? buildPart.split('.').slice(1).join('.') : '';
    const parsed = dateText ? Date.parse(dateText) : NaN;
    return Number.isFinite(parsed) ? parsed : null;
}

const STANDALONE_PAGES = ['dashboard', 'getstarted', 'logs', 'usage', 'settings'];
const SERVICE_PAGES = ['explorer', 'editor', 'db-history', 'db-stats'];

function parseProject() {
    const params = new URLSearchParams(window.location.search);
    return params.get('project') || null;
}

function parsePath() {
    const pathname = window.location.pathname.replace(/\/$/, '').replace(/\/+$/, '');
    const parts = pathname.split('/').filter(Boolean);
    if (parts.length === 0) return { page: 'dashboard', service: null };
    const first = decodeURIComponent(parts[0]);
    if (STANDALONE_PAGES.includes(first)) return { page: first, service: null };
    if (parts.length >= 2 && SERVICE_PAGES.includes(parts[1])) {
        return { page: parts[1], service: first, subpath: parts.slice(2) };
    }
    return { page: 'explorer', service: first, subpath: parts.slice(1) };
}

function buildPath(page, service, subpath) {
    const segments = [];
    if (service && SERVICE_PAGES.includes(page)) segments.push(service, page);
    else segments.push(page);
    if (subpath && subpath.length > 0) segments.push(...subpath);
    return '/' + segments.join('/');
}

function buildUrl(page, service, subpath, projectId) {
    let url = buildPath(page, service, subpath);
    if (projectId) url += '?project=' + encodeURIComponent(projectId);
    return url;
}

function navigateWithProject(page, service, subpath, projectId) {
    const url = buildUrl(page, service, subpath, projectId);
    if (window.location.pathname + window.location.search !== url) {
        history.pushState(null, '', url);
    }
}

function App() {
    const initial = parsePath();
    const [currentPage, setCurrentPage] = createSignal(initial.page);
    const [selectedService, setSelectedService] = createSignal(initial.service);
    const [subpath, setSubpath] = createSignal(initial.subpath || []);
    const [darkMode, setDarkMode] = createSignal(false);
    const [healthData, setHealthData] = createSignal(null);
    const [routingData, setRoutingData] = createSignal(null);
    const [credentialData, setCredentialData] = createSignal(null);
    const [refreshInterval, setRefreshInterval] = createSignal(5000);
    const [connectionStatus, setConnectionStatus] = createSignal('connecting');
    const [sidebarPinned, setSidebarPinned] = createSignal(true);
    const [mobileSidebarOpen, setMobileSidebarOpen] = createSignal(false);
    const [searchOpen, setSearchOpen] = createSignal(false);
    const [searchQuery, setSearchQuery] = createSignal('');
    let searchInputEl;

    createEffect(() => {
        if (searchOpen()) {
            requestAnimationFrame(() => searchInputEl?.focus());
        }
    });
    const [globalSearchIndex, setGlobalSearchIndex] = createSignal([]);
    const [updateDismissed, setUpdateDismissed] = createSignal(false);
    const [imageUpdateInfo, setImageUpdateInfo] = createSignal(null);
    const [settingsMenuOpen, setSettingsMenuOpen] = createSignal(false);
    const [shortcutsOpen, setShortcutsOpen] = createSignal(false);
    const [onboardingVisible, setOnboardingVisible] = createSignal((() => {
        try { return localStorage.getItem('localcloud-onboarding-complete') !== 'true'; } catch { return true; }
    })());
    // Recently used services (persisted in localStorage)
    const [recentServices, setRecentServices] = createSignal((() => {
        try { return JSON.parse(localStorage.getItem('localcloud-recent-services') || '[]'); } catch { return []; }
    })());
    // All groups expanded by default
    const [collapsedGroups, setCollapsedGroups] = createSignal({});
    let healthFailCount = 0;
    let imageUpdateCheckStarted = false;

    const urlProject = parseProject();
    const storedProject = (() => {
        try { return localStorage.getItem('localcloud-active-project') || null; } catch { return null; }
    })();
    // Prefer URL project, then localStorage, then default
    const initialProject = urlProject || storedProject || 'local-project';

    const [projects, setProjects] = createSignal([]);
    const [activeProject, setActiveProjectState] = createSignal(initialProject);
    // Sync activeProject to api.js and localStorage, and update URL if needed
    const syncProject = (projectId) => {
        setActiveProject(projectId);
        setActiveProjectState(projectId);
        try { localStorage.setItem('localcloud-active-project', projectId); } catch {}
        // Update URL to reflect project — pushState so project switches are in browser history
        const currentUrlProject = parseProject();
        if (currentUrlProject !== projectId) {
            const page = currentPage();
            const svc = selectedService();
            const sp = subpath();
            const url = buildUrl(page, svc, sp, projectId);
            if (window.location.pathname + window.location.search !== url) {
                history.pushState(null, '', url);
            }
        }
    };
    const [projectDropdownOpen, setProjectDropdownOpen] = createSignal(false);
    const [showNewProjectDialog, setShowNewProjectDialog] = createSignal(false);
    const [newProjectId, setNewProjectId] = createSignal('');
    const [newProjectName, setNewProjectName] = createSignal('');
    const [newProjectLocation, setNewProjectLocation] = createSignal('us-central1');
    const [newProjectZone, setNewProjectZone] = createSignal('');
    const [projectError, setProjectError] = createSignal(null);

    const switchProject = (projectId) => {
        syncProject(projectId);
        setProjectDropdownOpen(false);
    };

    const fetchProjects = async () => {
        try {
            const data = await api.projects();
            const list = Array.isArray(data?.projects) ? data.projects : (Array.isArray(data) ? data : []);
            setProjects(list);
            if (!activeProject() && list.length > 0) {
                const match = list.find(p => p.project_id === initialProject);
                syncProject(match ? match.project_id : list[0].project_id);
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
            await api.createProject(id, name || id, newProjectLocation(), newProjectZone());
            setNewProjectId('');
            setNewProjectName('');
            setNewProjectLocation('us-central1');
            setNewProjectZone('');
            setShowNewProjectDialog(false);
            await fetchProjects();
            syncProject(id);
        } catch (err) {
            setProjectError('Failed to create project: ' + err.message);
            setTimeout(() => setProjectError(null), 8000);
        }
    };

    const initDark = (() => {
        try {
            const stored = localStorage.getItem('localcloud-dark-mode');
            return stored === null ? false : stored === 'true';
        } catch { return false; }
    })();
    setDarkMode(initDark);

    createEffect(() => {
        document.body.dataset.theme = darkMode() ? 'dark' : 'light';
        document.body.dataset.syncMode = Object.values(routingData() || {}).some(route => route?.mode === 'remote') ? 'remote' : 'local';
    });

    const toggleDarkMode = () => {
        const next = !darkMode();
        setDarkMode(next);
        try { localStorage.setItem('localcloud-dark-mode', String(next)); } catch {}
    };

    createEffect(() => {
        const page = currentPage();
        const svc = selectedService();
        const sp = subpath();
        const project = activeProject();
        const url = buildUrl(page, svc, sp, project);
        if (window.location.pathname + window.location.search !== url) {
            history.replaceState(null, '', url);
        }
    });

    const onPopState = () => {
        const { page, service, subpath: sp } = parsePath();
        const urlProject = parseProject();
        setCurrentPage(page);
        if (service) setSelectedService(service);
        else setSelectedService(null);
        setSubpath(sp || []);
        // Restore project from URL if present
        if (urlProject && urlProject !== activeProject()) {
            setActiveProject(urlProject);
            setActiveProjectState(urlProject);
            try { localStorage.setItem('localcloud-active-project', urlProject); } catch {}
        }
    };
    window.addEventListener('popstate', onPopState);
    onCleanup(() => window.removeEventListener('popstate', onPopState));

    const onKeyDown = (e) => {
        const isCommandK = (e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k';
        if (isCommandK) {
            e.preventDefault();
            setSearchOpen(true);
            setSearchQuery('');
            if (globalSearchIndex().length === 0) buildGlobalSearchIndex();
        }
        if (e.key === 'Escape') setSearchOpen(false);
    };
    window.addEventListener('keydown', onKeyDown);
    onCleanup(() => window.removeEventListener('keydown', onKeyDown));

    const onGlobalClick = (e) => {
        if (projectDropdownOpen() && !e.target.closest('.aura-project-wrap')) {
            setProjectDropdownOpen(false);
        }
        if (settingsMenuOpen() && !e.target.closest('.aura-settings-wrap')) {
            setSettingsMenuOpen(false);
        }
    };
    window.addEventListener('click', onGlobalClick);
    onCleanup(() => window.removeEventListener('click', onGlobalClick));

    // Fetch routing + credentials once on load (they rarely change)
    const fetchRoutingAndCreds = async () => {
        try {
            const [routing, creds] = await Promise.all([
                api.routing().catch(() => null),
                api.credentials().catch(() => null),
            ]);
            if (routing) setRoutingData(routing);
            if (creds) setCredentialData(creds);
        } catch {}
    };
    fetchRoutingAndCreds();

    createEffect(() => {
        const interval = refreshInterval();
        let timer;
        const fetchHealth = async () => {
            try {
                const health = await api.health();
                setHealthData(health);
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

    const navigateTo = (page) => {
        batch(() => {
            setSelectedService(null);
            setCurrentPage(page);
            setSubpath([]);
            navigateWithProject(page, null, null, activeProject());
        });
        setSearchOpen(false);
        setSettingsMenuOpen(false);
        setMobileSidebarOpen(false);
    };

    const handleServiceClick = (serviceId) => {
        batch(() => {
            setSelectedService(serviceId);
            setCurrentPage('explorer');
            setSubpath([]);
            navigateWithProject('explorer', serviceId, null, activeProject());
        });
        // Track recently used
        const recent = recentServices().filter(s => s !== serviceId);
        const updated = [serviceId, ...recent].slice(0, 5);
        setRecentServices(updated);
        try { localStorage.setItem('localcloud-recent-services', JSON.stringify(updated)); } catch {}
        // Expand the sidebar group containing this service
        const svc = FLAT_SERVICES.find(s => s.id === serviceId);
        if (svc) {
            setCollapsedGroups(prev => ({ ...prev, [svc.group]: false }));
        }
        setSearchOpen(false);
        setSettingsMenuOpen(false);
        setMobileSidebarOpen(false);
    };

    const toggleGroup = (name) => {
        setCollapsedGroups(prev => ({ ...prev, [name]: !prev[name] }));
    };

    const healthStatus = (serviceId) => connectionStatus() === 'offline' ? 'offline' : (healthData()?.services?.[serviceId]?.status || 'unknown');
    const routingMode = (serviceId) => routingData()?.[serviceId]?.mode || 'local';
    const projectId = () => activeProject() || healthData()?.project_id || 'local-project';
    const projectRegion = () => {
        // Prefer project-level location stored in DB
        const active = projects().find(p => p.project_id === activeProject());
        if (active?.location) return active.location;
        return routingData()?.__default_region || credentialData()?.region || 'us-central1';
    };
    const projectZone = () => {
        const active = projects().find(p => p.project_id === activeProject());
        if (active?.zone) return active.zone;
        return '';
    };
    const persistenceMode = () => healthData()?.persistence || 'PostgreSQL';
    const versionDisplay = () => shortVersion(healthData());
    const versionTitle = () => healthData()?.version_display || healthData()?.version || versionDisplay();

    const rawUpdateAvailable = () => {
        const h = healthData();
        return h?.update_available || imageUpdateInfo();
    };
    const updateAvailable = () => {
        const info = rawUpdateAvailable();
        return info && !updateDismissed() ? info : null;
    };

    createEffect(() => {
        const h = healthData();
        if (!h || h.update_available || imageUpdateCheckStarted) return;
        const localBuildTime = parseBuildDate(h);
        if (!localBuildTime) return;
        imageUpdateCheckStarted = true;
        const controller = new AbortController();
        fetch(DOCKERHUB_LATEST_TAG_URL, { cache: 'no-store', signal: controller.signal })
            .then(res => res.ok ? res.json() : null)
            .then(tag => {
                const remoteUpdated = tag?.last_updated;
                const remoteBuildTime = remoteUpdated ? Date.parse(remoteUpdated) : NaN;
                if (!Number.isFinite(remoteBuildTime) || remoteBuildTime <= localBuildTime + 60000) return;
                setImageUpdateInfo({
                    current: h.version || versionDisplay(),
                    remote_updated: remoteUpdated.slice(0, 10),
                    pull: `docker pull ${DOCKERHUB_IMAGE}:latest`
                });
            })
            .catch(() => {});
        onCleanup(() => controller.abort());
    });

    const buildGlobalSearchIndex = async () => {
        const dataServices = ['firestore', 'spanner', 'bigtable', 'bigquery', 'gcs', 'memorystore'];
        const index = [];
        for (const svc of dataServices) {
            try {
                const mode = routingData()?.[svc]?.mode || 'local';
                let nodes = [];
                if (mode === 'remote') {
                    const data = await api.syncBrowse(svc);
                    const rawNodes = data.nodes || data || [];
                    nodes = normalizeRemoteBrowse(rawNodes, svc);
                } else {
                    const data = await api.schema(svc);
                    nodes = normalizeLocalSchema(data, svc);
                }
                const svcName = FLAT_SERVICES.find(s => s.id === svc)?.label || svc;
                
                const extract = (nodeList, parentName = '') => {
                    for (const n of nodeList) {
                        index.push({
                            type: n.type,
                            label: parentName ? `${parentName}.${n.name}` : n.name,
                            id: n.id,
                            icon: svc,
                            datastore: svcName,
                            action: () => {
                                setSearchOpen(false);
                                setCurrentPage('explorer');
                                setSelectedService(svc);
                                navigateWithProject('explorer', svc, null, activeProject());
                            }
                        });
                        if (n.children && n.children.length > 0) {
                            extract(n.children, parentName ? `${parentName}.${n.name}` : n.name);
                        }
                    }
                };
                extract(nodes);
            } catch (e) { }
        }
        setGlobalSearchIndex(index);
    };

    const searchResults = createMemo(() => {
        const q = searchQuery().toLowerCase().trim();
        const pages = NAV_ITEMS.map(item => ({ type: 'Page', label: item.label, id: item.id, icon: item.icon, action: () => navigateTo(item.id) }));
        const services = FLAT_SERVICES.map(svc => ({ type: svc.group, label: svc.label, id: svc.id, icon: svc.id, action: () => handleServiceClick(svc.id) }));
        const combined = [...pages, ...services, ...globalSearchIndex()];
        return combined.filter(item => !q || item.label.toLowerCase().includes(q) || item.id.toLowerCase().includes(q)).slice(0, 12);
    });

    const renderPage = () => {
        if (!activeProject()) return <div class="loading-state"><div class="loading-spinner" /> Loading project…</div>;
        switch (currentPage()) {
            case 'dashboard':
                return <Dashboard healthData={healthData} routingData={routingData} onServiceClick={handleServiceClick} activeProject={activeProject} />;
            case 'getstarted':
                return <GetStarted healthData={healthData} activeProject={activeProject} onServiceClick={handleServiceClick} recentServices={recentServices} projectRegion={projectRegion} />;
            case 'logs':
                return <Logs activeProject={activeProject} />;
            case 'explorer':
            case 'editor':
            case 'db-history':
            case 'db-stats':
                return <ServiceExplorer selectedService={selectedService} activeView={currentPage} onViewChange={setCurrentPage} onTabChange={setSelectedService} activeProject={activeProject} projectRegion={projectRegion} subpath={subpath} onSubpathChange={setSubpath} />;
            case 'usage':
                return <Usage activeProject={activeProject} />;
            case 'settings':
                return <Settings darkMode={darkMode} toggleDarkMode={toggleDarkMode} refreshInterval={refreshInterval} setRefreshInterval={setRefreshInterval} routingData={routingData} credentialData={credentialData} healthData={healthData} onRoutingChanged={fetchRoutingAndCreds} />;
            default:
                return <Dashboard healthData={healthData} routingData={routingData} onServiceClick={handleServiceClick} activeProject={activeProject} />;
        }
    };

    return (
        <>
            <Show when={onboardingVisible()}>
                <Onboarding onDone={() => setOnboardingVisible(false)} />
            </Show>
            <a class="skip-link" href="#main-content">Skip to content</a>
            <Show when={updateAvailable()}>
                {() => {
                    const info = updateAvailable();
                    return <div class="aura-update-banner">
                        <span>New LocalCloud image available{info.remote_updated ? ` (${info.remote_updated})` : ''}</span>
                        <code>{info.pull || 'docker pull localcloud/localcloud:latest'}</code>
                        <button onClick={() => setUpdateDismissed(true)} aria-label="Dismiss update">&times;</button>
                    </div>;
                }}
            </Show>

            <header class="topbar aura-topbar">
                <div class="topbar-left aura-topbar-left">
                    <button class="aura-sidebar-toggle" onClick={() => {
                        if (window.innerWidth <= 900) setMobileSidebarOpen(!mobileSidebarOpen());
                        else setSidebarPinned(!sidebarPinned());
                    }} title={sidebarPinned() ? 'Collapse services' : 'Pin services'} aria-label={sidebarPinned() ? 'Collapse services sidebar' : 'Pin services sidebar'} aria-expanded={window.innerWidth <= 900 ? mobileSidebarOpen() : sidebarPinned()}>
                        <img src="/icons/localcloud-mark.svg" alt="" width="28" height="28" />
                    </button>
                    <button class="aura-brand-lockup aura-brand-button" onClick={() => navigateTo('dashboard')} title="Dashboard">
                        <span class="aura-brand-name">LocalCloud</span>
                        <span
                            class={`aura-brand-version ${rawUpdateAvailable() ? 'has-update' : ''}`}
                            title={rawUpdateAvailable() ? `New image available: ${rawUpdateAvailable().pull || `docker pull ${DOCKERHUB_IMAGE}:latest`}` : versionTitle()}
                            aria-label={rawUpdateAvailable() ? `${versionDisplay()}, newer Docker image available` : versionDisplay()}
                        >
                            {versionDisplay()}
                            <Show when={rawUpdateAvailable()}>
                                <span class="aura-version-alert-dot" aria-hidden="true" />
                            </Show>
                        </span>
                    </button>
                    <div class="aura-project-wrap">
                        <button class="topbar-project-chip aura-project-chip" onClick={() => setProjectDropdownOpen(!projectDropdownOpen())} aria-expanded={projectDropdownOpen()}>
                            <span class="aura-project-main">{projectId()}</span>
                            <span class="aura-project-sub">{projectRegion()}{projectZone() ? ' (' + projectZone() + ')' : ''} · {persistenceMode()}</span>
                        </button>
                        <Show when={projectDropdownOpen()}>
                            <div class="project-dropdown aura-project-dropdown">
                                <div class="project-dropdown-label">Projects</div>
                                <For each={projects()}>
                                    {(p) => <button class={`project-dropdown-item ${activeProject() === p.project_id ? 'active' : ''}`} onClick={() => switchProject(p.project_id)}>
                                        <span>{p.project_id}</span>
                                        <Show when={p.display_name && p.display_name !== p.project_id}><span>{p.display_name}</span></Show>
                                    </button>}
                                </For>
                                <button class="project-dropdown-item aura-new-project" onClick={() => { setProjectDropdownOpen(false); setShowNewProjectDialog(true); }}>New Project</button>
                            </div>
                        </Show>
                    </div>
                </div>
                <div class="topbar-center aura-search-center">
                    <button class="aura-global-search" onClick={() => { setSearchOpen(true); setSearchQuery(''); if (globalSearchIndex().length === 0) buildGlobalSearchIndex(); }}>
                        <Icon name="search" size={16} />
                        <span>Search services, tables, logs…</span>
                        <kbd>{navigator.platform?.includes('Mac') ? '⌘' : 'Ctrl'}</kbd><kbd>K</kbd>
                    </button>
                </div>
                <div class="topbar-right">
                    <button class="aura-top-icon" onClick={() => setShortcutsOpen(true)} title="Keyboard shortcuts" aria-label="Keyboard shortcuts">
                        <span style={{ "font-weight": "800", "font-size": "16px" }}>?</span>
                    </button>
                    <button class="aura-top-icon" onClick={() => navigateTo('settings')} title="Documentation" aria-label="Open documentation">
                        <Icon name="docs" size={22} />
                    </button>
                    <div class="aura-settings-wrap">
                        <button class="aura-top-icon" onClick={() => setSettingsMenuOpen(!settingsMenuOpen())} title="Settings" aria-label="Open settings menu" aria-expanded={settingsMenuOpen()}>
                            <Icon name="settings" size={22} />
                        </button>
                        <Show when={settingsMenuOpen()}>
                            <div class="project-dropdown aura-settings-menu">
                                <div class="project-dropdown-label">Settings</div>
                                <button class="project-dropdown-item" onClick={() => navigateTo('logs')}><span>Logs</span></button>
                                <button class="project-dropdown-item" onClick={() => navigateTo('usage')}><span>Cost Analysis</span></button>
                                <button class="project-dropdown-item" onClick={toggleDarkMode}><span>{darkMode() ? 'Light Mode' : 'Dark Mode'}</span></button>
                            </div>
                        </Show>
                    </div>
                    <button class="aura-user-btn" title="Guest · Sign in coming soon" aria-label="User menu">
                        <Icon name="user" size={22} />
                    </button>
                </div>
            </header>

            <Show when={connectionStatus() === 'offline'}>
                <div class="offline-banner" role="alert">Cannot reach LocalCloud backend. Is the container running?</div>
            </Show>
            <Show when={connectionStatus() === 'connecting'}>
                <div class="offline-banner connecting" role="status"><div class="loading-spinner" /> Connecting to LocalCloud…</div>
            </Show>

            <Show when={mobileSidebarOpen()}>
                <div class="mobile-overlay" onClick={() => setMobileSidebarOpen(false)} />
            </Show>

            <nav class={`sidebar aura-sidebar ${sidebarPinned() ? 'pinned' : ''} ${mobileSidebarOpen() ? 'mobile-open' : ''}`} aria-label="Service navigation">
                <div class="aura-sidebar-header">
                    <button class={`aura-sidebar-nav-item ${currentPage() === 'dashboard' ? 'active' : ''}`} onClick={() => navigateTo('dashboard')}>
                        <Icon name="dashboard" size={22} />
                        <span class="aura-sidebar-nav-label">Dashboard</span>
                        <div class="aura-tooltip">Dashboard</div>
                    </button>
                    <button class="aura-sidebar-pin-btn" onClick={() => setSidebarPinned(!sidebarPinned())} aria-label={sidebarPinned() ? 'Unpin sidebar' : 'Pin sidebar'} aria-pressed={sidebarPinned()}>
                        <Icon name="pin" size={16} />
                        <div class="aura-tooltip">{sidebarPinned() ? 'Unpin sidebar' : 'Pin sidebar'}</div>
                    </button>
                </div>
                <div class="aura-service-groups">
                    <For each={SERVICE_GROUPS}>
                        {(group) => <div class={`aura-service-group tone-${group.tone}`}>
                            <button class={`aura-group-label ${collapsedGroups()[group.name] ? 'collapsed' : ''}`} onClick={() => toggleGroup(group.name)} title={`${group.name} services`} aria-expanded={!collapsedGroups()[group.name]}>
                                <Icon name="chevron" size={16} />
                                <span>{group.name}</span>
                                <small>{group.services.length}</small>
                            </button>
                            <Show when={!collapsedGroups()[group.name]}>
                                <div class="aura-group-services">
                                    <For each={group.services}>
                                        {(svc) => <button
                                            class={`sidebar-sub-item ${selectedService() === svc.id && SERVICE_PAGES.includes(currentPage()) ? 'active' : ''} ${svc.tag === 'Coming up' ? 'coming-up' : ''}`}
                                            onClick={() => svc.tag !== 'Coming up' && handleServiceClick(svc.id)}
                                            disabled={svc.tag === 'Coming up'}
                                            aria-disabled={svc.tag === 'Coming up'}
                                            title={svc.tag === 'Coming up' ? `${svc.label} — coming soon` : svc.label}
                                        >
                                            <img src={`/icons/${svc.id}.svg`} alt="" width="20" height="20" class="sidebar-sub-item-icon" />
                                            <span class="sidebar-sub-item-label">{svc.label}</span>
                                            <Show when={svc.desc && sidebarPinned()}>
                                                <span class="sidebar-sub-item-desc">{svc.desc}</span>
                                            </Show>
                                            <Show when={svc.tag}><span class="badge badge-coming-up">{svc.tag}</span></Show>
                                            <Show when={routingMode(svc.id) === 'remote'}><span class="badge badge-cloud">Cloud</span></Show>
                                            <span class={`sidebar-sub-item-dot ${healthStatus(svc.id)}`} title={healthStatus(svc.id)} />
                                            <div class="aura-tooltip">{svc.label}{svc.desc ? ' — ' + svc.desc : ''}</div>
                                        </button>}
                                    </For>
                                </div>
                            </Show>
                        </div>}
                    </For>
                </div>
                <Show when={recentServices().length > 0}>
                    <div class="aura-recent-section">
                        <div class="aura-group-label" style="cursor:default;opacity:0.7">
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" style="opacity:0.6"><path d="M13 3a9 9 0 0 0-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42A8.954 8.954 0 0 0 13 21a9 9 0 0 0 0-18zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z"/></svg>
                            <span>Recent</span>
                        </div>
                        <div class="aura-group-services">
                            <For each={recentServices().map(sid => FLAT_SERVICES.find(s => s.id === sid)).filter(Boolean)}>
                                {(svc) => <button
                                    class={`sidebar-sub-item ${selectedService() === svc.id && SERVICE_PAGES.includes(currentPage()) ? 'active' : ''}`}
                                    onClick={() => handleServiceClick(svc.id)}
                                    title={svc.label}
                                >
                                    <img src={`/icons/${svc.id}.svg`} alt="" width="18" height="18" class="sidebar-sub-item-icon" />
                                    <span class="sidebar-sub-item-label">{svc.label}</span>
                                    <span class={`sidebar-sub-item-dot ${healthStatus(svc.id)}`} title={healthStatus(svc.id)} />
                                </button>}
                            </For>
                        </div>
                    </div>
                </Show>
            </nav>

            <main id="main-content" class="main-content aura-main">
                {renderPage()}
            </main>

            <Show when={searchOpen()}>
                <div class="aura-command-overlay" onClick={() => setSearchOpen(false)}>
                    <div class="aura-command-menu" onClick={(e) => e.stopPropagation()}>
                        <div class="aura-command-input">
                            <Icon name="search" size={18} />
                            <input ref={searchInputEl} value={searchQuery()} onInput={(e) => setSearchQuery(e.currentTarget.value)} aria-label="Command search" autocomplete="new-password" autocapitalize="off" autocorrect="off" spellcheck={false} data-lpignore="true" data-1p-ignore="true" placeholder="Jump to a service, page, table, or log surface…" />
                        </div>
                        <div class="aura-command-list">
                            <For each={searchResults()}>
                                {(item) => <button class="aura-command-item" onClick={item.action}>
                                    <span class="aura-command-icon">
                                        <Show when={item.type === 'Page'} fallback={<img src={`/icons/${item.icon}.svg`} alt="" width="16" height="16" />}>
                                            <Icon name={item.icon} size={16} />
                                        </Show>
                                    </span>
                                    <span>{item.label}</span>
                                    <small style={{ "text-transform": "capitalize" }}>
                                        {item.type}{item.datastore ? ` in ${item.datastore}` : ''}
                                    </small>
                                </button>}
                            </For>
                        </div>
                    </div>
                </div>
            </Show>

            <Show when={shortcutsOpen()}>
                <div class="modal-overlay" role="dialog" aria-modal="true" aria-label="Keyboard shortcuts" onClick={(e) => { if (e.target === e.currentTarget) setShortcutsOpen(false); }}>
                    <div class="create-dialog" ref={el => { if (el) requestAnimationFrame(() => trapFocus(el, () => setShortcutsOpen(false))); }}
                        style={{ width: "420px", padding: "0" }} onClick={(e) => e.stopPropagation()}>
                        <div class="create-dialog-accent" style="background:var(--primary)" />
                        <div class="create-dialog-header">
                            <h2 class="create-dialog-title">Keyboard Shortcuts</h2>
                        </div>
                        <div class="create-dialog-body" style={{ "padding-bottom": "20px" }}>
                            <div style={{ display: "flex", "flex-direction": "column", gap: "12px" }}>
                                {[
                                    { keys: navigator.platform?.includes('Mac') ? '⌘K' : 'Ctrl+K', desc: 'Command palette — search services, tables, logs' },
                                    { keys: '⌘Enter', desc: 'Run current SQL query' },
                                    { keys: 'Esc', desc: 'Close command palette / modals' },
                                ].map(s => (
                                    <div style={{ display: "flex", "align-items": "center", gap: "12px" }}>
                                        <kbd style={{
                                            "min-width": "64px",
                                            display: "inline-flex",
                                            "align-items": "center",
                                            "justify-content": "center",
                                            padding: "3px 8px",
                                            "font-family": "var(--font-mono)",
                                            "font-size": "12px",
                                            "font-weight": "700",
                                            "border-radius": "6px",
                                            border: "1px solid var(--border)",
                                            background: "var(--surface-variant)",
                                            color: "var(--text)",
                                        }}>{s.keys}</kbd>
                                        <span style={{ "font-size": "13px", color: "var(--text-secondary)" }}>{s.desc}</span>
                                    </div>
                                ))}
                            </div>
                        </div>
                    </div>
                </div>
            </Show>

            <Show when={showNewProjectDialog()}>
                <div class="modal-overlay" role="dialog" aria-modal="true" aria-labelledby="new-project-title" onClick={(e) => { if (e.target === e.currentTarget) { setShowNewProjectDialog(false); setProjectError(null); } }}>
                    <div class="create-dialog" onClick={(e) => e.stopPropagation()} ref={el => {
                        if (el) requestAnimationFrame(() => trapFocus(el, () => { setShowNewProjectDialog(false); setProjectError(null); }));
                    }}>
                        <div class="create-dialog-accent" style="background:var(--purple)" />
                        <div class="create-dialog-header">
                            <div class="create-dialog-header-icon" style="color:var(--purple);border-color:var(--purple-soft);background:var(--purple-soft)">
                                <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
                            </div>
                            <h2 id="new-project-title" class="create-dialog-title">New Project</h2>
                            <p class="create-dialog-context">Create a new GCP project for local development</p>
                        </div>
                        <div class="create-dialog-body">
                            <Show when={projectError()}><div class="create-dialog-error" role="alert">{projectError()}</div></Show>
                            <div class="create-dialog-field">
                                <label class="create-dialog-label" for="project-id-input">Project ID</label>
                                <input id="project-id-input" name="project-id" autocomplete="off" class="create-dialog-input create-dialog-input-mono" value={newProjectId()} onInput={(e) => setNewProjectId(e.currentTarget.value)} placeholder="my-project" />
                            </div>
                            <div class="create-dialog-field">
                                <label class="create-dialog-label" for="project-name-input">Display Name</label>
                                <input id="project-name-input" name="project-display-name" autocomplete="off" class="create-dialog-input" value={newProjectName()} onInput={(e) => setNewProjectName(e.currentTarget.value)} placeholder="My Project" />
                            </div>
                            <div class="create-dialog-field">
                                <label class="create-dialog-label" for="project-location">Default Location (Region)</label>
                                <ComboBox value={newProjectLocation()} onChange={setNewProjectLocation} options={GCP_REGIONS} placeholder="Type or select a region..." />
                            </div>
                            <div class="create-dialog-field">
                                <label class="create-dialog-label" for="project-zone">Default Zone</label>
                                <ComboBox value={newProjectZone()} onChange={setNewProjectZone} options={newProjectLocation() ? getZonesForRegion(newProjectLocation()) : []} placeholder="Type or select a zone (optional)" />
                            </div>
                        </div>
                        <div class="create-dialog-footer">
                            <button class="create-dialog-btn-cancel" onClick={() => { setShowNewProjectDialog(false); setProjectError(null); }}>Cancel</button>
                            <button class="create-dialog-btn-submit" style="background:var(--purple);color:#fff" onClick={handleCreateProject} disabled={!newProjectId().trim()}>Create</button>
                        </div>
                    </div>
                </div>
            </Show>
        </>
    );
}

render(() => <App />, document.getElementById('root'));

import { createSignal, createEffect, For } from 'solid-js';
import { services } from './api.js';

// Pages
import Dashboard from './pages/Dashboard.jsx';
import Services from './pages/Services.jsx';
import Logs from './pages/Logs.jsx';
import DataBrowser from './pages/DataBrowser.jsx';
import Settings from './pages/Settings.jsx';

function App() {
    const [currentPage, setCurrentPage] = createSignal('dashboard');
    const [darkMode, setDarkMode] = createSignal(true);
    const [refreshInterval, setRefreshInterval] = createSignal(5000);

    // Poll services every refreshInterval
    const [serviceList, setServiceList] = createSignal([]);
    const [loading, setLoading] = createSignal(false);

    const fetchServices = async () => {
        setLoading(true);
        try {
            const result = await services.listServices();
            setServiceList(result.services || []);
        } catch (err) {
            console.error('Failed to fetch services:', err);
        } finally {
            setLoading(false);
        }
    };

    // Initial load and polling
    createEffect(() => {
        fetchServices();
        const interval = setInterval(fetchServices, refreshInterval());
        return () => clearInterval(interval);
    });

    const renderPage = () => {
        const page = currentPage();
        const pageProps = { services: serviceList, refreshInterval, loading };

        switch (page) {
            case 'dashboard':
                return <Dashboard {...pageProps} />;
            case 'services':
                return <Services {...pageProps} />;
            case 'logs':
                return <Logs {...pageProps} />;
            case 'data':
                return <DataBrowser {...pageProps} />;
            case 'settings':
                return <Settings darkMode={darkMode} setDarkMode={setDarkMode} {...pageProps} />;
            default:
                return <Dashboard {...pageProps} />;
        }
    };

    return (
        <div class={`app-container ${darkMode() ? 'dark' : 'light'}`}>
            {/* Header */}
            <div class="app-header">
                <h1>LocalCloud Console</h1>
                <div class="app-header-right">
                    <button class="secondary" onClick={() => setDarkMode(!darkMode())}>
                        {darkMode() ? '☀️' : '🌙'}
                    </button>
                    <span style={{ 'font-size': '12px', 'color': 'var(--text-secondary)' }}>
                        local-project
                    </span>
                </div>
            </div>

            {/* Main container */}
            <div style={{ display: 'flex', flex: 1 }}>
                {/* Sidebar */}
                <div class="app-sidebar">
                    {[
                        { name: 'Dashboard', value: 'dashboard', icon: '📊' },
                        { name: 'Services', value: 'services', icon: '⚙️' },
                        { name: 'Logs', value: 'logs', icon: '📝' },
                        { name: 'Data Browser', value: 'data', icon: '💾' },
                        { name: 'Settings', value: 'settings', icon: '⚙️' },
                    ].map(item => (
                        <div
                            class={`sidebar-item ${currentPage() === item.value ? 'active' : ''}`}
                            onClick={() => setCurrentPage(item.value)}
                        >
                            <span>{item.icon}</span>
                            <span>{item.name}</span>
                        </div>
                    ))}
                </div>

                {/* Content */}
                <div class="app-content">
                    {renderPage()}
                </div>
            </div>
        </div>
    );
}

// Render app
import { render } from 'solid-js/web';
render(() => <App />, document.getElementById('root'));

/**
 * Main App Component
 * Solid.js entry point for LocalCloud Dashboard
 * Manages routing, global state, and component lifecycle
 */

import { createSignal, createEffect, Show } from 'solid-js';
import { Header } from './components/Header';
import { Sidebar } from './components/Sidebar';
import { Dashboard } from './pages/Dashboard';
import { Services } from './pages/Services';
import { Logs } from './pages/Logs';
import { DataBrowser } from './pages/DataBrowser';
import { Settings } from './pages/Settings';
import * as api from './api';

export function App() {
  // Current page state
  const [currentPage, setCurrentPage] = createSignal('dashboard');

  // Settings state
  const [darkMode, setDarkMode] = createSignal(false);
  const [refreshInterval, setRefreshInterval] = createSignal(5);

  // Data state
  const [health, setHealth] = createSignal(null);
  const [services, setServices] = createSignal([]);
  const [loading, setLoading] = createSignal(true);
  const [error, setError] = createSignal(null);
  const [projectId, setProjectId] = createSignal('local-project');

  // Fetch health status
  const fetchHealthData = async () => {
    try {
      const data = await api.fetchHealth();
      setHealth(data);
      setProjectId(data.project_id || 'local-project');
    } catch (err) {
      console.error('Failed to fetch health:', err);
      setError(`Failed to fetch health: ${err.message}`);
    }
  };

  // Fetch services list
  const fetchServicesData = async () => {
    try {
      const response = await api.fetchServices();
      const serviceList = response.services || [];
      setServices(serviceList);
      setLoading(false);
    } catch (err) {
      console.error('Failed to fetch services:', err);
      setError(`Failed to fetch services: ${err.message}`);
      setLoading(false);
    }
  };

  // Refresh both health and services
  const refreshData = async () => {
    setLoading(true);
    await Promise.all([fetchHealthData(), fetchServicesData()]);
    setLoading(false);
  };

  // Setup auto-refresh effect
  createEffect(() => {
    // Initial fetch
    refreshData();

    // Setup interval for polling
    const interval = setInterval(() => {
      refreshData();
    }, refreshInterval() * 1000);

    // Cleanup on unmount or when refreshInterval changes
    return () => clearInterval(interval);
  });

  // Apply dark mode to document
  createEffect(() => {
    if (darkMode()) {
      document.documentElement.classList.add('dark-mode');
    } else {
      document.documentElement.classList.remove('dark-mode');
    }
  });

  // Handle theme toggle
  const handleThemeToggle = () => {
    setDarkMode(!darkMode());
  };

  // Handle navigation
  const handleNavigate = (page) => {
    setCurrentPage(page);
  };

  // Handle settings change
  const handleSettingsChange = (settings) => {
    if (settings.refreshInterval) {
      setRefreshInterval(settings.refreshInterval);
    }
    if (settings.darkMode !== undefined) {
      setDarkMode(settings.darkMode);
    }
  };

  // Fetch logs for Logs page
  const fetchLogs = async (options) => {
    return await api.fetchRequestLog(options);
  };

  // Browse data for DataBrowser page
  const browseService = async (service, path = '') => {
    return await api.browsePath(service, path);
  };

  // Export environment variables
  const handleExportEnv = async (format) => {
    try {
      const envData = await api.fetchEnvVars(format);

      // Create a blob and download
      const isText = format === 'shell' || format === 'docker-compose';
      const content = isText ? envData : JSON.stringify(envData, null, 2);
      const blob = new Blob([content], {
        type: isText ? 'text/plain' : 'application/json'
      });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `localcloud-env.${format === 'json' ? 'json' : format}`;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      URL.revokeObjectURL(url);
    } catch (err) {
      console.error('Failed to export env:', err);
      alert(`Failed to export environment variables: ${err.message}`);
    }
  };

  // Reset services
  const handleResetServices = async () => {
    if (!window.confirm('Reset all services to initial state? This will clear all data.')) {
      return;
    }

    try {
      setLoading(true);
      const result = await api.resetServices({ restore_seed: true });
      console.log('Reset successful:', result);
      await refreshData();
    } catch (err) {
      console.error('Failed to reset services:', err);
      alert(`Failed to reset services: ${err.message}`);
    } finally {
      setLoading(false);
    }
  };

  // Render the current page
  const renderPage = () => {
    return (
      <>
        <Show when={currentPage() === 'dashboard'}>
          <Dashboard
            health={health()}
            services={services()}
            refreshInterval={refreshInterval()}
            onRefreshService={refreshData}
            onResetServices={handleResetServices}
            onExportEnv={() => handleExportEnv('shell')}
          />
        </Show>

        <Show when={currentPage() === 'services'}>
          <Services
            services={services()}
            refreshInterval={refreshInterval()}
          />
        </Show>

        <Show when={currentPage() === 'logs'}>
          <Logs
            refreshInterval={refreshInterval()}
            onFetchLogs={fetchLogs}
          />
        </Show>

        <Show when={currentPage() === 'databrowser'}>
          <DataBrowser
            onBrowse={browseService}
          />
        </Show>

        <Show when={currentPage() === 'settings'}>
          <Settings
            darkMode={darkMode()}
            refreshInterval={refreshInterval()}
            onThemeToggle={handleThemeToggle}
            onSettingsChange={handleSettingsChange}
            onExportEnv={handleExportEnv}
          />
        </Show>
      </>
    );
  };

  return (
    <div class={`app-container ${darkMode() ? 'dark-mode' : ''}`}>
      <Header
        projectId={projectId()}
        darkMode={darkMode()}
        onThemeToggle={handleThemeToggle}
      />

      <div class="app-main">
        <Sidebar
          currentPage={currentPage()}
          refreshInterval={refreshInterval()}
          onNavigate={handleNavigate}
        />

        <main class="app-content">
          <Show when={error()}>
            <div class="error-banner">
              <p>{error()}</p>
              <button onclick={() => setError(null)}>Dismiss</button>
            </div>
          </Show>

          <Show when={!loading()} fallback={<p class="loading-text">Loading...</p>}>
            {renderPage()}
          </Show>
        </main>
      </div>
    </div>
  );
}

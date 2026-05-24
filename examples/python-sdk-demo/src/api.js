/**
 * API wrapper for LocalCloud admin endpoints
 * All endpoints are under  base path
 */

const API_BASE = '';

/**
 * Fetch health status of the platform
 * @returns {Promise} Health status including services, uptime, project_id
 */
export async function fetchHealth() {
  try {
    const response = await fetch(`${API_BASE}/health`);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return await response.json();
  } catch (error) {
    console.error('Failed to fetch health:', error);
    throw error;
  }
}

/**
 * Fetch list of services with their status and configuration
 * @returns {Promise} Array of service objects with id, name, status, port, protocol, etc.
 */
export async function fetchServices() {
  try {
    const response = await fetch(`${API_BASE}/services`);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return await response.json();
  } catch (error) {
    console.error('Failed to fetch services:', error);
    throw error;
  }
}

/**
 * Fetch request log entries
 * @param {Object} options - Query parameters
 * @param {number} options.limit - Maximum number of entries (default 100, max 1000)
 * @param {string} options.service - Filter by service ID (optional)
 * @param {string} options.since - ISO 8601 timestamp (optional)
 * @returns {Promise} Object with requests array and metadata
 */
export async function fetchRequestLog(options = {}) {
  try {
    const params = new URLSearchParams();
    if (options.limit) params.append('limit', options.limit);
    if (options.service) params.append('service', options.service);
    if (options.since) params.append('since', options.since);

    const query = params.toString();
    const url = query ? `${API_BASE}/requests?${query}` : `${API_BASE}/requests`;
    const response = await fetch(url);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return await response.json();
  } catch (error) {
    console.error('Failed to fetch request log:', error);
    throw error;
  }
}

/**
 * Browse data from a service
 * @param {string} service - Service ID (gcs, pubsub, firestore, etc.)
 * @param {string} path - Optional path for nested browsing
 * @returns {Promise} Service-specific data structure
 */
export async function browsePath(service, path = '') {
  try {
    const url = path
      ? `${API_BASE}/browse/${service}/${path}`
      : `${API_BASE}/browse/${service}`;
    const response = await fetch(url);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return await response.json();
  } catch (error) {
    console.error(`Failed to browse ${service}:`, error);
    throw error;
  }
}

/**
 * Load a seed file (YAML)
 * @param {string} yamlContent - YAML file content as string
 * @returns {Promise} Response with resources_created counts
 */
export async function loadSeed(yamlContent) {
  try {
    const response = await fetch(`${API_BASE}/seed`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/yaml' },
      body: yamlContent
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return await response.json();
  } catch (error) {
    console.error('Failed to load seed:', error);
    throw error;
  }
}

/**
 * Reset all services to initial state
 * @param {Object} options - Reset options
 * @param {boolean} options.restore_seed - Whether to restore seed data
 * @returns {Promise} Response with cleared services list
 */
export async function resetServices(options = {}) {
  try {
    const response = await fetch(`${API_BASE}/reset`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(options)
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return await response.json();
  } catch (error) {
    console.error('Failed to reset services:', error);
    throw error;
  }
}

/**
 * Fetch environment variable export in specified format
 * @param {string} format - Format: 'shell', 'docker-compose', or 'json'
 * @returns {Promise} Environment variables in requested format
 */
export async function fetchEnvVars(format = 'json') {
  try {
    const url = `${API_BASE}/env?format=${encodeURIComponent(format)}`;
    const response = await fetch(url);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);

    // For shell and docker-compose formats, return text
    if (format === 'shell' || format === 'docker-compose') {
      return await response.text();
    }

    // For JSON, return parsed object
    return await response.json();
  } catch (error) {
    console.error('Failed to fetch environment variables:', error);
    throw error;
  }
}

/**
 * Fetch logging entries
 * @param {Object} options - Filter options
 * @param {string} options.severity - Filter by severity (ERROR, WARNING, INFO, etc.)
 * @param {number} options.limit - Maximum entries
 * @returns {Promise} Array of log entries
 */
export async function fetchLogs(options = {}) {
  try {
    const params = new URLSearchParams();
    if (options.severity) params.append('severity', options.severity);
    if (options.limit) params.append('limit', options.limit);

    const query = params.toString();
    const url = query
      ? `${API_BASE}/browse/logging/entries?${query}`
      : `${API_BASE}/browse/logging/entries`;

    const response = await fetch(url);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return await response.json();
  } catch (error) {
    console.error('Failed to fetch logs:', error);
    throw error;
  }
}

/**
 * Fetch monitoring time series
 * @param {Object} options - Filter options
 * @param {string} options.metric_type - Filter by metric type
 * @returns {Promise} Array of time series objects
 */
export async function fetchMetrics(options = {}) {
  try {
    const params = new URLSearchParams();
    if (options.metric_type) params.append('metric_type', options.metric_type);

    const query = params.toString();
    const url = query
      ? `${API_BASE}/browse/monitoring/timeseries?${query}`
      : `${API_BASE}/browse/monitoring/timeseries`;

    const response = await fetch(url);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return await response.json();
  } catch (error) {
    console.error('Failed to fetch metrics:', error);
    throw error;
  }
}

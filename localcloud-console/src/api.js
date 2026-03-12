/**
 * API wrapper for fetching from Flask backend.
 */

const BASE_URL = '';  // Relative to current host

export async function apiGet(path) {
    try {
        const resp = await fetch(`${BASE_URL}${path}`);
        if (!resp.ok) throw new Error(`${resp.status}: ${resp.statusText}`);
        return await resp.json();
    } catch (err) {
        console.error(`API GET ${path}:`, err);
        throw err;
    }
}

export async function apiPost(path, data = {}) {
    try {
        const resp = await fetch(`${BASE_URL}${path}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(data),
        });
        if (!resp.ok) throw new Error(`${resp.status}: ${resp.statusText}`);
        return await resp.json();
    } catch (err) {
        console.error(`API POST ${path}:`, err);
        throw err;
    }
}

// Service API calls
export const services = {
    getStatus: () => apiGet('/api/status'),
    listServices: () => apiGet('/api/services'),
    getService: (name) => apiGet(`/api/services/${name}`),
    startService: (name) => apiPost(`/api/services/${name}/start`),
    stopService: (name) => apiPost(`/api/services/${name}/stop`),
    restartService: (name) => apiPost(`/api/services/${name}/restart`),
    reset: () => apiPost('/api/reset'),
    getLogs: (service, lines = 100) => apiGet(`/api/logs/${service}?lines=${lines}`),
};

// Data API calls
export const data = {
    getFirestoreCollections: () => apiGet('/api/firestore/collections'),
    getBigQueryDatasets: () => apiGet('/api/bigquery/datasets'),
    getGCSBuckets: () => apiGet('/api/gcs/buckets'),
};

/**
 * LocalCloud Console API client.
 * Calls the LocalCloud Admin API directly on the gateway.
 * Project-aware: passes ?project= on browse/env/reset calls.
 */

import { createSignal } from 'solid-js';

const BASE = '';

const [_activeProject, _setActiveProject] = createSignal(null);

export function setActiveProject(projectId) {
    _setActiveProject(projectId);
}

export function getActiveProject() {
    return _activeProject();
}

function projectParam() {
    return _activeProject() ? `?project=${encodeURIComponent(_activeProject())}` : '';
}

function appendProject(path) {
    if (!_activeProject()) return path;
    const sep = path.includes('?') ? '&' : '?';
    return `${path}${sep}project=${encodeURIComponent(_activeProject())}`;
}

async function handleResponse(r) {
    if (!r.ok) {
        // Try to extract server error message from JSON body
        try {
            const body = await r.json();
            throw new Error(body.message || `${r.status} ${r.statusText}`);
        } catch (e) {
            if (e.message && !e.message.startsWith('Unexpected')) throw e;
            throw new Error(`${r.status} ${r.statusText}`);
        }
    }
    return r.json();
}

async function get(path) {
    const r = await fetch(`${BASE}${path}`);
    return handleResponse(r);
}

async function post(path, body) {
    const opts = { method: 'POST' };
    if (body) {
        opts.headers = { 'Content-Type': 'application/json' };
        opts.body = JSON.stringify(body);
    }
    const r = await fetch(`${BASE}${path}`, opts);
    return handleResponse(r);
}

async function put(path, body) {
    const r = await fetch(`${BASE}${path}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    return handleResponse(r);
}

async function del(path) {
    const r = await fetch(`${BASE}${path}`, { method: 'DELETE' });
    return handleResponse(r);
}

async function postJson(path, body) {
    const r = await fetch(`${BASE}${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    if (!r.ok) {
        try {
            const body = await r.json();
            throw new Error(body.message || `${r.status} ${r.statusText}`);
        } catch (e) {
            if (e.message && !e.message.startsWith('Unexpected')) throw e;
            throw new Error(`${r.status} ${r.statusText}`);
        }
    }
    return r.json();
}

export const api = {
    health: () => get('/health'),
    services: () => get('/services'),
    requests: () => get('/requests'),
    env: () => get(appendProject('/env?format=json')),
    reset: () => post(appendProject('/reset')),
    browse: (service, sub) => get(appendProject(`/browse/${service}${sub ? '/' + sub : ''}`)),
    mutate: async (service, operation, data) => {
        const res = await postJson(`/mutate/${service}/${operation}`, data);
        if (res && res.error) {
            throw new Error(res.message || 'Mutation failed');
        }
        return res;
    },
    mutateSub: async (service, operation, subOp, data) => {
        const res = await postJson(`/mutate/${service}/${operation}/${subOp}`, data);
        if (res && res.error) {
            throw new Error(res.message || 'Mutation failed');
        }
        return res;
    },
    merge: async (service, data) => {
        const res = await postJson(`/mutate/${service}/merge`, data);
        if (res && res.error) {
            throw new Error(res.message || 'Merge failed');
        }
        return res;
    },
    resetService: (service, restoreSeed) => post(appendProject(`/reset/${service}`), { restore_seed: restoreSeed }),
    export: () => get('/export'),
    // Project management
    projects: () => get('/projects'),
    createProject: (projectId, displayName) => post('/projects', { project_id: projectId, display_name: displayName }),
    deleteProject: (projectId) => del(`/projects/${encodeURIComponent(projectId)}`),
    // Routing & credentials
    routing: () => get(appendProject('/routing')),
    credentials: () => get('/credentials'),
    setRouting: (serviceId, mode, remoteProject, remoteRegion) =>
        put(appendProject(`/routing/${encodeURIComponent(serviceId)}`), { mode, remote_project: remoteProject, remote_region: remoteRegion }),
    enableService: (serviceId) => post(`/services/${encodeURIComponent(serviceId)}/enable`),
    disableService: (serviceId) => post(`/services/${encodeURIComponent(serviceId)}/disable`),
    // SQL query execution
    query: (service, sql, params) => postJson(appendProject('/query'), { ...params, service, sql }),
    queryBatch: (service, statements, params) => postJson(appendProject('/query/batch'), { ...params, service, statements }),
    queryDryRun: (sql) => postJson(appendProject('/query/dryrun'), { sql }),
    // Schema info
    schema: (service, params) => {
        let url = appendProject(`/schema/${encodeURIComponent(service)}`);
        if (params) {
            const qs = Object.entries(params).map(([k,v]) => `${k}=${encodeURIComponent(v)}`).join('&');
            url += (url.includes('?') ? '&' : '?') + qs;
        }
        return get(url);
    },
    // BigQuery INFORMATION_SCHEMA browsing
    bigqueryInfoSchema: (viewType) => get(appendProject(`/browse/bigquery/information_schema${viewType ? '/' + encodeURIComponent(viewType) : ''}`)),
    // GCS file schema detection
    gcsFileSchema: (bucket, object) => get(appendProject(`/gcs/file-schema?bucket=${encodeURIComponent(bucket)}&object=${encodeURIComponent(object)}`)),
    // Usage metrics (persistent cumulative counts)
    usage: () => get(appendProject('/usage')),
    // Spanner system insights / per-database statistics
    spannerStats: (instance, database) =>
        get(appendProject(`/browse/spanner/instances/${encodeURIComponent(instance)}/${encodeURIComponent(database)}/stats`)),
    // Query execution history
    queryHistory: (service, limit, offset) => {
        let url = appendProject('/query-history');
        const params = [];
        if (service) params.push(`service=${encodeURIComponent(service)}`);
        if (limit !== undefined) params.push(`limit=${limit}`);
        if (offset !== undefined) params.push(`offset=${offset}`);
        if (params.length) url += (url.includes('?') ? '&' : '?') + params.join('&');
        return get(url);
    },
    // Workflow env vars
    workflowEnvVars: (preset) => get(appendProject(`/workflow-env${preset ? '?preset=' + encodeURIComponent(preset) : ''}`)),
    workflowEnvVarsAll: () => get(appendProject('/workflow-env?all=true')),
    createWorkflowEnvVar: (varName, varValue, preset) => postJson(appendProject('/workflow-env'), { varName, varValue, preset }),
    updateWorkflowEnvVar: (varName, varValue, preset) => put(appendProject(`/workflow-env/${encodeURIComponent(varName)}`), { varValue, preset }),
    deleteWorkflowEnvVar: (varName, preset) => {
        const url = appendProject(`/workflow-env/${encodeURIComponent(varName)}${preset ? (appendProject('').includes('?') ? '&' : '?') + 'preset=' + encodeURIComponent(preset) : ''}`);
        return fetch(`${BASE}${url}`, { method: 'DELETE' }).then(r => { if (!r.ok) throw new Error(`${r.status}`); return {}; });
    },
    workflowPresets: () => get(appendProject('/workflow-env/presets')),
    activatePreset: (preset) => postJson(appendProject('/workflow-env/presets/activate'), { preset }),
    // Workflow connector
    workflowConnectStatus: () => get(appendProject('/workflow/connect')),
    workflowConnect: (url, username) => postJson(appendProject('/workflow/connect'), { url, username }),
    workflowRemoteList: () => get(appendProject('/workflow/workflows')),
    workflowRemoteServices: () => get(appendProject('/workflow/services')),
    workflowImport: (name) => postJson(appendProject('/workflow/import'), { name }),
    // Data Mirror sync
    syncAuthStatus:       ()           => get(appendProject('/sync/auth/status')),
    syncAuthStart:        (body)       => postJson(appendProject('/sync/auth/start'), body),
    syncConnect:          (body)       => postJson(appendProject('/sync/auth/connect'), body),
    syncDisconnect:       ()           => post(appendProject('/sync/auth/disconnect')),
    syncBrowse:           (service)    => get(appendProject(`/sync/${service}/browse`)),
    syncPreview:          (service, resource, limit = 5) =>
        get(appendProject(`/sync/${service}/preview`) + `&resource=${encodeURIComponent(resource)}&limit=${limit}`),
    syncEstimate:         (service, body) => postJson(appendProject(`/sync/${service}/estimate`), body),
    syncStart:            (service, body) => postJson(appendProject(`/sync/${service}/start`), body),
    syncManifests:        ()           => get(appendProject('/sync/manifests')),
    syncServiceManifests: (service)    => get(appendProject(`/sync/${service}/manifests`)),
    syncProgress:         (service, resource) =>
        get(appendProject(`/sync/${service}/progress`) + `&resource=${encodeURIComponent(resource)}`),
    syncCancel:           (service, body) => postJson(appendProject(`/sync/${service}/cancel`), body),
    syncResync:           (id)         => postJson(appendProject(`/sync/resync/${id}`), {}),
    syncDeleteManifest:   (id)         => del(appendProject(`/sync/manifests/${id}`)),
};

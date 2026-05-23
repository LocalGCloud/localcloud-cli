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
    health: () => get('/_localcloud/health'),
    services: () => get('/_localcloud/services'),
    requests: () => get('/_localcloud/requests'),
    env: () => get(appendProject('/_localcloud/env?format=json')),
    reset: () => post(appendProject('/_localcloud/reset')),
    browse: (service, sub) => get(appendProject(`/_localcloud/browse/${service}${sub ? '/' + sub : ''}`)),
    mutate: async (service, operation, data) => {
        const res = await postJson(`/_localcloud/mutate/${service}/${operation}`, data);
        if (res && res.error) {
            throw new Error(res.message || 'Mutation failed');
        }
        return res;
    },
    mutateSub: async (service, operation, subOp, data) => {
        const res = await postJson(`/_localcloud/mutate/${service}/${operation}/${subOp}`, data);
        if (res && res.error) {
            throw new Error(res.message || 'Mutation failed');
        }
        return res;
    },
    merge: async (service, data) => {
        const res = await postJson(`/_localcloud/mutate/${service}/merge`, data);
        if (res && res.error) {
            throw new Error(res.message || 'Merge failed');
        }
        return res;
    },
    resetService: (service, restoreSeed) => post(appendProject(`/_localcloud/reset/${service}`), { restore_seed: restoreSeed }),
    export: () => get('/_localcloud/export'),
    // Project management
    projects: () => get('/_localcloud/projects'),
    createProject: (projectId, displayName) => post('/_localcloud/projects', { project_id: projectId, display_name: displayName }),
    deleteProject: (projectId) => del(`/_localcloud/projects/${encodeURIComponent(projectId)}`),
    // Routing & credentials
    routing: () => get(appendProject('/_localcloud/routing')),
    credentials: () => get('/_localcloud/credentials'),
    setRouting: (serviceId, mode, remoteProject, remoteRegion) =>
        put(appendProject(`/_localcloud/routing/${encodeURIComponent(serviceId)}`), { mode, remote_project: remoteProject, remote_region: remoteRegion }),
    enableService: (serviceId) => post(`/_localcloud/services/${encodeURIComponent(serviceId)}/enable`),
    disableService: (serviceId) => post(`/_localcloud/services/${encodeURIComponent(serviceId)}/disable`),
    // SQL query execution
    query: (service, sql, params) => postJson(appendProject('/_localcloud/query'), { ...params, service, sql }),
    queryBatch: (service, statements, params) => postJson(appendProject('/_localcloud/query/batch'), { ...params, service, statements }),
    queryDryRun: (sql) => postJson(appendProject('/_localcloud/query/dryrun'), { sql }),
    // Schema info
    schema: (service, params) => {
        let url = appendProject(`/_localcloud/schema/${encodeURIComponent(service)}`);
        if (params) {
            const qs = Object.entries(params).map(([k,v]) => `${k}=${encodeURIComponent(v)}`).join('&');
            url += (url.includes('?') ? '&' : '?') + qs;
        }
        return get(url);
    },
    // BigQuery INFORMATION_SCHEMA browsing
    bigqueryInfoSchema: (viewType) => get(appendProject(`/_localcloud/browse/bigquery/information_schema${viewType ? '/' + encodeURIComponent(viewType) : ''}`)),
    // GCS file schema detection
    gcsFileSchema: (bucket, object) => get(appendProject(`/_localcloud/gcs/file-schema?bucket=${encodeURIComponent(bucket)}&object=${encodeURIComponent(object)}`)),
    // Usage metrics (persistent cumulative counts)
    usage: () => get(appendProject('/_localcloud/usage')),
    // Spanner system insights / per-database statistics
    spannerStats: (instance, database) =>
        get(appendProject(`/_localcloud/browse/spanner/instances/${encodeURIComponent(instance)}/${encodeURIComponent(database)}/stats`)),
    // Query execution history
    queryHistory: (service, limit, offset) => {
        let url = appendProject('/_localcloud/query-history');
        const params = [];
        if (service) params.push(`service=${encodeURIComponent(service)}`);
        if (limit !== undefined) params.push(`limit=${limit}`);
        if (offset !== undefined) params.push(`offset=${offset}`);
        if (params.length) url += (url.includes('?') ? '&' : '?') + params.join('&');
        return get(url);
    },
    // Workflow env vars
    workflowEnvVars: (preset) => get(appendProject(`/_localcloud/workflow-env${preset ? '?preset=' + encodeURIComponent(preset) : ''}`)),
    workflowEnvVarsAll: () => get(appendProject('/_localcloud/workflow-env?all=true')),
    createWorkflowEnvVar: (varName, varValue, preset) => postJson(appendProject('/_localcloud/workflow-env'), { varName, varValue, preset }),
    updateWorkflowEnvVar: (varName, varValue, preset) => put(appendProject(`/_localcloud/workflow-env/${encodeURIComponent(varName)}`), { varValue, preset }),
    deleteWorkflowEnvVar: (varName, preset) => {
        const url = appendProject(`/_localcloud/workflow-env/${encodeURIComponent(varName)}${preset ? (appendProject('').includes('?') ? '&' : '?') + 'preset=' + encodeURIComponent(preset) : ''}`);
        return fetch(`${BASE}${url}`, { method: 'DELETE' }).then(r => { if (!r.ok) throw new Error(`${r.status}`); return {}; });
    },
    workflowPresets: () => get(appendProject('/_localcloud/workflow-env/presets')),
    activatePreset: (preset) => postJson(appendProject('/_localcloud/workflow-env/presets/activate'), { preset }),
    // Workflow connector
    workflowConnectStatus: () => get(appendProject('/_localcloud/workflow/connect')),
    workflowConnect: (url, username) => postJson(appendProject('/_localcloud/workflow/connect'), { url, username }),
    workflowRemoteList: () => get(appendProject('/_localcloud/workflow/workflows')),
    workflowRemoteServices: () => get(appendProject('/_localcloud/workflow/services')),
    workflowImport: (name) => postJson(appendProject('/_localcloud/workflow/import'), { name }),
    // Data Mirror sync
    syncAuthStatus:       ()           => get(appendProject('/_localcloud/sync/auth/status')),
    syncAuthStart:        (body)       => postJson(appendProject('/_localcloud/sync/auth/start'), body),
    syncConnect:          (body)       => postJson(appendProject('/_localcloud/sync/auth/connect'), body),
    syncDisconnect:       ()           => post(appendProject('/_localcloud/sync/auth/disconnect')),
    syncBrowse:           (service)    => get(appendProject(`/_localcloud/sync/${service}/browse`)),
    syncPreview:          (service, resource, limit = 5) =>
        get(appendProject(`/_localcloud/sync/${service}/preview`) + `&resource=${encodeURIComponent(resource)}&limit=${limit}`),
    syncEstimate:         (service, body) => postJson(appendProject(`/_localcloud/sync/${service}/estimate`), body),
    syncStart:            (service, body) => postJson(appendProject(`/_localcloud/sync/${service}/start`), body),
    syncManifests:        ()           => get(appendProject('/_localcloud/sync/manifests')),
    syncServiceManifests: (service)    => get(appendProject(`/_localcloud/sync/${service}/manifests`)),
    syncProgress:         (service, resource) =>
        get(appendProject(`/_localcloud/sync/${service}/progress`) + `&resource=${encodeURIComponent(resource)}`),
    syncCancel:           (service, body) => postJson(appendProject(`/_localcloud/sync/${service}/cancel`), body),
    syncResync:           (id)         => postJson(appendProject(`/_localcloud/sync/resync/${id}`), {}),
    syncDeleteManifest:   (id)         => del(appendProject(`/_localcloud/sync/manifests/${id}`)),
};

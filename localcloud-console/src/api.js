/**
 * LocalCloud Console API client.
 * Calls the LocalCloud Admin API directly on the gateway.
 * Project-aware: passes ?project= on browse/env/reset calls.
 */

const BASE = '';

let _activeProject = null;

export function setActiveProject(projectId) {
    _activeProject = projectId;
}

export function getActiveProject() {
    return _activeProject;
}

function projectParam() {
    return _activeProject ? `?project=${encodeURIComponent(_activeProject)}` : '';
}

function appendProject(path) {
    if (!_activeProject) return path;
    const sep = path.includes('?') ? '&' : '?';
    return `${path}${sep}project=${encodeURIComponent(_activeProject)}`;
}

async function get(path) {
    const r = await fetch(`${BASE}${path}`);
    if (!r.ok) throw new Error(`${r.status} ${r.statusText}`);
    return r.json();
}

async function post(path, body) {
    const opts = { method: 'POST' };
    if (body) {
        opts.headers = { 'Content-Type': 'application/json' };
        opts.body = JSON.stringify(body);
    }
    const r = await fetch(`${BASE}${path}`, opts);
    if (!r.ok) throw new Error(`${r.status} ${r.statusText}`);
    return r.json();
}

async function put(path, body) {
    const r = await fetch(`${BASE}${path}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    if (!r.ok) throw new Error(`${r.status} ${r.statusText}`);
    return r.json();
}

async function del(path) {
    const r = await fetch(`${BASE}${path}`, { method: 'DELETE' });
    if (!r.ok) throw new Error(`${r.status} ${r.statusText}`);
    return r.json();
}

async function postJson(path, body) {
    const r = await fetch(`${BASE}${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    if (!r.ok) throw new Error(`${r.status} ${r.statusText}`);
    return r.json();
}

export const api = {
    health: () => get('/_localcloud/health'),
    services: () => get('/_localcloud/services'),
    requests: () => get('/_localcloud/requests'),
    env: () => get(appendProject('/_localcloud/env?format=json')),
    reset: () => post(appendProject('/_localcloud/reset')),
    browse: (service, sub) => get(appendProject(`/_localcloud/browse/${service}${sub ? '/' + sub : ''}`)),
    mutate: (service, operation, data) => postJson(`/_localcloud/mutate/${service}/${operation}`, data),
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
        put(`/_localcloud/routing/${encodeURIComponent(serviceId)}`, { mode, remote_project: remoteProject, remote_region: remoteRegion }),
    enableService: (serviceId) => post(`/_localcloud/services/${encodeURIComponent(serviceId)}/enable`),
    disableService: (serviceId) => post(`/_localcloud/services/${encodeURIComponent(serviceId)}/disable`),
    // SQL query execution
    query: (service, sql, params) => postJson(appendProject('/_localcloud/query'), { service, sql, ...params }),
    // Schema info
    schema: (service) => get(appendProject(`/_localcloud/schema/${encodeURIComponent(service)}`)),
    // GCS file schema detection
    gcsFileSchema: (bucket, object) => get(appendProject(`/_localcloud/gcs/file-schema?bucket=${encodeURIComponent(bucket)}&object=${encodeURIComponent(object)}`)),
    // Usage metrics (persistent cumulative counts)
    usage: () => get(appendProject('/_localcloud/usage')),
};

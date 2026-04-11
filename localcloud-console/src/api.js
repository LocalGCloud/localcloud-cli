/**
 * LocalCloud Console API client.
 * All calls proxy through Flask backend to LocalCloud Admin API.
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
    health: () => get('/api/health'),
    services: () => get('/api/services'),
    requests: () => get('/api/requests'),
    env: () => get(appendProject('/api/env')),
    reset: () => post(appendProject('/api/reset')),
    browse: (service, sub) => get(appendProject(`/api/browse/${service}${sub ? '/' + sub : ''}`)),
    mutate: (service, operation, data) => postJson(`/api/mutate/${service}/${operation}`, data),
    resetService: (service, restoreSeed) => post(appendProject(`/api/reset/${service}`), { restore_seed: restoreSeed }),
    export: () => get('/api/export'),
    // Project management
    projects: () => get('/api/projects'),
    createProject: (projectId, displayName) => post('/api/projects', { project_id: projectId, display_name: displayName }),
    deleteProject: (projectId) => del(`/api/projects/${encodeURIComponent(projectId)}`),
};

import { createSignal, onCleanup } from 'solid-js';

function canUseUrl() {
    return typeof window !== 'undefined' && typeof window.location !== 'undefined' && typeof window.history !== 'undefined';
}

function normalizeAllowed(allowedValues) {
    return Array.isArray(allowedValues) ? allowedValues.map(String) : [];
}

export function normalizeUrlTab(value, allowedValues, fallback) {
    const fallbackValue = String(fallback);
    const next = value == null || value === '' ? fallbackValue : String(value);
    const allowed = normalizeAllowed(allowedValues);
    return allowed.length === 0 || allowed.includes(next) ? next : fallbackValue;
}

export function readUrlTab(paramName, allowedValues, fallback) {
    if (!canUseUrl()) return String(fallback);
    const params = new URLSearchParams(window.location.search);
    return normalizeUrlTab(params.get(paramName), allowedValues, fallback);
}

export function commitUrl(target, { history = 'replace', compareHash = true } = {}) {
    if (!canUseUrl()) return false;
    const nextUrl = new URL(target, window.location.href);
    const nextComparable = nextUrl.pathname + nextUrl.search + (compareHash ? nextUrl.hash : '');
    const currentComparable = window.location.pathname + window.location.search + (compareHash ? window.location.hash : '');
    if (nextComparable === currentComparable) return false;
    const nextRelative = nextUrl.pathname + nextUrl.search + nextUrl.hash;
    const method = history === 'push' ? 'pushState' : 'replaceState';
    window.history[method](null, '', nextRelative);
    return true;
}

export function writeUrlTab(paramName, value, fallback, options = {}) {
    if (!canUseUrl()) return;
    const next = normalizeUrlTab(value, options.allowedValues || [], fallback);
    const url = new URL(window.location.href);
    if (next === String(fallback) && options.omitDefault !== false) {
        url.searchParams.delete(paramName);
    } else {
        url.searchParams.set(paramName, next);
    }
    const target = url.pathname + url.search + url.hash;
    commitUrl(target, {
        history: options.history === 'replace' ? 'replace' : 'push',
    });
}

export function createUrlBackedTab(paramName, allowedValues, fallback, options = {}) {
    const [tab, setTabSignal] = createSignal(readUrlTab(paramName, allowedValues, fallback));

    const syncFromUrl = () => {
        const next = readUrlTab(paramName, allowedValues, fallback);
        if (next !== tab()) setTabSignal(next);
    };

    if (canUseUrl()) {
        window.addEventListener('popstate', syncFromUrl);
        onCleanup(() => window.removeEventListener('popstate', syncFromUrl));
    }

    const setTab = (next, writeOptions = {}) => {
        const normalized = normalizeUrlTab(next, allowedValues, fallback);
        setTabSignal(normalized);
        writeUrlTab(paramName, normalized, fallback, {
            allowedValues,
            ...options,
            ...writeOptions,
        });
    };

    return [tab, setTab];
}

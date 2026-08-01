import { createSignal } from 'solid-js';

const LOGS_INTERVAL_KEY = 'localcloud-logs-interval';
const USAGE_INTERVAL_KEY = 'localcloud-usage-interval';

function readInterval(key, fallback, max) {
    try {
        const value = Number.parseInt(localStorage.getItem(key) || String(fallback), 10);
        return Number.isInteger(value) && value >= 1 && value <= max ? value : fallback;
    } catch {
        return fallback;
    }
}

const [refreshIntervals, setRefreshIntervals] = createSignal((() => ({
    logs: readInterval(LOGS_INTERVAL_KEY, 3, 60),
    usage: readInterval(USAGE_INTERVAL_KEY, 30, 120),
}))());

export const logsRefreshInterval = () => refreshIntervals().logs;
export const usageRefreshInterval = () => refreshIntervals().usage;

function updateInterval(name, key, seconds, max) {
    if (!Number.isInteger(seconds) || seconds < 1 || seconds > max) return false;
    setRefreshIntervals(current => ({ ...current, [name]: seconds }));
    try { localStorage.setItem(key, String(seconds)); } catch {}
    return true;
}

export const setLogsRefreshInterval = seconds => updateInterval('logs', LOGS_INTERVAL_KEY, seconds, 60);
export const setUsageRefreshInterval = seconds => updateInterval('usage', USAGE_INTERVAL_KEY, seconds, 120);

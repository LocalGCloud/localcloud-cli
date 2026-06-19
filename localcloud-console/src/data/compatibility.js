import { FALLBACK_WARNINGS, REGISTRY_VERSION } from './compatibilityFallback.js';

let warningRows = FALLBACK_WARNINGS;

export function setCompatibilityWarnings(rows) {
  if (Array.isArray(rows) && rows.length > 0) {
    const merged = new Map();
    for (const row of warningRows) {
      const key = `${row.service_id}:${row.surface}:${row.keyword || row.operation || row.id || row.message}`;
      merged.set(key, row);
    }
    for (const row of rows) {
      const key = `${row.service_id}:${row.surface}:${row.keyword || row.operation || row.id || row.message}`;
      merged.set(key, row);
    }
    warningRows = Array.from(merged.values());
    try {
      localStorage.setItem('localcloud.compatibilityWarnings', JSON.stringify({
        registry_version: REGISTRY_VERSION,
        warnings: warningRows,
      }));
    } catch {}
  }
}

export function loadCachedCompatibilityWarnings() {
  try {
    const raw = localStorage.getItem('localcloud.compatibilityWarnings');
    if (!raw) return;
    const parsed = JSON.parse(raw);
    if (parsed?.registry_version === REGISTRY_VERSION && Array.isArray(parsed.warnings)) {
      warningRows = parsed.warnings;
    }
  } catch {}
}

/**
 * Get all unsupported keywords for a dialect as a flat list.
 */
export function getUnsupportedKeywords(dialect) {
  const serviceId = dialect === 'googlesql' || dialect === 'spanner' ? 'spanner' : dialect;
  return warningRows
    .filter(row => row.service_id === serviceId && row.surface === 'sql')
    .map(row => row.keyword);
}

/**
 * Get the warning message for a specific keyword in a dialect.
 */
export function getWarningMessage(dialect, keyword) {
  const serviceId = dialect === 'googlesql' || dialect === 'spanner' ? 'spanner' : dialect;
  const upper = keyword.toUpperCase();
  const row = warningRows.find(item =>
    item.service_id === serviceId &&
    item.surface === 'sql' &&
    item.keyword?.toUpperCase() === upper);
  if (!row) return null;
  return [row.message, row.workaround].filter(Boolean).join(' ');
}

export function getActionWarning(serviceId, surface, actionText) {
  if (!serviceId) return null;
  const text = (actionText || '').toLowerCase();
  const row = warningRows.find(item => {
    if (item.service_id !== serviceId || item.surface !== surface) return false;
    const needle = String(item.operation || item.keyword || item.id || '').toLowerCase();
    return !needle || text.includes(needle);
  });
  if (!row) return null;
  return [row.message, row.workaround].filter(Boolean).join(' ');
}

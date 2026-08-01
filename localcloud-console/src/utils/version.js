/**
 * Version metadata helpers shared by every console surface.
 */

/**
 * Extract the short "vX.Y.Z" label from a /health payload.
 * Uses the raw build version when available and falls back to version_display.
 * @param {object|null|undefined} health
 * @returns {string} e.g. "v1.2.3", or an em dash before metadata is available.
 */
export function shortVersion(health) {
    const raw = String(health?.version || health?.version_display || '').trim();
    const base = raw.split('+', 1)[0].split(/[\s(]/, 1)[0].trim();
    if (!base) return '—';
    return base.startsWith('v') ? base : `v${base}`;
}

/**
 * Parse the build date out of a version stamp of the form "X.Y.Z+build.YYYY.MM.DD...".
 * @param {object|null|undefined} health
 * @returns {number|null} epoch ms, or null if unparseable.
 */
export function parseBuildDate(health) {
    const raw = String(health?.version || '').trim();
    const buildPart = raw.includes('+') ? raw.split('+').slice(1).join('+') : '';
    const dateText = buildPart.includes('.') ? buildPart.split('.').slice(1).join('.') : '';
    const parsed = dateText ? Date.parse(dateText) : NaN;
    return Number.isFinite(parsed) ? parsed : null;
}
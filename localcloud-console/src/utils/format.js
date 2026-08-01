export function formatSize(bytes) {
    if (bytes == null) return '--';
    const n = Number(bytes);
    if (n === 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(n) / Math.log(1024));
    return `${(n / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0)} ${units[i]}`;
}

export function formatNumber(value) {
  if (value === null || value === undefined || value === '') {
    return '0';
  }
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) {
    return String(value);
  }
  return new Intl.NumberFormat(undefined).format(numeric);
}

function toDate(value) {
  if (!value) {
    return null;
  }
  if (value instanceof Date) {
    return Number.isNaN(value.getTime()) ? null : value;
  }
  const normalized = typeof value === 'string' ? value.replace(' ', 'T') : value;
  const date = new Date(normalized);
  return Number.isNaN(date.getTime()) ? null : date;
}

export function formatDateTime(value, options = { dateStyle: 'medium', timeStyle: 'short' }) {
  const date = toDate(value);
  if (!date) {
    return 'Unknown';
  }
  return new Intl.DateTimeFormat(undefined, options).format(date);
}

export function formatTime(value, options = {
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
}) {
  const date = toDate(value);
  if (!date) {
    return 'Unknown';
  }
  return new Intl.DateTimeFormat(undefined, options).format(date);
}

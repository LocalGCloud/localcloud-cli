export function onActivate(handler) {
  return (event) => {
    if (event.key !== 'Enter' && event.key !== ' ') {
      return;
    }
    if (event.target !== event.currentTarget && event.target.closest?.('button,a,input,select,textarea,label,[role="button"]')) {
      return;
    }
    event.preventDefault();
    handler(event);
  };
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
    return '';
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
    return '';
  }
  return new Intl.DateTimeFormat(undefined, options).format(date);
}

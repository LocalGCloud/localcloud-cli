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

const FOCUSABLE_SELECTOR = 'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Traps focus within a container element. Call on mount.
 * Returns a cleanup function.
 */
export function trapFocus(containerEl, onEscape) {
    const first = containerEl.querySelector(FOCUSABLE_SELECTOR);
    if (first) first.focus();

    const handler = (e) => {
        if (e.key === 'Escape' && onEscape) {
            onEscape();
            return;
        }
        if (e.key !== 'Tab') return;

        const focusable = containerEl.querySelectorAll(FOCUSABLE_SELECTOR);
        if (focusable.length === 0) return;

        const first = focusable[0];
        const last = focusable[focusable.length - 1];

        if (e.shiftKey) {
            if (document.activeElement === first) {
                e.preventDefault();
                last.focus();
            }
        } else {
            if (document.activeElement === last) {
                e.preventDefault();
                first.focus();
            }
        }
    };

    containerEl.addEventListener('keydown', handler);
    return () => containerEl.removeEventListener('keydown', handler);
}

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

const FOCUSABLE_SELECTOR = 'a[href], button:not([disabled]), textarea:not([disabled]), '
    + 'input:not([disabled]), select:not([disabled]), [contenteditable]:not([contenteditable="false"]), '
    + 'summary:not([tabindex="-1"]), audio[controls], video[controls], '
    + '[tabindex]:not([tabindex="-1"])';

/**
 * Traps focus within a container element (WCAG 2.4.3 compliant). Call on open.
 *
 * - Captures the previously-focused element and restores focus to it on cleanup.
 * - If the container has no focusable children, focuses the container itself (made
 *   programmatically focusable via tabindex=-1) and prevents Tab from escaping.
 * - Returns a cleanup function that removes the keydown handler and restores focus;
 *   callers SHOULD capture and invoke it on close (via `onCleanup` for components).
 *
 * @param {HTMLElement} containerEl  the dialog/menu root
 * @param {function} [onEscape]     invoked on Escape
 * @returns {function} cleanup — call to tear down the trap and restore focus
 */
export function trapFocus(containerEl, onEscape) {
    const previouslyFocused = document.activeElement;
    const previousTabIndex = containerEl.getAttribute('tabindex');
    let active = true;

    // Make the container itself focusable so we always have a Tab anchor.
    containerEl.tabIndex = -1;

    const firstFocusable = containerEl.querySelector(FOCUSABLE_SELECTOR);
    if (firstFocusable) {
        firstFocusable.focus();
    } else {
        containerEl.focus();
    }

    const handler = (e) => {
        if (e.key === 'Escape' && onEscape) {
            onEscape();
            return;
        }
        if (e.key !== 'Tab') return;

        const focusable = Array.from(containerEl.querySelectorAll(FOCUSABLE_SELECTOR));
        if (focusable.length === 0) {
            // No focusable children — keep focus pinned to the container.
            e.preventDefault();
            containerEl.focus();
            return;
        }

        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        const active = document.activeElement;

        if (e.shiftKey) {
            if (active === first || active === containerEl) {
                e.preventDefault();
                last.focus();
            }
        } else {
            if (active === last || active === containerEl) {
                e.preventDefault();
                first.focus();
            }
        }
    };

    containerEl.addEventListener('keydown', handler);

    return () => {
        if (!active) return;
        active = false;
        containerEl.removeEventListener('keydown', handler);
        if (previousTabIndex === null) {
            containerEl.removeAttribute('tabindex');
        } else {
            containerEl.setAttribute('tabindex', previousTabIndex);
        }
        if (previouslyFocused?.isConnected && typeof previouslyFocused.focus === 'function') {
            previouslyFocused.focus();
        }
    };
}

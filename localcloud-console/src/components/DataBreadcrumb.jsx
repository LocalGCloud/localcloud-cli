import { Show, For } from 'solid-js';

/**
 * Shared breadcrumb for multi-level data services.
 * Props: crumbs: [{label, onClick, active}]
 */
export default function DataBreadcrumb(props) {
    return (
        <nav class="data-breadcrumb" aria-label="Data path">
            <For each={props.crumbs}>
                {(crumb, i) => (
                    <>
                        <Show when={i() > 0}>
                            <span class="data-breadcrumb-sep" aria-hidden="true">{'\u203A'}</span>
                        </Show>
                        <Show when={crumb.onClick && !crumb.active} fallback={
                            <span class="data-breadcrumb-active">{crumb.label}</span>
                        }>
                            <button class="data-breadcrumb-link" onClick={crumb.onClick}>
                                {crumb.label}
                            </button>
                        </Show>
                    </>
                )}
            </For>
        </nav>
    );
}

import { Show, For } from 'solid-js';

/**
 * Shared breadcrumb for multi-level data services.
 * Props: crumbs: [{label, tag, type, onClick, active}]
 */
export default function DataBreadcrumb(props) {
    const tagStyle = (type) => {
        const colors = {
            service: ['#5f6368', 'rgba(95,99,104,0.12)'],
            cluster: ['#a142f4', 'rgba(161,66,244,0.13)'],
            instance: ['#1a73e8', 'rgba(26,115,232,0.12)'],
            dataset: ['#0b8043', 'rgba(11,128,67,0.12)'],
            database: ['#0b8043', 'rgba(11,128,67,0.12)'],
            table: ['#c5221f', 'rgba(197,34,31,0.12)'],
            collection: ['#b06000', 'rgba(176,96,0,0.13)'],
            keyspace: ['#5f6368', 'rgba(95,99,104,0.12)'],
            bucket: ['#f9ab00', 'rgba(249,171,0,0.13)'],
            folder: ['#5f6368', 'rgba(95,99,104,0.12)'],
        };
        const [color, background] = colors[type] || colors.service;
        return {
            color,
            background,
            border: `1px solid ${color}33`,
            "border-radius": "999px",
            "font-size": "10px",
            "font-weight": "700",
            "line-height": "1",
            padding: "3px 6px",
            "text-transform": "uppercase",
        };
    };

    const Content = (props) => (
        <span style={{ display: "inline-flex", "align-items": "center", gap: "6px" }}>
            <Show when={props.crumb.tag || props.crumb.type}>
                <span style={tagStyle(props.crumb.type)}>{props.crumb.tag || props.crumb.type}</span>
            </Show>
            <span>{props.crumb.label}</span>
        </span>
    );

    return (
        <nav class="data-breadcrumb" aria-label="Data path">
            <For each={props.crumbs}>
                {(crumb, i) => (
                    <>
                        <Show when={i() > 0}>
                            <span class="data-breadcrumb-sep" aria-hidden="true">{'\u203A'}</span>
                        </Show>
                        <Show when={crumb.onClick && !crumb.active} fallback={
                            <span class="data-breadcrumb-active"><Content crumb={crumb} /></span>
                        }>
                            <button class="data-breadcrumb-link" onClick={crumb.onClick}>
                                <Content crumb={crumb} />
                            </button>
                        </Show>
                    </>
                )}
            </For>
        </nav>
    );
}

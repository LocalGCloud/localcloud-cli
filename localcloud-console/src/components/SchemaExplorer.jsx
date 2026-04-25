import { createSignal, createEffect, For, Show } from 'solid-js';
import { api } from '../api.js';

export function SchemaExplorer(props) {
    // props: source ("local"|"remote"), serviceId, onSelect, syncManifests
    const [nodes, setNodes] = createSignal([]);
    const [expanded, setExpanded] = createSignal({});
    const [selected, setSelected] = createSignal(null);
    const [loading, setLoading] = createSignal(false);
    const [error, setError] = createSignal(null);

    createEffect(async () => {
        const svc = props.serviceId;
        if (!svc) return;
        setLoading(true);
        setError(null);
        try {
            if (props.source === 'remote') {
                const data = await api.syncBrowse(svc);
                setNodes(data.nodes || []);
            } else {
                // Local schema
                const data = await api.schema(svc);
                setNodes(normalizeLocalSchema(data, svc));
            }
        } catch (e) {
            setError(e.message || 'Failed to load');
        } finally {
            setLoading(false);
        }
    });

    const toggle = (key) => setExpanded(prev => ({ ...prev, [key]: !prev[key] }));
    const select = (node) => { setSelected(node.id); props.onSelect?.(node); };

    const getSyncBadge = (id) => {
        const manifests = typeof props.syncManifests === 'function' ? props.syncManifests() : props.syncManifests;
        if (!manifests) return null;
        const m = manifests.find(x => x.resource_path === id);
        if (!m) return null;
        const stale = Date.now() - new Date(m.synced_at).getTime() > 86400000;
        return { badge: stale ? '\u26A0' : '\u2713', class: stale ? 'stale' : 'synced' };
    };

    return (
        <div class="schema-explorer">
            <div class="schema-explorer-header">
                <span class={`schema-source-badge ${props.source}`}>
                    {props.source === 'remote' ? 'REMOTE' : 'LOCAL'}
                </span>
            </div>
            <Show when={loading()}>
                <div class="loading-state" style="padding: 24px; text-align: center">
                    <div class="loading-spinner" />
                </div>
            </Show>
            <Show when={error()}>
                <div class="alert alert-error" style="margin: 8px">{error()}</div>
            </Show>
            <Show when={!loading() && !error()}>
                <div class="sql-explorer-tree" style="flex: 1; overflow-y: auto; padding: 4px 0">
                    <For each={nodes()}>
                        {(node) => (
                            <div>
                                <div class={`tree-row tree-row-db`} onClick={() => toggle(node.id)}
                                     style="cursor: pointer">
                                    <span class="tree-chevron" style={{ transform: expanded()[node.id] ? 'rotate(90deg)' : 'none' }}>&rsaquo;</span>
                                    <span class="tree-name">{node.name}</span>
                                    <span class="tree-badge" style="margin-left: auto; font-size: 11px; color: var(--text-secondary)">
                                        {node.children?.length || 0} tbl
                                    </span>
                                </div>
                                <Show when={expanded()[node.id]}>
                                    <For each={node.children || []}>
                                        {(child) => {
                                            const syncInfo = () => getSyncBadge(child.id);
                                            return (
                                                <div>
                                                    <div class={`tree-row tree-row-tbl ${selected() === child.id ? 'active' : ''}`}
                                                         onClick={() => select(child)} style="cursor: pointer; padding-left: 28px">
                                                        <span class="tree-name">{child.name}</span>
                                                        <span class="tree-badge" style="margin-left: auto; font-size: 11px; color: var(--text-secondary)">
                                                            {child.metadata?.rowCount ? formatNum(child.metadata.rowCount) + ' rows' : ''}
                                                        </span>
                                                        <Show when={syncInfo()}>
                                                            <span style={`margin-left: 4px; font-size: 11px; color: ${syncInfo().class === 'synced' ? 'var(--success, #34a853)' : 'var(--warning, #fbbc04)'}`}>
                                                                {syncInfo().badge}
                                                            </span>
                                                        </Show>
                                                    </div>
                                                    <Show when={selected() === child.id && child.schema}>
                                                        <For each={child.schema || []}>
                                                            {(col) => (
                                                                <div class="tree-row tree-row-col" style="padding-left: 44px">
                                                                    <span class="tree-col-name">{col.name}</span>
                                                                    <span class="tree-col-type" style="margin-left: auto">{col.type}</span>
                                                                </div>
                                                            )}
                                                        </For>
                                                    </Show>
                                                </div>
                                            );
                                        }}
                                    </For>
                                </Show>
                            </div>
                        )}
                    </For>
                </div>
            </Show>
        </div>
    );
}

/**
 * Normalize the local schema API response into the tree node format
 * used by SchemaExplorer. api.schema() returns:
 *   { tables: [{ name: "dataset.table" | "table", columns: [{ name, type }] }] }
 * We group by database/dataset (splitting on '.') and produce:
 *   [{ id, name, type: 'database', children: [{ id, name, type: 'table', schema, metadata }] }]
 */
function normalizeLocalSchema(data, serviceId) {
    const tables = data?.tables || [];
    const groups = {};
    for (const t of tables) {
        let dbName, tableName;
        if (t.name && t.name.includes('.')) {
            const parts = t.name.split('.');
            dbName = parts[0];
            tableName = parts.slice(1).join('.');
        } else {
            dbName = 'public';
            tableName = t.name;
        }
        if (!groups[dbName]) groups[dbName] = [];
        groups[dbName].push({
            id: `${serviceId}/${t.name}`,
            name: tableName,
            type: 'table',
            schema: (t.columns || []).map(c => ({ name: c.name || c, type: c.type || 'unknown' })),
            metadata: { rowCount: t.row_count || null }
        });
    }
    return Object.entries(groups).map(([dbName, children]) => ({
        id: `${serviceId}/${dbName}`,
        name: dbName,
        type: 'database',
        children
    }));
}

function formatNum(n) {
    if (n >= 1e9) return (n / 1e9).toFixed(1) + 'B';
    if (n >= 1e6) return (n / 1e6).toFixed(1) + 'M';
    if (n >= 1e3) return (n / 1e3).toFixed(1) + 'K';
    return String(n);
}

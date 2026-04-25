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
                const rawNodes = data.nodes || data || [];
                setNodes(normalizeRemoteBrowse(rawNodes, svc));
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

    const typeLabel = (type) => {
        switch(type) {
            case 'dataset': return 'dataset';
            case 'collection': return 'collection';
            case 'bucket': return 'bucket';
            case 'instance': return 'instance';
            default: return '';
        }
    };

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
                <div class="sql-explorer-tree" role="tree" aria-label="Resource explorer" style="flex: 1; overflow-y: auto; padding: 4px 0">
                    <For each={nodes()}>
                        {(node) => (
                            <div>
                                <button class={`tree-row tree-row-db`} onClick={() => toggle(node.id)}
                                     aria-expanded={!!expanded()[node.id]}
                                     role="treeitem"
                                     style="cursor: pointer; width: 100%; text-align: left; background: none; border: none; color: inherit; font: inherit; padding: inherit">
                                    <span class="tree-chevron" style={{ transform: expanded()[node.id] ? 'rotate(90deg)' : 'none' }}>&rsaquo;</span>
                                    <span class="tree-name" style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 160px" title={node.name}>{node.name}</span>
                                    <span class="tree-badge" style="margin-left: auto; font-size: 11px; color: var(--text-secondary)">
                                        {node.children?.length || 0} {typeLabel(node.type) || 'items'}
                                    </span>
                                </button>
                                <Show when={expanded()[node.id]}>
                                    <div role="group">
                                        <For each={node.children || []}>
                                            {(child) => {
                                                const syncInfo = () => getSyncBadge(child.id);
                                                return (
                                                    <div>
                                                        <button class={`tree-row tree-row-tbl ${selected() === child.id ? 'active' : ''}`}
                                                             onClick={() => select(child)}
                                                             role="treeitem"
                                                             aria-selected={selected() === child.id}
                                                             style="cursor: pointer; width: 100%; text-align: left; background: none; border: none; color: inherit; font: inherit; padding-left: 28px">
                                                            <span class="tree-name" style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 160px" title={child.name}>{child.name}</span>
                                                            <span class="tree-badge" style="margin-left: auto; font-size: 11px; color: var(--text-secondary)">
                                                                {child.metadata?.rowCount ? formatNum(child.metadata.rowCount) + ' rows' : ''}
                                                            </span>
                                                            <Show when={syncInfo()}>
                                                                <span style={`margin-left: 4px; font-size: 11px; color: ${syncInfo().class === 'synced' ? 'var(--success, #34a853)' : 'var(--warning, #fbbc04)'}`}>
                                                                    {syncInfo().badge}
                                                                </span>
                                                            </Show>
                                                        </button>
                                                        <Show when={selected() === child.id && child.schema}>
                                                            <For each={child.schema || []}>
                                                                {(col) => (
                                                                    <div class="tree-row tree-row-col" role="none" style="padding-left: 44px">
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
                                    </div>
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

/**
 * Normalize the remote browse API response into the tree node format
 * used by SchemaExplorer. Each adapter returns a different shape inside
 * BrowseResult.nodes — this function maps them all to the standard:
 *   [{ id, name, type, children: [{ id, name, type, metadata, schema }] }]
 *
 * Adapter shapes:
 *   BigQuery:   {id, type:"dataset", tables: [{id, name, type, numRows, numBytes, columns}]}
 *   Firestore:  {id, type:"collection", documentCount, fields: [string]}
 *   GCS:        {id, type:"bucket", storageClass, location, prefixes, topLevelObjects}
 *   Spanner:    {id, type:"instance", databases: [{id, name, type:"database", tables: [string]}]}
 *   Bigtable:   {id, type:"instance", tables: [{id, name, type:"table", columnFamilies}]}
 */
function normalizeRemoteBrowse(rawNodes, serviceId) {
    if (!rawNodes || rawNodes.length === 0) return [];

    // Detect adapter type from the first node
    const first = rawNodes[0];
    const nodeType = first.type;

    if (nodeType === 'dataset') {
        // BigQuery: datasets with tables
        return rawNodes.map(ds => ({
            id: ds.id,
            name: ds.id,
            type: 'dataset',
            children: (ds.tables || []).map(t => ({
                id: t.id,
                name: t.name || t.id,
                type: 'table',
                metadata: {
                    rowCount: parseInt(t.numRows, 10) || 0,
                    sizeBytes: parseInt(t.numBytes, 10) || 0,
                },
                schema: (t.columns || []).map(c => ({
                    name: c.name,
                    type: c.type,
                })),
            })),
        }));
    }

    if (nodeType === 'collection') {
        // Firestore: flat collections (no nested children)
        return rawNodes.map(col => ({
            id: col.id,
            name: col.id,
            type: 'collection',
            children: [{
                id: col.id,
                name: col.id,
                type: 'collection',
                metadata: { rowCount: col.documentCount || 0 },
                schema: (col.fields || []).map(f => ({
                    name: typeof f === 'string' ? f : f.name,
                    type: typeof f === 'string' ? 'VALUE' : (f.type || 'VALUE'),
                })),
            }],
        }));
    }

    if (nodeType === 'bucket') {
        // GCS: buckets with prefixes as children
        return rawNodes.map(b => ({
            id: b.id,
            name: b.id,
            type: 'bucket',
            children: (b.prefixes || []).map(p => ({
                id: `${b.id}/${p}`,
                name: p,
                type: 'prefix',
                metadata: { storageClass: b.storageClass, location: b.location },
                schema: [],
            })).concat(b.topLevelObjects > 0 ? [{
                id: `${b.id}/`,
                name: `${b.topLevelObjects} root object${b.topLevelObjects !== 1 ? 's' : ''}`,
                type: 'objects',
                metadata: { rowCount: b.topLevelObjects },
                schema: [],
            }] : []),
        }));
    }

    if (nodeType === 'instance' && first.databases) {
        // Spanner: instances -> databases -> tables
        return rawNodes.map(inst => ({
            id: inst.id,
            name: inst.id,
            type: 'instance',
            children: (inst.databases || []).map(db => ({
                id: db.id,
                name: db.name || db.id,
                type: 'database',
                metadata: { rowCount: (db.tables || []).length },
                schema: (db.tables || []).map(t => ({
                    name: typeof t === 'string' ? t : t.name,
                    type: 'TABLE',
                })),
            })),
        }));
    }

    if (nodeType === 'instance' && first.tables) {
        // Bigtable: instances -> tables with column families
        return rawNodes.map(inst => ({
            id: inst.id,
            name: inst.id,
            type: 'instance',
            children: (inst.tables || []).map(t => ({
                id: t.id,
                name: t.name || t.id,
                type: 'table',
                metadata: { columnFamilies: (t.columnFamilies || []).length },
                schema: (t.columnFamilies || []).map(cf => ({
                    name: typeof cf === 'string' ? cf : cf.name,
                    type: 'COLUMN_FAMILY',
                })),
            })),
        }));
    }

    // Fallback: wrap each raw node as-is with minimal normalization
    return rawNodes.map(n => ({
        id: n.id || n.name || 'unknown',
        name: n.name || n.id || 'unknown',
        type: n.type || 'unknown',
        children: (n.children || []),
    }));
}

function formatNum(n) {
    if (n >= 1e9) return (n / 1e9).toFixed(1) + 'B';
    if (n >= 1e6) return (n / 1e6).toFixed(1) + 'M';
    if (n >= 1e3) return (n / 1e3).toFixed(1) + 'K';
    return String(n);
}

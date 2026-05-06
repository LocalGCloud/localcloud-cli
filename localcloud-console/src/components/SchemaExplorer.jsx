import { createSignal, createEffect, For, Show } from 'solid-js';
import { api } from '../api.js';
import { IconChevron, IconColumn, iconForType } from './TreeIcons.jsx';

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

    /** Render a tree node recursively */
    const TreeNode = (nodeProps) => {
        const node = nodeProps.node;
        const depth = nodeProps.depth || 0;
        const hasChildren = node.children && node.children.length > 0;
        const isLeaf = !hasChildren;
        const Icon = iconForType(node.type);
        const syncInfo = () => isLeaf ? getSyncBadge(node.id) : null;

        return (
            <div class="tree-group">
                <div class={`tree-row ${isLeaf ? 'tree-row-tbl' : 'tree-row-db'} ${isLeaf && selected() === node.id ? 'active' : ''}`}
                     onClick={() => isLeaf ? select(node) : toggle(node.id)}
                     role={isLeaf ? 'treeitem' : 'button'}
                     aria-selected={isLeaf ? selected() === node.id : undefined}
                     aria-expanded={!isLeaf ? !!expanded()[node.id] : undefined}
                     tabIndex={0}
                     style={depth > 0 ? {} : {}}>
                    <Show when={!isLeaf} fallback={<span style="width: 10px; display: inline-block" />}>
                        <IconChevron open={expanded()[node.id]} />
                    </Show>
                    <Icon />
                    <span class="tree-name" title={node.name}>{node.name}</span>
                    <Show when={!isLeaf}>
                        <span class="tree-badge tree-badge-db">{node.children.length} {typeLabel(node.type)}</span>
                    </Show>
                    <Show when={isLeaf && node.metadata?.rowCount != null}>
                        <span class="tree-badge">{formatNum(node.metadata.rowCount)}</span>
                    </Show>
                    <Show when={syncInfo()}>
                        <span style={`margin-left: 4px; font-size: 11px; color: ${syncInfo().class === 'synced' ? 'var(--success)' : 'var(--warning)'}`}>
                            {syncInfo().badge}
                        </span>
                    </Show>
                </div>
                <Show when={!isLeaf && expanded()[node.id]}>
                    <div class="tree-children">
                        <For each={node.children}>
                            {(child) => (
                                <>
                                    <TreeNode node={child} depth={depth + 1} />
                                    {/* Show columns inline when leaf is selected */}
                                    <Show when={!child.children?.length && selected() === child.id && child.schema?.length > 0}>
                                        <For each={child.schema}>
                                            {(col) => (
                                                <div class="tree-row tree-row-col" title={`${col.name} (${col.type})`}>
                                                    <IconColumn />
                                                    <span class="tree-col-name">{col.name}</span>
                                                    <span class="tree-col-type">{col.type}</span>
                                                </div>
                                            )}
                                        </For>
                                    </Show>
                                </>
                            )}
                        </For>
                    </div>
                </Show>
            </div>
        );
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
                <Show when={nodes().length === 0}>
                    <div class="sql-explorer-empty">
                        <svg width="32" height="32" viewBox="0 0 24 24" fill="currentColor" style={{ opacity: 0.12 }}>
                            <ellipse cx="12" cy="5.5" rx="9" ry="3.5"/>
                            <path d="M3 5.5v13c0 1.93 4.03 3.5 9 3.5s9-1.57 9-3.5v-13"/>
                        </svg>
                        <span>No resources found</span>
                    </div>
                </Show>
                <div class="sql-explorer-tree" role="tree" aria-label="Resource explorer" style="flex: 1; overflow-y: auto; padding: 4px 0">
                    <For each={nodes()}>
                        {(node) => <TreeNode node={node} depth={0} />}
                    </For>
                </div>
            </Show>
        </div>
    );
}

function typeLabel(type) {
    switch(type) {
        case 'dataset': return 'tables';
        case 'collection': return 'docs';
        case 'bucket': return 'items';
        case 'instance': return 'dbs';
        case 'database': return 'tables';
        default: return 'items';
    }
}

/**
 * Normalize the local schema API response into tree nodes.
 */
export function normalizeLocalSchema(data, serviceId) {
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
 * Normalize the remote browse API response into tree nodes.
 */
export function normalizeRemoteBrowse(rawNodes, serviceId) {
    if (!rawNodes || rawNodes.length === 0) return [];

    const first = rawNodes[0];
    const nodeType = first.type;

    if (nodeType === 'dataset') {
        return rawNodes.map(ds => ({
            id: ds.id, name: ds.id, type: 'dataset',
            children: (ds.tables || []).map(t => ({
                id: t.id, name: t.name || t.id, type: 'table',
                metadata: { rowCount: parseInt(t.numRows, 10) || 0, sizeBytes: parseInt(t.numBytes, 10) || 0 },
                schema: (t.columns || []).map(c => ({ name: c.name, type: c.type })),
            })),
        }));
    }

    if (nodeType === 'collection') {
        return rawNodes.map(col => ({
            id: col.id, name: col.id, type: 'collection',
            children: [{
                id: col.id, name: col.id, type: 'collection',
                metadata: { rowCount: col.documentCount || 0 },
                schema: (col.fields || []).map(f => ({
                    name: typeof f === 'string' ? f : f.name,
                    type: typeof f === 'string' ? 'VALUE' : (f.type || 'VALUE'),
                })),
            }],
        }));
    }

    if (nodeType === 'bucket') {
        return rawNodes.map(b => ({
            id: b.id, name: b.id, type: 'bucket',
            children: (b.prefixes || []).map(p => ({
                id: `${b.id}/${p}`, name: p, type: 'prefix',
                metadata: { storageClass: b.storageClass, location: b.location },
                schema: [],
            })).concat(b.topLevelObjects > 0 ? [{
                id: `${b.id}/`, name: `${b.topLevelObjects} root object${b.topLevelObjects !== 1 ? 's' : ''}`,
                type: 'objects', metadata: { rowCount: b.topLevelObjects }, schema: [],
            }] : []),
        }));
    }

    if (nodeType === 'instance' && first.databases) {
        return rawNodes.map(inst => ({
            id: inst.id, name: inst.id, type: 'instance',
            children: (inst.databases || []).map(db => ({
                id: db.id, name: db.name || db.id, type: 'database',
                metadata: { rowCount: (db.tables || []).length },
                schema: (db.tables || []).map(t => ({
                    name: typeof t === 'string' ? t : t.name, type: 'TABLE',
                })),
            })),
        }));
    }

    if (nodeType === 'instance' && first.tables) {
        return rawNodes.map(inst => ({
            id: inst.id, name: inst.id, type: 'instance',
            children: (inst.tables || []).map(t => ({
                id: t.id, name: t.name || t.id, type: 'table',
                metadata: { columnFamilies: (t.columnFamilies || []).length },
                schema: (t.columnFamilies || []).map(cf => ({
                    name: typeof cf === 'string' ? cf : cf.name, type: 'COLUMN_FAMILY',
                })),
            })),
        }));
    }

    return rawNodes.map(n => ({
        id: n.id || n.name || 'unknown', name: n.name || n.id || 'unknown',
        type: n.type || 'unknown', children: (n.children || []),
    }));
}

function formatNum(n) {
    if (n >= 1e9) return (n / 1e9).toFixed(1) + 'B';
    if (n >= 1e6) return (n / 1e6).toFixed(1) + 'M';
    if (n >= 1e3) return (n / 1e3).toFixed(1) + 'K';
    return String(n);
}

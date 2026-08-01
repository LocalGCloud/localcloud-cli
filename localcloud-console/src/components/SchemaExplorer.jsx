import { createSignal, createEffect, For, Show, onCleanup } from 'solid-js';
import { api } from '../api.js';
import { IconChevron, IconColumn, iconForType } from './TreeIcons.jsx';
import { onActivate } from '../utils/a11y.js';

export function SchemaExplorer(props) {
    // props: source ("local"|"remote"), serviceId, onSelect, syncManifests
    const [nodes, setNodes] = createSignal([]);
    const [expanded, setExpanded] = createSignal({});
    const [selected, setSelected] = createSignal(null);
    const [loading, setLoading] = createSignal(false);
    const [error, setError] = createSignal(null);
    let schemaSeq = 0;

    createEffect(() => {
        const my = ++schemaSeq;
        const svc = props.serviceId;
        const source = props.source;
        onCleanup(() => {
            if (my === schemaSeq) schemaSeq++;
        });
        if (!svc) {
            setNodes([]);
            setLoading(false);
            setError(null);
            return;
        }
        setLoading(true);
        setError(null);
        (async () => {
            try {
                if (source === 'remote') {
                    const data = await api.syncBrowse(svc);
                    if (my !== schemaSeq) return;
                    const rawNodes = data.nodes || data || [];
                    setNodes(normalizeRemoteBrowse(rawNodes, svc));
                } else {
                    const data = await api.schema(svc);
                    if (my !== schemaSeq) return;
                    setNodes(normalizeLocalSchema(data, svc));
                }
            } catch (e) {
                if (my !== schemaSeq) return;
                setError(e.message || 'Failed to load');
            } finally {
                if (my === schemaSeq) setLoading(false);
            }
        })();
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
        const syncInfo = () => isLeaf ? getSyncBadge(node.resourcePath) : null;

        const activate = () => isLeaf ? select(node) : toggle(node.id);

        return (
            <div class="tree-group">
                <div class={`tree-row ${isLeaf ? 'tree-row-tbl' : 'tree-row-db'} ${isLeaf && selected() === node.id ? 'active' : ''}`}
                     onClick={activate}
                     onKeyDown={onActivate(activate)}
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
                <div class="alert alert-error" role="alert" style="margin: 8px">{error()}</div>
            </Show>
            <Show when={!loading() && !error()}>
                <Show when={nodes().length === 0}>
                    <div class="sql-explorer-empty">
                        <svg width="32" height="32" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false" style={{ opacity: 0.12 }}>
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
function treeId(serviceId, type, ...context) {
    return [serviceId, type, ...context].map(part => encodeURIComponent(String(part ?? ''))).join('/');
}

function arrayOf(value) {
    return Array.isArray(value) ? value : [];
}

function itemValue(item, ...keys) {
    if (item == null) return '';
    if (typeof item !== 'object') return String(item);
    for (const key of keys) {
        if (item[key] != null && item[key] !== '') return String(item[key]);
    }
    return '';
}

function countValue(value) {
    const count = Number.parseInt(value, 10);
    return Number.isNaN(count) ? 0 : count;
}

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
            id: treeId(serviceId, 'table', dbName, tableName),
            resourcePath: t.name,
            name: tableName,
            type: 'table',
            schema: (t.columns || []).map(c => ({ name: c.name || c, type: c.type || 'unknown' })),
            metadata: { rowCount: t.row_count ?? null }
        });
    }
    return Object.entries(groups).map(([dbName, children]) => ({
        id: treeId(serviceId, 'database', dbName),
        name: dbName,
        type: 'database',
        children
    }));
}

/**
 * Normalize every remote node independently because browse responses can mix
 * resource kinds and primitive/object entries in their nested arrays.
 */
export function normalizeRemoteBrowse(rawNodes, serviceId) {
    if (!Array.isArray(rawNodes) || rawNodes.length === 0) return [];

    return rawNodes.map(rawNode => {
        const n = rawNode && typeof rawNode === 'object'
            ? rawNode
            : { id: String(rawNode ?? 'unknown'), name: String(rawNode ?? 'unknown') };
        const nodeType = n.type || 'unknown';
        const nodeName = itemValue(n, 'id', 'name') || 'unknown';

        switch (nodeType) {
            case 'dataset': {
                const datasetId = itemValue(n, 'id', 'name') || 'unknown';
                return {
                    id: treeId(serviceId, 'dataset', datasetId),
                    name: datasetId,
                    type: 'dataset',
                    children: arrayOf(n.tables).map(item => {
                        const table = item && typeof item === 'object' ? item : { id: item, name: item };
                        const tableId = itemValue(table, 'id', 'name') || 'unknown';
                        const tableName = itemValue(table, 'name', 'id') || tableId;
                        const resourcePath = tableId.includes('.') ? tableId : `${datasetId}.${tableId}`;
                        return {
                            id: treeId(serviceId, 'table', datasetId, tableId),
                            resourcePath,
                            name: tableName,
                            type: 'table',
                            metadata: {
                                rowCount: countValue(table.numRows ?? table.rowCount),
                                sizeBytes: countValue(table.numBytes ?? table.sizeBytes),
                            },
                            schema: arrayOf(table.columns).map(column => ({
                                name: itemValue(column, 'name', 'id') || 'unknown',
                                type: typeof column === 'object' ? (column.type || 'unknown') : 'unknown',
                            })),
                        };
                    }),
                };
            }
            case 'collection': {
                const collectionId = itemValue(n, 'id', 'name') || 'unknown';
                return {
                    id: treeId(serviceId, 'collection-group', collectionId),
                    name: collectionId,
                    type: 'collection',
                    children: [{
                        id: treeId(serviceId, 'collection', collectionId),
                        resourcePath: collectionId,
                        name: collectionId,
                        type: 'collection',
                        metadata: { rowCount: n.documentCount ?? 0 },
                        schema: arrayOf(n.fields).map(field => ({
                            name: itemValue(field, 'name', 'id') || 'unknown',
                            type: typeof field === 'object' ? (field.type || 'VALUE') : 'VALUE',
                        })),
                    }],
                };
            }
            case 'bucket': {
                const bucketId = itemValue(n, 'id', 'name') || 'unknown';
                const prefixNodes = arrayOf(n.prefixes).map(item => {
                    const prefix = itemValue(item, 'prefix', 'name', 'id') || 'unknown';
                    return {
                        id: treeId(serviceId, 'prefix', bucketId, prefix),
                        resourcePath: `${bucketId}/${prefix}`,
                        name: prefix,
                        type: 'prefix',
                        metadata: { storageClass: n.storageClass, location: n.location },
                        schema: [],
                    };
                });
                if ((n.topLevelObjects ?? 0) > 0) {
                    prefixNodes.push({
                        id: treeId(serviceId, 'objects', bucketId, ''),
                        resourcePath: `${bucketId}/`,
                        name: `${n.topLevelObjects} root object${n.topLevelObjects !== 1 ? 's' : ''}`,
                        type: 'objects',
                        metadata: { rowCount: n.topLevelObjects },
                        schema: [],
                    });
                }
                return {
                    id: treeId(serviceId, 'bucket', bucketId),
                    name: bucketId,
                    type: 'bucket',
                    children: prefixNodes,
                };
            }
            case 'instance': {
                const instanceId = itemValue(n, 'id', 'name') || 'unknown';
                if (Array.isArray(n.databases)) {
                    return {
                        id: treeId(serviceId, 'instance', instanceId),
                        name: instanceId,
                        type: 'instance',
                        children: n.databases.map(item => {
                            const database = item && typeof item === 'object' ? item : { id: item, name: item };
                            const databasePath = itemValue(database, 'id', 'name') || 'unknown';
                            const databaseName = itemValue(database, 'name') || databasePath.split('/').pop();
                            return {
                                id: treeId(serviceId, 'database', instanceId, databaseName),
                                name: databaseName,
                                type: 'database',
                                children: arrayOf(database.tables).map(tableItem => {
                                    const table = tableItem && typeof tableItem === 'object'
                                        ? tableItem
                                        : { id: tableItem, name: tableItem };
                                    const tablePath = itemValue(table, 'id', 'name') || 'unknown';
                                    const tableName = itemValue(table, 'name') || tablePath.split('/').pop();
                                    const resourcePath = tablePath.split('/').length >= 3
                                        ? tablePath
                                        : `${instanceId}/${databaseName}/${tableName}`;
                                    return {
                                        id: treeId(serviceId, 'table', instanceId, databaseName, tableName),
                                        resourcePath,
                                        name: tableName,
                                        type: 'table',
                                        metadata: {},
                                        schema: [],
                                    };
                                }),
                            };
                        }),
                    };
                }
                if (Array.isArray(n.tables)) {
                    return {
                        id: treeId(serviceId, 'instance', instanceId),
                        name: instanceId,
                        type: 'instance',
                        children: n.tables.map(item => {
                            const table = item && typeof item === 'object' ? item : { id: item, name: item };
                            const tablePath = itemValue(table, 'id', 'name') || 'unknown';
                            const tableName = itemValue(table, 'name') || tablePath.split('/').pop();
                            return {
                                id: treeId(serviceId, 'table', instanceId, tableName),
                                resourcePath: tablePath.includes('/') ? tablePath : `${instanceId}/${tablePath}`,
                                name: tableName,
                                type: 'table',
                                metadata: { columnFamilies: arrayOf(table.columnFamilies).length },
                                schema: arrayOf(table.columnFamilies).map(family => ({
                                    name: itemValue(family, 'name', 'id') || 'unknown',
                                    type: 'COLUMN_FAMILY',
                                })),
                            };
                        }),
                    };
                }
                break;
            }
            default:
                break;
        }
        return {
            id: treeId(serviceId, nodeType, nodeName),
            resourcePath: itemValue(n, 'resourcePath', 'id', 'name') || nodeName,
            name: itemValue(n, 'name', 'id') || 'unknown',
            type: nodeType,
            children: arrayOf(n.children).map((child, index) => {
                const childName = itemValue(child, 'name', 'id') || String(index);
                return {
                    ...(child && typeof child === 'object' ? child : {}),
                    id: treeId(serviceId, nodeType, nodeName, childName),
                    resourcePath: itemValue(child, 'resourcePath', 'id', 'name') || childName,
                    name: childName,
                };
            }),
        };
    });
}

function formatNum(n) {
    if (n >= 1e9) return (n / 1e9).toFixed(1) + 'B';
    if (n >= 1e6) return (n / 1e6).toFixed(1) + 'M';
    if (n >= 1e3) return (n / 1e3).toFixed(1) + 'K';
    return String(n);
}

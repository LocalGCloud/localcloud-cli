import { createEffect, createMemo, createSignal, For, Show, onCleanup, untrack } from 'solid-js';
import { api } from '../api.js';
import CsvImportWizard from './CsvImportWizard.jsx';
import DataBreadcrumb from './DataBreadcrumb.jsx';
import { onActivate } from '../utils/a11y.js';
import { generateMockRow } from '../utils/mockGenerator.js';



function idFromName(name) {
    if (!name) return '';
    const s = String(name);
    return s.includes('/') ? s.substring(s.lastIndexOf('/') + 1) : s;
}

function parseSpannerTables(ddl) {
    const statements = ddl?.statements || [];
    return statements
        .map(stmt => stmt.match(/CREATE\s+TABLE\s+`?([A-Za-z0-9_]+)`?/i)?.[1])
        .filter(Boolean);
}

function splitSqlStatements(text) {
    const clean = String(text || '').trim();
    if (!clean) return [];
    return clean.split(';').map(s => s.trim()).filter(Boolean);
}

function columnsFromRows(rows) {
    const cols = new Set();
    (rows || []).forEach(row => Object.keys(row || {}).forEach(k => cols.add(k)));
    return Array.from(cols).map(name => ({ name, type: 'STRING' }));
}

function normalizeColumns(columns) {
    return (columns || []).map(c => typeof c === 'string' ? { name: c, type: 'STRING' } : {
        name: c.name || c.column || c.id,
        type: c.type || c.dataType || 'STRING',
        readOnly: !!c.readOnly,
    }).filter(c => c.name);
}

function rowValue(row, column) {
    if (!row) return '';
    if (Object.prototype.hasOwnProperty.call(row, column)) return row[column];
    if (row.cells && Object.prototype.hasOwnProperty.call(row.cells, column)) return row.cells[column];
    return '';
}

function stringifyCell(value) {
    if (value == null) return '';
    if (typeof value === 'object') return JSON.stringify(value);
    return String(value);
}

function makeNode(type, id, label, raw, metadata = {}) {
    return { type, id, label: label || id, raw, metadata };
}

function cleanType(type = 'STRING') {
    const raw = String(type || 'STRING').toUpperCase().trim();
    const match = raw.match(/^(DOUBLE\s+PRECISION|[A-Z0-9_]+)(?:\s*\([^)]*\))?/);
    return match ? match[1].replace(/\s+/g, ' ') : raw.split(/\s+/)[0];
}

function coerceValue(value, type) {
    if (value === '' || value === undefined) return null;
    const t = cleanType(type);
    if (['INT64', 'INT', 'INTEGER', 'BIGINT', 'SMALLINT'].includes(t)) {
        const n = Number.parseInt(value, 10);
        return Number.isNaN(n) ? value : n;
    }
    if (['FLOAT64', 'FLOAT', 'DOUBLE', 'DOUBLE PRECISION', 'REAL', 'NUMERIC', 'DECIMAL'].includes(t)) {
        const n = Number.parseFloat(value);
        return Number.isNaN(n) ? value : n;
    }
    if (['BOOL', 'BOOLEAN'].includes(t)) {
        if (typeof value === 'boolean') return value;
        return ['true', '1', 'yes', 'y'].includes(String(value).toLowerCase());
    }
    if (['JSON', 'JSONB'].includes(t) && typeof value === 'string') {
        try { return JSON.parse(value); } catch { return value; }
    }
    return value;
}

function coerceRow(row, columns) {
    const byName = Object.fromEntries((columns || []).map(c => [c.name, c]));
    return Object.fromEntries(Object.entries(row || {}).map(([key, value]) => [
        key,
        coerceValue(value, byName[key]?.type),
    ]));
}

function quoteIdent(identifier, quote = '"') {
    const text = String(identifier || '');
    return quote + text.replaceAll(quote, quote + quote) + quote;
}

function escapeRegExp(text) {
    return String(text).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function ddlType(type, dialect = 'postgres') {
    const t = cleanType(type);
    if (dialect === 'bigquery') {
        if (t === 'TEXT') return 'STRING';
        if (t === 'BIGINT' || t === 'INTEGER' || t === 'INT') return 'INT64';
        if (t === 'DOUBLE PRECISION' || t === 'REAL' || t === 'FLOAT') return 'FLOAT64';
        if (t === 'BOOLEAN') return 'BOOL';
        return type || 'STRING';
    }
    if (dialect === 'spanner') {
        if (t === 'STRING' || t === 'TEXT') return 'STRING(MAX)';
        if (t === 'INTEGER' || t === 'BIGINT') return 'INT64';
        if (t === 'BOOLEAN') return 'BOOL';
        return type || 'STRING(MAX)';
    }
    if (t === 'STRING') return 'TEXT';
    if (t === 'INT64') return 'BIGINT';
    if (t === 'FLOAT64') return 'DOUBLE PRECISION';
    if (t === 'BOOL') return 'BOOLEAN';
    return type || 'TEXT';
}

function createTableDdl(tableName, columns, options = {}) {
    const quote = options.quote || '"';
    const dialect = options.dialect || 'postgres';
    const qualifiedName = options.qualifiedName || quoteIdent(tableName, quote);
    const columnLines = (columns || []).map(c => `  ${quoteIdent(c.name, quote)} ${ddlType(c.type, dialect)}`);
    if (dialect === 'spanner' && options.primaryKey?.length) {
        return `CREATE TABLE ${qualifiedName} (\n${columnLines.join(',\n')}\n) PRIMARY KEY (${options.primaryKey.map(c => quoteIdent(c, quote)).join(', ')});`;
    }
    const primaryKey = options.primaryKey?.length ? `,\n  PRIMARY KEY (${options.primaryKey.map(c => quoteIdent(c, quote)).join(', ')})` : '';
    return `CREATE TABLE ${qualifiedName} (\n${columnLines.join(',\n')}${primaryKey}\n);`;
}

function statementForTable(ddl, tableName) {
    const statements = ddl?.statements || [];
    const escaped = escapeRegExp(tableName);
    const match = statements.find(stmt => new RegExp(`CREATE\\s+TABLE\\s+\`?${escaped}\`?\\b`, 'i').test(stmt));
    return match ? (match.trim().endsWith(';') ? match.trim() : `${match.trim()};`) : null;
}

function splitTopLevelComma(text) {
    const parts = [];
    let depth = 0;
    let start = 0;
    for (let i = 0; i < text.length; i++) {
        const ch = text[i];
        if (ch === '(') depth++;
        else if (ch === ')') depth--;
        else if (ch === ',' && depth === 0) {
            parts.push(text.slice(start, i).trim());
            start = i + 1;
        }
    }
    const last = text.slice(start).trim();
    if (last) parts.push(last);
    return parts;
}

function columnsFromCreateStatement(statement) {
    const body = statement?.match(/\(([\s\S]*)\)\s*(PRIMARY\s+KEY|$)/i)?.[1];
    if (!body) return [];
    return splitTopLevelComma(body)
        .map(part => part.trim().replace(/,$/, ''))
        .filter(part => part && !/^(?:CONSTRAINT|PRIMARY\s+KEY|FOREIGN\s+KEY|UNIQUE|CHECK)\b/i.test(part))
        .map(part => {
            const match = part.match(/^`?("?)([A-Za-z0-9_]+)\1`?\s+((?:ARRAY\s*<[^>]+>)|(?:[A-Za-z][A-Za-z0-9_]*(?:\s+PRECISION)?(?:\([^)]*\))?))/i);
            return match ? { name: match[2], type: match[3].trim() } : null;
        })
        .filter(Boolean);
}

function formatError(error) {
    return error?.message || String(error || 'Operation failed');
}

const adapters = {
    spanner: {
        serviceName: 'Spanner',
        rootType: 'instance',
        levels: ['instance', 'database', 'table'],
        root(data) {
            return (data?.instances || []).map(inst =>
                makeNode('instance', idFromName(inst.name || inst.displayName), inst.displayName || idFromName(inst.name), inst, {
                    state: inst.state,
                }));
        },
        async children(path) {
            if (path.length === 1) {
                const result = await api.browse('spanner', 'instances/' + path[0].id);
                return (result.databases || []).map(db => makeNode('database', idFromName(db.name || db), idFromName(db.name || db), db));
            }
            if (path.length === 2) {
                const result = await api.browse('spanner', `instances/${path[0].id}/${path[1].id}`);
                return parseSpannerTables(result).map(t => makeNode('table', t, t, { ddl: result }));
            }
            return [];
        },
        async table(path) {
            const result = await api.browse('spanner', `instances/${path[0].id}/${path[1].id}/tables/${path[2].id}`);
            const ddl = statementForTable(path[2].raw?.ddl, path[2].id);
            const ddlColumns = columnsFromCreateStatement(ddl);
            const columns = ddlColumns.length > 0 ? normalizeColumns(ddlColumns) : normalizeColumns(result.columns || []);
            const keyColumns = columns.slice(0, 1).map(c => c.name);
            const tableDdl = ddl
                || createTableDdl(path[2].id, columns, { quote: '`', dialect: 'spanner', primaryKey: keyColumns });
            return { columns, rows: result.rows || [], ddl: tableDdl, keyColumns };
        },
        primaryAction(path) {
            if (path.length === 0) return { label: 'Create Instance', title: 'Create Spanner Instance', fields: [{ name: 'name', type: 'text' }], run: f => api.mutate('spanner', 'createInstance', { instance: f.name, displayName: f.name }) };
            if (path.length === 1) return { label: 'Create Database', title: 'Create Spanner Database', fields: [{ name: 'name', type: 'text' }], run: f => api.mutate('spanner', 'createDatabase', { instance: path[0].id, database: f.name }) };
            if (path.length === 2) return { label: 'Create Table', title: 'Create Spanner Table', fields: [{ name: 'ddl', type: 'textarea', value: 'CREATE TABLE Example (\n  id STRING(MAX) NOT NULL,\n  name STRING(MAX)\n) PRIMARY KEY (id);' }], run: f => api.mutate('spanner', 'ddl', { instance: path[0].id, database: path[1].id, statements: splitSqlStatements(f.ddl) }) };
            return null;
        },
        tableActions: { ddl: true, importCsv: true, mockRow: true, addRow: true, deleteTable: true, editRow: true, deleteRow: true },
        async insertRow(path, row, columnTypes) {
            return api.mutate('spanner', 'rows', { instance: path[0].id, database: path[1].id, table: path[2].id, columns: Object.keys(row), values: [Object.values(row)], columnTypes });
        },
        async updateRow(path, row, originalRow, table, columnTypes) {
            return api.mutateSub('spanner', 'rows', 'update', { instance: path[0].id, database: path[1].id, table: path[2].id, columns: Object.keys(row), values: [Object.values(row)], columnTypes });
        },
        async deleteRow(path, row, table) {
            const key = (table.keyColumns?.[0]) || table.columns[0]?.name;
            return api.mutateSub('spanner', 'rows', 'delete', { instance: path[0].id, database: path[1].id, table: path[2].id, keyColumns: [key], keyValues: [[rowValue(row, key)]] });
        },
        deleteTable(path) {
            return api.mutate('spanner', 'ddl', { instance: path[0].id, database: path[1].id, statements: [`DROP TABLE ${path[2].id}`] });
        },
    },
    bigquery: {
        serviceName: 'BigQuery',
        rootType: 'dataset',
        levels: ['dataset', 'table'],
        root(data) {
            return (data?.items || data?.datasets || []).map(ds => {
                const id = ds.datasetReference?.datasetId || ds.id || ds.name;
                return makeNode('dataset', id, id, ds);
            });
        },
        async children(path) {
            const result = await api.browse('bigquery', `datasets/${path[0].id}`);
            return (result.tables || result.items || []).map(tbl => {
                const id = tbl.tableReference?.tableId || tbl.name || tbl.id;
                return makeNode('table', id, id, tbl);
            });
        },
        async table(path) {
            const result = await api.browse('bigquery', `datasets/${path[0].id}/tables/${path[1].id}/data`);
            const rawColumns = result.schema?.fields || path[1].raw?.schema?.fields || result.columns || columnsFromRows(result.rows || []);
            const columns = normalizeColumns(rawColumns);
            const ddl = createTableDdl(path[1].id, columns, { quote: '`', dialect: 'bigquery', qualifiedName: `\`${path[0].id}.${path[1].id}\`` });
            return { columns, rows: result.rows || [], ddl, keyColumns: columns.slice(0, 1).map(c => c.name) };
        },
        primaryAction(path) {
            if (path.length === 0) return { label: 'Create Dataset', title: 'Create BigQuery Dataset', fields: [{ name: 'datasetId', type: 'text' }], run: f => api.mutate('bigquery', 'datasets', { datasetId: f.datasetId }) };
            if (path.length === 1) return { label: 'Create Table', title: 'Create BigQuery Table', fields: [{ name: 'tableId', type: 'text' }, { name: 'columns', type: 'textarea', value: 'id:STRING:REQUIRED\nname:STRING\ncreated_at:TIMESTAMP' }], run: f => api.mutate('bigquery', 'tables', { datasetId: path[0].id, tableId: f.tableId, schema: String(f.columns || '').split('\n').filter(Boolean).map(line => { const [name, type, mode] = line.split(':').map(s => s.trim()); return { name, type: type || 'STRING', mode: mode || 'NULLABLE' }; }) }) };
            return null;
        },
        tableActions: { ddl: true, importCsv: true, mockRow: true, addRow: true, deleteTable: true, editRow: true, deleteRow: true },
        insertRow(path, row) { return api.mutate('bigquery', 'rows', { dataset: path[0].id, table: path[1].id, row }); },
        updateRow(path, row, original, table) {
            const key = table.keyColumns?.[0] || table.columns[0]?.name;
            return api.mutateSub('bigquery', 'rows', 'update', { dataset: path[0].id, table: path[1].id, setValues: row, whereClause: `${key} = '${String(rowValue(original, key)).replace(/'/g, "''")}'` });
        },
        deleteRow(path, row, table) {
            const key = table.keyColumns?.[0] || table.columns[0]?.name;
            return api.mutateSub('bigquery', 'rows', 'delete', { dataset: path[0].id, table: path[1].id, whereClause: `${key} = '${String(rowValue(row, key)).replace(/'/g, "''")}'` });
        },
        deleteTable(path) { return api.mutateSub('bigquery', 'tables', 'delete', { datasetId: path[0].id, tableId: path[1].id }); },
    },
    alloydb: {
        serviceName: 'AlloyDB',
        rootType: 'cluster',
        levels: ['cluster', 'instance', 'database', 'table'],
        root(data) {
            return (data?.clusters || []).map(c => makeNode('cluster', c.clusterId || idFromName(c.name), c.clusterId || idFromName(c.name), c, { state: c.state }));
        },
        async children(path) {
            if (path.length === 1) {
                const result = await api.browse('alloydb', `instances/${path[0].id}`);
                return (result.instances || []).map(i => makeNode('instance', i.instanceId, i.instanceId, i, { state: i.state }));
            }
            if (path.length === 2) {
                const result = await api.browse('alloydb', `databases/${path[0].id}`);
                return (result.databases || []).map(db => makeNode('database', db.databaseName || db.name, db.databaseName || db.name, db));
            }
            if (path.length === 3) {
                const result = await api.browse('alloydb', `tables/${path[0].id}/${path[2].id}`);
                return (result.tables || []).map(t => makeNode('table', t.name, t.name, t));
            }
            return [];
        },
        async table(path) {
            const result = await api.browse('alloydb', `rows/${path[0].id}/${path[2].id}/${path[3].id}`);
            const columns = normalizeColumns(path[3].raw?.columns || result.columns || columnsFromRows(result.rows || []));
            const keyColumns = columns.slice(0, 1).map(c => c.name);
            return { columns, rows: result.rows || [], ddl: createTableDdl(path[3].id, columns, { primaryKey: keyColumns }), keyColumns };
        },
        primaryAction(path) {
            if (path.length === 0) return { label: 'Create Cluster', title: 'Create AlloyDB Cluster', fields: [{ name: 'name', type: 'text' }], run: f => api.mutate('alloydb', 'clusters', { name: f.name }) };
            if (path.length === 1) return { label: 'Create Instance', title: 'Create AlloyDB Instance', fields: [{ name: 'name', type: 'text' }], run: f => api.mutate('alloydb', 'instances', { clusterId: path[0].id, name: f.name }) };
            if (path.length === 2) return { label: 'Create Database', title: 'Create AlloyDB Database', fields: [{ name: 'name', type: 'text' }], run: f => api.mutate('alloydb', 'databases', { clusterId: path[0].id, instanceId: path[1].id, name: f.name }) };
            if (path.length === 3) return { label: 'Create Table', title: 'Create AlloyDB Table', fields: [{ name: 'ddl', type: 'textarea', value: 'CREATE TABLE example (\n  id TEXT PRIMARY KEY,\n  name TEXT,\n  created_at TIMESTAMP\n);' }], run: f => api.mutate('alloydb', 'tables', { clusterId: path[0].id, database: path[2].id, ddl: f.ddl }) };
            return null;
        },
        tableActions: { ddl: true, importCsv: true, mockRow: true, addRow: true, deleteTable: true, editRow: true, deleteRow: true },
        insertRow(path, row) { return api.mutate('alloydb', 'rows', { clusterId: path[0].id, database: path[2].id, table: path[3].id, row }); },
        updateRow(path, row, original, table) {
            const key = table.keyColumns?.[0] || table.columns[0]?.name;
            return api.mutateSub('alloydb', 'rows', 'update', { clusterId: path[0].id, database: path[2].id, table: path[3].id, keyColumn: key, keyValue: rowValue(original, key), row });
        },
        deleteRow(path, row, table) {
            const key = table.keyColumns?.[0] || table.columns[0]?.name;
            return api.mutateSub('alloydb', 'rows', 'delete', { clusterId: path[0].id, database: path[2].id, table: path[3].id, keyColumn: key, keyValue: rowValue(row, key) });
        },
        deleteTable(path) { return api.mutateSub('alloydb', 'tables', 'delete', { clusterId: path[0].id, database: path[2].id, table: path[3].id }); },
    },
    bigtable: {
        serviceName: 'Bigtable',
        rootType: 'instance',
        levels: ['instance', 'table'],
        root(data) {
            return (data?.instances || []).map(i => makeNode('instance', i.instanceId, i.instanceId, i, { tables: 0, type: i.instanceType }));
        },
        async children(path) {
            if (path.length === 1) {
                const result = await api.browse('bigtable', 'instances/' + encodeURIComponent(path[0].id));
                return (result.tables || []).map(t => makeNode('table', t.tableId, t.tableId, t, { granularity: t.granularity }));
            }
            return [];
        },
        async table(path) {
            const result = await api.browse('bigtable', `tables/${path[0].id}/${path[1].id}`);
            const rows = result.rows || [];
            const cols = ['rowKey', ...Array.from(new Set(rows.flatMap(r => Object.keys(r.cells || {}))))];
            return { columns: cols.map(name => ({ name, type: 'STRING' })), rows: rows.map(r => ({ rowKey: r.rowKey, ...(r.cells || {}) })), keyColumns: ['rowKey'] };
        },
        tableActions: { importCsv: true, mockRow: true, addRow: true, deleteRow: true },
        insertRow(path, row) {
            const rowKey = row.rowKey || `row-${Date.now()}`;
            const cells = {};
            Object.entries(row).forEach(([k, v]) => { if (k !== 'rowKey' && v != null && v !== '') cells[k.includes(':') ? k : `cf1:${k}`] = v; });
            if (Object.keys(cells).length === 0) cells['cf1:value'] = 'mock-value';
            return api.mutate('bigtable', 'rows', { table: `${path[0].id}/${path[1].id}`, rowKey, cells });
        },
        deleteRow(path, row) { return api.mutateSub('bigtable', 'rows', 'delete', { table: `${path[0].id}/${path[1].id}`, rowKey: row.rowKey }); },
    },
    firestore: {
        serviceName: 'Firestore',
        rootType: 'collection',
        levels: ['collection'],
        root(data) {
            return (data?.collections || []).map(c => makeNode('collection', typeof c === 'string' ? c : (c.name || c.id), typeof c === 'string' ? c : (c.name || c.id), c));
        },
        async table(path) {
            const result = await api.browse('firestore', path[0].id);
            const rows = result.documents || [];
            return { columns: [{ name: '__id', type: 'STRING' }, ...columnsFromRows(rows).filter(c => c.name !== '__id')], rows, keyColumns: ['__id'] };
        },
        primaryAction(path) {
            if (path.length === 1) return { label: 'Add Document', title: 'Add Firestore Document', fields: [{ name: 'documentId', type: 'text' }, { name: 'fields', type: 'textarea', value: '{}' }], run: f => api.mutate('firestore', 'documents', { collection: path[0].id, documentId: f.documentId, fields: JSON.parse(f.fields || '{}') }) };
            return null;
        },
        tableActions: { importCsv: true, mockRow: true, addRow: true, editRow: true, deleteRow: true },
        insertRow(path, row) {
            const documentId = row.__id || row.documentId || `doc-${Date.now()}`;
            const fields = { ...row };
            delete fields.__id;
            delete fields.documentId;
            return api.mutate('firestore', 'documents', { collection: path[0].id, documentId, fields });
        },
        updateRow(path, row) { return this.insertRow(path, row); },
        deleteRow(path, row) { return api.mutateSub('firestore', 'documents', 'delete', { collection: path[0].id, documentId: row.__id || row.id }); },
    },
    memorystore: {
        serviceName: 'Memorystore',
        rootType: 'keyspace',
        levels: ['keyspace'],
        root(data) {
            return (data?.databases || []).map(db => makeNode('keyspace', String(db.database ?? db.index ?? db.id), `db${db.database ?? db.index ?? db.id}`, db, { keyCount: db.keyCount }));
        },
        async table(path) {
            const result = await api.browse('memorystore', `db/${path[0].id}`);
            return { columns: [{ name: 'key', type: 'STRING' }, { name: 'type', type: 'STRING' }, { name: 'value', type: 'STRING' }, { name: 'ttl', type: 'INTEGER' }], rows: result.keys || [], keyColumns: ['key'] };
        },
        tableActions: { mockRow: true, addRow: true, editRow: true, deleteRow: true },
        insertRow(path, row) {
            const requestedType = String(row.type || '').toLowerCase();
            const type = ['string', 'hash', 'list', 'set'].includes(requestedType) ? requestedType : 'string';
            return api.mutate('memorystore', 'keys', { db: Number(path[0].id), key: row.key, type, value: row.value || '', ttl: row.ttl || null });
        },
        updateRow(path, row) {
            const requestedType = String(row.type || '').toLowerCase();
            const type = ['string', 'hash', 'list', 'set'].includes(requestedType) ? requestedType : 'string';
            return api.mutateSub('memorystore', 'keys', 'update', { db: Number(path[0].id), key: row.key, type, value: row.value || '', ttl: row.ttl || null });
        },
        deleteRow(path, row) { return api.mutateSub('memorystore', 'keys', 'delete', { db: Number(path[0].id), key: row.key }); },
    },
    cloudsql: {
        serviceName: 'Cloud SQL',
        rootType: 'instance',
        levels: ['instance', 'database', 'table'],
        root(data) {
            return (data?.instances || []).map(inst =>
                makeNode('instance', inst.instanceId, inst.instanceId, inst, {
                    databases: 0,
                    version: inst.databaseVersion,
                })
            );
        },
        async children(path) {
            if (path.length === 1) {
                const result = await api.browse('cloudsql', 'instances/' + encodeURIComponent(path[0].id));
                return [
                    ...((result.databases || []).map(db =>
                        makeNode('database', db.databaseName, db.databaseName, db, { charset: db.charset })
                    )),
                    ...((result.users || []).map(user =>
                        makeNode('user', user.userName, user.userName, user, { host: user.host })
                    )),
                ];
            }
            if (path.length === 2) {
                // List tables for the selected instance/database
                const schema = await api.schema('cloudsql');
                const tables = (schema?.tables || [])
                    .filter(t => t.instance === path[0].id && t.database === path[1].id)
                    .map(t => makeNode('table', t.name, t.name.split('.').pop(), t));
                return tables;
            }
            return [];
        },
        async table(path) {
            // Query actual data from the physical database
            const tableName = path[2].id; // instance.database.tableName
            try {
                const result = await api.query('cloudsql', `SELECT * FROM ${quoteIdent(tableName)} LIMIT 200`);
                const columns = (result.columns || []).map(c => ({ name: c, type: 'STRING' }));
                return { columns, rows: result.rows || [], keyColumns: columns.slice(0, 1).map(c => c.name) };
            } catch (e) {
                return { columns: [], rows: [], ddl: `-- Error: ${e.message || 'Failed to load table'}` };
            }
        },
        tableActions: {},
    },
};


export default function DatabaseExplorer(props) {
    const adapter = () => adapters[props.serviceId];
    const [path, setPath] = createSignal([]);
    let navSeq = 0;
    onCleanup(() => {
        navSeq++;
    });
    const [children, setChildren] = createSignal([]);
    const [table, setTable] = createSignal(null);
    const [loading, setLoading] = createSignal(false);
    const [showCsvImport, setShowCsvImport] = createSignal(false);
    const [ddlText, setDdlText] = createSignal(null);
    const [ddlCopied, setDdlCopied] = createSignal(false);
    const [tableError, setTableError] = createSignal(null);
    const [addMenuOpen, setAddMenuOpen] = createSignal(false);


    const rootNodes = createMemo(() => adapter()?.root(props.data?.()) || []);
    const selected = createMemo(() => path()[path().length - 1] || null);
    const isTableLevel = createMemo(() => {
        const a = adapter();
        if (!a) return false;
        return path().length === a.levels.length;
    });
    const currentNodes = createMemo(() => path().length === 0 ? rootNodes() : children());

    const syncSubpath = (nextPath) => props.onSubpathChange?.(nextPath.map(n => n.id));
    const subpathValue = () => {
        const value = typeof props.subpath === 'function' ? props.subpath() : props.subpath;
        return Array.isArray(value) ? value.map(String) : [];
    };

    const samePath = (nodes, ids) => (
        nodes.length === ids.length && nodes.every((node, index) => node.id === ids[index])
    );

    const restoreSubpath = async (ids, force = false) => {
        if (!force && samePath(path(), ids)) return;
        const a = adapter();
        const my = ++navSeq;
        setPath([]);
        setChildren([]);
        setTable(null);
        setTableError(null);
        setAddMenuOpen(false);
        if (!a || ids.length === 0) {
            setLoading(false);
            return;
        }

        setLoading(true);
        try {
            const next = [];
            let available = rootNodes();
            for (let index = 0; index < ids.length; index++) {
                const node = available.find(candidate => candidate.id === ids[index]);
                if (!node || next.length >= a.levels.length) {
                    if (my === navSeq) setLoading(false);
                    return;
                }
                next.push(node);
                if (index < ids.length - 1) {
                    available = await a.children(next);
                    if (my !== navSeq) return;
                }
            }

            if (next.length === a.levels.length) {
                const restoredTable = await a.table(next);
                if (my !== navSeq) return;
                setPath(next);
                setTable(restoredTable);
            } else {
                const restoredChildren = await a.children(next);
                if (my !== navSeq) return;
                setPath(next);
                setChildren(restoredChildren);
            }
        } catch (error) {
            if (my !== navSeq) return;
            setTableError(formatError(error));
        } finally {
            if (my === navSeq) setLoading(false);
        }
    };

    const reloadCurrent = async () => {
        const a = adapter();
        const p = path();
        const my = ++navSeq;
        if (!a) {
            setLoading(false);
            return;
        }
        setLoading(true);
        try {
            if (p.length === a.levels.length) {
                const t = await a.table(p);
                if (my !== navSeq) return;
                setTable(t);
            } else if (p.length > 0) {
                const c = await a.children(p);
                if (my !== navSeq) return;
                setChildren(c);
                setTable(null);
            } else {
                setChildren([]);
                setTable(null);
                await props.onRefresh?.();
            }
        } finally {
            if (my === navSeq) setLoading(false);
        }
    };

    const selectNode = async (node) => {
        const a = adapter();
        const next = [...path(), node];
        const my = ++navSeq;
        setPath(next);
        syncSubpath(next);
        setTable(null);
        setChildren([]);
        setTableError(null);
        setAddMenuOpen(false);
        if (!a) {
            setLoading(false);
            return;
        }
        setLoading(true);
        try {
            if (next.length === a.levels.length) {
                const t = await a.table(next);
                if (my !== navSeq) return;
                setTable(t);
            } else {
                const c = await a.children(next);
                if (my !== navSeq) return;
                setChildren(c);
            }
        } finally {
            if (my === navSeq) setLoading(false);
        }
    };

    const goToDepth = async (depth) => {
        const a = adapter();
        const next = path().slice(0, depth);
        const my = ++navSeq;
        setPath(next);
        syncSubpath(next);
        setTable(null);
        setChildren([]);
        setTableError(null);
        setAddMenuOpen(false);
        if (!a || next.length === 0) {
            setLoading(false);
            return;
        }
        setLoading(true);
        try {
            if (next.length === a.levels.length) {
                const t = await a.table(next);
                if (my !== navSeq) return;
                setTable(t);
            } else {
                const c = await a.children(next);
                if (my !== navSeq) return;
                setChildren(c);
            }
        } finally {
            if (my === navSeq) setLoading(false);
        }
    };


    let restoredService;
    let restoredData;
    createEffect(() => {
        const service = props.serviceId;
        const data = props.data?.();
        const ids = subpathValue();
        const force = service !== restoredService || data !== restoredData;
        restoredService = service;
        restoredData = data;
        void untrack(() => restoreSubpath(ids, force));
    });

    let breadcrumbPath;
    createEffect(() => {
        const depth = path().length;
        queueMicrotask(() => {
            if (path().length !== depth) return;
            breadcrumbPath?.querySelector('[aria-current="page"]')?.scrollIntoView?.({
                block: 'nearest',
                inline: 'nearest',
            });
        });
    });

    const breadcrumbs = createMemo(() => [
        { label: adapter()?.serviceName || props.serviceId, active: path().length === 0, onClick: () => goToDepth(0) },
        ...path().map((n, i) => ({
            label: n.label,
            tag: n.type,
            type: n.type,
            active: i === path().length - 1,
            onClick: () => goToDepth(i + 1),
        })),
    ]);

    const action = createMemo(() => adapter()?.primaryAction?.(path()));
    const runPrimaryAction = () => {
        const a = action();
        if (!a || !props.onAdd) return;
        props.onAdd(a.title, a.fields, async (formData) => {
            await a.run(formData);
            addOperation(a.label, 'SUCCESS', selected()?.label || adapter().serviceName);
            await reloadCurrent();
        });
    };

    const tableActions = () => adapter()?.tableActions || {};
    const insertableColumns = () => table()?.columns?.filter(c => !c.readOnly) || [];
    const addOperation = () => {};

    const insertRow = async (row, label = 'Insert Row') => {
        setTableError(null);
        const cols = insertableColumns();
        const columnTypes = Object.fromEntries(cols.map(c => [c.name, c.type]));
        await adapter().insertRow(path(), coerceRow(row, cols), columnTypes);
        addOperation(label, 'SUCCESS', selected()?.label);
        await reloadCurrent();
    };

    const addMockRow = async () => {
        try {
            const row = generateMockRow(insertableColumns());
            await insertRow(row, 'Insert Mock Row');
        } catch (error) {
            const message = formatError(error);
            addOperation('Insert Mock Row', 'FAILED', selected()?.label, message);
            setTableError(message);
        }
    };

    const openAddRow = () => {
        setAddMenuOpen(false);
        props.onAdd?.(`Add Row to ${selected()?.label}`, insertableColumns().map(c => ({ name: c.name, type: 'text' })), insertRow);
    };
    const openEditRow = (row) => props.onEdit?.('Edit Row', insertableColumns().map(c => ({ name: c.name, type: 'text', value: stringifyCell(rowValue(row, c.name)) })), async (formData) => {
        const cols = insertableColumns();
        const columnTypes = Object.fromEntries(cols.map(c => [c.name, c.type]));
        await adapter().updateRow(path(), coerceRow(formData, cols), row, table(), columnTypes);
        addOperation('Edit Row', 'SUCCESS', selected()?.label);
        await reloadCurrent();
    });
    const confirmDeleteRow = (row) => props.onDelete?.('Delete this row?', async () => {
        await adapter().deleteRow(path(), row, table());
        addOperation('Delete Row', 'SUCCESS', selected()?.label);
        await reloadCurrent();
    });
    const confirmDeleteTable = () => props.onDelete?.(`Delete table "${selected()?.label}"?`, async () => {
        await adapter().deleteTable(path());
        addOperation('Delete Table', 'SUCCESS', selected()?.label);
        await goToDepth(path().length - 1);
    });

    const showDdl = () => {
        const value = table()?.ddl;
        if (!value) return;
        setDdlCopied(false);
        setDdlText(String(value));
    };

    const copyDdl = async () => {
        try {
            await navigator.clipboard.writeText(ddlText());
            setDdlCopied(true);
        } catch {
            setDdlCopied(false);
        }
    };

    const ContextActions = () => {
        const tableColumns = () => table()?.columns || [];
        const canAddRows = () => isTableLevel() && table() && tableActions().addRow && adapter()?.insertRow;
        const canShowDdl = () => isTableLevel() && table() && tableActions().ddl && table().ddl;
        const canImportCsv = () => isTableLevel() && table() && tableActions().importCsv;
        const canDeleteTable = () => isTableLevel() && table() && tableActions().deleteTable && adapter()?.deleteTable;

        return (
            <div class="data-explorer-actions" aria-label="Data explorer actions">
                <Show when={!isTableLevel() && action()}>
                    <button class="btn btn-primary" onClick={runPrimaryAction}>{action().label}</button>
                </Show>
                <Show when={canShowDdl()}><button class="btn btn-secondary" onClick={showDdl}>Show DDL</button></Show>
                <Show when={canImportCsv()}><button class="btn btn-secondary" onClick={() => setShowCsvImport(true)}>Import CSV</button></Show>
                <Show when={canAddRows()}>
                    <Show when={tableActions().mockRow && tableColumns().length > 0} fallback={<button class="btn btn-primary" onClick={openAddRow}>Add Row</button>}>
                        <div class="data-explorer-split-action">
                            <button class="btn btn-primary data-explorer-split-main" onClick={addMockRow}>Add Row</button>
                            <button class="btn btn-primary data-explorer-split-menu" aria-label="Add row options" onClick={() => setAddMenuOpen(v => !v)}>v</button>
                            <Show when={addMenuOpen()}>
                                <div class="data-explorer-action-menu">
                                    <button onClick={openAddRow}>Add new row manually</button>
                                </div>
                            </Show>
                        </div>
                    </Show>
                </Show>
                <Show when={canDeleteTable()}><button class="btn btn-secondary data-explorer-danger" onClick={confirmDeleteTable}>Delete Table</button></Show>
            </div>
        );
    };

    const NodeList = () => (
        <Show when={currentNodes().length > 0} fallback={<div class="empty-state"><div class="empty-state-icon">{'\u2205'}</div><div class="empty-state-title">No resources found</div></div>}>
            <div class="data-table-wrapper">
                <table class="data-table">
                    <thead><tr><th>{adapter().levels[path().length] || 'Resource'}</th><th>Type</th><th>Details</th></tr></thead>
                    <tbody>
                        <For each={currentNodes()}>
                            {(node) => (
                                <tr class="clickable-row" onClick={() => selectNode(node)} onKeyDown={onActivate(() => selectNode(node))} role="button" tabIndex="0">
                                    <td style="font-weight:500">{node.label}</td>
                                    <td><span class="badge badge-info">{node.type}</span></td>
                                    <td>{Object.entries(node.metadata || {}).map(([k, v]) => `${k}: ${v}`).join('  ')}</td>
                                </tr>
                            )}
                        </For>
                    </tbody>
                </table>
            </div>
        </Show>
    );

    const TableSurface = () => {
        const t = table();
        const cols = t?.columns || [];
        return (
            <Show when={t} fallback={<div class="empty-state"><div class="empty-state-title">No table data</div></div>}>
                <Show when={tableError()}>
                    <div class="alert alert-error" role="alert" style="margin-bottom:12px">{tableError()}</div>
                </Show>
                <div class="data-table-wrapper" style="overflow-x:auto">
                    <table class="data-table" style="min-width:max-content">
                        <thead><tr><For each={cols}>{c => <th>{c.name}</th>}</For><Show when={tableActions().editRow || tableActions().deleteRow}><th>Actions</th></Show></tr></thead>
                        <tbody>
                            <For each={t.rows || []} fallback={<tr><td colSpan={Math.max(1, cols.length)}>No rows</td></tr>}>
                                {(row) => (
                                    <tr>
                                        <For each={cols}>{c => <td>{stringifyCell(rowValue(row, c.name)) || '--'}</td>}</For>
                                        <Show when={tableActions().editRow || tableActions().deleteRow}>
                                            <td>
                                                <Show when={tableActions().editRow && adapter().updateRow}><button class="btn btn-secondary" style="height:24px;font-size:11px;padding:0 8px" onClick={() => openEditRow(row)}>Edit</button></Show>
                                                <Show when={tableActions().deleteRow && adapter().deleteRow}><button class="btn btn-secondary" style="height:24px;font-size:11px;padding:0 8px;margin-left:4px;color:#ea4335" onClick={() => confirmDeleteRow(row)}>Del</button></Show>
                                            </td>
                                        </Show>
                                    </tr>
                                )}
                            </For>
                        </tbody>
                    </table>
                </div>
            </Show>
        );
    };

    return (
        <div class="data-explorer-shell">
            <div class="data-explorer-header">
                <div class="data-explorer-path" ref={element => { breadcrumbPath = element; }}>
                    <DataBreadcrumb crumbs={breadcrumbs()} />
                </div>
                <ContextActions />
            </div>

            <div class="data-explorer-body">
                <Show when={loading()}><div class="loading-state"><div class="loading-spinner" /> Loading…</div></Show>
                <Show when={!loading()}>
                    <Show when={isTableLevel()} fallback={<NodeList />}>
                        <TableSurface />
                    </Show>
                </Show>
            </div>

            <Show when={ddlText()}>
                <div class="modal-overlay" role="dialog" aria-modal="true" onClick={() => setDdlText(null)}>
                    <div class="card modal-card" onClick={e => e.stopPropagation()} style="max-width:900px;width:90vw">
                        <h2 style="margin-top:0;font-size:16px">Schema / DDL</h2>
                        <pre style="white-space:pre-wrap;max-height:60vh;overflow:auto;background:var(--bg);border:1px solid var(--border);padding:12px;border-radius:6px">{ddlText()}</pre>
                        <div style="display:flex;justify-content:flex-end;gap:8px">
                            <button class="btn btn-secondary" onClick={copyDdl}>{ddlCopied() ? 'Copied' : 'Copy'}</button>
                            <button class="btn btn-secondary" onClick={() => setDdlText(null)}>Close</button>
                        </div>
                    </div>
                </div>
            </Show>
            <CsvImportWizard
                show={showCsvImport()}
                onClose={() => setShowCsvImport(false)}
                tableName={selected()?.label}
                columns={insertableColumns().map(c => c.name)}
                columnTypes={Object.fromEntries(insertableColumns().map(c => [c.name, c.type]))}
                serviceName={adapter()?.serviceName}
                onImportRow={async (targetCols, values) => {
                    const row = {};
                    targetCols.forEach((c, i) => row[c] = values[i]);
                    return insertRow(row, 'Import CSV Row');
                }}
                onImportDone={reloadCurrent}
            />
        </div>
    );
}


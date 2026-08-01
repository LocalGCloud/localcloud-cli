export const INFO_SCHEMA_VIEWS = {
    bigquery: ['tables', 'columns', 'schemata', 'views', 'routines', 'partitions', 'table_storage'],
    spanner: ['tables', 'columns', 'table_statistics'],
    alloydb: ['tables', 'columns', 'schemata', 'views', 'routines'],
    cloudsql: ['tables', 'columns', 'schemata', 'views', 'routines'],
};

function presentEntries(entries) {
    return Object.fromEntries(Object.entries(entries).filter(([, value]) => value != null && value !== ''));
}

export function normalizeMatrixRows(result) {
    const columns = Array.isArray(result?.columns) ? result.columns : [];
    const rows = Array.isArray(result?.rows) ? result.rows : [];
    return {
        columns,
        rows: rows.map(row => {
            if (!Array.isArray(row)) return row && typeof row === 'object' ? row : {};
            return Object.fromEntries(columns.map((column, index) => [column, row[index]]));
        }),
    };
}

export function resolveDatabaseContext(serviceId, options = {}) {
    const subpath = Array.isArray(options.subpath) ? options.subpath : [];
    const schema = options.schema || {};

    if (serviceId === 'spanner') {
        const instance = options.selectedInstance || subpath[0] || schema.selectedInstance || '';
        const database = options.selectedDatabase || subpath[1] || schema.selectedDatabase || schema.databases?.[0] || '';
        return {
            instance,
            database,
            queryParams: presentEntries({ instance, database }),
            schemaParams: presentEntries({ instance, database }),
        };
    }

    if (serviceId === 'alloydb') {
        const cluster = options.selectedCluster || subpath[0] || schema.selectedCluster || '';
        const instance = options.selectedInstance || subpath[1] || schema.selectedInstance || '';
        const database = options.selectedDatabase || subpath[2] || schema.selectedDatabase || '';
        return {
            cluster,
            instance,
            database,
            queryParams: presentEntries({ database }),
            schemaParams: presentEntries({ cluster, database }),
        };
    }

    if (serviceId === 'cloudsql') {
        const instance = options.selectedInstance || subpath[0] || schema.selectedInstance || '';
        const database = options.selectedDatabase || subpath[1] || schema.selectedDatabase || '';
        return {
            instance,
            database,
            queryParams: presentEntries({ instance, database }),
            schemaParams: presentEntries({ instance, database }),
        };
    }

    return { instance: '', database: '', queryParams: {}, schemaParams: {} };
}

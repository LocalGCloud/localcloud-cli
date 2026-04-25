import { createSignal, For } from 'solid-js';

const OPS = {
    STRING: ['=', '!=', 'LIKE', 'IN'],
    TIMESTAMP: ['>=', '<=', '=', 'BETWEEN'],
    DATE: ['>=', '<=', '=', 'BETWEEN'],
    INT64: ['=', '!=', '>', '<', '>=', '<='],
    FLOAT64: ['=', '!=', '>', '<', '>=', '<='],
    INTEGER: ['=', '!=', '>', '<', '>=', '<='],
    FLOAT: ['=', '!=', '>', '<', '>=', '<='],
    NUMERIC: ['=', '!=', '>', '<', '>=', '<='],
    BOOL: ['='],
};

export function SyncFilterBuilder(props) {
    const [filters, setFilters] = createSignal([]);

    const addFilter = () => {
        const schema = props.schema || [];
        if (!schema.length) return;
        const f = { column: schema[0].name, operator: '=', value: '', columnType: schema[0].type };
        const updated = [...filters(), f];
        setFilters(updated);
        props.onChange?.(updated);
    };

    const update = (i, field, val) => {
        const updated = filters().map((f, idx) => {
            if (idx !== i) return f;
            const nf = { ...f, [field]: val };
            if (field === 'column') {
                const col = props.schema?.find(c => c.name === val);
                if (col) {
                    nf.columnType = col.type;
                    const ops = OPS[col.type] || OPS.STRING;
                    if (!ops.includes(nf.operator)) nf.operator = ops[0];
                }
            }
            return nf;
        });
        setFilters(updated);
        props.onChange?.(updated);
    };

    const remove = (i) => {
        const updated = filters().filter((_, idx) => idx !== i);
        setFilters(updated);
        props.onChange?.(updated);
    };

    return (
        <div style="display: flex; flex-direction: column; gap: 8px">
            <For each={filters()}>
                {(f, i) => {
                    const ops = () => OPS[f.columnType] || OPS.STRING;
                    return (
                        <div style="display: flex; gap: 4px; align-items: center">
                            <select class="form-input" style="width: 140px; padding: 6px 8px"
                                    value={f.column} onChange={e => update(i(), 'column', e.target.value)}>
                                <For each={props.schema || []}>
                                    {(col) => <option value={col.name}>{col.name}</option>}
                                </For>
                            </select>
                            <select class="form-input" style="width: 80px; padding: 6px 8px"
                                    value={f.operator} onChange={e => update(i(), 'operator', e.target.value)}>
                                <For each={ops()}>
                                    {(op) => <option value={op}>{op}</option>}
                                </For>
                            </select>
                            <input class="form-input" type="text" value={f.value}
                                   onInput={e => update(i(), 'value', e.target.value)}
                                   placeholder="value" style="flex: 1" />
                            <button class="btn btn-icon" onClick={() => remove(i())}
                                    style="padding: 4px 8px; color: var(--text-secondary)">&times;</button>
                        </div>
                    );
                }}
            </For>
            <button class="btn btn-secondary" onClick={addFilter}
                    style="align-self: flex-start; font-size: 13px">+ Add filter</button>
        </div>
    );
}

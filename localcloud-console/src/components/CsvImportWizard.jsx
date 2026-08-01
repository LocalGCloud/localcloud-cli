/**
 * CsvImportWizard — Reusable CSV import wizard for any database service.
 *
 * Props:
 *   show          - Boolean signal, controls modal visibility
 *   onClose       - Callback to close the modal
 *   tableName     - Display name of the target table/collection
 *   columns       - Array of insertable column names
 *   columnTypes   - Optional {col: type} map for display (e.g., {id: 'INT64', name: 'STRING(64)'})
 *   notNullColumns - Optional Set of NOT NULL column names
 *   onImportRow   - async (targetCols: string[], values: string[]) => {error?: string}
 *   onImportDone  - Optional callback after import completes (to refresh data)
 *   serviceName   - Display name (e.g., 'Spanner', 'BigQuery')
 */
import { createSignal, createMemo, Show, For, onCleanup } from 'solid-js';
import { onActivate } from '../utils/a11y.js';
import { ErrorAlert } from './AsyncState.jsx';

export default function CsvImportWizard(props) {
    const [csvFile, setCsvFile] = createSignal(null);
    const [csvParsed, setCsvParsed] = createSignal(null);
    const [csvMapping, setCsvMapping] = createSignal({});
    const [csvErrors, setCsvErrors] = createSignal([]);
    const [csvWarnings, setCsvWarnings] = createSignal([]);
    const [csvImportResult, setCsvImportResult] = createSignal(null);
    const [csvSelectedRows, setCsvSelectedRows] = createSignal(new Set());
    const [csvStep, setCsvStep] = createSignal('upload');
    const [connecting, setConnecting] = createSignal(null);
    const [mousePos, setMousePos] = createSignal({x: 0, y: 0});
    const [trimCsvCells, setTrimCsvCells] = createSignal(false);
    const [dropBlankCsvRows, setDropBlankCsvRows] = createSignal(false);

    const csvMappedTargets = createMemo(() => new Set(Object.values(csvMapping()).filter(v => v)));
    const csvMappedCount = createMemo(() => Object.values(csvMapping()).filter(v => v).length);

    // If no columns provided, use CSV headers as target columns (schema-less services like Firestore)
    const columns = () => props.columns || (csvParsed()?.headers || []);
    const colTypes = () => props.columnTypes || {};
    const nnCols = () => props.notNullColumns || new Set();
    // Generation token shared by file reads and imports. Resetting or closing
    // invalidates pending asynchronous work before it can mutate wizard state.
    let generation = 0;
    onCleanup(() => { generation++; });

    const parseCSV = (text, {trimCells = false, dropBlankRows = false} = {}) => {
        if (text.charCodeAt(0) === 0xFEFF) text = text.slice(1);
        const firstLine = text.split('\n')[0];
        const delimiters = [',', '\t', ';', '|'];
        let bestDelim = ',', bestCount = 0;
        for (const d of delimiters) {
            const count = (firstLine.match(new RegExp(d === '|' ? '\\|' : (d === '\t' ? '\t' : d), 'g')) || []).length;
            if (count > bestCount) { bestCount = count; bestDelim = d; }
        }

        const lines = [];
        let field = '', inQuote = false, row = [], endedWithRowBreak = false;
        const commitField = () => {
            row.push(trimCells ? field.trim() : field);
            field = '';
            inQuote = false;
        };
        const commitRow = () => {
            commitField();
            if (!dropBlankRows || row.some(cell => cell.trim() !== '')) lines.push(row);
            row = [];
        };

        for (let i = 0; i < text.length; i++) {
            const ch = text[i];
            endedWithRowBreak = false;
            if (inQuote) {
                if (ch === '"') {
                    if (text[i + 1] === '"') { field += '"'; i++; }
                    else { inQuote = false; }
                } else {
                    field += ch;
                }
            } else if (ch === '"') {
                inQuote = true;
            } else if (ch === bestDelim) {
                commitField();
            } else if (ch === '\n' || ch === '\r') {
                if (ch === '\r' && text[i + 1] === '\n') i++;
                commitRow();
                endedWithRowBreak = true;
            } else {
                field += ch;
            }
        }
        if (!endedWithRowBreak) commitRow();
        if (lines.length < 2) return null;
        return {headers: lines[0], rows: lines.slice(1), delimiter: bestDelim === '\t' ? 'TAB' : bestDelim, rowCount: lines.length - 1};
    };

    const handleCsvFile = (file) => {
        if (!file) return;
        const maxFileSize = 10 * 1024 * 1024;
        if (file.size > maxFileSize) {
            setCsvErrors([{row: -1, col: '', message: `File too large (${(file.size / 1024 / 1024).toFixed(1)} MB). Select a file no larger than 10 MB.`}]);
            return;
        }

        const myGeneration = ++generation;
        setCsvFile(file);
        setCsvParsed(null);
        setCsvMapping({});
        setCsvWarnings([]);
        setCsvSelectedRows(new Set());
        setCsvErrors([]);
        const reader = new FileReader();
        reader.onload = (e) => {
            if (myGeneration !== generation) return;
            const parsed = parseCSV(e.target.result, {
                trimCells: trimCsvCells(),
                dropBlankRows: dropBlankCsvRows(),
            });
            if (!parsed) {
                setCsvErrors([{row: -1, col: '', message: 'Could not parse CSV. Check format.'}]);
                return;
            }
            setCsvParsed(parsed);
            const cols = props.columns || parsed.headers;
            const mapping = {};
            for (const h of parsed.headers) {
                const exact = cols.find(c => c === h);
                const ci = cols.find(c => c.toLowerCase() === h.toLowerCase());
                const snake = cols.find(c => c.toLowerCase() === h.replace(/([A-Z])/g, '_$1').toLowerCase().replace(/^_/, ''));
                if (exact) mapping[h] = exact;
                else if (ci) mapping[h] = ci;
                else if (snake) mapping[h] = snake;
                else mapping[h] = '';
            }
            setCsvMapping(mapping);
            setCsvStep('mapping');
            setCsvErrors([]);
        };
        reader.onerror = () => {
            if (myGeneration !== generation) return;
            setCsvErrors([{row: -1, col: '', message: 'Could not read the selected CSV file.'}]);
        };
        reader.readAsText(file);
    };

    const validateCsvRows = () => {
        const parsed = csvParsed();
        const mapping = csvMapping();
        if (!parsed) return;
        const errors = [];
        const mappedCols = Object.values(mapping).filter(v => v);
        if (mappedCols.length === 0) {
            errors.push({row: -1, col: '', message: 'No columns mapped. Map at least one column.'});
            setCsvErrors(errors);
            return;
        }
        const nn = nnCols();
        const warnings = [];
        parsed.rows.forEach((row, rowIdx) => {
            if (row.length !== parsed.headers.length) {
                errors.push({row: rowIdx, col: '', message: `Row ${rowIdx + 1}: expected ${parsed.headers.length} fields, got ${row.length}`});
                return;
            }
            parsed.headers.forEach((h, colIdx) => {
                const targetCol = mapping[h];
                if (!targetCol) return;
                const val = (colIdx < row.length ? row[colIdx] : '').trim();
                const isEmpty = !val || val.toLowerCase() === 'null';
                if (isEmpty && nn.has(targetCol)) {
                    warnings.push({row: rowIdx, col: targetCol, message: `Row ${rowIdx + 1}: ${targetCol} is NOT NULL but value is empty`});
                }
            });
        });
        for (const n of nn) {
            if (!mappedCols.includes(n)) {
                warnings.push({row: -1, col: n, message: `Required column ${n} (NOT NULL) has no CSV mapping`});
            }
        }
        setCsvWarnings(warnings);
        setCsvErrors(errors);
        const errorRows = new Set(errors.map(e => e.row));
        const selected = new Set();
        parsed.rows.forEach((_, i) => { if (!errorRows.has(i)) selected.add(i); });
        setCsvSelectedRows(selected);
        setCsvStep('preview');
    };

    const executeCsvImport = async () => {
        const parsed = csvParsed();
        const mapping = csvMapping();
        const selected = csvSelectedRows();
        if (!parsed || selected.size === 0) return;

        const myGeneration = ++generation;
        setCsvStep('importing');
        const mappedHeaders = parsed.headers.filter(h => mapping[h]);
        const targetCols = mappedHeaders.map(h => mapping[h]);

        // Batch mode: send all rows in a single request (used by Spanner).
        if (props.onImportBatch) {
            const sortedIndices = [...selected].sort((a, b) => a - b);
            const allValues = sortedIndices.map(rowIdx => {
                const row = parsed.rows[rowIdx];
                return mappedHeaders.map((h) => {
                    const colIdx = parsed.headers.indexOf(h);
                    return colIdx < row.length ? row[colIdx] : '';
                });
            });

            let importResult;
            try {
                const result = await props.onImportBatch(targetCols, allValues);
                if (myGeneration !== generation) return;
                let imported = 0, failed = 0;
                const failedRows = [];
                if (result && result.results) {
                    result.results.forEach((r, i) => {
                        if (r.success) {
                            imported++;
                        } else {
                            failed++;
                            let errMsg = r.error || 'Unknown error';
                            if (errMsg.includes('failed to marshal')) errMsg = 'Constraint violation (NOT NULL, duplicate key, or type mismatch)';
                            failedRows.push({row: sortedIndices[i] + 1, error: errMsg});
                        }
                    });
                } else {
                    imported = allValues.length;
                }
                importResult = {imported, failed, failedRows, total: selected.size};
            } catch (e) {
                if (myGeneration !== generation) return;
                importResult = {imported: 0, failed: selected.size, failedRows: [{row: 1, error: e.message || 'Batch import failed'}], total: selected.size};
            }

            if (myGeneration !== generation) return;
            setCsvImportResult(importResult);
            setCsvStep('done');
            if (props.onImportDone) props.onImportDone();
            return;
        }

        // Row-by-row mode (fallback for non-batch services).
        let imported = 0, failed = 0;
        const failedRows = [];
        for (const rowIdx of [...selected].sort((a, b) => a - b)) {
            if (myGeneration !== generation) return;
            const row = parsed.rows[rowIdx];
            const values = mappedHeaders.map((h) => {
                const colIdx = parsed.headers.indexOf(h);
                return colIdx < row.length ? row[colIdx] : '';
            });
            try {
                const result = await props.onImportRow(targetCols, values);
                if (myGeneration !== generation) return;
                if (result && result.error) {
                    failed++;
                    let errMsg = result.error;
                    if (errMsg.includes('failed to marshal')) errMsg = 'Constraint violation (NOT NULL, duplicate key, or type mismatch)';
                    failedRows.push({row: rowIdx + 1, error: errMsg});
                } else {
                    imported++;
                }
            } catch (e) {
                if (myGeneration !== generation) return;
                failed++;
                failedRows.push({row: rowIdx + 1, error: e.message || 'Insert failed'});
            }
        }

        if (myGeneration !== generation) return;
        setCsvImportResult({imported, failed, failedRows, total: selected.size});
        setCsvStep('done');
        if (imported > 0 && props.onImportDone) props.onImportDone();
    };
    const resetCsvImport = () => {
        generation++;
        setCsvFile(null); setCsvParsed(null); setCsvMapping({});
        setCsvErrors([]); setCsvWarnings([]); setCsvImportResult(null);
        setCsvSelectedRows(new Set()); setCsvStep('upload'); setConnecting(null);
        setTrimCsvCells(false); setDropBlankCsvRows(false);
    };

    const closeWizard = () => { resetCsvImport(); if (props.onClose) props.onClose(); };

    // Interactive mapping helpers
    const startConnect = (header) => {
        if (csvMapping()[header]) setCsvMapping(prev => ({...prev, [header]: ''}));
        setConnecting(header);
    };
    const finishConnect = (targetCol) => {
        const src = connecting();
        if (!src) return;
        const newMapping = {...csvMapping()};
        for (const [k, v] of Object.entries(newMapping)) { if (v === targetCol) newMapping[k] = ''; }
        newMapping[src] = targetCol;
        setCsvMapping(newMapping);
        setConnecting(null);
    };
    const cancelConnect = () => setConnecting(null);
    const deleteMapping = (header) => setCsvMapping(prev => ({...prev, [header]: ''}));

    const ROW_H = 40, HDR_H = 30, COL_W = 240, GAP = 200, TOTAL_W = COL_W + GAP + COL_W;

    let containerRef;
    let fileInput;
    const onMouseMove = (e) => {
        if (!connecting() || !containerRef) return;
        const inner = containerRef.firstElementChild;
        if (!inner) return;
        const innerRect = inner.getBoundingClientRect();
        const px = e.clientX - innerRect.left;
        const py = e.clientY - innerRect.top;
        setMousePos({x: (px / innerRect.width) * TOTAL_W, y: (py / innerRect.height) * inner.offsetHeight});
    };

    return (
        <Show when={props.show}>
            <div class="modal-overlay" role="dialog" aria-modal="true" aria-labelledby="csv-import-title" onClick={(e) => { if (e.target === e.currentTarget) closeWizard(); }}>
                <div class="card modal-card" onClick={(e) => e.stopPropagation()} style="max-width:900px;width:90vw;max-height:85vh;display:flex;flex-direction:column">
                    {/* Header */}
                    <div style="margin-bottom:16px">
                        <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:12px">
                            <h2 id="csv-import-title" style="margin:0;font-size:16px">Import CSV to {props.tableName || 'Table'}</h2>
                            <button onClick={closeWizard} aria-label="Close CSV import wizard" style="background:none;border:none;color:var(--text-tertiary);cursor:pointer;font-size:18px;padding:4px">{'\u00D7'}</button>
                        </div>
                        <div style="display:flex;gap:4px;align-items:center">
                            <For each={[{id:'upload',label:'Upload'},{id:'mapping',label:'Map Columns'},{id:'preview',label:'Preview'},{id:'importing',label:'Import'},{id:'done',label:'Done'}]}>
                                {(step, i) => {
                                    const steps = ['upload','mapping','preview','importing','done'];
                                    return (
                                        <>
                                            <Show when={i() > 0}><div style={`width:20px;height:1px;background:${steps.indexOf(csvStep()) >= i() ? 'var(--primary)' : 'var(--border)'}`} /></Show>
                                            <div style={`font-size:11px;padding:3px 8px;border-radius:10px;font-weight:${csvStep() === step.id ? '600' : '400'};background:${csvStep() === step.id ? 'var(--primary)' : (steps.indexOf(csvStep()) > steps.indexOf(step.id) ? 'var(--surface-hover)' : 'transparent')};color:${csvStep() === step.id ? 'white' : 'var(--text-tertiary)'};transition:background 0.2s, color 0.2s`}>
                                                {step.label}
                                            </div>
                                        </>
                                    );
                                }}
                            </For>
                        </div>
                    </div>

                    <div style="flex:1;overflow-y:auto;min-height:0">
                        {/* Step 1: Upload */}
	                        <Show when={csvStep() === 'upload'}>
                                <input ref={el => fileInput = el} id="csv-file-input" name="csv-file" type="file" accept=".csv,.tsv,.txt" style="display:none" onChange={(e) => handleCsvFile(e.currentTarget.files[0])} />
	                            <div
                                onDragOver={(e) => { e.preventDefault(); e.currentTarget.style.borderColor = 'var(--primary)'; e.currentTarget.style.background = 'var(--surface-hover)'; }}
                                onDragLeave={(e) => { e.currentTarget.style.borderColor = 'var(--border)'; e.currentTarget.style.background = 'transparent'; }}
                                onDrop={(e) => { e.preventDefault(); e.currentTarget.style.borderColor = 'var(--border)'; e.currentTarget.style.background = 'transparent'; handleCsvFile(e.dataTransfer.files[0]); }}
	                                style="border:2px dashed var(--border);border-radius:8px;padding:40px 24px;text-align:center;cursor:pointer;transition:border-color 0.2s, background 0.2s"
	                                onClick={() => fileInput?.click()}
                                    onKeyDown={onActivate(() => fileInput?.click())}
                                    role="button"
                                    tabIndex="0"
                                    aria-label="Upload CSV file"
	                            >
                                <div style="font-size:32px;margin-bottom:8px;opacity:0.4">{'\u2191'}</div>
                                <div style="font-size:14px;font-weight:500;margin-bottom:4px;color:var(--text)">Drop CSV file here or click to browse</div>
                                <div style="font-size:12px;color:var(--text-tertiary)">Supports .csv, .tsv, .txt with comma, tab, semicolon, or pipe delimiters. Maximum 10 MB.</div>
                            </div>
                            <div style="display:flex;gap:16px;align-items:center;margin-top:12px;font-size:12px;color:var(--text-secondary);flex-wrap:wrap">
                                <label style="display:flex;gap:6px;align-items:center">
                                    <input
                                        type="checkbox"
                                        checked={trimCsvCells()}
                                        disabled={!!csvParsed()}
                                        onChange={(e) => setTrimCsvCells(e.currentTarget.checked)}
                                    />
                                    Trim cell whitespace
                                </label>
                                <label style="display:flex;gap:6px;align-items:center">
                                    <input
                                        type="checkbox"
                                        checked={dropBlankCsvRows()}
                                        disabled={!!csvParsed()}
                                        onChange={(e) => setDropBlankCsvRows(e.currentTarget.checked)}
                                    />
                                    Drop blank rows
                                </label>
                                <span style="color:var(--text-tertiary)">Off by default to preserve CSV content exactly.</span>
                            </div>
                            <Show when={csvParsed()}>
                                <div style="display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:12px;padding:10px 12px;border:1px solid var(--border);border-radius:6px">
                                    <span style="font-size:12px;color:var(--text-secondary)">
                                        {csvFile()?.name} remains loaded with {csvParsed()?.rowCount} rows.
                                    </span>
                                    <button class="btn btn-primary" onClick={() => setCsvStep('mapping')}>Continue Mapping</button>
                                </div>
                            </Show>
                            <ErrorAlert message={csvErrors()[0]?.message} role="alert" style="margin-top:12px" />
                        </Show>

                        {/* Step 2: Interactive Mapping */}
                        <Show when={csvStep() === 'mapping'}>
                            {(() => {
                                return (
                                    <div>
                                        <div style="display:flex;gap:16px;font-size:12px;color:var(--text-secondary);margin-bottom:8px;flex-wrap:wrap;align-items:center">
                                            <span>{'\u2713'} {csvParsed()?.rowCount} rows</span>
                                            <span>Delimiter: <strong>{csvParsed()?.delimiter}</strong></span>
                                            <span>File: <strong>{csvFile()?.name}</strong></span>
                                            <span style="margin-left:auto">
                                                <strong style={`color:${csvMappedCount() === (csvParsed()?.headers || []).length ? '#34a853' : 'var(--text)'}`}>{csvMappedCount()}/{(csvParsed()?.headers || []).length}</strong> mapped
                                            </span>
                                        </div>
                                        <div style="font-size:11px;color:var(--text-tertiary);margin-bottom:12px">
                                            Click source column, then click target to connect. Click a mapped source to disconnect.
                                        </div>

                                        <div ref={el => containerRef = el} onMouseMove={onMouseMove}
                                            onClick={(e) => { if (e.target === e.currentTarget || e.target.tagName === 'svg') cancelConnect(); }}
                                            style={`position:relative;width:100%;overflow-x:auto;overflow-y:auto;max-height:420px;margin-bottom:12px;cursor:${connecting() ? 'crosshair' : 'default'}`}>
                                            {(() => {
                                                const csvHeaders = csvParsed()?.headers || [];
                                                const tableCols = columns();
                                                const mapping = csvMapping();
                                                const mappedTargets = csvMappedTargets();
                                                const types = colTypes();
                                                const nn = nnCols();
                                                const targetIdx = {};
                                                tableCols.forEach((c, i) => { targetIdx[c] = i; });
                                                const leftH = HDR_H + csvHeaders.length * ROW_H;
                                                const rightH = HDR_H + tableCols.length * ROW_H;
                                                const svgH = Math.max(leftH, rightH);
                                                const conn = connecting();
                                                const mp = mousePos();
                                                const connIdx = conn ? csvHeaders.indexOf(conn) : -1;

                                                return (
                                                    <div style={`position:relative;min-width:${TOTAL_W}px;height:${svgH}px`}>
                                                        <svg style="position:absolute;top:0;left:0;width:100%;height:100%;pointer-events:none;z-index:1" viewBox={`0 0 ${TOTAL_W} ${svgH}`} preserveAspectRatio="none" aria-hidden="true" focusable="false">
                                                            <For each={csvHeaders}>
                                                                {(header, i) => {
                                                                    const target = mapping[header];
                                                                    if (!target) return null;
                                                                    const tIdx = targetIdx[target];
                                                                    if (tIdx === undefined) return null;
                                                                    const y1 = HDR_H + i() * ROW_H + ROW_H / 2;
                                                                    const y2 = HDR_H + tIdx * ROW_H + ROW_H / 2;
                                                                    const x1 = COL_W, x2 = COL_W + GAP;
                                                                    const midX = (x1 + x2) / 2, midY = (y1 + y2) / 2;
                                                                    const sampleVal = csvParsed()?.rows[0]?.[i()] || '';
                                                                    const displayVal = sampleVal.length > 16 ? sampleVal.slice(0, 14) + '…' : sampleVal;
                                                                    return (<>
                                                                        <path d={`M ${x1} ${y1} C ${midX} ${y1}, ${midX} ${y2}, ${x2} ${y2}`} fill="none" stroke="#34a853" stroke-width="1.5" opacity="0.5" />
                                                                        <circle cx={x1} cy={y1} r="4" fill="#34a853" />
                                                                        <circle cx={x2} cy={y2} r="4" fill="#34a853" />
                                                                        <rect x={midX - 48} y={midY - 9} width="96" height="18" rx="9" fill="var(--surface, #1e1e2e)" stroke="#34a853" stroke-width="0.5" opacity="0.9" />
                                                                        <text x={midX} y={midY + 3} text-anchor="middle" fill="#34a853" font-size="9" font-family="var(--font-mono, monospace)">{displayVal}</text>
                                                                    </>);
                                                                }}
                                                            </For>
                                                            <Show when={conn && connIdx >= 0}>
                                                                {(() => {
                                                                    const y1 = HDR_H + connIdx * ROW_H + ROW_H / 2;
                                                                    return <path d={`M ${COL_W} ${y1} C ${COL_W + GAP/2} ${y1}, ${mp.x - GAP/4} ${mp.y}, ${mp.x} ${mp.y}`} fill="none" stroke="#4285f4" stroke-width="2" stroke-dasharray="6,3" opacity="0.7" />;
                                                                })()}
                                                            </Show>
                                                        </svg>

                                                        {/* Left: Source */}
                                                        <div style={`position:absolute;top:0;left:0;width:${COL_W}px`}>
                                                            <div style={`height:${HDR_H}px;display:flex;align-items:center;padding:0 12px;font-size:10px;font-weight:700;text-transform:uppercase;letter-spacing:0.06em;color:var(--text-tertiary)`}>Source (CSV)</div>
                                                            <For each={csvHeaders}>
                                                                {(header) => {
                                                                    const isMapped = () => !!csvMapping()[header];
                                                                    const isActive = () => connecting() === header;
                                                                    return (
                                                                        <div onClick={() => isMapped() ? deleteMapping(header) : startConnect(header)}
                                                                            onKeyDown={onActivate(() => isMapped() ? deleteMapping(header) : startConnect(header))}
                                                                            role="button"
                                                                            tabIndex="0"
                                                                            aria-pressed={isMapped()}
                                                                            style={`height:${ROW_H}px;display:flex;align-items:center;gap:8px;padding:0 12px;border-bottom:1px solid var(--border-subtle);cursor:pointer;transition:background 0.15s, border-color 0.15s;color:var(--text);user-select:none;background:${isActive() ? 'rgba(66,133,244,0.1)' : isMapped() ? 'transparent' : 'rgba(251,188,4,0.06)'};border-right:${isActive() ? '2px solid #4285f4' : '2px solid transparent'}`}
                                                                            title={isMapped() ? 'Click to disconnect' : 'Click to connect'}>
                                                                            <div style={`width:8px;height:8px;border-radius:50%;flex-shrink:0;border:2px solid ${isActive() ? '#4285f4' : isMapped() ? '#34a853' : '#fbbc04'};background:${isActive() ? '#4285f4' : isMapped() ? '#34a853' : 'transparent'};transition:background 0.15s, border-color 0.15s`} />
                                                                            <div style="flex:1;min-width:0"><div style="font-size:12px;font-weight:500;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">{header}</div></div>
                                                                            <Show when={isMapped()}><span style="font-size:9px;color:#ea4335;opacity:0.6">{'\u00D7'}</span></Show>
                                                                        </div>
                                                                    );
                                                                }}
                                                            </For>
                                                        </div>

                                                        {/* Right: Target */}
                                                        <div style={`position:absolute;top:0;right:0;width:${COL_W}px`}>
                                                            <div style={`height:${HDR_H}px;display:flex;align-items:center;padding:0 12px;font-size:10px;font-weight:700;text-transform:uppercase;letter-spacing:0.06em;color:var(--text-tertiary)`}>Target ({props.serviceName || 'Table'})</div>
                                                            <For each={tableCols}>
                                                                {(col) => {
                                                                    const isMapped = () => csvMappedTargets().has(col);
                                                                    const isNN = nn.has(col);
                                                                    const colType = types[col] || '';
                                                                    return (
                                                                        <div onClick={() => { if (connecting()) finishConnect(col); }}
                                                                            onKeyDown={onActivate(() => { if (connecting()) finishConnect(col); })}
                                                                            role="button"
                                                                            tabIndex="0"
                                                                            aria-disabled={!connecting()}
                                                                            style={`height:${ROW_H}px;display:flex;align-items:center;gap:8px;padding:0 12px;border-bottom:1px solid var(--border-subtle);transition:background 0.15s, border-color 0.15s;user-select:none;cursor:${connecting() ? 'pointer' : 'default'};background:${connecting() && !isMapped() ? 'rgba(66,133,244,0.06)' : isMapped() ? 'transparent' : (isNN ? 'rgba(234,67,53,0.04)' : 'rgba(100,100,100,0.03)')};border-left:${connecting() && !isMapped() ? '2px solid #4285f4' : '2px solid transparent'}`}>
                                                                            <div style={`width:8px;height:8px;border-radius:50%;flex-shrink:0;border:2px solid ${isMapped() ? '#34a853' : (isNN ? '#ea4335' : 'var(--border)')};background:${isMapped() ? '#34a853' : 'transparent'};transition:background 0.15s, border-color 0.15s`} />
                                                                            <div style="flex:1;min-width:0"><div style={`font-size:12px;font-weight:500;color:${isMapped() ? 'var(--text)' : 'var(--text-tertiary)'};white-space:nowrap;overflow:hidden;text-overflow:ellipsis`}>{col}</div></div>
                                                                            <Show when={isNN}>
                                                                                <span style={`font-size:8px;padding:1px 5px;border-radius:3px;font-weight:600;white-space:nowrap;background:${isMapped() ? 'rgba(52,168,83,0.1)' : 'rgba(234,67,53,0.12)'};color:${isMapped() ? '#34a853' : '#ea4335'}`}>{isMapped() ? 'REQ \u2713' : 'REQUIRED'}</span>
                                                                            </Show>
                                                                            <Show when={colType}>
                                                                                <span style={`font-size:9px;padding:1px 5px;border-radius:3px;font-family:var(--font-mono, monospace);white-space:nowrap;background:${isMapped() ? 'rgba(52,168,83,0.1)' : 'var(--surface-hover)'};color:${isMapped() ? '#34a853' : 'var(--text-tertiary)'}`}>{colType}</span>
                                                                            </Show>
                                                                        </div>
                                                                    );
                                                                }}
                                                            </For>
                                                        </div>
                                                    </div>
                                                );
                                            })()}
                                        </div>

                                        <div style="display:flex;gap:12px;font-size:11px;color:var(--text-tertiary);margin-bottom:12px;flex-wrap:wrap">
                                            <span style="display:flex;align-items:center;gap:4px"><span style="width:8px;height:8px;border-radius:50%;background:#34a853;display:inline-block" /> Mapped</span>
                                            <span style="display:flex;align-items:center;gap:4px"><span style="width:8px;height:8px;border-radius:50%;border:2px solid #fbbc04;display:inline-block;box-sizing:border-box" /> Unmapped</span>
                                            <span style="display:flex;align-items:center;gap:4px"><span style="width:8px;height:8px;border-radius:50%;border:2px solid #ea4335;display:inline-block;box-sizing:border-box" /> Required</span>
                                        </div>
                                        <ErrorAlert message={csvErrors()[0]?.message} role="alert" style="margin-bottom:12px" />
                                        <div style="display:flex;gap:8px;justify-content:flex-end">
                                            <button class="btn btn-secondary" onClick={() => setCsvStep('upload')}>Back</button>
                                            <button class="btn btn-primary" onClick={validateCsvRows}>Validate & Preview</button>
                                        </div>
                                    </div>
                                );
                            })()}
                        </Show>

                        {/* Step 3: Preview */}
                        <Show when={csvStep() === 'preview'}>
                            {(() => {
                                const parsed = csvParsed();
                                const mapping = csvMapping();
                                const errors = csvErrors();
                                const warnings = csvWarnings();
                                const selected = csvSelectedRows();
                                const errorRows = new Set(errors.map(e => e.row));
                                const mappedHeaders = (parsed?.headers || []).filter(h => mapping[h]);
                                const validCount = [...selected].length;
                                const errorCount = errors.length;
                                const warnCount = warnings.filter(w => w.row >= 0).length;
                                const warnCells = {};
                                const warnRows = new Set();
                                for (const w of warnings) { if (w.row >= 0 && w.col) { warnCells[w.row + ':' + w.col] = w.message; warnRows.add(w.row); } }
                                const globalWarnings = warnings.filter(w => w.row < 0);
                                const types = colTypes();
                                const nn = nnCols();
                                return (
                                    <div>
                                        <div style="display:flex;gap:16px;align-items:center;margin-bottom:12px;font-size:12px;flex-wrap:wrap">
                                            <span style="color:var(--text-secondary)">{parsed?.rowCount} total rows</span>
                                            <span style="color:#34a853;font-weight:500">{'\u2713'} {validCount} selected</span>
                                            <Show when={errorCount > 0}><span style="color:#ea4335;font-weight:500">{'\u2717'} {errorCount} errors</span></Show>
                                            <Show when={warnCount > 0}><span style="color:#fbbc04;font-weight:500">{'\u26A0'} {warnCount} warnings</span></Show>
                                            <div style="flex:1" />
                                            <button onClick={() => { const all = new Set(); (parsed?.rows || []).forEach((_, i) => { if (!errorRows.has(i)) all.add(i); }); setCsvSelectedRows(all); }} style="font-size:11px;background:none;border:1px solid var(--border);border-radius:4px;padding:3px 8px;color:var(--text-secondary);cursor:pointer">Select All Valid</button>
                                            <button onClick={() => setCsvSelectedRows(new Set())} style="font-size:11px;background:none;border:1px solid var(--border);border-radius:4px;padding:3px 8px;color:var(--text-secondary);cursor:pointer">Deselect All</button>
                                        </div>
                                        <Show when={globalWarnings.length > 0}>
                                            <div style="background:rgba(251,188,4,0.08);border:1px solid rgba(251,188,4,0.3);border-radius:6px;padding:8px 12px;margin-bottom:12px;font-size:12px">
                                                <div style="font-weight:600;color:#fbbc04;margin-bottom:4px">{'\u26A0'} Warnings</div>
                                                <For each={globalWarnings}>{(w) => <div style="color:var(--text-secondary);margin-bottom:2px">{w.message}</div>}</For>
                                            </div>
                                        </Show>
                                        <Show when={errorCount > 0}>
                                            <div style="background:var(--surface-hover);border:1px solid #ea433530;border-radius:6px;padding:8px 12px;margin-bottom:12px;font-size:12px">
                                                <div style="font-weight:600;color:#ea4335;margin-bottom:4px">Errors</div>
                                                <For each={errors.slice(0, 5)}>{(err) => <div style="color:var(--text-secondary);margin-bottom:2px">{err.message}</div>}</For>
                                                <Show when={errors.length > 5}><div style="color:var(--text-tertiary);margin-top:4px">… and {errors.length - 5} more</div></Show>
                                            </div>
                                        </Show>
                                        <div class="data-table-wrapper" style="max-height:300px;overflow:auto">
                                            <table class="data-table" style="min-width:max-content">
                                                <thead><tr>
                                                    <th style="width:32px;position:sticky;left:0;z-index:3;background:var(--surface)"></th>
                                                    <th style="width:40px">Row</th>
                                                    <For each={mappedHeaders}>{(h) => {
                                                        const col = mapping[h];
                                                        const colType = types[col] || '';
                                                        const isNN = nn.has(col);
                                                        return (<th><div style="display:flex;flex-direction:column;gap:2px"><span>{col}</span><div style="display:flex;gap:4px;align-items:center"><span style="font-size:8px;font-weight:400;font-family:var(--font-mono,monospace);opacity:0.7">{colType}</span><Show when={isNN}><span style="font-size:7px;padding:0 3px;border-radius:2px;background:rgba(234,67,53,0.15);color:#ea4335;font-weight:600">REQ</span></Show></div></div></th>);
                                                    }}</For>
                                                    <th>Status</th>
                                                </tr></thead>
                                                <tbody>
                                                    <For each={(parsed?.rows || []).slice(0, 100)}>
                                                        {(row, rowIdx) => {
                                                            const hasError = errorRows.has(rowIdx());
                                                            const hasWarn = warnRows.has(rowIdx());
                                                            const isSelected = selected.has(rowIdx());
                                                            return (
                                                                <tr style={`background:${hasError ? 'rgba(234,67,53,0.05)' : hasWarn ? 'rgba(251,188,4,0.05)' : ''}`}>
                                                                    <td style="position:sticky;left:0;z-index:2;background:var(--surface)">
                                                                        <input type="checkbox" checked={isSelected} disabled={hasError} aria-label={`Select CSV row ${rowIdx() + 1}`} onChange={(e) => { const next = new Set(csvSelectedRows()); if (e.target.checked) next.add(rowIdx()); else next.delete(rowIdx()); setCsvSelectedRows(next); }} />
                                                                    </td>
                                                                    <td style="font-size:10px;color:var(--text-tertiary)">{rowIdx() + 1}</td>
                                                                    <For each={mappedHeaders}>
                                                                        {(h) => {
                                                                            const ci = (parsed?.headers || []).indexOf(h);
                                                                            const cellVal = ci >= 0 && ci < row.length ? row[ci] : '';
                                                                            const cellWarn = warnCells[rowIdx() + ':' + mapping[h]];
                                                                            return (
                                                                                <td style={`max-width:180px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:11px;${cellWarn ? 'border-bottom:2px solid #fbbc04;' : ''}`} title={cellWarn || ''}>
                                                                                    {cellWarn ? <span style="color:#fbbc04;margin-right:3px">{'\u26A0'}</span> : null}
                                                                                    {cellVal || <span style="color:var(--text-tertiary);font-style:italic">null</span>}
                                                                                </td>
                                                                            );
                                                                        }}
                                                                    </For>
                                                                    <td>
                                                                        <Show when={hasError} fallback={<Show when={hasWarn} fallback={<span style="color:#34a853;font-size:11px">{'\u2713'}</span>}><span style="color:#fbbc04;font-size:11px">{'\u26A0'}</span></Show>}>
                                                                            <span style="color:#ea4335;font-size:11px">{'\u2717'}</span>
                                                                        </Show>
                                                                    </td>
                                                                </tr>
                                                            );
                                                        }}
                                                    </For>
                                                </tbody>
                                            </table>
                                        </div>
                                        <Show when={(parsed?.rows || []).length > 100}>
                                            <div style="text-align:center;font-size:11px;color:var(--text-tertiary);margin-top:6px">Showing first 100 of {parsed?.rowCount} rows</div>
                                        </Show>
                                        <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:16px">
                                            <button class="btn btn-secondary" onClick={() => setCsvStep('mapping')}>Back</button>
                                            <button class="btn btn-primary" onClick={executeCsvImport} disabled={validCount === 0}>Import {validCount} Row{validCount !== 1 ? 's' : ''}</button>
                                        </div>
                                    </div>
                                );
                            })()}
                        </Show>

                        {/* Step 4: Importing */}
                        <Show when={csvStep() === 'importing'}>
                            <div style="text-align:center;padding:40px">
                                <div class="loading-spinner" style="margin:0 auto 16px" />
                                <div style="font-size:14px;font-weight:500" aria-live="polite">Importing rows…</div>
                                <div style="font-size:12px;color:var(--text-secondary);margin-top:4px">Inserting into {props.tableName}</div>
                            </div>
                        </Show>

                        {/* Step 5: Done */}
                        <Show when={csvStep() === 'done'}>
                            {(() => {
                                const r = csvImportResult();
                                return (
                                    <div style="padding:16px 0">
                                        <div style="text-align:center;margin-bottom:20px">
                                            <div style={`font-size:36px;margin-bottom:8px;${r?.failed === 0 ? 'color:#34a853' : 'color:#fbbc04'}`}>{r?.failed === 0 ? '\u2713' : '\u26A0'}</div>
                                            <div style="font-size:16px;font-weight:600;margin-bottom:4px">Import Complete</div>
                                            <div style="font-size:13px;color:var(--text-secondary)">
                                                <span style="color:#34a853;font-weight:500">{r?.imported}</span> imported
                                                <Show when={r?.failed > 0}> &middot; <span style="color:#ea4335;font-weight:500">{r?.failed}</span> failed</Show>
                                                &nbsp;of {r?.total} rows
                                            </div>
                                        </div>
                                        <Show when={r?.failedRows?.length > 0}>
                                            <div role="alert" style="background:var(--surface-hover);border:1px solid #ea433530;border-radius:6px;padding:10px 12px;margin-bottom:16px;max-height:160px;overflow-y:auto">
                                                <div style="font-size:11px;font-weight:600;color:#ea4335;margin-bottom:6px;text-transform:uppercase;letter-spacing:0.04em">Failed Rows</div>
                                                <For each={r.failedRows}>{(fr) => <div style="font-size:12px;color:var(--text-secondary);margin-bottom:3px;font-family:var(--font-mono)">Row {fr.row}: {fr.error}</div>}</For>
                                            </div>
                                        </Show>
                                        <div style="display:flex;gap:8px;justify-content:flex-end">
                                            <button class="btn btn-secondary" onClick={closeWizard}>Close</button>
                                            <Show when={r?.failed > 0}><button class="btn btn-primary" onClick={resetCsvImport}>Import Another</button></Show>
                                        </div>
                                    </div>
                                );
                            })()}
                        </Show>
                    </div>
                </div>
            </div>
        </Show>
    );
}

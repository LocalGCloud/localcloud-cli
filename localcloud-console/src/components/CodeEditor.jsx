/**
 * CodeEditor — Reusable CodeMirror 6 wrapper for Solid.js
 *
 * Props:
 *   value       - Initial/external text content
 *   onChange    - Callback(newValue) when content changes
 *   dialect     - SQL dialect: 'postgresql' | 'bigquery' | 'googlesql' | 'standard'
 *   schema      - CodeMirror SQL schema: { tableName: ['col1', 'col2'], ... }
 *   placeholder - Placeholder text
 *   onRun       - Callback when Cmd/Ctrl+Enter is pressed
 *   readOnly    - Boolean, makes editor read-only
 *   height      - CSS height string (default: '200px')
 *   maxHeight   - CSS max-height string (default: '400px')
 */
import { onMount, onCleanup, createEffect } from 'solid-js';
import { EditorView, keymap, lineNumbers, highlightActiveLineGutter, highlightActiveLine, drawSelection, highlightSpecialChars, placeholder as placeholderExt } from '@codemirror/view';
import { EditorState, Compartment, Prec } from '@codemirror/state';
import { sql, PostgreSQL, StandardSQL, SQLDialect } from '@codemirror/lang-sql';
import { autocompletion, closeBrackets, closeBracketsKeymap, completionKeymap, acceptCompletion } from '@codemirror/autocomplete';
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
import { searchKeymap, highlightSelectionMatches } from '@codemirror/search';
import { syntaxHighlighting, indentOnInput, bracketMatching, foldGutter, foldKeymap, HighlightStyle } from '@codemirror/language';
import { tags } from '@lezer/highlight';
import { linter, lintGutter } from '@codemirror/lint';
import { getUnsupportedKeywords, getWarningMessage, loadCachedCompatibilityWarnings } from '../data/compatibility.js';

// ─── SQL Dialect Map ───────────────────────────────────────────────────
// GoogleSQL (Spanner) uses backticks for identifiers, not double quotes
const GoogleSQL = SQLDialect.define({
    identifierQuotes: '`',
    operatorChars: '*+-%<>!=&|~^/',
    specialVar: '?',
    keywords: 'abort accept access add all alter and any array as asc assert at begin between bool by call case cast check close cluster collate column commit compute constraint contains continue create cross current cursor database date declare default delete desc distinct do double drop else end enum escape except exception exclusive exists explain export external extract false fetch first float for forall force foreign from full function get global goto grant group hash having if ignore immediate import in index inner insert int64 integer intersect interval into is join key left like limit loop merge modify natural new next no not null numeric of on open option or order out outer over partition plan pragma primary procedure public raise read record ref release rename replace resource return returning revoke right rollback row run schema select sequence session set size source space start stored struct string subtype table tablesample temp then to trailing true type union unique unnest update use using validate values variable view when where while window with',
    types: 'int64 float64 float32 numeric bool string bytes date timestamp array struct json',
});

// NoQuote dialect for services where we don't want identifier quoting in autocomplete
const NoQuoteSQL = SQLDialect.define({
    identifierQuotes: '',
});

const DIALECTS = {
    postgresql: PostgreSQL,
    bigquery: StandardSQL,
    googlesql: GoogleSQL,
    standard: NoQuoteSQL,
};

function getDialect(name) {
    return DIALECTS[name] || StandardSQL;
}

// ─── Compatibility Linter ─────────────────────────────────────────────
function createCompatibilityLinter(dialect) {
    const keywords = getUnsupportedKeywords(dialect);
    if (keywords.length === 0) return [];

    // Build regex patterns for each unsupported keyword (word-boundary match)
    const patterns = keywords.map(kw => ({
        regex: new RegExp('\\b' + kw.replace(/\./g, '\\.') + '\\b', 'gi'),
        keyword: kw,
    }));

    return linter((view) => {
        const text = view.state.doc.toString();
        const diagnostics = [];
        for (const { regex, keyword } of patterns) {
            regex.lastIndex = 0;
            let match;
            while ((match = regex.exec(text))) {
                const msg = getWarningMessage(dialect, keyword);
                diagnostics.push({
                    from: match.index,
                    to: match.index + match[0].length,
                    severity: 'warning',
                    message: `${keyword} is not supported by the ${dialect === 'bigquery' ? 'BigQuery' : 'Spanner'} emulator. ${msg || ''}`.trim(),
                });
            }
        }
        return diagnostics;
    }, { delay: 500 });
}

// ─── Theme (uses CSS variables — auto-switches with data-theme) ───────
const editorTheme = EditorView.theme({
    '&': {
        fontSize: '13px',
        fontFamily: 'var(--font-mono)',
    },
    '&.cm-focused': {
        outline: '2px solid var(--primary)',
        outlineOffset: '-1px',
    },
    '.cm-scroller': {
        fontFamily: 'var(--font-mono)',
        lineHeight: '20px',
    },
    '.cm-content': {
        padding: '12px 0',
        caretColor: 'var(--text)',
    },
    '.cm-line': {
        padding: '0 12px',
    },
    '.cm-gutters': {
        backgroundColor: 'var(--sql-editor-gutter)',
        borderRight: '1px solid var(--border-subtle)',
        color: 'var(--sql-editor-line)',
        fontFamily: 'var(--font-mono)',
        fontSize: '12px',
        minWidth: '40px',
    },
    '.cm-gutter.cm-lineNumbers .cm-gutterElement': {
        padding: '0 8px 0 4px',
        minWidth: '32px',
        textAlign: 'right',
    },
    '.cm-activeLineGutter': {
        backgroundColor: 'var(--surface-hover)',
        color: 'var(--text)',
    },
    '.cm-activeLine': {
        backgroundColor: 'var(--primary-softer)',
    },
    '.cm-selectionBackground': {
        backgroundColor: 'var(--primary-soft) !important',
    },
    '&.cm-focused .cm-selectionBackground': {
        backgroundColor: 'rgba(26,115,232,0.2) !important',
    },
    '.cm-cursor': {
        borderLeftColor: 'var(--text)',
        borderLeftWidth: '2px',
    },
    '.cm-matchingBracket': {
        backgroundColor: 'var(--primary-soft)',
        outline: '1px solid var(--primary)',
        color: 'var(--primary) !important',
    },
    '.cm-placeholder': {
        color: 'var(--text-tertiary)',
        fontStyle: 'italic',
    },
    '.cm-foldGutter .cm-gutterElement': {
        padding: '0 4px',
        cursor: 'pointer',
        color: 'var(--text-tertiary)',
    },
    '.cm-tooltip': {
        backgroundColor: 'var(--surface)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-sm)',
        boxShadow: 'var(--shadow-hover)',
    },
    '.cm-tooltip-autocomplete': {
        '& > ul': {
            fontFamily: 'var(--font-mono)',
            fontSize: '12px',
            maxHeight: '200px',
        },
        '& > ul > li': {
            padding: '4px 12px',
            lineHeight: '1.5',
        },
        '& > ul > li[aria-selected]': {
            backgroundColor: 'var(--primary-soft)',
            color: 'var(--primary)',
        },
    },
    '.cm-completionLabel': {
        fontFamily: 'var(--font-mono)',
    },
    '.cm-completionDetail': {
        fontStyle: 'normal',
        color: 'var(--text-tertiary)',
        fontSize: '10px',
        marginLeft: '8px',
    },
    '.cm-panels': {
        backgroundColor: 'var(--surface)',
        borderBottom: '1px solid var(--border)',
        color: 'var(--text)',
    },
    '.cm-panels .cm-button': {
        backgroundImage: 'none',
        backgroundColor: 'var(--primary)',
        color: '#fff',
        border: 'none',
        borderRadius: 'var(--radius-xs)',
        fontSize: '12px',
        padding: '2px 10px',
    },
    '.cm-panels .cm-textfield': {
        backgroundColor: 'var(--surface)',
        color: 'var(--text)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-xs)',
        fontSize: '12px',
    },
    '.cm-search .cm-button': {
        backgroundImage: 'none',
    },
    '.cm-foldPlaceholder': {
        backgroundColor: 'var(--surface-variant)',
        border: '1px solid var(--border)',
        color: 'var(--text-secondary)',
        borderRadius: 'var(--radius-xs)',
        padding: '0 6px',
        margin: '0 2px',
    },
});

// ─── Syntax Highlighting (CSS variables for auto theme switching) ─────
const highlightStyle = HighlightStyle.define([
    { tag: tags.keyword, color: 'var(--sql-keyword)', fontWeight: '600' },
    { tag: tags.operatorKeyword, color: 'var(--sql-keyword)', fontWeight: '600' },
    { tag: tags.string, color: 'var(--sql-string)' },
    { tag: tags.number, color: 'var(--sql-number)' },
    { tag: tags.bool, color: 'var(--sql-number)' },
    { tag: tags.null, color: 'var(--sql-number)', fontStyle: 'italic' },
    { tag: tags.comment, color: 'var(--sql-comment)', fontStyle: 'italic' },
    { tag: tags.lineComment, color: 'var(--sql-comment)', fontStyle: 'italic' },
    { tag: tags.blockComment, color: 'var(--sql-comment)', fontStyle: 'italic' },
    { tag: tags.operator, color: 'var(--sql-operator)' },
    { tag: tags.punctuation, color: 'var(--sql-paren)' },
    { tag: tags.paren, color: 'var(--sql-paren)' },
    { tag: tags.squareBracket, color: 'var(--sql-paren)' },
    { tag: tags.brace, color: 'var(--sql-paren)' },
    { tag: tags.standard(tags.name), color: 'var(--sql-function)' },
    { tag: tags.definition(tags.variableName), color: 'var(--text)' },
    { tag: tags.typeName, color: 'var(--sql-function)' },
    { tag: tags.special(tags.string), color: 'var(--sql-identifier)' },
]);

// ─── Component ─────────────────────────────────────────────────────────
export default function CodeEditor(props) {
    let containerRef;
    let view;
    let isInternalUpdate = false;

    const langCompartment = new Compartment();
    const readOnlyCompartment = new Compartment();

    function buildLangExtension() {
        return sql({
            dialect: getDialect(props.dialect),
            schema: props.schema || undefined,
            upperCaseKeywords: true,
        });
    }

    onMount(() => {
        loadCachedCompatibilityWarnings();
        const extensions = [
            // Appearance
            // Appearance
            props.lineNumbers !== false ? lineNumbers() : [],
            props.lineNumbers !== false ? highlightActiveLineGutter() : [],
            highlightActiveLine(),
            drawSelection(),
            highlightSpecialChars(),
            props.lineNumbers !== false ? foldGutter() : [],
            indentOnInput(),
            bracketMatching(),
            closeBrackets(),
            highlightSelectionMatches(),

            // Autocomplete
            autocompletion({
                activateOnTyping: true,
                maxRenderedOptions: 30,
            }),

            // History (undo/redo)
            history(),

            // Cmd/Ctrl+Enter → run query (highest priority, before any other keymap)
            Prec.highest(keymap.of([
                {
                    key: 'Mod-Enter',
                    run: () => {
                        if (props.onRun) { props.onRun(); return true; }
                        return false;
                    },
                },
            ])),

            // Keymaps
            keymap.of([
                // Tab accepts autocomplete; Enter inserts newline (not accept)
                { key: 'Tab', run: acceptCompletion },
                ...closeBracketsKeymap,
                ...defaultKeymap,
                ...searchKeymap,
                ...historyKeymap,
                ...completionKeymap,
                ...foldKeymap,
                indentWithTab,
            ]),

            // SQL language (in compartment for dynamic reconfiguration)
            langCompartment.of(buildLangExtension()),

            // Read-only state
            readOnlyCompartment.of(EditorState.readOnly.of(!!props.readOnly)),

            // Theme + syntax highlighting
            editorTheme,
            syntaxHighlighting(highlightStyle),

            // Compatibility linter (yellow warnings for unsupported SQL)
            props.lineNumbers !== false ? lintGutter() : [],
            createCompatibilityLinter(props.dialect || 'postgresql'),

            // Placeholder
            props.placeholder ? placeholderExt(props.placeholder) : [],

            // Update listener → props.onChange
            EditorView.updateListener.of((update) => {
                if (update.docChanged && !isInternalUpdate) {
                    props.onChange?.(update.state.doc.toString());
                }
            }),
        ];

        view = new EditorView({
            parent: containerRef,
            state: EditorState.create({
                doc: props.value || '',
                extensions,
            }),
        });
    });

    // Sync external value changes into the editor
    createEffect(() => {
        const newVal = props.value ?? '';
        if (view && view.state.doc.toString() !== newVal) {
            isInternalUpdate = true;
            view.dispatch({
                changes: { from: 0, to: view.state.doc.length, insert: newVal },
            });
            isInternalUpdate = false;
        }
    });

    // Reconfigure SQL dialect + schema when they change
    createEffect(() => {
        const _dialect = props.dialect;
        const _schema = props.schema;
        if (view) {
            view.dispatch({
                effects: langCompartment.reconfigure(buildLangExtension()),
            });
        }
    });

    // Reconfigure read-only when it changes
    createEffect(() => {
        const ro = !!props.readOnly;
        if (view) {
            view.dispatch({
                effects: readOnlyCompartment.reconfigure(EditorState.readOnly.of(ro)),
            });
        }
    });

    onCleanup(() => {
        view?.destroy();
    });

    return (
        <div
            ref={containerRef}
            class={`cm-wrapper ${props.class || ''}`}
            style={{
                'min-height': props.height || '100px',
                'max-height': props.maxHeight || 'none',
                overflow: 'auto',
                ...(typeof props.style === 'object' ? props.style : {})
            }}
        />
    );
}

/**
 * Utility: Convert a SERVICE_SCHEMAS table list to CodeMirror schema format.
 * Supports hierarchical nesting for qualified names (schema.table, instance.table).
 *
 * Input:  [{ name: 'instance.table', columns: [{ name: 'cf:col', type: 'COLUMN_FAMILY' }] }]
 * Output: { instance: { table: [{ label: 'cf:col', type: 'variable', info: 'COLUMN_FAMILY' }] } }
 *
 * Also works with flat names (no dot): { tableName: ['col1', 'col2'] }
 */
export function toCodeMirrorSchema(tables) {
    if (!tables || !Array.isArray(tables)) return undefined;
    const schema = {};
    for (const t of tables) {
        const cols = (t.columns || []).map(c => {
            if (typeof c === 'string') return c;
            // Rich completion with type tooltip
            return { label: c.name, type: 'variable', info: c.type || undefined };
        });

        // Split dotted names into nested schema: "instance.table" → { instance: { table: [...] } }
        if (t.name && t.name.includes('.')) {
            const parts = t.name.split('.');
            const parent = parts[0];
            const child = parts.slice(1).join('.');
            if (!schema[parent]) schema[parent] = {};
            schema[parent][child] = cols;
        } else {
            schema[t.name] = cols;
        }
    }
    return Object.keys(schema).length > 0 ? schema : undefined;
}

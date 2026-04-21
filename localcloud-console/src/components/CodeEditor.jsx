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
import { EditorState, Compartment } from '@codemirror/state';
import { sql, PostgreSQL, StandardSQL } from '@codemirror/lang-sql';
import { autocompletion, closeBrackets, closeBracketsKeymap, completionKeymap } from '@codemirror/autocomplete';
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
import { searchKeymap, highlightSelectionMatches } from '@codemirror/search';
import { syntaxHighlighting, indentOnInput, bracketMatching, foldGutter, foldKeymap, HighlightStyle } from '@codemirror/language';
import { tags } from '@lezer/highlight';
import { linter, lintGutter } from '@codemirror/lint';
import { getUnsupportedKeywords, getWarningMessage } from '../data/compatibility.js';

// ─── SQL Dialect Map ───────────────────────────────────────────────────
const DIALECTS = {
    postgresql: PostgreSQL,
    bigquery: StandardSQL,
    googlesql: StandardSQL,
    standard: StandardSQL,
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
        const extensions = [
            // Appearance
            lineNumbers(),
            highlightActiveLineGutter(),
            highlightActiveLine(),
            drawSelection(),
            highlightSpecialChars(),
            foldGutter(),
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

            // Keymaps
            keymap.of([
                ...closeBracketsKeymap,
                ...defaultKeymap,
                ...searchKeymap,
                ...historyKeymap,
                ...completionKeymap,
                ...foldKeymap,
                indentWithTab,
                // Cmd/Ctrl+Enter → run
                {
                    key: 'Mod-Enter',
                    run: () => {
                        if (props.onRun) { props.onRun(); return true; }
                        return false;
                    },
                },
            ]),

            // SQL language (in compartment for dynamic reconfiguration)
            langCompartment.of(buildLangExtension()),

            // Read-only state
            readOnlyCompartment.of(EditorState.readOnly.of(!!props.readOnly)),

            // Theme + syntax highlighting
            editorTheme,
            syntaxHighlighting(highlightStyle),

            // Compatibility linter (yellow warnings for unsupported SQL)
            lintGutter(),
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
            }}
        />
    );
}

/**
 * Utility: Convert a SERVICE_SCHEMAS table list to CodeMirror schema format.
 * Input:  [{ name: 'users', columns: [{ name: 'id', type: 'INT' }, ...] }]
 * Output: { users: ['id', 'name', ...] }
 */
export function toCodeMirrorSchema(tables) {
    if (!tables || !Array.isArray(tables)) return undefined;
    const schema = {};
    for (const t of tables) {
        schema[t.name] = (t.columns || []).map(c => typeof c === 'string' ? c : c.name);
    }
    return Object.keys(schema).length > 0 ? schema : undefined;
}

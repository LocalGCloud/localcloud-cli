import { createSignal, createEffect, createMemo, Show, For, onCleanup } from 'solid-js';
import { Portal } from 'solid-js/web';

/**
 * Searchable combobox — both dropdown AND typable.
 * Filters options as you type, supports keyboard navigation.
 * Uses Portal to render the dropdown into document.body,
 * so it floats above all overflow:hidden / z-index traps.
 * Like Ant Design's Select with showSearch.
 */
export default function ComboBox(props) {
    const [open, setOpen] = createSignal(false);
    const [filter, setFilter] = createSignal('');
    const [highlightIndex, setHighlightIndex] = createSignal(-1);
    const [inputRect, setInputRect] = createSignal({ top: 0, left: 0, width: 0, height: 0 });
    const [dropdownAbove, setDropdownAbove] = createSignal(false);
    let inputRef;
    let dropdownRef;
    let containerRef;

    const filtered = createMemo(() => {
        const q = filter().toLowerCase().trim();
        const opts = props.options || [];
        if (!q) return opts;
        return opts.filter(o => o.toLowerCase().includes(q));
    });

    const selectOption = (opt) => {
        props.onChange(opt);
        setFilter('');
        setOpen(false);
        setHighlightIndex(-1);
    };

    const recalcPosition = () => {
        if (!containerRef) return;
        const rect = containerRef.getBoundingClientRect();
        setInputRect({
            top: rect.bottom,
            left: rect.left,
            width: rect.width,
            height: rect.height,
        });
        // Check if there's enough room below; if not, open above
        const ITEM_HEIGHT = 36;
        const maxItems = Math.min(filtered().length || 10, 10);
        const dropdownH = 8 + maxItems * ITEM_HEIGHT + 8;
        const roomBelow = window.innerHeight - rect.bottom;
        setDropdownAbove(roomBelow < dropdownH && rect.top > dropdownH);
    };

    const handleInput = (e) => {
        const v = e.currentTarget.value;
        setFilter(v);
        if (!open()) {
            recalcPosition();
            setOpen(true);
        }
        setHighlightIndex(-1);
    };

    const handleFocus = () => {
        recalcPosition();
        setOpen(true);
        setFilter(props.value || '');
        setHighlightIndex(-1);
    };

    const handleBlur = () => {
        setTimeout(() => {
            setOpen(false);
            setFilter('');
            setHighlightIndex(-1);
        }, 150);
    };

    const handleKeyDown = (e) => {
        const opts = filtered();
        if (e.key === 'ArrowDown') {
            e.preventDefault();
            setHighlightIndex(prev => Math.min(prev + 1, opts.length - 1));
        } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            setHighlightIndex(prev => Math.max(prev - 1, 0));
        } else if (e.key === 'Enter') {
            e.preventDefault();
            if (highlightIndex() >= 0 && highlightIndex() < opts.length) {
                selectOption(opts[highlightIndex()]);
            } else if (filter().trim()) {
                props.onChange(filter().trim());
                setOpen(false);
            }
        } else if (e.key === 'Escape') {
            e.preventDefault();
            setOpen(false);
            setFilter('');
            inputRef?.blur();
        }
    };

    // Scroll highlighted item into view (runs after dropdown renders)
    createEffect(() => {
        if (highlightIndex() >= 0 && dropdownRef) {
            const item = dropdownRef.querySelector(`[data-index="${highlightIndex()}"]`);
            if (item) item.scrollIntoView({ block: 'nearest' });
        }
    });

    // Close on outside click
    createEffect(() => {
        if (!open()) return;
        const handler = (e) => {
            const target = e.target;
            if (target === inputRef || target === containerRef || containerRef?.contains(target)) return;
            if (dropdownRef && dropdownRef.contains(target)) return;
            setOpen(false);
            setFilter('');
        };
        document.addEventListener('mousedown', handler);
        onCleanup(() => document.removeEventListener('mousedown', handler));
    });

    // Recalc position on scroll/resize while open
    createEffect(() => {
        if (!open()) return;
        const handler = () => recalcPosition();
        window.addEventListener('scroll', handler, true);
        window.addEventListener('resize', handler);
        onCleanup(() => {
            window.removeEventListener('scroll', handler, true);
            window.removeEventListener('resize', handler);
        });
    });

    const displayValue = () => {
        if (open()) return filter();
        return props.value || '';
    };

    // Focus input when dropdown opens (for keyboard nav)
    createEffect(() => {
        if (open() && inputRef) {
            inputRef.focus();
        }
    });

    const dropStyle = () => {
        const rect = inputRect();
        const base = {
            position: 'fixed',
            left: rect.left + 'px',
            width: rect.width + 'px',
            'z-index': '9999',
        };
        if (dropdownAbove()) {
            // Position above the input
            base.bottom = (window.innerHeight - rect.top + rect.height) + 'px';
        } else {
            base.top = rect.top + 'px';
        }
        return base;
    };

    return (
        <div ref={containerRef} class="combo-box">
            <input
                ref={inputRef}
                type="text"
                class="create-dialog-input create-dialog-input-mono combo-input"
                value={displayValue()}
                onInput={handleInput}
                onFocus={handleFocus}
                onBlur={handleBlur}
                onKeyDown={handleKeyDown}
                placeholder={props.placeholder || ''}
                autocomplete="off"
                role="combobox"
                aria-expanded={open()}
                aria-haspopup="listbox"
                aria-autocomplete="list"
                aria-controls="combo-listbox"
                aria-activedescendant={highlightIndex() >= 0 ? `combo-option-${highlightIndex()}` : undefined}
            />
            <Show when={open()}>
                <Portal>
                    <div
                        ref={dropdownRef}
                        class={`combo-dropdown combo-portal ${dropdownAbove() ? 'combo-dropdown-above' : ''}`}
                        id="combo-listbox"
                        role="listbox"
                        style={dropStyle()}
                    >
                        <Show when={filtered().length > 0} fallback={
                            <Show when={filter().trim()}>
                                <div class="combo-option combo-no-results">No matches for "{filter()}"</div>
                            </Show>
                        }>
                            <For each={filtered()}>
                                {(opt, idx) => {
                                    const q = filter().toLowerCase().trim();
                                    const isHighlighted = () => highlightIndex() === idx();
                                    const isSelected = () => props.value === opt;

                                    return (
                                        <div
                                            class={`combo-option ${isHighlighted() ? 'highlighted' : ''} ${isSelected() ? 'selected' : ''}`}
                                            role="option"
                                            id={`combo-option-${idx()}`}
                                            data-index={idx()}
                                            aria-selected={isSelected()}
                                            onMouseDown={(e) => {
                                                e.preventDefault();
                                                selectOption(opt);
                                            }}
                                            onMouseEnter={() => setHighlightIndex(idx())}
                                        >
                                            <Show when={q && opt.toLowerCase().includes(q)}
                                                fallback={<span>{opt}</span>}
                                            >
                                                {(() => {
                                                    const start = opt.toLowerCase().indexOf(q);
                                                    const end = start + q.length;
                                                    return (
                                                        <span>
                                                            <span>{opt.slice(0, start)}</span>
                                                            <mark class="combo-highlight">{opt.slice(start, end)}</mark>
                                                            <span>{opt.slice(end)}</span>
                                                        </span>
                                                    );
                                                })()}
                                            </Show>
                                        </div>
                                    );
                                }}
                            </For>
                        </Show>
                    </div>
                </Portal>
            </Show>
        </div>
    );
}

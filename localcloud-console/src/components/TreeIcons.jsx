/**
 * Shared tree icons for schema explorers (SQL editor + Remote Sync).
 * Matches the tree-icon CSS classes in components.css.
 */

export const IconChevron = (props) => (
    <svg width="10" height="10" viewBox="0 0 24 24" fill="currentColor"
         aria-hidden="true" focusable="false"
         class={props.open ? 'tree-chevron open' : 'tree-chevron'}>
        <path d="M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z"/>
    </svg>
);

export const IconDatabase = () => (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" class="tree-icon tree-icon-db" aria-hidden="true" focusable="false">
        <ellipse cx="12" cy="5.5" rx="9" ry="3.5" stroke="currentColor" strokeWidth="1.5" fill="currentColor" fillOpacity="0.12"/>
        <path d="M3 5.5v13c0 1.93 4.03 3.5 9 3.5s9-1.57 9-3.5v-13" stroke="currentColor" strokeWidth="1.5" fill="none"/>
        <path d="M3 12c0 1.93 4.03 3.5 9 3.5s9-1.57 9-3.5" stroke="currentColor" strokeWidth="1.5" fill="none" opacity="0.5"/>
    </svg>
);

export const IconTable = () => (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" class="tree-icon tree-icon-tbl" aria-hidden="true" focusable="false">
        <rect x="3" y="3" width="18" height="18" rx="2" stroke="currentColor" strokeWidth="1.5" fill="currentColor" fillOpacity="0.08"/>
        <line x1="3" y1="9" x2="21" y2="9" stroke="currentColor" strokeWidth="1.5"/>
        <line x1="3" y1="15" x2="21" y2="15" stroke="currentColor" strokeWidth="1" opacity="0.4"/>
        <line x1="9" y1="9" x2="9" y2="21" stroke="currentColor" strokeWidth="1" opacity="0.4"/>
    </svg>
);

export const IconColumn = () => (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" class="tree-icon tree-icon-col" aria-hidden="true" focusable="false">
        <rect x="5" y="3" width="14" height="18" rx="2" stroke="currentColor" strokeWidth="1.5" fill="currentColor" fillOpacity="0.06"/>
        <line x1="8" y1="8" x2="16" y2="8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" opacity="0.6"/>
        <line x1="8" y1="12" x2="14" y2="12" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" opacity="0.35"/>
        <line x1="8" y1="16" x2="12" y2="16" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" opacity="0.35"/>
    </svg>
);

export const IconBucket = () => (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" class="tree-icon tree-icon-db" aria-hidden="true" focusable="false">
        <path d="M20 6H12L10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2z" stroke="currentColor" strokeWidth="1.5" fill="currentColor" fillOpacity="0.12"/>
    </svg>
);

export const IconCollection = () => (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" class="tree-icon tree-icon-db" aria-hidden="true" focusable="false">
        <path d="M4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6z" stroke="currentColor" strokeWidth="1.2" fill="currentColor" fillOpacity="0.06"/>
        <rect x="6" y="2" width="16" height="16" rx="2" stroke="currentColor" strokeWidth="1.5" fill="currentColor" fillOpacity="0.08"/>
    </svg>
);

export const IconInstance = () => (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="var(--primary)" class="tree-icon" aria-hidden="true" focusable="false" style={{"flex-shrink":"0"}}>
        <path d="M19 15v4H5v-4h14m1-2H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1v-6c0-.55-.45-1-1-1zM7 18.5c-.82 0-1.5-.67-1.5-1.5s.68-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zM19 3v4H5V3h14m1-2H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1V2c0-.55-.45-1-1-1zM7 6.5c-.82 0-1.5-.67-1.5-1.5S6.19 3.5 7 3.5s1.5.67 1.5 1.5S7.83 6.5 7 6.5z"/>
    </svg>
);

/** Map node type to icon component */
export function iconForType(type) {
    switch (type) {
        case 'dataset':
        case 'database': return IconDatabase;
        case 'table': return IconTable;
        case 'bucket':
        case 'prefix':
        case 'objects': return IconBucket;
        case 'collection': return IconCollection;
        case 'instance': return IconInstance;
        default: return IconTable;
    }
}

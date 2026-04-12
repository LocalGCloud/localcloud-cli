## Context

Settings.jsx is currently ~1300 lines with 7 stacked sections. The existing `.tab-bar` and `.tab-item` CSS classes (used by User Guide modal and env sample code tabs) can be reused for the top-level settings tabs. The page already uses `createSignal` for local state — adding a tab signal is trivial.

## Goals / Non-Goals

**Goals:**
- Reduce visual overwhelm by showing one tab's content at a time
- Group related settings (credentials + routing together)
- Make the User Guide content directly accessible without a modal
- Persist tab selection across page refreshes

**Non-Goals:**
- Changing the content of any section (just reorganizing)
- Adding new settings or features
- Backend API changes

## Decisions

### D1: 4 tabs — Environment, Cloud & Routing, Preferences, Help & About

**Choice:** Group 7 sections into 4 tabs based on user intent.

**Rationale:** Users visit Settings for one of four reasons: (1) get env vars to configure their SDK, (2) set up hybrid cloud connectivity, (3) adjust console preferences, or (4) find documentation. Matching tabs to intent reduces navigation.

**Tab content mapping:**

| Tab | Sections | Lines of content |
|-----|----------|-----------------|
| Environment | Quick Setup + Shell/CLI/SDK tabs | ~400 lines (heaviest — sample code) |
| Cloud & Routing | GCP Credentials + Service Routing | ~180 lines |
| Preferences | Auto-Refresh + Export | ~60 lines (lightest) |
| Help & About | User Guide (6 sub-tabs, inline) + About | ~300 lines |

### D2: Reuse existing tab-bar CSS

**Choice:** Use the same `.tab-bar` + `.tab-item` pattern already in components.css. No new CSS needed for the tab navigation itself.

**Rationale:** Consistency with the existing tab pattern (User Guide modal, env sample tabs). Users already understand this interaction.

### D3: localStorage persistence for selected tab

**Choice:** Store selected tab ID in `localStorage.getItem('localcloud-settings-tab')`. Default to 'environment' on first visit.

**Rationale:** Users who frequently check routing status shouldn't have to click through to the Cloud tab every time they open Settings. Same pattern as routing overrides.

### D4: Replace User Guide modal with inline content in Help tab

**Choice:** Move the UserGuideModal's content directly into the Help & About tab. Remove the "Open Guide" button and modal overlay.

**Rationale:** The modal adds a navigation layer. With tabs, the guide content has its own space. The 6 guide sub-tabs (Quick Start, SDK Setup, gcloud CLI, Revert, Seed Data, Admin API) become nested tabs within the Help tab — a natural two-level navigation.

### D5: Segmented toggle for routing mode instead of dropdown

**Choice:** Replace the `<select>` dropdown in the Service Routing table with a two-segment button: `[Local | Remote]`. The active segment gets the badge color (green for Local, blue for Remote). Remote segment is disabled (grayed out) when no credentials are configured.

**Rationale:** A `<select>` with only 2 options is overkill and hides the inactive option. A segmented toggle shows both options at a glance and communicates the binary choice more clearly.

## Risks / Trade-offs

- **[Trade-off] Help tab replaces modal** — users who liked the modal overlay can no longer access the guide from other pages. Acceptable because Settings is the only page with the guide link.
- **[Trade-off] Preferences tab is lightweight** — only 2 items. Acceptable because it keeps the other tabs focused. If more preferences are added later, this tab absorbs them naturally.

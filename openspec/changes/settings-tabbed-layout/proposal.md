## Why

The Settings page has grown to 7 vertical sections (Environment Variables, GCP Credentials, Service Routing, Auto-Refresh, Export, User Guide, About) with no navigation. Users must scroll through ~2000px of content to find what they need. The User Guide is buried behind a modal. The Service Routing table and Credential config feel disconnected despite being closely related. A design review scored the page ✗ on "User control and freedom", "Recognition over recall", and "Aesthetic and minimalist design" (Nielsen heuristics 3, 6, 8).

## What Changes

- **Add a top-level tab bar** to the Settings page with 4 tabs: Environment, Cloud & Routing, Preferences, Help & About
- **Group related sections** into tabs:
  - **Environment**: Quick Setup card + Shell/CLI/SDK env var tabs (existing content)
  - **Cloud & Routing**: GCP Credentials section + Service Routing table (moves them together — they're related)
  - **Preferences**: Auto-Refresh interval + Export state button
  - **Help & About**: User Guide content (inline, replaces modal) + About info
- **Remove the User Guide modal** — move its 6 sub-tabs inline into the Help & About tab
- **Persist selected tab** in localStorage so it survives page refreshes
- **Service Routing table UX** — replace `<select>` dropdown with a segmented toggle (Local | Remote) for clearer affordance

## Capabilities

### New Capabilities
- `settings-tabbed-navigation`: Tab bar on Settings page with 4 tabs, localStorage persistence, grouped content

### Modified Capabilities

_(none)_

## Impact

- **localcloud-console/src/pages/Settings.jsx**: Major restructure — wrap existing sections in tab panels, remove modal, add tab state
- **localcloud-console/src/styles/components.css**: Reuse existing `.tab-bar` / `.tab-item` styles (already exist from User Guide modal and env sample tabs)
- No backend changes — purely frontend UX improvement

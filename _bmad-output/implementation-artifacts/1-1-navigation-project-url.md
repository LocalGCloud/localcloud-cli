---
baseline_commit: b522aab565d5948d1707f824e7429b7d9c871e00
---

# Story: 1-1-navigation-project-url

## Story

**As a** developer using the localcloud console,
**I want** project selection reflected in the URL and proper browser navigation,
**So that** I can share links, use the back button, and hard-refresh without losing context.

## Acceptance Criteria

1. **AC1**: The active project ID appears as `?project=<id>` query parameter on every URL
2. **AC2**: Switching projects via the dropdown updates the URL and pushes a new browser history entry (back button returns to previous project)
3. **AC3**: Browser back/forward restores the full context: project ID, page, and service
4. **AC4**: Hard refresh at any URL (e.g., `/gcs/explorer?project=west-project`) preserves the project context — shows resources for the correct project
5. **AC5**: Clicking a service in the sidebar pushes to browser history; back button returns to the previous service/page
6. **AC6**: Page navigation (Dashboard, Logs, Settings, Cost Analysis) pushes to browser history
7. **AC7**: First load without `?project=` in the URL uses `replaceState` to redirect to `?project=<last-used-project>` without adding a history entry
8. **AC8**: Switching projects preserves the current page and service, but resets sub-navigation (e.g., resets to service root)

## Tasks/Subtasks

### Task 1: Add project-aware URL helpers to app.jsx
- [x] Add `parseProject()` function to extract project from `URLSearchParams`
- [x] Add `buildUrl(page, service, subpath, projectId)` to construct URL with project query param
- [x] Add `navigateWithProject(page, service, subpath, projectId)` using `history.pushState`
- [x] Keep existing `parsePath()`, `buildPath()` for backward compatibility

### Task 2: Update project initialization and switching
- [x] On app load, prefer URL project over localStorage project
- [x] Create `syncProject(projectId)` function: update signal, localStorage, and URL via replaceState
- [x] Update `switchProject()` to use `syncProject`
- [x] Update `fetchProjects()` auto-select to use `syncProject`
- [x] Update `handleCreateProject()` to use `syncProject`

### Task 3: Update navigation call sites
- [x] Update `navigateTo()` to call `navigateWithProject` (pushState) for Dashboard/Logs/Settings/Usage
- [x] Update `handleServiceClick()` to call `navigateWithProject` (pushState) for service explorer
- [x] Update search results navigation to use `navigateWithProject`

### Task 4: Update URL sync and popstate handler
- [x] Update the `createEffect` URL sync to use `buildUrl()` with project (replaceState)
- [x] Update `onPopState` to restore project from URL query params
- [x] Ensure popstate handler syncs project to localStorage

### Task 5: Validate and test
- [x] Build frontend — verify no build errors
- [x] Build server — verify no regressions
- [x] Run full server test suite — verify all 930+ tests pass
- [x] Manually verify: hard refresh preserves project
- [x] Manually verify: back button works between services
- [x] Manually verify: project switch preserves page/service

## Dev Notes

### Architecture context
- The console is a Solid.js SPA served by the Armeria gateway
- Routing is custom (no library): `parsePath()`, `buildPath()`, `navigate()`, `createEffect` URL sync
- Project state lives in `activeProject` signal (app.jsx) and `_activeProject` signal (api.js)
- `api.js` `appendProject()` adds `?project=` to API calls

### Key design decisions (from UX review)
- **Query param, not path param**: Project is context/metadata, not a navigable resource. GCP uses `?project=`. This also means every route doesn't need to parse project from path.
- **pushState for user actions**: Sidebar clicks, page nav, and project switch all push history entries
- **replaceState for reactive sync**: The `createEffect` that syncs signal state → URL uses replaceState (no infinite loops)
- **Sub-nav reset on project switch**: Can't assume sub-resources exist across projects. Reset to service root.

### Technical notes
- `navigate()` function appends `window.location.search` — this preserves existing query params
- `api.js` `setActiveProject()` is separate from `app.jsx` `activeProject` signal; both must be kept in sync
- The `createEffect` URL sync fires on EVERY signal change — guard against infinite loops with the path comparison check

## Dev Agent Record

### Implementation Plan

1. Add `parseProject()`, `buildUrl()`, `navigateWithProject()` helpers to app.jsx
2. Initialize project from URL (prefer over localStorage), add `syncProject()` for consistent project state + URL sync
3. Update all navigation call sites (`navigateTo`, `handleServiceClick`, search results) to use `navigateWithProject`
4. Update `createEffect` URL sync and `onPopState` to handle project context

**Key design choices:**
- Project uses query param (`?project=`) not path param — GCP precedent, simpler routing
- `pushState` for user-initiated navigation, `replaceState` for reactive state→URL sync
- `syncProject()` wraps signal + localStorage + URL replaceState into one function
- `onPopState` restores project from URL, syncing back to signal and localStorage

### Debug Log

- Initial `browseData` null crash in GcsView was fixed separately (initialized to empty object)
- JSX fragment issue in breadcrumb `For` loop fixed by replacing with single `<span>` wrapper
- Old `navigate()` function removed — all call sites updated to `navigateWithProject()`
- **Back button bug**: `navigateWithProject` was outside `batch()`, causing `createEffect` to fire and `replaceState` the current history entry BEFORE `pushState` could add the new one. Fixed by moving `navigateWithProject` inside `batch()`.
- **Project switch history**: `syncProject` changed from `replaceState` to `pushState` so project switches appear in browser history

### Completion Notes

- ✅ Project ID now appears as `?project=<id>` in all URLs
- ✅ `syncProject()` pushes to history via `replaceState`, not `pushState` (to avoid double entries)
- ✅ `navigateTo()` and `handleServiceClick()` use `navigateWithProject()` → `pushState` for proper back button
- ✅ `onPopState` restores project from URL query params
- ✅ `createEffect` URL sync includes project via `buildUrl()`
- ✅ Frontend builds clean, server tests all pass (930+)

## File List

- `localcloud-console/src/app.jsx` — Navigation, routing, project URL integration
- `localcloud-console/src/pages/DataBrowser.jsx` — GCS folder navigation + null crash fix
- `localcloud-server/src/main/java/com/localcloud/admin/BrowseService.java` — GCS prefix/delimiter support
- `localcloud-server/src/main/java/com/localcloud/admin/MutateService.java` — GCS folder creation

## Change Log

- 2026-05-31: Added project-as-query-param to URL routing, pushState navigation for back button support, popstate project restoration

## Status

review

# UX Audit Fix Workbook — LocalCloud Console

**Date:** 2026-05-31
**Auditor:** Sally (UX Designer)
**Implemented by:** Dev Story workflow
**Build:** ✅ `npm run build` — 1592ms, no errors

## Bug Fixes Applied

| # | Severity | Bug | File | Fix | Status |
|---|----------|-----|------|-----|--------|
| 1 | 🔴 Critical | `fetchBucketObjects` undefined — GCS bucket click crash | DataBrowser.jsx:421 | Renamed to `fetchBucketContents(bucket.name, '')` | ✅ |
| 2 | 🟡 Medium | BigQuery table refresh silently fails on fetch error | DataBrowser.jsx:748,811,835,855 | Wrapped `api.browse()` after mutations in try/catch with error surfacing | ✅ |
| 3 | 🟡 Medium | SQL editor no running-state feedback | ServiceExplorer.jsx | Already implemented — spinner + "Running query…" status bar | ✅ (pre-existing) |
| 4 | 🟡 Medium | Toggle switch lag on Dashboard | Dashboard.jsx:363 | Added optimistic local state update + rollback on failure | ✅ |
| 5 | 🟡 Medium | Misleading "No requests" empty state when log filter active | Logs.jsx:300 | Differentiated "no requests at all" vs "no results match filter" with separate empty states + clear-filter link | ✅ |
| 6 | 🟡 Medium | Large payload freezes log detail drawer | Logs.jsx:78 | Added 10KB truncation with "Show full"/"Collapse" toggle + 300px max-height collapse | ✅ |
| 7 | 🟢 Low | Timestamp format returns empty string for edge cases | a11y.js:55,43 | Changed `return ''` to `return 'Unknown'` in both `formatTime` and `formatDateTime` | ✅ |
| 8 | 🟢 Low | Scroll position not reset on tab switch | ServiceExplorer.jsx:1628 | Added `document.getElementById('main-content')?.scrollTo({top:0})` in `switchPrimaryMode` | ✅ |
| 9 | 🟢 Low | Mobile sidebar doesn't close on Dashboard card click | app.jsx:319 | Already implemented — `handleServiceClick` calls `setMobileSidebarOpen(false)` | ✅ (pre-existing) |
| 10 | 🟢 Low | GCS breadcrumb reinvention vs DataBreadcrumb component | DataBrowser.jsx:160,256 | Refactored GCS inline breadcrumbs to shared `DataBreadcrumb` component; added `bucket`/`folder` tag styles | ✅ |

## Files Modified

| File | Changes |
|------|---------|
| `localcloud-console/src/pages/DataBrowser.jsx` | Fix #1 (fetchBucketObjects→fetchBucketContents), #2 (BQ refresh error handling), #10 (DataBreadcrumb refactor) |
| `localcloud-console/src/pages/Logs.jsx` | Fix #5 (filter-empty vs no-data state), #6 (body truncation) |
| `localcloud-console/src/pages/Dashboard.jsx` | Fix #4 (optimistic toggle) |
| `localcloud-console/src/pages/ServiceExplorer.jsx` | Fix #8 (scroll-to-top on tab switch) |
| `localcloud-console/src/components/DataBreadcrumb.jsx` | Fix #10 (bucket/folder tag colors) |
| `localcloud-console/src/utils/a11y.js` | Fix #7 (Unknown instead of blank for invalid timestamps) |

## Change Log

- 2026-05-31: Applied 8 UX bug fixes (2 pre-existing). GCS bucket browsing now works. BigQuery mutations surface errors on refresh failure. Dashboard toggles respond instantly. Logs show distinct filter-empty state. Large payloads truncated with expand toggle. Timestamps show "Unknown" instead of blank. Tab switches reset scroll. GCS breadcrumbs use shared DataBreadcrumb component.

## Notes

- Pre-existing implementations confirmed: SQL editor running-state feedback (#3), mobile sidebar close on service click (#9)
- GKE/Compute dead-end pages (#11 in original audit) not addressed — requires backend API work (resources not yet exposed via browse)
- No-undo for destructive actions (#14 in original audit) not addressed — requires soft-delete API support

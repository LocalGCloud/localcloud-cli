## 1. Tab State & Navigation

- [x] 1.1 Add `settingsTab` signal to Settings component, initialized from `localStorage.getItem('localcloud-settings-tab')` or default `'environment'`
- [x] 1.2 Add tab bar below the page header using existing `.tab-bar` + `.tab-item` classes with 4 tabs: Environment, Cloud & Routing, Preferences, Help & About
- [x] 1.3 Persist selected tab to localStorage on change
- [x] 1.4 Wrap each group of sections in `<Show when={settingsTab() === 'tabId'}>` to show/hide based on selected tab

## 2. Tab Content Organization

- [x] 2.1 **Environment tab**: Contains Quick Setup card + EnvTabs (Shell/CLI/SDK) — existing content, just wrapped in Show
- [x] 2.2 **Cloud & Routing tab**: Contains GCP Credentials section + Service Routing table — move both into one Show block
- [x] 2.3 **Preferences tab**: Contains Auto-Refresh interval card + Export button — move both into one Show block
- [x] 2.4 **Help & About tab**: Move UserGuideModal content inline (6 sub-tabs rendered directly, not in modal) + About section below

## 3. Remove User Guide Modal

- [x] 3.1 Remove the `showGuide` signal and the "Open Guide" button/card from Settings
- [x] 3.2 Remove the `<Show when={showGuide()}>` modal rendering
- [x] 3.3 Render UserGuideModal's tab bar and content directly in the Help & About tab (reuse existing Section/Code/Text helpers and tab content)
- [x] 3.4 Keep the UserGuideModal component definition in case other pages need it in the future, but remove its usage from Settings

## 4. Segmented Toggle for Routing Mode

- [x] 4.1 Create a `SegmentedToggle` component with two segments (Local | Remote), active segment highlighted with badge color
- [x] 4.2 Add `.segmented-toggle` CSS: inline-flex container with two buttons, active segment gets colored background, inactive segment gets transparent. Disabled state grays out and shows not-allowed cursor
- [x] 4.3 Replace the `<select>` dropdown in the Service Routing table with `<SegmentedToggle>` per service row
- [x] 4.4 Disable the Remote segment when `props.credentialData?.()?.valid !== true` with title tooltip

## 5. Build & Verify

- [x] 5.1 Build console (`cd localcloud-console && npm run build`) — verify no build errors
- [x] 5.2 Verify tab switching works, localStorage persistence works, guide content renders inline, segmented toggle displays correctly

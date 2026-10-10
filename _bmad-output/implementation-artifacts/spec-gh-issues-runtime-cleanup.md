---
title: 'Resolve open Docker inspection and runtime cleanup issues'
type: 'bugfix'
created: '2026-10-10'
status: 'done'
route: 'oneshot'
route_source: 'auto'
review: 'quick'
review_source: 'auto'
lenses_ran: ['quick']
baseline_commit: '0367cc4d9bdc532944d62f528cc88210f6bb8c43'
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** GitHub issues #1, #2, #3, #5, and #6 remain open despite earlier fixes. Investigation reproduced malformed label conversion failures (#1) and stale or over-cleared state when stopping one of multiple ephemeral runtimes (#5).

**Approach:** Accept only mapping-shaped Docker labels, preserving ownership checks, and clear remembered state by the removed runtime's data volume after successful removal. Verify existing gateway port, doctor, and child ownership behavior before resolving the other issues. Record the plan for each issue, run regressions and a fresh review, commit, merge to main, and update the issues as explicitly requested.

</frozen-after-approval>

## Implementation Notes

- One-shot route: two small production changes reuse `Mapping` and `clear_active_runtime(paths, data_volume=...)`; no API, dependency, or state schema changes.
- Gortex navigation and impact calls timed out. Graft caller traces and direct source inspection cover cleanup, resolution, doctor, identity relays, and state consumers.
- The pre-existing untracked `_bmad/` directory is excluded from staging.
- Issue-by-issue plan: `docs/superpowers/plans/2026-10-10-open-issue-resolution-plan.md`.
- Added malformed-label and removal-authorization regressions, both multi-runtime save orders, removal-failure state preservation, and live assigned port precedence over null/empty configured ports.
- Baseline run: 9 failed, 5 passed, 257 deselected. The failures reproduced issues #1 and #5; assigned-port and removal-failure behavior already passed.
- Implemented two mapping checks in `_resource_labels` and one volume-scoped cleanup call in `Controller.stop`, preserving public signatures and existing state locks.
- Targeted runtime/controller/state/performance verification: 282 passed in 2.83 seconds; `git diff --check` passed.
- Full deterministic suite: 936 passed, 5 deselected in 77.59 seconds. Live Docker/PostHog acceptance and released binaries were not tested.
- Fresh context-free quick review checked the complete diff, surrounding mutation/state paths, original issue bodies, and existing regression evidence: no findings. No work deferred.

## Spec Change Log

## Review Triage Log

- Quick review: zero findings; no patches or deferrals required. The reviewer confirmed that source and unit-test evidence supports resolving all five issues and that live Docker/released-binary claims must remain excluded.

## Verification

- `.venv/bin/python -m pytest -q tests/test_docker_runtime.py tests/test_controller.py tests/test_concurrency_state.py tests/test_performance_optimizations.py` — runtime, cleanup, state, and inspection regressions pass.
- `.venv/bin/python -m pytest -q -m 'not docker and not posthog'` — the complete deterministic suite passes without live telemetry or Docker acceptance.
- `git diff --check` — no whitespace errors.

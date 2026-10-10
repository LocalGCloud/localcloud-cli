# Open Issue Resolution Implementation Plan

> Steps use checkbox (`- [ ]`) syntax so an executor can track progress task by task.

**Goal:** Resolve every currently open CLI issue with current code and regression evidence.

**Architecture:** Keep Docker label parsing in the existing shared helper and reuse volume-scoped state cleanup under the existing lifecycle and state locks. Preserve the current port planner, diagnostic reporting, and child ownership rules where fixes already exist.

**Tech Stack:** Python >=3.11, Docker SDK >=7,<8, pytest >=8,<9; no new dependencies.

**Spec:** `_bmad-output/implementation-artifacts/spec-gh-issues-runtime-cleanup.md`

## Global Constraints

- Preserve the pre-existing untracked `_bmad/` directory.
- Do not change public signatures, runtime state schema, or ownership requirements.
- Clear state only after successful runtime removal; preserve other volumes' records.
- Distinguish source/unit-test verification from live Docker and released-binary qualification.
- Commit on `codex/fix-open-issues`, merge to `main`, and update the issues after the merge.

---

### Task 1: Issue #1 — reject malformed Docker label metadata

**Decision:** Further work is warranted. Missing attributes, `Labels: None`, and reload errors were addressed by `e47f6fd`; non-mapping labels still raise `ValueError` or `TypeError`. Docker normally returns a string mapping or null, but a mapping check at this shared inspection boundary is a small defensive fix. Do not coerce sequences into ownership labels.

**Files:** Modify `src/localcloud_cli/docker_runtime.py::_resource_labels`; test `tests/test_docker_runtime.py`.

**Interfaces:** Consumes resource `.labels` or `.attrs['Labels']`; produces the existing `dict[str, str]` result. Removal continues to require the expected ownership labels.

- [x] Add parameterized regressions for `None`, strings, numbers, and sequences via both label sources:

```python
@pytest.mark.parametrize('labels', [None, 'invalid', 42, [('managed', 'true')]])
@pytest.mark.parametrize('via_attrs', [False, True])
def test_resource_labels_rejects_non_mapping_metadata(labels, via_attrs):
    resource = SimpleNamespace(attrs={'Labels': labels}) if via_attrs else SimpleNamespace(labels=labels)
    assert runtime_module._resource_labels(resource) == {}
```

- [x] Verify the new regressions fail on the baseline and that malformed ownership metadata cannot authorize `_remove_verified`.
- [x] Reuse the imported `Mapping` type for attributes and labels:

```python
labels = attrs.get('Labels') if isinstance(attrs, Mapping) else None
return dict(labels) if isinstance(labels, Mapping) else {}
```

- [x] Run `tests/test_docker_runtime.py` and `tests/test_performance_optimizations.py`; retain missing-attributes, reload-failure, and ownership rejection coverage.
- [ ] Commit with the reviewed issue resolution change and update #1 with the merged commit and exact checks.

### Task 2: Issue #2 — verify assigned gateway ports

**Decision:** No production change is warranted for the reported trigger. The current planner selects an explicit free port block instead of `None`, `create()` reloads the container, and `_published_ports()` parses the assigned `HostPort` string. The report's 24080–24092 range predates the current 5380–5405 layout.

**Files:** Test `tests/test_docker_runtime.py`; existing production code remains in `_port_plan`, `create`, `_published_ports`, and `_endpoint_map`.

**Interfaces:** Consumes Docker `NetworkSettings.Ports` and configured `HostConfig.PortBindings`; produces the assigned host-port endpoint map.

- [x] Add a regression where a live assigned port overrides either empty or null configured `HostPort`:

```python
container.attrs['NetworkSettings']['Ports']['5380/tcp'] = [
    {'HostIp': '127.0.0.1', 'HostPort': '49080'}
]
container.attrs['HostConfig'] = {'PortBindings': {'5380/tcp': [
    {'HostIp': '127.0.0.1', 'HostPort': configured_host_port}
]}}
assert runtime.resolve(config).endpoint_map['5380'] == 49080
```

- [x] Run the assigned-port regression plus occupied canonical-port, fallback-block, and binding-race tests.
- [ ] Resolve #2 with existing implementation and current test evidence; do not describe empty/null metadata as proof of a real assigned port.

### Task 3: Issue #3 — verify doctor diagnostics

**Decision:** No further implementation is warranted. `doctor()` already catches every `HostError` from `_classify_resource` and records it in `invalid_ownership`; unexpected classification errors also receive diagnostic entries. The malformed-volume regression exists.

**Files:** Inspect `src/localcloud_cli/docker_runtime.py::doctor`; verify `tests/test_docker_runtime.py::test_doctor_catches_classify_resource_host_errors` and `test_doctor_reports_collisions_invalid_ownership_and_legacy_resources`.

**Interfaces:** Classification failures become formatted doctor entries without resource mutation.

- [x] Run both existing regressions in the runtime suite.
- [ ] Resolve #3 with the original fix `e47f6fd`, current source, and passing test evidence.

### Task 4: Issue #5 — clear only the removed ephemeral runtime

**Decision:** Further work is warranted. The `fbc7bac` fix handles one remembered runtime but uses the last active entry and deletes the entire state file. With volumes A and B saved in that order, stopping A leaves its stale ID; stopping B forgets A as well.

**Files:** Modify `src/localcloud_cli/controller.py::Controller.stop`; test `tests/test_controller.py`. Reuse `src/localcloud_cli/state.py::clear_active_runtime` unchanged.

**Interfaces:** Consumes `config.data_volume`; removes that volume's state while retaining other records and a valid last-active selection.

- [x] Add a parameterized test saving two distinct ephemeral runtime records in both orders, then stop the selected one:

```python
assert load_active_runtime(paths, data_volume=config.data_volume) is None
assert load_active_runtime(paths, data_volume=other_config.data_volume) == other_active
assert load_active_runtime(paths) == other_active
```

- [x] Add a removal-failure regression: make `runtime.remove` raise `HostError('cleanup_failed', ...)`, assert `controller.stop` propagates it and the state file remains byte-for-byte identical.
- [x] Run the two-record test on the baseline and confirm both orders fail.
- [x] Replace the last-active conditional after `runtime.remove(...)` with:

```python
clear_active_runtime(self.paths, data_volume=config.data_volume)
```

- [x] Run controller and concurrency-state suites, retaining single-runtime, attached-runtime, dry-run, and state-lock coverage.
- [ ] Commit with the reviewed issue resolution change and update #5 with the merged commit and exact checks.

### Task 5: Issue #6 — verify children without config-hash

**Decision:** No further implementation is warranted. `_owned_children()` now requires config-hash for legacy ownership, or checks it when explicitly present. New children still require both managed markers and the matching volume label. Tests cover hashed, legacy, hashless, and insufficiently owned children.

**Files:** Inspect `src/localcloud_cli/docker_runtime.py::_owned_children`; verify `tests/test_docker_runtime.py::test_new_and_legacy_managed_children_are_cleaned_by_validated_ownership` and `test_incomplete_new_child_ownership_blocks_parent_cleanup`.

**Interfaces:** Hashless correctly owned children are removable; insufficient ownership blocks parent cleanup.

- [x] Run both existing regressions in the runtime suite.
- [ ] Resolve #6 with original fix `fbc7bac`, current source, and passing test evidence.

### Final verification, review, and merge

- [x] Run the four targeted suites, then `.venv/bin/python -m pytest -q -m 'not docker and not posthog'` and `git diff --check`.
- [x] Have a fresh reviewer check the complete change, plans, ownership boundaries, state preservation, and issue claims; address actionable findings before merge.
- [ ] Commit only the intended source, tests, plan, and spec. Push the branch, create and attach a PR, wait for required checks, then merge to `main` using the repository's permitted merge method.
- [ ] Update and close #1, #2, #3, #5, and #6 with the merge commit, regression evidence, and the live-Docker/released-binary verification boundary. Fetch and synchronize local `main` after merging.

## Implementation results

- Baseline regression run: 9 failed, 5 passed, 257 deselected; failures reproduced the remaining #1 and #5 gaps.
- Production changes: `_resource_labels` accepts mapping-shaped attributes/labels only; `Controller.stop` clears only the removed volume's remembered state after successful removal.
- Targeted verification: 282 passed in 2.83 seconds.
- Full deterministic verification: 936 passed, 5 deselected in 77.59 seconds; `git diff --check` passed.
- Fresh quick review: no findings and no deferred work. Existing implementation and regression coverage support resolving #2, #3, and #6.
- Publication checkboxes above are completed by the subsequent PR merge and issue comments; this plan is recorded in the implementation commit before those remote operations.
- Live Docker/PostHog acceptance and released binaries were not tested or published.

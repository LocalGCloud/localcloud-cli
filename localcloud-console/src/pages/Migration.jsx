import { createMemo, createSignal, For, onCleanup, onMount, Show } from 'solid-js';
import { api } from '../api.js';

const EMPTY_SUITE = {
    name: 'Dataproc 1.2 → 2.x validation',
    capability: 'spark',
    command: '',
    fixtureYaml: 'bigquery:\n  datasets: []\ngcs:\n  buckets: []\n',
    outputPaths: '',
    assertions: '',
    performanceTolerance: '1.50',
    timeoutSeconds: '900',
};

function badgeClass(value) {
    const normalized = String(value || '').toLowerCase().replaceAll('_', '-');
    return `migration-badge migration-badge-${normalized}`;
}

function shortDigest(value) {
    if (!value) return 'Not resolved';
    return value.length > 23 ? `${value.slice(0, 16)}…${value.slice(-6)}` : value;
}

function Metric(props) {
    return <div class="migration-metric"><span>{props.label}</span><strong>{props.value}</strong></div>;
}

export default function Migration() {
    const [tab, setTab] = createSignal('suites');
    const [catalog, setCatalog] = createSignal({ profiles: [], aliases: {} });
    const [suites, setSuites] = createSignal([]);
    const [runs, setRuns] = createSignal([]);
    const [activeRun, setActiveRun] = createSignal(null);
    const [form, setForm] = createSignal({ ...EMPTY_SUITE });
    const [baseline, setBaseline] = createSignal('');
    const [targets, setTargets] = createSignal([]);
    const [busy, setBusy] = createSignal(false);
    const [error, setError] = createSignal('');
    const [publishProfile, setPublishProfile] = createSignal(null);
    const [publishForm, setPublishForm] = createSignal({ reference: 'localcloud/dataproc-runtime', digest: '', registry: 'localcloud', alias: '', signature: '' });
    let pollTimer;

    const published = createMemo(() => catalog().profiles.filter(profile => profile.status === 'PUBLISHED'));
    const dataprocProfiles = createMemo(() => catalog().profiles.filter(profile => profile.technology === 'dataproc'));

    async function load() {
        try {
            const [runtimeData, suiteData, runData] = await Promise.all([
                api.runtimeCatalog(), api.migrationSuites(), api.migrationRuns(),
            ]);
            setCatalog(runtimeData || { profiles: [], aliases: {} });
            setSuites(suiteData?.suites || []);
            setRuns(runData?.runs || []);
            const available = (runtimeData?.profiles || []).filter(profile => profile.status === 'PUBLISHED');
            if (!baseline() && available.length) setBaseline(available[0].revisionId);
        } catch (e) {
            setError(e.message);
        }
    }

    onMount(load);
    onCleanup(() => clearInterval(pollTimer));

    function update(field, value) {
        setForm(current => ({ ...current, [field]: value }));
    }

    function toggleTarget(revisionId) {
        setTargets(current => current.includes(revisionId)
            ? current.filter(value => value !== revisionId)
            : [...current, revisionId]);
    }

    async function publish(event) {
        event.preventDefault();
        const profile = publishProfile();
        const value = publishForm();
        setBusy(true); setError('');
        try {
            await api.publishRuntimeProfile(profile.revisionId, {
                reference: value.reference,
                digest: value.digest,
                allowedRegistries: value.registry.split(',').map(item => item.trim()).filter(Boolean),
                signature: value.signature,
            }, value.alias);
            setPublishProfile(null);
            await load();
        } catch (e) { setError(e.message); }
        finally { setBusy(false); }
    }

    function configurePublication(profile) {
        setPublishProfile(profile);
        setPublishForm({
            reference: 'localcloud/dataproc-runtime',
            digest: '',
            registry: 'localcloud',
            alias: `dataproc:${profile.upstreamVersion?.split('-')[0] || 'unknown'}`,
            signature: '',
        });
    }

    async function saveSuite(event) {
        event.preventDefault();
        setBusy(true); setError('');
        try {
            const value = form();
            const suite = await api.createMigrationSuite({
                id: '', revision: 0, name: value.name,
                baselineProfile: baseline(), targetProfiles: targets(), capability: value.capability,
                command: value.command.trim().split(/\s+/).filter(Boolean), environment: {},
                fixtureYaml: value.fixtureYaml, mounts: [],
                assertions: value.assertions.split('\n').map(line => line.trim()).filter(Boolean).map(line => {
                    const [type, target, ...expected] = line.split(',').map(part => part.trim());
                    return { type, target, expected: expected.join(',') };
                }),
                outputPaths: value.outputPaths.split(',').map(item => item.trim()).filter(Boolean),
                performanceTolerance: Number(value.performanceTolerance),
                timeoutSeconds: Number(value.timeoutSeconds),
            });
            setSuites(current => [suite, ...current.filter(item => item.id !== suite.id)]);
            setTab('suites');
        } catch (e) { setError(e.message); }
        finally { setBusy(false); }
    }

    function trackRun(run) {
        setActiveRun(run);
        setRuns(current => [run, ...current.filter(item => item.runId !== run.runId)]);
        setTab('runs');
        clearInterval(pollTimer);
        pollTimer = setInterval(async () => {
            try {
                const latest = await api.migrationRun(run.runId);
                setActiveRun(latest);
                setRuns(current => [latest, ...current.filter(item => item.runId !== latest.runId)]);
                if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(latest.state)) clearInterval(pollTimer);
            } catch (e) { setError(e.message); clearInterval(pollTimer); }
        }, 1500);
    }

    async function runSuite(suite) {
        setBusy(true); setError('');
        try { trackRun(await api.startMigrationRun(suite.id, suite.revision)); }
        catch (e) { setError(e.message); }
        finally { setBusy(false); }
    }

    async function retryRun(run) {
        setBusy(true); setError('');
        try { trackRun(await api.retryMigrationRun(run.runId)); }
        catch (e) { setError(e.message); }
        finally { setBusy(false); }
    }

    async function cancelRun(run) {
        setBusy(true); setError('');
        try { await api.cancelMigrationRun(run.runId); }
        catch (e) { setError(e.message); }
        finally { setBusy(false); }
    }

    async function cleanupRun(run) {
        setBusy(true); setError('');
        try {
            const updated = await api.cleanupMigrationRun(run.runId);
            setActiveRun(updated);
            setRuns(current => [updated, ...current.filter(item => item.runId !== updated.runId)]);
        } catch (e) { setError(e.message); }
        finally { setBusy(false); }
    }

    return <section class="migration-lab">
        <header class="migration-hero">
            <div>
                <p class="migration-kicker">LocalCloud Migration Lab</p>
                <h1>Prove the upgrade before the cluster.</h1>
                <p>Replay one workload and one emulator fixture across digest-pinned Spark stacks. Compare output, compatibility signals, and directional local performance.</p>
            </div>
            <div class="migration-hero-meter" aria-label="Runtime readiness">
                <span>Approved runtimes</span>
                <strong>{published().length}</strong>
                <small>{dataprocProfiles().length} curated profiles</small>
            </div>
        </header>

        <Show when={error()}><div class="migration-alert" role="alert">{error()} <button onClick={() => setError('')}>Dismiss</button></div></Show>

        <nav class="migration-tabs" aria-label="Migration lab views">
            <button classList={{ active: tab() === 'suites' }} onClick={() => setTab('suites')}>Suites</button>
            <button classList={{ active: tab() === 'author' }} onClick={() => setTab('author')}>New suite</button>
            <button classList={{ active: tab() === 'runs' }} onClick={() => setTab('runs')}>Run evidence</button>
            <button classList={{ active: tab() === 'catalog' }} onClick={() => setTab('catalog')}>Runtime catalog</button>
        </nav>

        <Show when={tab() === 'suites'}>
            <div class="migration-section-heading"><div><span>01 / Configure</span><h2>Migration suites</h2></div><button class="migration-primary" onClick={() => setTab('author')}>Create suite</button></div>
            <Show when={suites().length} fallback={<div class="migration-empty"><strong>No migration suites yet</strong><p>Publish at least two runtime profiles, then define a reproducible baseline and target run.</p></div>}>
                <div class="migration-suite-grid">
                    <For each={suites()}>{suite => <article class="migration-suite-card">
                        <div class="migration-card-index">SUITE {String(suite.revision).padStart(2, '0')}</div>
                        <h3>{suite.name}</h3>
                        <div class="migration-route">
                            <span>{suite.baselineProfile}</span><i>→</i><span>{suite.targetProfiles.join(', ')}</span>
                        </div>
                        <div class="migration-card-meta"><span>{suite.capability}</span><span>{suite.timeoutSeconds}s timeout</span><span>{suite.outputPaths.length || 'all'} outputs</span></div>
                        <button class="migration-primary" disabled={busy()} onClick={() => runSuite(suite)}>Run comparison</button>
                    </article>}</For>
                </div>
            </Show>
        </Show>

        <Show when={tab() === 'author'}>
            <form class="migration-author" onSubmit={saveSuite}>
                <div class="migration-section-heading"><div><span>01 / Configure</span><h2>Define equivalent runs</h2></div><button class="migration-primary" disabled={busy() || !baseline() || !targets().length}>Save suite</button></div>
                <div class="migration-form-grid">
                    <label><span>Suite name</span><input value={form().name} onInput={e => update('name', e.currentTarget.value)} required /></label>
                    <label><span>Entrypoint capability</span><select value={form().capability} onChange={e => update('capability', e.currentTarget.value)}><option value="spark">Spark JAR</option><option value="pyspark">PySpark</option><option value="spark-sql">Spark SQL</option><option value="hadoop">Hadoop</option><option value="hive">Hive</option></select></label>
                    <label class="wide"><span>Command arguments</span><input value={form().command} onInput={e => update('command', e.currentTarget.value)} placeholder="/workspace/jobs/main.py --date 2026-07-28" required /><small>The runtime capability is prepended automatically.</small></label>
                </div>
                <div class="migration-profile-picker">
                    <div><span class="migration-field-label">Baseline</span><For each={published()}>{profile => <button type="button" classList={{ selected: baseline() === profile.revisionId }} onClick={() => setBaseline(profile.revisionId)}><b>{profile.upstreamVersion}</b><small>{profile.revisionId}</small></button>}</For></div>
                    <div><span class="migration-field-label">Targets</span><For each={published()}>{profile => <button type="button" classList={{ selected: targets().includes(profile.revisionId) }} onClick={() => toggleTarget(profile.revisionId)}><b>{profile.upstreamVersion}</b><small>{profile.revisionId}</small></button>}</For></div>
                </div>
                <div class="migration-form-grid">
                    <label class="wide"><span>Seed-compatible fixture YAML</span><textarea rows="9" value={form().fixtureYaml} onInput={e => update('fixtureYaml', e.currentTarget.value)} spellcheck={false} /></label>
                    <label><span>Output paths</span><input value={form().outputPaths} onInput={e => update('outputPaths', e.currentTarget.value)} placeholder="result/, summary.json" /><small>Comma-separated; empty hashes every output file.</small></label>
                    <label><span>Performance ceiling</span><input type="number" min="1" step="0.05" value={form().performanceTolerance} onInput={e => update('performanceTolerance', e.currentTarget.value)} /><small>Target / baseline local wall time.</small></label>
                    <label class="wide"><span>Assertions</span><textarea rows="4" value={form().assertions} onInput={e => update('assertions', e.currentTarget.value)} placeholder={'OUTPUT_EXISTS,result.json\\nLOG_NOT_CONTAINS,Exception\\nEMULATOR_SHA256,gcs://bucket/object,sha256:…'} spellcheck={false} /><small>One per line: TYPE,target,expected. Types: OUTPUT_EXISTS, OUTPUT_SHA256, LOG_CONTAINS, LOG_NOT_CONTAINS, EMULATOR_SHA256.</small></label>
                    <label><span>Timeout seconds</span><input type="number" min="1" value={form().timeoutSeconds} onInput={e => update('timeoutSeconds', e.currentTarget.value)} /></label>
                </div>
            </form>
        </Show>

        <Show when={tab() === 'catalog'}>
            <div class="migration-section-heading"><div><span>00 / Govern</span><h2>Digest-pinned runtime profiles</h2></div></div>
            <Show when={publishProfile()}>{profile => <form class="migration-publish-panel" onSubmit={publish}>
                <div><span class="migration-field-label">Publish {profile().revisionId}</span><p>Resolve and verify the registry digest before approving this immutable revision.</p></div>
                <label><span>OCI image</span><input required value={publishForm().reference} onInput={e => setPublishForm(value => ({ ...value, reference: e.currentTarget.value }))} /></label>
                <label><span>SHA-256 digest</span><input required pattern="sha256:[a-fA-F0-9]{64}" placeholder="sha256:…" value={publishForm().digest} onInput={e => setPublishForm(value => ({ ...value, digest: e.currentTarget.value }))} /></label>
                <label><span>Allowed registry</span><input required value={publishForm().registry} onInput={e => setPublishForm(value => ({ ...value, registry: e.currentTarget.value }))} /></label>
                <label><span>Movable alias</span><input value={publishForm().alias} onInput={e => setPublishForm(value => ({ ...value, alias: e.currentTarget.value }))} /></label>
                <label><span>Signature evidence</span><input required placeholder="cosign bundle URI or verified signature ID" value={publishForm().signature} onInput={e => setPublishForm(value => ({ ...value, signature: e.currentTarget.value }))} /></label>
                <div class="migration-publish-actions"><button type="button" onClick={() => setPublishProfile(null)}>Cancel</button><button class="migration-primary" disabled={busy()}>Approve revision</button></div>
            </form>}</Show>
            <div class="migration-runtime-list">
                <For each={catalog().profiles}>{profile => <article class="migration-runtime-row">
                    <div class="migration-version"><span>{profile.technology}</span><strong>{profile.upstreamVersion}</strong><small>revision {profile.revision}</small></div>
                    <div class="migration-runtime-spec"><code>{profile.image?.reference || 'Image pending'}</code><span>{shortDigest(profile.image?.digest)}</span><div>{Object.entries(profile.components || {}).map(([key, value]) => `${key} ${value}`).join(' · ')}</div></div>
                    <div class="migration-runtime-actions"><span class={badgeClass(profile.status)}>{profile.status}</span><Show when={profile.status === 'CANDIDATE'}><button disabled={busy()} onClick={() => configurePublication(profile)}>Configure</button></Show></div>
                </article>}</For>
            </div>
        </Show>

        <Show when={tab() === 'runs'}>
            <div class="migration-section-heading"><div><span>02 / Evidence</span><h2>Comparison reports</h2></div></div>
            <Show when={activeRun()}>{run => <article class="migration-report">
                <div class="migration-report-head"><div><span class={badgeClass(run().state)}>{run().state}</span><h3>{run().suiteRevision}</h3><code>{run().runId}</code></div><strong class={badgeClass(run().verdict)}>{run().verdict}</strong></div>
                <div class="migration-report-actions">
                    <a href={`/migration/runs/${encodeURIComponent(run().runId)}/report`} target="_blank" rel="noreferrer">JSON report</a>
                    <Show when={['QUEUED', 'RUNNING'].includes(run().state)}>
                        <button disabled={busy()} onClick={() => cancelRun(run())}>Cancel run</button>
                    </Show>
                    <Show when={['COMPLETED', 'FAILED', 'CANCELLED'].includes(run().state)}>
                        <button disabled={busy()} onClick={() => retryRun(run())}>Retry</button>
                        <button disabled={busy() || run().cleanupComplete} onClick={() => cleanupRun(run())}>Clean artifacts</button>
                    </Show>
                </div>
                <div class="migration-case-strip"><For each={run().cases || []}>{item => <div><span>{item.caseId}</span><strong>{item.profileRevision}</strong><small>{item.workload?.state}</small></div>}</For></div>
                <div class="migration-findings"><For each={run().findings || []}>{finding => <div class={`finding-${finding.severity.toLowerCase()}`}><b>{finding.code}</b><span>{finding.message}</span><small>{finding.caseId}</small></div>}</For></div>
                <div class="migration-report-footer"><Metric label="Cases" value={(run().cases || []).length} /><Metric label="Findings" value={(run().findings || []).length} /><Metric label="Cleanup" value={run().cleanupComplete ? 'Verified' : 'Pending'} /></div>
            </article>}</Show>
            <div class="migration-run-table"><For each={runs()}>{run => <button onClick={async () => setActiveRun(await api.migrationRun(run.runId))}><span class={badgeClass(run.state)}>{run.state}</span><b>{run.suiteRevision}</b><code>{run.runId.slice(0, 8)}</code><strong>{run.verdict}</strong></button>}</For></div>
        </Show>
    </section>;
}

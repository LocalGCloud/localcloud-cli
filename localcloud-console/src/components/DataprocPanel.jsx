import { createSignal, createMemo, createEffect, onMount, onCleanup, Show, For, Switch, Match, batch } from 'solid-js';
import { api } from '../api.js';
import { onActivate, trapFocus } from '../utils/a11y.js';
import { ErrorAlert, LoadingShield } from './AsyncState.jsx';

const CLUSTER_DEFAULTS_PREFIX = 'localcloud.dataproc.cluster-defaults';

function emptyClusterDefaults() {
    return { imageVersion: '', labels: '' };
}

function clusterDefaultsKey(project, region) {
    return `${CLUSTER_DEFAULTS_PREFIX}.${project}.${region}`;
}

function readClusterDefaults(project, region) {
    try {
        const stored = JSON.parse(localStorage.getItem(clusterDefaultsKey(project, region)) || 'null');
        if (!stored || typeof stored !== 'object') return emptyClusterDefaults();
        return {
            imageVersion: typeof stored.imageVersion === 'string' ? stored.imageVersion : '',
            labels: typeof stored.labels === 'string' ? stored.labels : '',
        };
    } catch {
        return emptyClusterDefaults();
    }
}

function writeClusterDefaults(project, region, defaults) {
    try {
        localStorage.setItem(clusterDefaultsKey(project, region), JSON.stringify(defaults));
    } catch {
        // Defaults still apply for the current session when storage is unavailable.
    }
}

// ============================================================================
// DataprocPanel — top-level panel with tabs
// ============================================================================

export function DataprocPanel(props) {
    const [tab, setTab] = createSignal('clusters');
    const project = () => props.project || 'local-project';
    const region = () => 'us-central1';
    const [clusterDefaults, setClusterDefaults] = createSignal(emptyClusterDefaults());

    createEffect(() => {
        setClusterDefaults(readClusterDefaults(project(), region()));
    });

    const saveClusterDefaults = defaults => {
        const normalized = {
            imageVersion: defaults.imageVersion || '',
            labels: defaults.labels || '',
        };
        setClusterDefaults(normalized);
        writeClusterDefaults(project(), region(), normalized);
    };

    return (
        <div style={{ display: 'flex', flex: 1, 'flex-direction': 'column', 'min-height': '0' }}>
            <div class="se-mode-bar" style={{ 'justify-content': 'flex-start', padding: '8px 16px 0' }}>
                <div class="se-mode-tabs" role="tablist" aria-label="Dataproc workspace">
                    <TabButton active={tab() === 'overview'} onClick={() => setTab('overview')} label="Overview" icon="▦" />
                    <TabButton active={tab() === 'runtime-images'} onClick={() => setTab('runtime-images')} label="Runtime Images" icon="📦" />
                    <TabButton active={tab() === 'clusters'} onClick={() => setTab('clusters')} label="Clusters" icon="⚙" />
                    <TabButton active={tab() === 'jobs'} onClick={() => setTab('jobs')} label="Jobs" icon="▶" />
                    <TabButton active={tab() === 'defaults'} onClick={() => setTab('defaults')} label="Defaults" icon="≡" />
                </div>
            </div>
            <div style={{ flex: 1, overflow: 'auto', padding: '16px' }}>
                <Switch>
                    <Match when={tab() === 'overview'}>
                        <DataprocOverview project={project()} region={region()} setTab={setTab} />
                    </Match>
                    <Match when={tab() === 'runtime-images'}>
                        <RuntimeImageCatalog project={project()} region={region()} />
                    </Match>
                    <Match when={tab() === 'clusters'}>
                        <ClusterManager project={project()} region={region()} clusterDefaults={clusterDefaults()} />
                    </Match>
                    <Match when={tab() === 'jobs'}>
                        <JobManager project={project()} region={region()} />
                    </Match>
                    <Match when={tab() === 'defaults'}>
                        <ClusterDefaults defaults={clusterDefaults()} onSave={saveClusterDefaults} />
                    </Match>
                </Switch>
            </div>
        </div>
    );
}

function TabButton(props) {
    return (
        <button
            role="tab"
            aria-selected={props.active}
            onClick={props.onClick}
            class={`se-mode-tab ${props.active ? 'active' : ''}`}
        >
            <span style={{ 'margin-right': '6px' }}>{props.icon}</span>{props.label}
        </button>
    );
}

// ============================================================================
// Overview Tab — quick stats + published profiles + active clusters
// ============================================================================

function DataprocOverview(props) {
    const [catalog, setCatalog] = createSignal({ profiles: [], aliases: {} });
    const [clusters, setClusters] = createSignal([]);
    const [loading, setLoading] = createSignal(true);
    const [error, setError] = createSignal(null);

    async function load() {
        setLoading(true);
        setError(null);
        try {
            const [cat, cls] = await Promise.all([
                api.runtimeCatalog().catch(e => { setError(e.message); return { profiles: [], aliases: {} }; }),
                api.dataprocClusters(props.project, props.region).catch(e => { setError(e.message); return { clusters: [] }; }),
            ]);
            batch(() => {
                setCatalog(cat);
                setClusters(cls.clusters || []);
                setLoading(false);
            });
        } catch (e) {
            setError(e.message);
            setLoading(false);
        }
    }

    onMount(load);

    const publishedProfiles = createMemo(() =>
        (catalog().profiles || []).filter(p => p.technology === 'dataproc' && p.status === 'PUBLISHED')
    );
    const totalProfiles = createMemo(() =>
        (catalog().profiles || []).filter(p => p.technology === 'dataproc')
    );

    return (
        <div>
            <ErrorAlert message={error()} role="alert" style={{ 'margin-bottom': '16px' }} />
            <LoadingShield loading={loading()} role="status">
                {/* Quick Stats */}
                <div style={{ display: 'flex', gap: '16px', 'margin-bottom': '24px', 'flex-wrap': 'wrap' }}>
                    <StatCard label="Profiles" value={totalProfiles().length} sub={`${publishedProfiles().length} published`} />
                    <StatCard label="Clusters" value={clusters().length} sub={`${clusters().filter(c => (c.status || '').toUpperCase() === 'RUNNING').length} running`} />
                </div>

                {/* Published Profiles */}
                <SectionHeader title="Published Runtime Images" onAction={() => props.setTab('runtime-images')} actionLabel="Manage" />
                <Show when={publishedProfiles().length > 0} fallback={<EmptyState text="No published runtime profiles yet" />}>
                    <div style={{ display: 'flex', 'flex-direction': 'column', gap: '8px' }}>
                        <For each={publishedProfiles()}>{profile => (
                            <ProfileCard profile={profile} compact />
                        )}</For>
                    </div>
                </Show>

                {/* Active Clusters */}
                <SectionHeader title="Active Clusters" onAction={() => props.setTab('clusters')} actionLabel="Manage" marginTop="24px" />
                <Show when={clusters().length > 0} fallback={<EmptyState text="No clusters created" />}>
                    <div style={{ display: 'flex', 'flex-direction': 'column', gap: '8px' }}>
                        <For each={clusters()}>{cluster => (
                            <ClusterCard cluster={cluster} compact />
                        )}</For>
                    </div>
                </Show>
            </LoadingShield>
        </div>
    );
}

function StatCard(props) {
    return (
        <div class="stat-card" style={{ 'min-width': '120px' }}>
            <div class="stat-card-value" style={{ 'font-size': '28px' }}>{props.value}</div>
            <div class="stat-card-label">{props.label}</div>
            <div class="stat-card-sublabel">{props.sub}</div>
        </div>
    );
}

function SectionHeader(props) {
    return (
        <div style={{
            display: 'flex', 'align-items': 'center', 'justify-content': 'space-between',
            'margin-bottom': '12px', 'margin-top': props.marginTop || '0',
        }}>
            <h3 style={{ margin: 0, 'font-size': 'var(--font-size-lg)', 'font-weight': '600' }}>{props.title}</h3>
            <Show when={props.onAction}>
                <button onClick={props.onAction} class="btn btn-secondary btn-sm">
                    {props.actionLabel}
                </button>
            </Show>
        </div>
    );
}

function EmptyState(props) {
    return (
        <div class="empty-state">
            <div class="empty-state-text">{props.text || 'Nothing to show'}</div>
        </div>
    );
}

// ============================================================================
// Cluster Defaults — project/region-scoped create-cluster baseline
// ============================================================================

function ClusterDefaults(props) {
    const [profiles, setProfiles] = createSignal([]);
    const [loading, setLoading] = createSignal(true);
    const [error, setError] = createSignal(null);
    const [imageVersion, setImageVersion] = createSignal('');
    const [labels, setLabels] = createSignal('');
    const [saved, setSaved] = createSignal(false);
    let savedTimer;
    let disposed = false;

    createEffect(() => {
        const defaults = props.defaults || emptyClusterDefaults();
        setImageVersion(defaults.imageVersion || '');
        setLabels(defaults.labels || '');
    });

    onMount(async () => {
        try {
            const catalog = await api.runtimeCatalog();
            if (!disposed) {
                setProfiles((catalog.profiles || []).filter(profile =>
                    profile.technology === 'dataproc' && profile.status === 'PUBLISHED'
                ));
            }
        } catch (e) {
            if (!disposed) setError(e.message);
        } finally {
            if (!disposed) setLoading(false);
        }
    });

    onCleanup(() => {
        disposed = true;
        clearTimeout(savedTimer);
    });

    const save = e => {
        e.preventDefault();
        props.onSave({
            imageVersion: imageVersion(),
            labels: labels().trim(),
        });
        setSaved(true);
        clearTimeout(savedTimer);
        savedTimer = setTimeout(() => setSaved(false), 2400);
    };

    const labelPreview = createMemo(() => Object.entries(parseLabels(labels())));

    return (
        <div style={{ 'max-width': '760px', margin: '0 auto', padding: '4px 0 24px' }}>
            <div style={{ 'margin-bottom': '24px' }}>
                <div style={{
                    'font-size': 'var(--font-size-2xs)', 'font-weight': '700',
                    'letter-spacing': '0.1em', color: 'var(--primary)', 'margin-bottom': '6px',
                }}>
                    CREATE CLUSTER BASELINE
                </div>
                <h2 style={{ margin: '0 0 6px', 'font-size': 'var(--font-size-xl)' }}>Cluster Defaults</h2>
                <p style={{ margin: 0, color: 'var(--text-secondary)', 'font-size': 'var(--font-size-sm)', 'line-height': '1.6' }}>
                    Applied when a new cluster is opened. Every value can still be changed before creation.
                </p>
            </div>

            <ErrorAlert message={error()} role="alert" style={{ 'margin-bottom': '12px' }} />

            <form onSubmit={save}>
                <div class="card" style={{ padding: '0', overflow: 'hidden' }}>
                    <div style={{
                        display: 'grid', 'grid-template-columns': 'repeat(auto-fit, minmax(min(100%, 260px), 1fr))',
                        gap: '24px', padding: '20px', 'border-bottom': '1px solid var(--border)',
                    }}>
                        <div>
                            <label for="dataproc-default-image" style={{ display: 'block', 'font-weight': '600', 'margin-bottom': '4px' }}>
                                Runtime image
                            </label>
                            <div style={{ color: 'var(--text-secondary)', 'font-size': 'var(--font-size-xs)', 'line-height': '1.5' }}>
                                Pin new clusters to a published image, or follow the catalog default.
                            </div>
                        </div>
                        <div>
                            <select
                                id="dataproc-default-image"
                                class="form-input"
                                style={fieldStyle()}
                                value={imageVersion()}
                                disabled={loading()}
                                onChange={e => setImageVersion(e.currentTarget.value)}
                            >
                                <option value="">{loading() ? 'Loading published images…' : 'Follow runtime catalog default'}</option>
                                <For each={profiles()}>{profile => (
                                    <option value={profile.upstreamVersion}>
                                        {profile.upstreamVersion} — Spark {profile.components?.spark} · Python {profile.components?.python}
                                    </option>
                                )}</For>
                            </select>
                            <Show when={imageVersion() && !profiles().some(profile => profile.upstreamVersion === imageVersion()) && !loading()}>
                                <div class="alert alert-warning" style={{ 'font-size': 'var(--font-size-xs)', 'margin-top': '8px' }}>
                                    This saved image is no longer published. New clusters will follow the catalog default.
                                </div>
                            </Show>
                        </div>
                    </div>

                    <div style={{
                        display: 'grid', 'grid-template-columns': 'repeat(auto-fit, minmax(min(100%, 260px), 1fr))',
                        gap: '24px', padding: '20px',
                    }}>
                        <div>
                            <label for="dataproc-default-labels" style={{ display: 'block', 'font-weight': '600', 'margin-bottom': '4px' }}>
                                Labels
                            </label>
                            <div style={{ color: 'var(--text-secondary)', 'font-size': 'var(--font-size-xs)', 'line-height': '1.5' }}>
                                Comma-separated metadata added to every new cluster.
                            </div>
                        </div>
                        <div>
                            <input
                                id="dataproc-default-labels"
                                class="form-input"
                                style={fieldStyle()}
                                placeholder="environment=local,team=data"
                                value={labels()}
                                onInput={e => setLabels(e.currentTarget.value)}
                            />
                            <Show when={labelPreview().length > 0}>
                                <div style={{ display: 'flex', 'flex-wrap': 'wrap', gap: '6px', 'margin-top': '10px' }}>
                                    <For each={labelPreview()}>{([key, value]) => (
                                        <span class="badge badge-neutral">{key}:{value}</span>
                                    )}</For>
                                </div>
                            </Show>
                        </div>
                    </div>
                </div>

                <div style={{ display: 'flex', 'align-items': 'center', gap: '12px', 'margin-top': '16px' }}>
                    <button type="submit" class="btn btn-primary">Save defaults</button>
                    <Show when={saved()}>
                        <span role="status" style={{ color: 'var(--success, #16854f)', 'font-size': 'var(--font-size-sm)', 'font-weight': '600' }}>
                            Defaults saved
                        </span>
                    </Show>
                </div>
            </form>
        </div>
    );
}

// ============================================================================
// Runtime Image Catalog — FR1.2
// ============================================================================

function RuntimeImageCatalog(props) {
    const [catalog, setCatalog] = createSignal({ profiles: [], aliases: {} });
    const [loading, setLoading] = createSignal(true);
    const [error, setError] = createSignal(null);
    const [selectedProfile, setSelectedProfile] = createSignal(null);
    const [statusFilter, setStatusFilter] = createSignal('all');
    const [showImport, setShowImport] = createSignal(false);
    const [showPublish, setShowPublish] = createSignal(null);

    async function load() {
        setLoading(true);
        setError(null);
        try {
            const data = await api.runtimeCatalog();
            setCatalog(data);
        } catch (e) {
            setError(e.message);
        }
        setLoading(false);
    }

    onMount(load);

    const dataprocProfiles = createMemo(() =>
        (catalog().profiles || []).filter(p => p.technology === 'dataproc')
    );

    const filteredProfiles = createMemo(() => {
        const f = statusFilter();
        return f === 'all' ? dataprocProfiles() : dataprocProfiles().filter(p => p.status === f);
    });

    const defaultAlias = createMemo(() => catalog().aliases?.default);

    return (
        <div>
            <div style={{ display: 'flex', 'align-items': 'center', 'justify-content': 'space-between', 'margin-bottom': '16px' }}>
                <h2 style={{ margin: 0, 'font-size': 'var(--font-size-xl)' }}>Runtime Image Catalog</h2>
                <div style={{ display: 'flex', gap: '8px' }}>
                    <button onClick={() => setShowImport(true)} class="btn btn-secondary btn-sm">
                        ⬆ Import Profile
                    </button>
                    <button onClick={load} class="btn btn-sm">↻</button>
                </div>
            </div>

            {/* Status filter */}
            <div style={{ display: 'flex', gap: '4px', 'margin-bottom': '12px', 'flex-wrap': 'wrap' }}>
                <FilterChip active={statusFilter() === 'all'} onClick={() => setStatusFilter('all')} label="All" />
                <FilterChip active={statusFilter() === 'PUBLISHED'} onClick={() => setStatusFilter('PUBLISHED')} label="Published" />
                <FilterChip active={statusFilter() === 'CANDIDATE'} onClick={() => setStatusFilter('CANDIDATE')} label="Candidate" />
                <FilterChip active={statusFilter() === 'DEPRECATED'} onClick={() => setStatusFilter('DEPRECATED')} label="Deprecated" />
            </div>

            <ErrorAlert message={error()} role="alert" style={{ 'margin-bottom': '12px' }} />

            <LoadingShield loading={loading()} role="status">
                <Show when={filteredProfiles().length > 0} fallback={<EmptyState text="No Dataproc runtime profiles found" />}>
                    <div style={{ display: 'flex', 'flex-direction': 'column', gap: '10px' }}>
                        <For each={filteredProfiles()}>{profile => (
                            <ProfileCard
                                profile={profile}
                                isDefault={defaultAlias() === profile.revisionId || defaultAlias() === (profile.id + '@' + profile.revision)}
                                onSelect={() => setSelectedProfile(profile)}
                                onPublish={() => setShowPublish(profile)}
                                onSetDefault={async () => {
                                    try {
                                        await api.setRuntimeProfileAlias('default', profile.id + '@' + profile.revision);
                                        await load();
                                    } catch (e) { setError(e.message); }
                                }}
                                onDeprecate={async () => {
                                    if (!confirm(`Deprecate ${profile.revisionId}?`)) return;
                                    try {
                                        await api.deprecateRuntimeProfile(profile.id + '@' + profile.revision);
                                        await load();
                                    } catch (e) { setError(e.message); }
                                }}
                            />
                        )}</For>
                    </div>
                </Show>
            </LoadingShield>

            {/* Profile detail modal */}
            <Show when={selectedProfile()}>
                <Modal onClose={() => setSelectedProfile(null)} title={selectedProfile().id}>
                    <RuntimeProfileDetail profile={selectedProfile()} />
                </Modal>
            </Show>

            {/* Import form */}
            <Show when={showImport()}>
                <Modal onClose={() => setShowImport(false)} title="Import Runtime Profile">
                    <ProfileImportForm onDone={() => { setShowImport(false); load(); }} />
                </Modal>
            </Show>

            {/* Publish form */}
            <Show when={showPublish()}>
                <Modal onClose={() => setShowPublish(null)} title={`Publish ${showPublish().revisionId}`}>
                    <ProfilePublishForm profile={showPublish()} onDone={() => { setShowPublish(null); load(); }} />
                </Modal>
            </Show>
        </div>
    );
}

function ProfileCard(props) {
    const components = () => props.profile.components || {};
    const componentSummary = () => Object.entries(components())
        .map(([k, v]) => `${k}: ${v}`).join(' · ');

    return (
        <div class="card" style={{ display: 'flex', 'align-items': 'center', 'justify-content': 'space-between' }}>
            <div style={{ flex: 1, 'min-width': 0 }}>
                <div style={{ display: 'flex', gap: '8px', 'align-items': 'center', 'margin-bottom': '6px' }}>
                    <strong style={{ 'font-size': 'var(--font-size-md)' }}>{props.profile.upstreamVersion}</strong>
                    <StatusBadge status={props.profile.status} />
                    <Show when={props.isDefault}>
                        <span class="badge badge-info">DEFAULT</span>
                    </Show>
                </div>
                <Show when={!props.compact}>
                    <div style={{ 'font-size': 'var(--font-size-sm)', color: 'var(--text-secondary)', margin: '4px 0' }}>{componentSummary()}</div>
                </Show>
                <Show when={props.compact}>
                    <div style={{ 'font-size': 'var(--font-size-xs)', color: 'var(--text-tertiary)', overflow: 'hidden', 'text-overflow': 'ellipsis', 'white-space': 'nowrap' }}>
                        {componentSummary()}
                    </div>
                </Show>
                <Show when={props.profile.image?.reference}>
                    <code style={{ 'font-size': 'var(--font-size-2xs)', color: 'var(--text-tertiary)' }}>{props.profile.image.reference}</code>
                </Show>
            </div>
            <Show when={!props.compact}>
                <div style={{ display: 'flex', gap: '4px', 'flex-shrink': 0 }}>
                    <Show when={props.profile.status === 'PUBLISHED' && !props.isDefault && props.onSetDefault}>
                        <button onClick={props.onSetDefault} class="btn btn-secondary btn-sm">Set Default</button>
                    </Show>
                    <Show when={props.profile.status === 'PUBLISHED' && props.onDeprecate}>
                        <button onClick={props.onDeprecate} class="btn btn-danger btn-sm">Deprecate</button>
                    </Show>
                    <Show when={props.profile.status === 'CANDIDATE' && props.onPublish}>
                        <button onClick={props.onPublish} class="btn btn-primary btn-sm">Publish</button>
                    </Show>
                    <Show when={props.onSelect}>
                        <button onClick={props.onSelect} class="btn btn-secondary btn-sm">Details</button>
                    </Show>
                </div>
            </Show>
        </div>
    );
}

function StatusBadge(props) {
    const s = () => (props.status || '').toString().toLowerCase();
    const cls = createMemo(() => {
        const v = s();
        if (v === 'published' || v === 'running' || v === 'done' || v === 'healthy') return 'badge-healthy';
        if (v === 'candidate' || v === 'pending' || v === 'setup') return 'badge-warning';
        if (v === 'deprecated' || v === 'error' || v === 'cancelled' || v === 'failed' || v === 'unhealthy') return 'badge-unhealthy';
        if (v === 'serverless') return 'badge-info';
        return 'badge-neutral';
    });
    return <span class={`badge ${cls()}`}>{props.status}</span>;
}

function FilterChip(props) {
    return (
        <button onClick={props.onClick} class={`btn btn-sm ${props.active ? 'btn-primary' : 'btn-secondary'}`} style={{ 'border-radius': 'var(--radius-pill)' }}>
            {props.label}
        </button>
    );
}

// ============================================================================
// FR5: Runtime Profile Detail View
// ============================================================================

function RuntimeProfileDetail(props) {
    const profile = () => props.profile;
    const components = () => Object.entries(profile().components || {});
    const capabilities = () => profile().capabilities || [];
    const limitations = () => profile().limitations || [];

    return (
        <div style={{ 'font-size': 'var(--font-size-sm)', 'line-height': '1.6' }}>
            <div style={{ display: 'flex', gap: '12px', 'margin-bottom': '16px' }}>
                <StatusBadge status={profile().status} />
                <span style={{ color: 'var(--text-secondary)' }}>Revision {profile().revision}</span>
            </div>

            {/* Image info */}
            <Show when={profile().image?.reference}>
                <div style={{ 'margin-bottom': '16px' }}>
                    <div style={{ 'font-weight': '600', 'margin-bottom': '4px' }}>Container Image</div>
                    <code style={{ 'font-size': 'var(--font-size-xs)', background: 'var(--surface-variant)', padding: '4px 8px', 'border-radius': 'var(--radius-xs)', display: 'block' }}>
                        {profile().image.reference}
                    </code>
                    <div style={{ 'font-size': 'var(--font-size-2xs)', color: 'var(--text-tertiary)', 'margin-top': '4px' }}>
                        Digest: {profile().image.digest?.substring(0, 24)}…
                    </div>
                </div>
            </Show>

            {/* Component Matrix */}
            <div style={{ 'margin-bottom': '16px' }}>
                <div style={{ 'font-weight': '600', 'margin-bottom': '8px' }}>Component Matrix</div>
                <table class="data-table" style={{ width: '100%', 'font-size': 'var(--font-size-xs)' }}>
                    <tbody>
                        <For each={components()}>{([key, value]) => (
                            <tr>
                                <td style={{ padding: '6px 0', 'font-weight': '500', 'text-transform': 'capitalize' }}>{prettyComp(key)}</td>
                                <td style={{ padding: '6px 0', 'text-align': 'right', 'font-family': 'var(--font-mono)' }}>{value}</td>
                            </tr>
                        )}</For>
                    </tbody>
                </table>
            </div>

            {/* Capabilities */}
            <div style={{ 'margin-bottom': '16px' }}>
                <div style={{ 'font-weight': '600', 'margin-bottom': '4px' }}>Capabilities</div>
                <div style={{ display: 'flex', gap: '6px', 'flex-wrap': 'wrap' }}>
                    <For each={capabilities()}>{cap => (
                        <span class="badge badge-neutral">{cap}</span>
                    )}</For>
                </div>
            </div>

            {/* Limitations */}
            <Show when={limitations().length > 0}>
                <div style={{ 'margin-bottom': '16px' }}>
                    <div style={{ 'font-weight': '600', 'margin-bottom': '4px' }}>Limitations</div>
                    <ul style={{ margin: 0, 'padding-left': '20px', color: 'var(--text-secondary)' }}>
                        <For each={limitations()}>{lim => <li style={{ 'font-size': 'var(--font-size-xs)', 'margin-bottom': '4px' }}>{lim}</li>}</For>
                    </ul>
                </div>
            </Show>

            {/* Environment */}
            <Show when={profile().environment && Object.keys(profile().environment).length > 0}>
                <div style={{ 'margin-bottom': '16px' }}>
                    <div style={{ 'font-weight': '600', 'margin-bottom': '4px' }}>Environment</div>
                    <For each={Object.entries(profile().environment)}>{([k, v]) => (
                        <div style={{ 'font-size': 'var(--font-size-2xs)', 'font-family': 'var(--font-mono)', color: 'var(--text-tertiary)' }}>{k}={v}</div>
                    )}</For>
                </div>
            </Show>
        </div>
    );
}

function prettyComp(key) {
    return key.replace(/-/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
}
function createFormAction() {
    const [busy, setBusy] = createSignal(false);
    const [error, setError] = createSignal(null);

    const run = async (action) => {
        if (busy()) return;
        setBusy(true);
        setError(null);
        try {
            await action();
        } catch (e) {
            setError(e.message);
        } finally {
            setBusy(false);
        }
    };

    return { busy, error, setError, run };
}


// ============================================================================
// FR1.1/1.4: Profile Import Form
// ============================================================================

function ProfileImportForm(props) {
    const [jsonText, setJsonText] = createSignal('');
    const { busy, error, run } = createFormAction();

    const doImport = async (e) => {
        e.preventDefault();
        await run(async () => {
            const profile = JSON.parse(jsonText());
            await api.importRuntimeProfile(profile);
            props.onDone();
        });
    };

    return (
        <form onSubmit={doImport} style={{ 'font-size': 'var(--font-size-sm)' }}>
            <p style={{ color: 'var(--text-secondary)', 'margin-bottom': '12px' }}>
                Paste a runtime profile JSON (as produced by <code>dataproc-runtime-factory</code> profile export).
                The profile will be imported as a CANDIDATE revision.
            </p>
            <textarea
                value={jsonText()}
                onInput={e => setJsonText(e.currentTarget.value)}
                placeholder='{\n  "id": "dataproc:2.3-debian12",\n  "technology": "dataproc",\n  ...\n}'
                class="form-input form-input-mono"
                style={{ width: '100%', 'min-height': '200px' }}
                required
            />
            <ErrorAlert message={error()} style={{ 'margin-top': '8px' }} />
            <div style={{ display: 'flex', gap: '8px', 'margin-top': '12px' }}>
                <button type="submit" disabled={busy()} class="btn btn-primary">
                    {busy() ? 'Importing…' : 'Import Profile'}
                </button>
            </div>
        </form>
    );
}

// ============================================================================
// FR1.3: Profile Publish Form
// ============================================================================

function ProfilePublishForm(props) {
    const [form, setForm] = createSignal({
        reference: '', digest: '', registry: 'docker.io', alias: '', signature: '',
    });
    const { busy, error, run } = createFormAction();

    const publish = async (e) => {
        e.preventDefault();
        await run(async () => {
            const revisionId = props.profile.id + '@' + props.profile.revision;
            const image = {
                reference: form().reference,
                digest: form().digest,
                allowedRegistries: form().registry.split(',').map(s => s.trim()).filter(Boolean),
                signature: form().signature,
            };
            await api.publishRuntimeProfile(revisionId, image, form().alias);
            props.onDone();
        });
    };

    const update = (key, value) => setForm(f => ({ ...f, [key]: value }));

    return (
        <form onSubmit={publish} style={{ 'font-size': 'var(--font-size-sm)' }}>
            <div class="create-dialog-field">
                <label class="create-dialog-label">OCI Image Reference</label>
                <input class="create-dialog-input create-dialog-input-mono" required placeholder="docker.io/localcloud/dataproc:2.3.34-debian12-r1"
                    value={form().reference} onInput={e => update('reference', e.currentTarget.value)} />
            </div>
            <div class="create-dialog-field">
                <label class="create-dialog-label">SHA-256 Digest</label>
                <input class="create-dialog-input create-dialog-input-mono" required placeholder="sha256:…" pattern="sha256:[a-fA-F0-9]{64}"
                    value={form().digest} onInput={e => update('digest', e.currentTarget.value)} />
            </div>
            <div class="create-dialog-field">
                <label class="create-dialog-label">Allowed Registries (comma-separated)</label>
                <input class="create-dialog-input create-dialog-input-mono" required
                    value={form().registry} onInput={e => update('registry', e.currentTarget.value)} />
            </div>
            <div class="create-dialog-field">
                <label class="create-dialog-label">Alias (optional)</label>
                <input class="create-dialog-input create-dialog-input-mono"
                    value={form().alias} onInput={e => update('alias', e.currentTarget.value)}
                    placeholder="e.g., default" />
            </div>
            <div class="create-dialog-field">
                <label class="create-dialog-label">Signature Evidence</label>
                <input class="create-dialog-input create-dialog-input-mono" required placeholder="cosign bundle URI or verified signature ID"
                    value={form().signature} onInput={e => update('signature', e.currentTarget.value)} />
            </div>
            <Show when={error()}>
                <div class="create-dialog-error">{error()}</div>
            </Show>
            <button type="submit" disabled={busy()} class="btn btn-primary">
                {busy() ? 'Publishing…' : 'Approve & Publish'}
            </button>
        </form>
    );
}

// ============================================================================
// FR2: Cluster Management — wizard + list + detail
// ============================================================================

const CLUSTER_TERMINAL_STATES = new Set(['RUNNING', 'ERROR', 'STOPPED', 'CANCELLED']);
const JOB_TERMINAL_STATES = new Set(['DONE', 'ERROR', 'CANCELLED']);

function dataprocStatus(resource) {
    const status = resource?.status;
    return (typeof status === 'string' ? status : status?.state || 'UNKNOWN').toUpperCase();
}

function isClusterTerminal(cluster) {
    return CLUSTER_TERMINAL_STATES.has(dataprocStatus(cluster));
}

function isJobTerminal(job) {
    return JOB_TERMINAL_STATES.has(dataprocStatus(job));
}

function ClusterManager(props) {
    const [clusters, setClusters] = createSignal([]);
    const [catalog, setCatalog] = createSignal({ profiles: [], aliases: {} });
    const [loading, setLoading] = createSignal(true);
    const [showCreate, setShowCreate] = createSignal(false);
    const [selectedCluster, setSelectedCluster] = createSignal(null);
    const [error, setError] = createSignal(null);
    const [deletingNames, setDeletingNames] = createSignal(new Set());
    let pollTimer;
    let disposed = false;
    let loadVersion = 0;

    function schedulePoll(nextClusters) {
        clearTimeout(pollTimer);
        pollTimer = undefined;
        if (!disposed && nextClusters.some(cluster => !isClusterTerminal(cluster))) {
            pollTimer = setTimeout(load, 10000);
        }
    }

    async function load() {
        clearTimeout(pollTimer);
        pollTimer = undefined;
        const requestId = ++loadVersion;
        setLoading(true);
        try {
            const [cls, cat] = await Promise.all([
                api.dataprocClusters(props.project, props.region),
                api.runtimeCatalog(),
            ]);
            if (disposed || requestId !== loadVersion) return;
            const nextClusters = cls.clusters || [];
            batch(() => {
                setClusters(nextClusters);
                setCatalog(cat);
                setError(null);
                setLoading(false);
            });
            schedulePoll(nextClusters);
        } catch (e) {
            if (disposed || requestId !== loadVersion) return;
            setError(e.message);
            setLoading(false);
            schedulePoll(clusters());
        }
    }

    async function deleteCluster(name) {
        if (deletingNames().has(name)) return;
        if (!confirm(`Delete cluster "${name}"?`)) return;
        setDeletingNames(current => {
            const next = new Set(current);
            next.add(name);
            return next;
        });
        try {
            await api.dataprocDeleteCluster(props.project, props.region, name);
            await load();
        } catch (e) {
            if (!disposed) setError(e.message);
        } finally {
            if (!disposed) {
                setDeletingNames(current => {
                    const next = new Set(current);
                    next.delete(name);
                    return next;
                });
            }
        }
    }

    onMount(load);
    onCleanup(() => {
        disposed = true;
        loadVersion++;
        clearTimeout(pollTimer);
    });

    const publishedProfiles = createMemo(() =>
        (catalog().profiles || []).filter(p => p.technology === 'dataproc' && p.status === 'PUBLISHED')
    );

    return (
        <div>
            <div style={{ display: 'flex', 'align-items': 'center', 'justify-content': 'space-between', 'margin-bottom': '16px' }}>
                <h2 style={{ margin: 0, 'font-size': 'var(--font-size-xl)' }}>Clusters</h2>
                <div style={{ display: 'flex', gap: '8px' }}>
                    <button onClick={() => setShowCreate(true)} class="btn btn-primary btn-sm">
                        + Create Cluster
                    </button>
                    <button onClick={load} class="btn btn-sm">↻</button>
                </div>
            </div>

            <ErrorAlert message={error()} role="alert" style={{ 'margin-bottom': '12px' }} />

            <LoadingShield loading={loading()} role="status">
                <Show when={clusters().length > 0} fallback={<EmptyState text="No clusters created yet. Click 'Create Cluster' to get started." />}>
                    <div class="data-table-wrapper">
                        <table class="data-table" style={{ width: '100%', 'font-size': 'var(--font-size-sm)' }}>
                            <thead>
                                <tr>
                                    <th>Cluster Name</th>
                                    <th>Image Version</th>
                                    <th>Status</th>
                                    <th>Labels</th>
                                    <th>Actions</th>
                                </tr>
                            </thead>
                            <tbody>
                                <For each={clusters()}>{cluster => {
                                    const name = () => cluster.clusterName || cluster.name || '';
                                    const imgVer = () => cluster.imageVersion || cluster.config?.imageVersion || '—';
                                    const statusVal = () => {
                                        if (typeof cluster.status === 'string') return cluster.status;
                                        if (cluster.status?.state) return cluster.status.state;
                                        return 'UNKNOWN';
                                    };
                                    return (
                                    <tr>
                                        <td style={{ 'font-weight': '500' }}>
                                            <a onClick={() => setSelectedCluster(cluster)} onKeyDown={onActivate(() => setSelectedCluster(cluster))}
                                                tabindex="0" role="button" class="service-name-link" style={{ cursor: 'pointer' }}>
                                                {name()}
                                            </a>
                                        </td>
                                        <td style={{ 'font-size': 'var(--font-size-xs)', 'font-family': 'var(--font-mono)', color: 'var(--text-secondary)' }}>
                                            {imgVer()}
                                        </td>
                                        <td>
                                            <StatusBadge status={statusVal()} />
                                        </td>
                                        <td style={{ 'font-size': 'var(--font-size-2xs)' }}>
                                            <Show when={cluster.labels && Object.keys(cluster.labels).length > 0}>
                                                <For each={Object.entries(cluster.labels)}>{([k, v]) => (
                                                    <span class="badge badge-neutral" style={{ 'margin-right': '4px' }}>{k}:{v}</span>
                                                )}</For>
                                            </Show>
                                            <Show when={!cluster.labels || Object.keys(cluster.labels || {}).length === 0}>
                                                <span style={{ color: 'var(--text-tertiary)' }}>—</span>
                                            </Show>
                                        </td>
                                        <td>
                                            <button
                                                class="btn btn-danger btn-sm"
                                                disabled={deletingNames().has(name())}
                                                aria-busy={deletingNames().has(name())}
                                                onClick={() => deleteCluster(name())}
                                            >
                                                {deletingNames().has(name()) ? 'Deleting…' : 'Delete'}
                                            </button>
                                        </td>
                                    </tr>
                                    );
                                }}</For>
                            </tbody>
                        </table>
                    </div>
                </Show>
            </LoadingShield>

            {/* Create Cluster modal */}
            <Show when={showCreate()}>
                <Modal onClose={() => setShowCreate(false)} title="Create Dataproc Cluster">
                    <CreateClusterWizard
                        project={props.project}
                        region={props.region}
                        profiles={publishedProfiles()}
                        defaultAlias={catalog().aliases?.default}
                        clusterDefaults={props.clusterDefaults}
                        onCreated={() => { setShowCreate(false); load(); }}
                    />
                </Modal>
            </Show>

            {/* Cluster detail */}
            <Show when={selectedCluster()}>
                <Modal onClose={() => setSelectedCluster(null)} title={selectedCluster().clusterName || selectedCluster().name}>
                    <ClusterDetail cluster={selectedCluster()} project={props.project} region={props.region} />
                </Modal>
            </Show>
        </div>
    );
}

// ============================================================================
// FR2.1: Create Cluster Wizard
// ============================================================================

function CreateClusterWizard(props) {
    const [name, setName] = createSignal('');
    const [imageVersion, setImageVersion] = createSignal(props.clusterDefaults?.imageVersion || '');
    const [labels, setLabels] = createSignal(props.clusterDefaults?.labels || '');
    const { busy, error, run } = createFormAction();

    // Prefer the saved cluster baseline while it references a published image.
    onMount(() => {
        const configured = props.profiles?.find(profile =>
            profile.upstreamVersion === props.clusterDefaults?.imageVersion
        );
        const catalogDefault = props.profiles?.find(profile =>
            props.defaultAlias && profile.id + '@' + profile.revision === props.defaultAlias
        );
        const selected = configured || catalogDefault || props.profiles?.[0];
        setImageVersion(selected?.upstreamVersion || '');
    });

    const create = async (e) => {
        e.preventDefault();
        await run(async () => {
            const cluster = {
                clusterName: name(),
                config: { softwareConfig: { imageVersion: imageVersion() } },
                labels: parseLabels(labels()),
            };
            await api.dataprocCreateCluster(props.project, props.region, cluster);
            props.onCreated();
        });
    };

    const fs = fieldStyle();

    return (
        <form onSubmit={create} style={{ 'font-size': 'var(--font-size-sm)', width: '100%' }}>
            {/* Step 1: Name */}
            <WizardStep num="1" title="Cluster Name">
                <input class="form-input" style={fs} required placeholder="my-cluster"
                    value={name()} onInput={e => setName(e.currentTarget.value)} />
            </WizardStep>

            {/* Step 2: Image Version */}
            <WizardStep num="2" title="Runtime Image">
                <Show when={props.profiles && props.profiles.length > 0} fallback={
                    <div class="alert alert-error" style={{ 'font-size': 'var(--font-size-xs)' }}>
                        No published runtime profiles. Import and publish one first.
                    </div>
                }>
                    <select class="form-input" style={fs} value={imageVersion()} onChange={e => setImageVersion(e.currentTarget.value)}>
                        <For each={props.profiles}>{p => (
                            <option value={p.upstreamVersion}>
                                {p.upstreamVersion} — Spark {p.components?.spark} · Py {p.components?.python} · Hive {p.components?.hive}
                            </option>
                        )}</For>
                    </select>
                    <Show when={imageVersion()}>
                        <ComponentSummary profile={props.profiles.find(p => p.upstreamVersion === imageVersion())} />
                    </Show>
                </Show>
            </WizardStep>

            {/* Step 3: Labels */}
            <WizardStep num="3" title="Labels (optional)">
                <input class="form-input" style={fs} placeholder="key1=value1,key2=value2"
                    value={labels()} onInput={e => setLabels(e.currentTarget.value)} />
            </WizardStep>

            <ErrorAlert message={error()} style={{ 'margin-bottom': '8px' }} />

            <button type="submit" disabled={busy()} class="btn btn-primary" style={{ 'margin-top': '12px' }}>
                {busy() ? 'Creating…' : 'Create Cluster'}
            </button>
        </form>
    );
}

function WizardStep(props) {
    return (
        <div style={{ 'margin-bottom': '16px' }}>
            <div style={{ display: 'flex', gap: '6px', 'align-items': 'center', 'margin-bottom': '6px' }}>
                <span style={{
                    width: '20px', height: '20px', 'border-radius': '50%',
                    background: 'var(--primary)', color: '#fff', 'font-size': 'var(--font-size-2xs)',
                    'font-weight': '700', display: 'flex', 'align-items': 'center', 'justify-content': 'center',
                }}>{props.num}</span>
                <span style={{ 'font-weight': '600', 'font-size': 'var(--font-size-sm)' }}>{props.title}</span>
            </div>
            <div style={{ 'padding-left': '26px' }}>{props.children}</div>
        </div>
    );
}

function ComponentSummary(props) {
    if (!props.profile?.components) return null;
    return (
        <div style={{ 'margin-top': '8px', 'font-size': 'var(--font-size-xs)', color: 'var(--text-secondary)', display: 'flex', 'flex-wrap': 'wrap', gap: '6px' }}>
            <For each={Object.entries(props.profile.components)}>{([k, v]) => (
                <span class="badge badge-neutral">
                    {prettyComp(k)} <strong>{v}</strong>
                </span>
            )}</For>
        </div>
    );
}

function fieldStyle() {
    return { width: '100%', padding: '6px 8px', border: '1px solid var(--border)', 'border-radius': 'var(--radius-xs)', 'font-size': 'var(--font-size-sm)' };
}

function parseLabels(text) {
    const labels = {};
    if (!text || !text.trim()) return labels;
    for (const pair of text.split(',')) {
        const [k, ...vParts] = pair.split('=');
        if (k && k.trim()) labels[k.trim()] = vParts.join('=').trim();
    }
    return labels;
}

// ============================================================================
// FR2.3: Cluster Detail
// ============================================================================

function ClusterDetail(props) {
    const cluster = () => props.cluster;
    const statusVal = () => {
        if (typeof cluster().status === 'string') return cluster().status;
        if (cluster().status?.state) return cluster().status.state;
        return 'RUNNING';
    };

    return (
        <div style={{ 'font-size': 'var(--font-size-sm)' }}>
            <div style={{ display: 'flex', gap: '12px', 'margin-bottom': '16px' }}>
                <StatusBadge status={statusVal()} />
                <span style={{ color: 'var(--text-secondary)' }}>{props.project} / {props.region}</span>
            </div>
            <div style={{ 'margin-bottom': '12px' }}>
                <strong>Image Version:</strong>
                <code style={{ 'margin-left': '8px', 'font-size': 'var(--font-size-xs)' }}>
                    {cluster().imageVersion || cluster().config?.imageVersion || '—'}
                </code>
            </div>
            <Show when={cluster().labels && Object.keys(cluster().labels || {}).length > 0}>
                <div style={{ 'margin-bottom': '12px' }}>
                    <strong>Labels:</strong>
                    <div style={{ 'margin-top': '4px', display: 'flex', 'flex-wrap': 'wrap', gap: '4px' }}>
                        <For each={Object.entries(cluster().labels || {})}>{([k, v]) => (
                            <span class="badge badge-neutral">{k}: {v}</span>
                        )}</For>
                    </div>
                </div>
            </Show>
        </div>
    );
}

// ============================================================================
// FR3: Job Management — submit + list + detail
// ============================================================================

function JobManager(props) {
    const [jobs, setJobs] = createSignal([]);
    const [loading, setLoading] = createSignal(true);
    const [showSubmit, setShowSubmit] = createSignal(false);
    const [clusters, setClusters] = createSignal([]);
    const [selectedJob, setSelectedJob] = createSignal(null);
    const [error, setError] = createSignal(null);
    const [statusFilter, setStatusFilter] = createSignal('all');
    const [cancellingIds, setCancellingIds] = createSignal(new Set());
    let pollTimer;
    let disposed = false;
    let loadVersion = 0;

    function schedulePoll(nextJobs) {
        clearTimeout(pollTimer);
        pollTimer = undefined;
        if (!disposed && nextJobs.some(job => !isJobTerminal(job))) {
            pollTimer = setTimeout(load, 10000);
        }
    }

    async function load() {
        clearTimeout(pollTimer);
        pollTimer = undefined;
        const requestId = ++loadVersion;
        try {
            const [jbs, cls] = await Promise.all([
                api.dataprocJobs(props.project, props.region),
                api.dataprocClusters(props.project, props.region).catch(() => ({ clusters: clusters() })),
            ]);
            if (disposed || requestId !== loadVersion) return;
            const nextJobs = jbs.jobs || [];
            batch(() => {
                setJobs(nextJobs);
                setClusters(cls.clusters || []);
                setError(null);
                setLoading(false);
            });
            schedulePoll(nextJobs);
        } catch (e) {
            if (disposed || requestId !== loadVersion) return;
            setError(e.message);
            setLoading(false);
            schedulePoll(jobs());
        }
    }

    async function cancelJob(jobId) {
        if (cancellingIds().has(jobId)) return;
        setCancellingIds(current => {
            const next = new Set(current);
            next.add(jobId);
            return next;
        });
        try {
            await api.dataprocCancelJob(props.project, props.region, jobId);
            await load();
        } catch (e) {
            if (!disposed) setError(e.message);
        } finally {
            if (!disposed) {
                setCancellingIds(current => {
                    const next = new Set(current);
                    next.delete(jobId);
                    return next;
                });
            }
        }
    }

    onMount(load);
    onCleanup(() => {
        disposed = true;
        loadVersion++;
        clearTimeout(pollTimer);
    });

    const filteredJobs = createMemo(() => {
        const f = statusFilter();
        return f === 'all' ? jobs() : jobs().filter(j => dataprocStatus(j).includes(f));
    });

    return (
        <div>
            <div style={{ display: 'flex', 'align-items': 'center', 'justify-content': 'space-between', 'margin-bottom': '16px' }}>
                <h2 style={{ margin: 0, 'font-size': 'var(--font-size-xl)' }}>Jobs</h2>
                <div style={{ display: 'flex', gap: '8px' }}>
                    <button onClick={() => setShowSubmit(true)} class="btn btn-primary btn-sm">
                        + Submit Job
                    </button>
                    <button onClick={load} class="btn btn-sm">↻</button>
                </div>
            </div>

            <ErrorAlert message={error()} role="alert" style={{ 'margin-bottom': '12px' }} />

            {/* Status filter */}
            <div style={{ display: 'flex', gap: '4px', 'margin-bottom': '12px', 'flex-wrap': 'wrap' }}>
                <FilterChip active={statusFilter() === 'all'} onClick={() => setStatusFilter('all')} label="All" />
                <FilterChip active={statusFilter() === 'RUNNING'} onClick={() => setStatusFilter('RUNNING')} label="Running" />
                <FilterChip active={statusFilter() === 'DONE'} onClick={() => setStatusFilter('DONE')} label="Done" />
                <FilterChip active={statusFilter() === 'ERROR'} onClick={() => setStatusFilter('ERROR')} label="Error" />
                <FilterChip active={statusFilter() === 'PENDING'} onClick={() => setStatusFilter('PENDING')} label="Pending" />
            </div>

            <LoadingShield loading={loading()} role="status">
                <Show when={filteredJobs().length > 0} fallback={<EmptyState text="No jobs submitted yet" />}>
                    <div class="data-table-wrapper">
                        <table class="data-table" style={{ width: '100%', 'font-size': 'var(--font-size-sm)' }}>
                            <thead>
                                <tr>
                                    <th>Job ID</th>
                                    <th>Cluster</th>
                                    <th>Status</th>
                                    <th>Actions</th>
                                </tr>
                            </thead>
                            <tbody>
                                <For each={filteredJobs()}>{job => (
                                    <tr>
                                        <td style={{ 'font-weight': '500' }}>
                                            <a onClick={() => setSelectedJob(job)} onKeyDown={onActivate(() => setSelectedJob(job))}
                                                tabindex="0" role="button" class="service-name-link" style={{ cursor: 'pointer' }}>
                                                {job.jobId}
                                            </a>
                                        </td>
                                        <td style={{ 'font-size': 'var(--font-size-xs)', color: 'var(--text-secondary)' }}>{job.clusterName || 'serverless'}</td>
                                        <td><StatusBadge status={job.status} /></td>
                                        <td>
                                            <Show when={!isJobTerminal(job)}>
                                                <button
                                                    class="btn btn-danger btn-sm"
                                                    disabled={cancellingIds().has(job.jobId)}
                                                    aria-busy={cancellingIds().has(job.jobId)}
                                                    onClick={() => cancelJob(job.jobId)}
                                                >
                                                    {cancellingIds().has(job.jobId) ? 'Cancelling…' : 'Cancel'}
                                                </button>
                                            </Show>
                                        </td>
                                    </tr>
                                )}</For>
                            </tbody>
                        </table>
                    </div>
                </Show>
            </LoadingShield>

            {/* Submit job modal */}
            <Show when={showSubmit()}>
                <Modal onClose={() => setShowSubmit(false)} title="Submit Dataproc Job">
                    <JobSubmitForm
                        project={props.project}
                        region={props.region}
                        clusters={clusters()}
                        onSubmitted={() => { setShowSubmit(false); load(); }}
                    />
                </Modal>
            </Show>

            {/* Job detail */}
            <Show when={selectedJob()}>
                <Modal onClose={() => setSelectedJob(null)} title={`Job: ${selectedJob().jobId}`}>
                    <JobDetail job={selectedJob()} project={props.project} region={props.region} />
                </Modal>
            </Show>
        </div>
    );
}

// ============================================================================
// FR3.1: Job Submit Form
// ============================================================================

function JobSubmitForm(props) {
    const [jobType, setJobType] = createSignal('spark');
    const [clusterName, setClusterName] = createSignal('');
    const [mainClass, setMainClass] = createSignal('');
    const [mainPythonFile, setMainPythonFile] = createSignal('');
    const [jarUris, setJarUris] = createSignal('');
    const [pyFiles, setPyFiles] = createSignal('');
    const [jobArgs, setJobArgs] = createSignal('');
    const [properties, setProperties] = createSignal('');
    const [queryText, setQueryText] = createSignal('');
    const [queryFileUri, setQueryFileUri] = createSignal('');
    const [mainJarUri, setMainJarUri] = createSignal('');
    const { busy, error, setError, run } = createFormAction();

    const submit = async (e) => {
        e.preventDefault();
        setError(null);

        // Validate: spark-sql/hive need at least one query source
        if ((jobType() === 'spark-sql' || jobType() === 'hive') && !queryFileUri().trim() && !queryText().trim()) {
            setError('A query file URI or query text is required for ' + jobType());
            return;
        }

        await run(async () => {
            const job = buildJob();
            await api.dataprocSubmitJob(props.project, props.region, job);
            props.onSubmitted();
        });
    };

    const buildJob = () => {
        // Only set clusterName if non-empty (serverless = omit)
        const placement = clusterName() ? { clusterName: clusterName() } : {};
        // Split args by newline to avoid breaking on spaces in values
        const args = jobArgs().split('\n').map(a => a.trim()).filter(a => a);
        const propsMap = {};
        for (const pair of properties().split(',')) {
            const [k, ...vParts] = pair.split('=');
            if (k && k.trim()) propsMap[k.trim()] = vParts.join('=').trim();
        }

        const job = { placement };

        switch (jobType()) {
            case 'spark':
                job.sparkJob = {
                    mainClass: mainClass(),
                    jarFileUris: jarUris().split(',').map(s => s.trim()).filter(s => s),
                    args,
                    properties: propsMap,
                };
                break;
            case 'pyspark':
                job.pysparkJob = {
                    mainPythonFileUri: mainPythonFile(),
                    pythonFileUris: pyFiles().split(',').map(s => s.trim()).filter(s => s),
                    jarFileUris: jarUris().split(',').map(s => s.trim()).filter(s => s),
                    args,
                    properties: propsMap,
                };
                break;
            case 'spark-sql':
                if (queryFileUri()) job.sparkSqlJob = { queryFileUri: queryFileUri(), properties: propsMap };
                else job.sparkSqlJob = { queryList: { queries: [queryText()] }, properties: propsMap };
                break;
            case 'hadoop':
                job.hadoopJob = {
                    mainJarFileUri: mainJarUri(),
                    mainClass: mainClass(),
                    args,
                };
                break;
            case 'hive':
                if (queryFileUri()) job.hiveJob = { queryFileUri: queryFileUri(), properties: propsMap };
                else job.hiveJob = { queryList: { queries: [queryText()] }, properties: propsMap };
                break;
        }
        return job;
    };

    const fs = fieldStyle();

    return (
        <form onSubmit={submit} style={{ 'font-size': 'var(--font-size-sm)', 'min-width': '480px' }}>
            {/* Job type selector */}
            <div style={{ 'margin-bottom': '16px' }}>
                <label class="form-label">Job Type</label>
                <div style={{ display: 'flex', gap: '4px', 'flex-wrap': 'wrap' }}>
                    <For each={['spark', 'pyspark', 'spark-sql', 'hadoop', 'hive']}>{type => (
                        <FilterChip active={jobType() === type} onClick={() => setJobType(type)} label={type} />
                    )}</For>
                </div>
            </div>

            {/* Cluster selector */}
            <div style={{ 'margin-bottom': '12px' }}>
                <label class="form-label">Target Cluster (leave empty for serverless)</label>
                <select class="form-input" style={fs} value={clusterName()} onChange={e => setClusterName(e.currentTarget.value)}>
                    <option value="">— Serverless (no cluster) —</option>
                    <For each={props.clusters}>{c => (
                        <option value={c.clusterName || c.name}>{c.clusterName || c.name}</option>
                    )}</For>
                </select>
            </div>

            {/* Per-type fields */}
            <Switch>
                <Match when={jobType() === 'spark'}>
                    <Field label="Main Class"><input class="form-input" style={fs} value={mainClass()} onInput={e => setMainClass(e.currentTarget.value)} placeholder="org.example.MyApp" required /></Field>
                    <Field label="JAR URIs (comma-separated)"><input class="form-input" style={fs} value={jarUris()} onInput={e => setJarUris(e.currentTarget.value)} placeholder="gs://bucket/app.jar" required /></Field>
                </Match>
                <Match when={jobType() === 'pyspark'}>
                    <Field label="Main Python File URI"><input class="form-input" style={fs} value={mainPythonFile()} onInput={e => setMainPythonFile(e.currentTarget.value)} placeholder="gs://bucket/main.py" required /></Field>
                    <Field label="Additional Python Files"><input class="form-input" style={fs} value={pyFiles()} onInput={e => setPyFiles(e.currentTarget.value)} placeholder="gs://bucket/utils.py,gs://bucket/lib.py" /></Field>
                    <Field label="JAR URIs (optional)"><input class="form-input" style={fs} value={jarUris()} onInput={e => setJarUris(e.currentTarget.value)} placeholder="gs://bucket/connector.jar" /></Field>
                </Match>
                <Match when={jobType() === 'spark-sql'}>
                    <Field label="Query File URI"><input class="form-input" style={fs} value={queryFileUri()} onInput={e => setQueryFileUri(e.currentTarget.value)} placeholder="gs://bucket/query.sql" /></Field>
                    <Field label="Or Query Text"><textarea class="form-input" style={{ ...fs, 'min-height': '80px' }} value={queryText()} onInput={e => setQueryText(e.currentTarget.value)} placeholder="SELECT * FROM my_table" /></Field>
                </Match>
                <Match when={jobType() === 'hadoop'}>
                    <Field label="Main JAR URI"><input class="form-input" style={fs} value={mainJarUri()} onInput={e => setMainJarUri(e.currentTarget.value)} placeholder="gs://bucket/hadoop-job.jar" required /></Field>
                    <Field label="Main Class"><input class="form-input" style={fs} value={mainClass()} onInput={e => setMainClass(e.currentTarget.value)} placeholder="org.example.HadoopJob" /></Field>
                </Match>
                <Match when={jobType() === 'hive'}>
                    <Field label="Query File URI"><input class="form-input" style={fs} value={queryFileUri()} onInput={e => setQueryFileUri(e.currentTarget.value)} placeholder="gs://bucket/query.hql" /></Field>
                    <Field label="Or Query Text"><textarea class="form-input" style={{ ...fs, 'min-height': '80px' }} value={queryText()} onInput={e => setQueryText(e.currentTarget.value)} placeholder="SELECT * FROM my_table" /></Field>
                </Match>
            </Switch>

            {/* Common fields */}
            <Show when={jobType() === 'spark' || jobType() === 'pyspark' || jobType() === 'hadoop'}>
                <Field label="Arguments (one per line)"><textarea class="form-input" style={{ ...fs, 'min-height': '60px' }} value={jobArgs()} onInput={e => setJobArgs(e.currentTarget.value)} placeholder="--input gs://data&#10;--output gs://result" /></Field>
            </Show>
            <Show when={jobType() !== 'hadoop'}>
                <Field label="Properties (comma-separated key=value)"><input class="form-input" style={fs} value={properties()} onInput={e => setProperties(e.currentTarget.value)} placeholder="spark.driver.memory=2g,spark.executor.memory=1g" /></Field>
            </Show>

            <ErrorAlert message={error()} style={{ 'margin-bottom': '8px' }} />

            <button type="submit" disabled={busy()} class="btn btn-primary" style={{ 'margin-top': '12px' }}>
                {busy() ? 'Submitting…' : 'Submit Job'}
            </button>
        </form>
    );
}

function Field(props) {
    return (
        <div style={{ 'margin-bottom': '12px' }}>
            <label class="form-label">{props.label}</label>
            {props.children}
        </div>
    );
}

// ============================================================================
// FR3.3: Job Detail with driver output
// ============================================================================

function JobDetail(props) {
    const job = () => props.job;
    const [driverOutput, setDriverOutput] = createSignal(null);
    const [loadingOutput, setLoadingOutput] = createSignal(false);
    const [outputError, setOutputError] = createSignal(null);

    async function loadOutput() {
        const driverPath = job().driverOutputUri || job().driver_output_path || job().driverOutputPath;
        if (!driverPath) return;
        setLoadingOutput(true);
        setOutputError(null);
        try {
            // Use the browse API to read driver output
            const encodedJobId = encodeURIComponent(job().jobId);
            const data = await api.browse('dataproc', `jobs/${encodedJobId}/output`).catch(async () => {
                // Fallback: try fetching job detail which includes driver output path
                const jobDetail = await api.dataprocGetJob(props.project, props.region, job().jobId);
                if (jobDetail?.driverOutputUri || jobDetail?.driver_output_path) {
                    return { content: '(Driver output path recorded)', output: jobDetail.driverOutputUri || jobDetail.driver_output_path };
                }
                return null;
            });
            setDriverOutput(data);
        } catch (e) {
            setOutputError(e.message);
        }
        setLoadingOutput(false);
    }

    onMount(loadOutput);

    return (
        <div style={{ 'font-size': 'var(--font-size-sm)' }}>
            <div style={{ display: 'flex', gap: '12px', 'margin-bottom': '16px' }}>
                <StatusBadge status={job().status} />
                <span style={{ color: 'var(--text-secondary)', 'font-size': 'var(--font-size-xs)' }}>Cluster: {job().clusterName || 'serverless'}</span>
                <span style={{ color: 'var(--text-secondary)', 'font-size': 'var(--font-size-xs)' }}>Done: {String(job().done)}</span>
            </div>

            <Show when={job().driverOutputUri || job().driver_output_path || job().driverOutputPath}>
                <div style={{ 'margin-bottom': '12px' }}>
                    <strong>Driver Output Log</strong>
                    <Show when={!loadingOutput()} fallback={<div style={{ color: 'var(--text-tertiary)' }}>Loading…</div>}>
                        <pre style={{
                            'background': 'var(--surface-variant)', color: 'var(--text)', padding: '12px', 'border-radius': 'var(--radius-xs)',
                            'font-size': 'var(--font-size-2xs)', 'font-family': 'var(--font-mono)', 'white-space': 'pre-wrap',
                            'max-height': '400px', overflow: 'auto', 'margin-top': '8px',
                        }}>
                            <Show when={driverOutput()?.content || driverOutput()?.output}
                                fallback={<span style={{ color: 'var(--text-tertiary)' }}>{outputError() || 'No output available'}</span>}>
                                {driverOutput().content || driverOutput().output}
                            </Show>
                        </pre>
                    </Show>
                </div>
            </Show>
        </div>
    );
}

// ============================================================================
// Cluster Card (for overview)
// ============================================================================

function ClusterCard(props) {
    const c = () => props.cluster;
    const statusVal = () => {
        if (typeof c().status === 'string') return c().status;
        if (c().status?.state) return c().status.state;
        return 'RUNNING';
    };
    return (
        <div class="card">
            <div style={{ display: 'flex', gap: '8px', 'align-items': 'center', 'margin-bottom': '4px' }}>
                <span style={{ 'font-weight': '600', 'font-size': 'var(--font-size-md)' }}>{c().clusterName || c().name}</span>
                <StatusBadge status={statusVal()} />
            </div>
            <div style={{ 'font-size': 'var(--font-size-2xs)', color: 'var(--text-tertiary)' }}>
                {c().imageVersion || c().config?.imageVersion || 'default image'}
            </div>
        </div>
    );
}

// ============================================================================
// Modal wrapper — accessible, uses console conventions
// ============================================================================

function Modal(props) {
    let panelRef;

    onMount(() => {
        const cleanupFocusTrap = panelRef ? trapFocus(panelRef, props.onClose) : undefined;
        document.body.style.overflow = 'hidden';

        onCleanup(() => {
            cleanupFocusTrap?.();
            document.body.style.overflow = '';
        });
    });

    return (
        <div
            class="modal-overlay"
            role="dialog"
            aria-modal="true"
            aria-label={props.title}
            onClick={e => { if (e.target === e.currentTarget) props.onClose(); }}
        >
            <div
                ref={panelRef}
                class="modal-content modal-card"
                style={{
                    width: props.width || 'min(520px, calc(100vw - 32px))',
                    'max-width': '90vw', 'max-height': '90vh', overflow: 'auto',
                    'min-width': props.minWidth || 'auto',
                }}
            >
                <div style={{ display: 'flex', 'justify-content': 'space-between', 'align-items': 'center', 'margin-bottom': '16px' }}>
                    <h3 style={{ margin: 0, 'font-size': 'var(--font-size-lg)' }}>{props.title}</h3>
                    <button onClick={props.onClose} aria-label="Close" class="btn btn-icon">
                        ×
                    </button>
                </div>
                {props.children}
            </div>
        </div>
    );
}
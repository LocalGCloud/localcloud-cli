import { createSignal, createEffect, createMemo, onCleanup, Show, For } from 'solid-js';
import { api } from '../api.js';
import { formatNumber } from '../utils/format.js';

const SERVICE_NAMES = {
  gcs: 'Cloud Storage',
  pubsub: 'Pub/Sub',
  firestore: 'Firestore',
  bigquery: 'BigQuery',
  secretmanager: 'Secret Manager',
  cloudtasks: 'Cloud Tasks',
  spanner: 'Spanner',
  bigtable: 'Bigtable',
  logging: 'Cloud Logging',
  monitoring: 'Cloud Monitoring',
  gke: 'GKE',
  compute: 'Compute Engine',
  cloudrun: 'Cloud Run',
  memorystore: 'Memorystore (Redis)',
  workflows: 'Cloud Workflows',
  vertexai: 'Vertex AI',
  kms: 'Cloud KMS',
  cloudsql: 'Cloud SQL',
  alloydb: 'AlloyDB',
  cloudscheduler: 'Cloud Scheduler',
  cloudfunctions: 'Cloud Functions',
  dataproc: 'Dataproc',
  cloudiam: 'Cloud IAM',
};

const ALL_SERVICE_IDS = [
  { id: 'gcs', port: 24081, protocol: 'REST', env_var: 'STORAGE_EMULATOR_HOST', endpoint: 'http://localhost:24081' },
  { id: 'pubsub', port: 24082, protocol: 'GRPC', env_var: 'PUBSUB_EMULATOR_HOST', endpoint: 'localhost:24082' },
  { id: 'firestore', port: 24083, protocol: 'GRPC', env_var: 'FIRESTORE_EMULATOR_HOST', endpoint: 'localhost:24083' },
  { id: 'bigtable', port: 24084, protocol: 'GRPC', env_var: 'BIGTABLE_EMULATOR_HOST', endpoint: 'localhost:24084' },
  { id: 'spanner', port: 24085, protocol: 'GRPC', env_var: 'SPANNER_EMULATOR_HOST', endpoint: 'localhost:24085' },
  { id: 'bigquery', port: 24087, protocol: 'REST', env_var: 'BIGQUERY_EMULATOR_HOST', endpoint: 'http://localhost:24087' },
  { id: 'secretmanager', port: 24080, protocol: 'GRPC', env_var: 'SECRET_MANAGER_EMULATOR_HOST', endpoint: 'localhost:24080' },
  { id: 'cloudtasks', port: 24080, protocol: 'GRPC', env_var: 'CLOUD_TASKS_EMULATOR_HOST', endpoint: 'localhost:24080' },
  { id: 'logging', port: 24080, protocol: 'GRPC', env_var: 'CLOUD_LOGGING_EMULATOR_HOST', endpoint: 'localhost:24080' },
  { id: 'monitoring', port: 24080, protocol: 'GRPC', env_var: 'CLOUD_MONITORING_EMULATOR_HOST', endpoint: 'localhost:24080' },
  { id: 'memorystore', port: 24089, protocol: 'REDIS', env_var: 'REDIS_HOST', endpoint: 'localhost:24089' },
  { id: 'gke', port: 24080, protocol: 'GRPC', env_var: 'GKE_EMULATOR_HOST', endpoint: 'localhost:24080' },
  { id: 'compute', port: 24080, protocol: 'REST', env_var: 'COMPUTE_EMULATOR_HOST', endpoint: 'http://localhost:24080' },
  { id: 'cloudrun', port: 24080, protocol: 'GRPC', env_var: 'CLOUD_RUN_EMULATOR_HOST', endpoint: 'localhost:24080' },
  { id: 'workflows', port: 24080, protocol: 'REST', env_var: 'WORKFLOWS_EMULATOR_HOST', endpoint: 'http://localhost:24080' },
  { id: 'vertexai', port: 24080, protocol: 'REST', env_var: 'AIPLATFORM_EMULATOR_HOST', endpoint: 'http://localhost:24080' },
  { id: 'kms', port: 24080, protocol: 'REST', env_var: 'CLOUD_KMS_EMULATOR_HOST', endpoint: 'http://localhost:24080' },
  { id: 'cloudsql', port: 24080, protocol: 'REST', env_var: 'CLOUD_SQL_EMULATOR_HOST', endpoint: 'http://localhost:24080' },
  { id: 'alloydb', port: 24080, protocol: 'GRPC', env_var: 'ALLOYDB_EMULATOR_HOST', endpoint: 'localhost:24080' },
  { id: 'cloudscheduler', port: 24080, protocol: 'GRPC', env_var: 'CLOUD_SCHEDULER_EMULATOR_HOST', endpoint: 'localhost:24080' },
  { id: 'cloudfunctions', port: 24080, protocol: 'GRPC', env_var: 'CLOUD_FUNCTIONS_EMULATOR_HOST', endpoint: 'localhost:24080' },
  { id: 'dataproc', port: 24080, protocol: 'GRPC', env_var: 'DATAPROC_EMULATOR_HOST', endpoint: 'localhost:24080' },
  { id: 'cloudiam', port: 24080, protocol: 'GRPC', env_var: 'IAM_EMULATOR_HOST', endpoint: 'localhost:24080' },
];

function ServiceIcon({ id, size = 20 }) {
  return <img src={`/icons/${id}.svg`} alt="" width={size} height={size} style={{ "object-fit": "contain" }} />;
}

function formatUptime(seconds) {
  if (!seconds && seconds !== 0) return '--';
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  const parts = [];
  if (h > 0) parts.push(`${h}h`);
  if (m > 0) parts.push(`${m}m`);
  parts.push(`${s}s`);
  return parts.join(' ');
}

function formatMemory(mb) {
  if (mb == null) return '--';
  if (mb >= 1024) return `${(mb / 1024).toFixed(2)} GB`;
  return `${Math.round(mb)} MB`;
}

function sameServiceRow(previous, next) {
  if (!previous) return false;
  const keys = Object.keys(next);
  return keys.length === Object.keys(previous).length
    && keys.every(key => Object.is(previous[key], next[key]));
}

/* ================================================================
   Full-background area chart with hover crosshair (CPU / Memory)
   ================================================================ */
function AreaChartCard(props) {
  let cardRef;
  const [hoverRatio, setHoverRatio] = createSignal(null);
  const [hoverRawVal, setHoverRawVal] = createSignal(null);

  const raw = () => props.data();
  const len = () => raw().length;
  const hasData = () => len() >= 2;

  const minVal = () => Math.min(...raw());
  const maxVal = () => Math.max(...raw());
  const range = () => Math.max(maxVal() - minVal(), 1);

  /* Normalise to 0-100 for SVG viewBox */
  const norm = () => raw().map(v => ((v - minVal()) / range()) * 100);

  const polyPoints = () => norm().map((v, i) => `${(i / (len() - 1)) * 100},${100 - v}`).join(' ');
  const currentY = () => {
    const value = props.currentValue();
    if (value == null) return null;
    return 100 - ((value - minVal()) / range()) * 100;
  };
  const areaPath = () => {
    const pts = norm().map((v, i) => `${(i / (len() - 1)) * 100},${100 - v}`).join(' L');
    return `M0,100 L${pts} L100,100 Z`;
  };

  const fillHue = () => {
    const rawV = props.currentValue();
    if (rawV == null) return props.accent;
    if (props.unit === '%') return rawV > 90 ? 'var(--dash-danger)' : rawV > 70 ? 'var(--dash-warning)' : props.accent;
    if (props.unit === 'MB') {
      const mx = props.maxRef?.() || 512;
      const pct = Math.min((rawV / mx) * 100, 100);
      return pct > 90 ? 'var(--dash-danger)' : pct > 75 ? 'var(--dash-warning)' : props.accent;
    }
    return props.accent;
  };

  const handleMove = (e) => {
    if (!hasData() || !cardRef) return;
    const rect = cardRef.getBoundingClientRect();
    const x = (e.clientX - rect.left) / rect.width;
    const ratio = Math.max(0, Math.min(1, x));
    const idx = Math.round(ratio * (len() - 1));
    const clamped = Math.max(0, Math.min(len() - 1, idx));
    setHoverRatio(clamped / (len() - 1));
    setHoverRawVal(raw()[clamped]);
  };

  const handleLeave = () => { setHoverRatio(null); setHoverRawVal(null); };

  const tipLeft = () => {
    const r = hoverRatio();
    if (r == null) return '50%';
    if (r < 0.06) return '6%';
    if (r > 0.94) return '94%';
    return `${r * 100}%`;
  };

  return (
    <div ref={cardRef} class={`dash-card dash-card-chart ${props.class || ''}`}
      style={{ '--dash-accent': props.accent, '--dash-accent-dim': props.accentDim }}>
      {/* ── Background SVG chart ── */}
      <svg class="dash-chart-bg" viewBox="0 0 100 100" preserveAspectRatio="none">
        <defs>
          <linearGradient id={`${props.id}-area`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color={props.accent} stop-opacity="0.35" />
            <stop offset="100%" stop-color={props.accent} stop-opacity="0.02" />
          </linearGradient>
        </defs>
        <Show when={hasData()}>
          <path d={areaPath()} fill={`url(#${props.id}-area)`} />
          <polyline points={polyPoints()} fill="none" stroke={props.accent} stroke-width="1.5" vector-effect="non-scaling-stroke" />
        </Show>
        {/* Grid lines */}
        <line x1="0" y1="25" x2="100" y2="25" stroke="var(--border)" stroke-width="0.25" opacity="0.12" />
        <line x1="0" y1="100" x2="100" y2="100" stroke="var(--border)" stroke-width="0.4" opacity="0.25" />
        <line x1="0" y1="50" x2="100" y2="50" stroke="var(--border)" stroke-width="0.3" opacity="0.12" stroke-dasharray="2,4" />
        <line x1="0" y1="75" x2="100" y2="75" stroke="var(--border)" stroke-width="0.25" opacity="0.12" />
        <Show when={hasData() && currentY() !== null}>
          <line x1="0" y1={currentY()} x2="100" y2={currentY()} stroke={props.accent} stroke-width="0.5" opacity="0.5" stroke-dasharray="4,4" />
        </Show>
        {/* Hover tracking */}
        <rect x="0" y="0" width="100" height="100" fill="transparent"
          onMouseMove={handleMove} onMouseLeave={handleLeave} />
        {/* Crosshair */}
        <Show when={hoverRatio() !== null}>
          <line x1={hoverRatio() * 100} y1="0" x2={hoverRatio() * 100} y2="100"
            stroke={props.accent} stroke-width="0.6" opacity="0.5" />
          <circle cx={hoverRatio() * 100}
            cy={100 - ((hoverRawVal() - minVal()) / range()) * 100}
            r="2.5" fill={props.accent} stroke="var(--surface)" stroke-width="1.2" />
        </Show>
      </svg>

      {/* ── Overlaid content ── */}
      <div class="dash-card-content">
        <div class="dash-card-header">
          <span class="dash-card-label">{props.label}</span>
          <span class="dash-card-value" style={{ color: fillHue() }}>
            {props.currentValue() != null ? props.formatter(props.currentValue()) : '--'}
          </span>
        </div>
        <Show when={props.extra}>
          <span class="dash-card-extra">{props.extra()}</span>
        </Show>
      </div>

      {/* ── Floating hover tooltip ── */}
      <Show when={hoverRawVal() !== null}>
        <div class="dash-chart-tip" style={{ left: tipLeft() }}>
          {props.formatter(hoverRawVal())}
        </div>
      </Show>
    </div>
  );
}

/* ================================================================
   Status hero card — green / degraded with service breakdown
   ================================================================ */
function StatusCard({ healthData, activeCount, inactiveCount, unhealthyCount, totalCount }) {
  const overallHealthy = () => {
    const h = healthData();
    return h && h.status === 'healthy';
  };

  return (
    <div class={`dash-card dash-card-status ${overallHealthy() ? 'is-healthy' : 'is-degraded'}`}>
      <div class="dash-status-indicator">
        <div class="dash-status-pulse">
          <div class="dash-status-dot" />
        </div>
        <span class="dash-status-label">{overallHealthy() ? 'SYSTEM HEALTHY' : 'DEGRADED'}</span>
      </div>
      <div class="dash-status-meta">
        <span class="dash-status-meta-item">
          <span class="dash-status-meta-val active">{activeCount()}</span>
          <span class="dash-status-meta-lbl">Active</span>
        </span>
        <span class="dash-status-meta-item">
          <span class="dash-status-meta-val inactive">{inactiveCount()}</span>
          <span class="dash-status-meta-lbl">Inactive</span>
        </span>
        <span class="dash-status-meta-item">
          <span class="dash-status-meta-val unhealthy">{unhealthyCount()}</span>
          <span class="dash-status-meta-lbl">Unhealthy</span>
        </span>
        <span class="dash-status-meta-item">
          <span class="dash-status-meta-val">{totalCount()}</span>
          <span class="dash-status-meta-lbl">Total</span>
        </span>
      </div>
    </div>
  );
}

/* ================================================================
   Uptime card — uptime and request activity
   ================================================================ */
function UptimeCard({ uptime, startTime, totalRequests }) {
  return (
    <div class="dash-card dash-card-uptime">
      <div class="dash-uptime-col">
        <svg class="dash-uptime-icon" viewBox="0 0 24 24" fill="none" stroke="var(--text-tertiary)" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="10" />
          <polyline points="12 6 12 12 16 14" />
        </svg>
        <span class="dash-card-label">Uptime</span>
        <span class="dash-uptime-value">{formatUptime(uptime())}</span>
        <Show when={startTime()}>
          <span class="dash-uptime-since">since {startTime()}</span>
        </Show>
      </div>
      <div class="dash-uptime-col">
        <span class="dash-card-label">Requests</span>
        <span class="dash-uptime-value">{formatNumber(totalRequests())}</span>
      </div>
    </div>
  );
}

/* ================================================================
   Main Dashboard
   ================================================================ */
export default function Dashboard(props) {
  const [servicesData, setServicesData] = createSignal([]);
  const [fetchError, setFetchError] = createSignal(null);
  const [toggleError, setToggleError] = createSignal(null);
  const [togglingService, setTogglingService] = createSignal(null);
  const [copiedEnvVar, setCopiedEnvVar] = createSignal(null);
  const [autoConfigCopied, setAutoConfigCopied] = createSignal(false);
  const [envCmdCopied, setEnvCmdCopied] = createSignal(false);
  let envCmdCopyTimer;
  let autoConfigCopyTimer;
  let failCounter = 0;
  const MAX_HISTORY = 60;
  const [cpuHistory, setCpuHistory] = createSignal([]);
  const [memHistory, setMemHistory] = createSignal([]);

  const showEnvCmdCopied = () => {
    clearTimeout(envCmdCopyTimer);
    setEnvCmdCopied(true);
    envCmdCopyTimer = setTimeout(() => setEnvCmdCopied(false), 2000);
  };

  const showAutoConfigCopied = () => {
    clearTimeout(autoConfigCopyTimer);
    setAutoConfigCopied(true);
    autoConfigCopyTimer = setTimeout(() => setAutoConfigCopied(false), 2000);
  };

  onCleanup(() => {
    clearTimeout(envCmdCopyTimer);
    clearTimeout(autoConfigCopyTimer);
  });

  const fetchServices = async () => {
    try {
      const data = await api.services();
      if (data && data.services) {
        setServicesData(data.services);
      }
      failCounter = 0;
      setFetchError(null);
    } catch (err) {
      failCounter++;
      if (failCounter >= 3) {
        setFetchError('Cannot reach LocalCloud backend. Is the container running?');
      }
    }
  };

  createEffect(() => {
    const _proj = typeof props.activeProject === 'function' ? props.activeProject() : props.activeProject;
    fetchServices();
    const timer = setInterval(fetchServices, 10000);
    onCleanup(() => clearInterval(timer));
  });

  createEffect(() => {
    const h = props.healthData();
    if (h?.cpu?.system_load != null) {
      setCpuHistory(prev => {
        if (prev.length > 0 && prev[prev.length - 1] === h.cpu.system_load) return prev;
        const next = [...prev, h.cpu.system_load];
        return next.length > MAX_HISTORY ? next.slice(-MAX_HISTORY) : next;
      });
    }
    const totalPhys = h?.memory?.total_physical_mb;
    const freePhys = h?.memory?.free_physical_mb;
    if (totalPhys != null && freePhys != null) {
      const usedPhys = totalPhys - freePhys;
      setMemHistory(prev => {
        if (prev.length > 0 && prev[prev.length - 1] === usedPhys) return prev;
        const next = [...prev, usedPhys];
        return next.length > MAX_HISTORY ? next.slice(-MAX_HISTORY) : next;
      });
    }
  });

  const serviceRowCache = new Map();
  let previousServices = [];
  const services = createMemo(() => {
    const svcList = servicesData();
    const h = props.healthData();
    const healthServices = h?.services || {};
    const liveMap = {};
    for (const svc of svcList) liveMap[svc.id] = svc;
    const nextServices = ALL_SERVICE_IDS.map(def => {
      const live = liveMap[def.id] || {};
      const healthStatus = healthServices[def.id]?.status;
      const next = {
        ...def, ...live,
        id: def.id,
        displayName: SERVICE_NAMES[def.id] || live.name || def.id,
        status: healthStatus || live.status || (live.enabled === false ? 'disabled' : 'unknown'),
        port: live.port || def.port,
        protocol: (live.protocol || def.protocol || '--').toUpperCase(),
        request_count: live.request_count || 0,
        enabled: live.enabled !== undefined ? live.enabled : true,
        enabledSource: live.enabledSource || 'default',
      };
      const previous = serviceRowCache.get(def.id);
      if (sameServiceRow(previous, next)) return previous;
      serviceRowCache.set(def.id, next);
      return next;
    });
    if (previousServices.length === nextServices.length
        && nextServices.every((service, index) => service === previousServices[index])) {
      return previousServices;
    }
    previousServices = nextServices;
    return nextServices;
  });

  const activeCount = () => services().filter(s => s.enabled && s.status === 'healthy').length;
  const inactiveCount = () => services().filter(s => !s.enabled).length;
  const unhealthyCount = () => services().filter(s => s.enabled && s.status !== 'healthy').length;
  const totalCount = () => services().length;
  const totalRequests = () => services().reduce((sum, s) => sum + (s.request_count || 0), 0);

  const handleToggle = async (serviceId, currentlyEnabled) => {
    setTogglingService(serviceId);
    setToggleError(null);
    // Optimistic update — flip local state immediately so checkbox responds
    setServicesData(prev => prev.map(s => s.id === serviceId ? { ...s, enabled: !currentlyEnabled } : s));
    try {
      if (currentlyEnabled) await api.disableService(serviceId);
      else await api.enableService(serviceId);
      await fetchServices();
    } catch (err) {
      // Revert optimistic update on failure
      setServicesData(prev => prev.map(s => s.id === serviceId ? { ...s, enabled: currentlyEnabled } : s));
      setToggleError(`Failed to ${currentlyEnabled ? 'disable' : 'enable'} ${SERVICE_NAMES[serviceId] || serviceId}`);
      setTimeout(() => setToggleError(null), 8000);
    } finally {
      setTogglingService(null);
    }
  };

  const handleRowClick = (serviceId) => {
    if (props.onServiceClick) props.onServiceClick(serviceId);
  };

  const handleCopyEnvVar = async (envVar, endpoint) => {
    if (!envVar || envVar === '--') return;
    let value = endpoint || 'localhost';
    if (value.startsWith('http://') || value.startsWith('https://')) value = value.replace(/^https?:\/\//, '');
    const exportCmd = `export ${envVar}="${value}"`;
    try {
      await navigator.clipboard.writeText(exportCmd);
      setCopiedEnvVar(envVar);
      setTimeout(() => setCopiedEnvVar(null), 2000);
    } catch (e) { console.error('Copy failed:', e); }
  };

  const health = () => props.healthData();
  const uptimeSec = () => health()?.uptime_seconds;
  const memMax = () => health()?.memory?.total_physical_mb;
  const cpuCores = () => health()?.cpu?.available_processors;

  const startTimeStr = () => {
    const sec = uptimeSec();
    if (!sec) return null;
    const d = new Date(Date.now() - sec * 1000);
    return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
  };

  return (
    <div class="dashboard">
      {/* ── Page header ── */}
      <div class="page-header dash-page-header">
        <div>
          <h1>Dashboard</h1>
          <p class="page-header-subtitle">System health overview and emulated service management.</p>
        </div>
      </div>

      <Show when={fetchError()}>
        <div class="alert alert-error" role="alert" style={{ "margin-bottom": "16px" }}>{fetchError()}</div>
      </Show>

      {/* ── Metric cards grid ── */}
      <div class="dash-grid">
        <StatusCard
          healthData={props.healthData}
          activeCount={activeCount}
          inactiveCount={inactiveCount}
          unhealthyCount={unhealthyCount}
          totalCount={totalCount} />

        <AreaChartCard
          id="cpu" class="dash-card-cpu"
          label="CPU" accent="#38bdf8" accentDim="rgba(56,189,248,0.12)"
          data={cpuHistory}
          currentValue={() => health()?.cpu?.system_load}
          unit="%"
          formatter={(v) => `${v.toFixed(1)}%`}
          extra={() => cpuCores() ? `${cpuCores()} cores` : ''}
        />

        <AreaChartCard
          id="memory" class="dash-card-memory"
          label="Memory" accent="#fbbf24" accentDim="rgba(251,191,36,0.12)"
          data={memHistory}
          currentValue={() => {
            const m = health()?.memory;
            if (m?.total_physical_mb != null && m?.free_physical_mb != null) return m.total_physical_mb - m.free_physical_mb;
            return null;
          }}
          unit="MB" maxRef={memMax}
          formatter={(v) => formatMemory(v)}
          extra={() => memMax() ? `of ${formatMemory(memMax())} max` : ''}
        />

        <UptimeCard uptime={uptimeSec} startTime={startTimeStr}
          totalRequests={totalRequests} />
      </div>

      {/* ── Auto-configure Quick Copy ── */}
      <div class="dash-env-banner">
        <div class="dash-env-banner-left">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true" style="flex-shrink:0"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>
          <span>Configure your shell to use LocalCloud:</span>
        </div>
        <code class="dash-env-code">eval "$(curl -s http://localhost:24080/env?format=shell)"</code>
        <button class="dash-env-copy" onClick={async () => {
          try {
            await navigator.clipboard.writeText('eval "$(curl -s http://localhost:24080/env?format=shell)"');
            showEnvCmdCopied();
          } catch {}
        }}>
          <Show when={envCmdCopied()} fallback={
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
          }>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><polyline points="20 6 9 17 4 12"/></svg>
          </Show>
          {envCmdCopied() ? 'Copied!' : 'Copy'}
        </button>
      </div>

      {/* ── APIs & Services section ── */}
      <div class="section" style={{ "margin-top": "32px" }}>
        <div class="dash-section-header">
          <h2 style={{ margin: 0 }}>APIs & Services</h2>
        </div>

      <Show when={toggleError()}>
        <div class="toggle-error" role="alert" style={{ 'margin-bottom': '12px' }}>{toggleError()}</div>
      </Show>

        <div style={{ overflow: 'auto', '-webkit-overflow-scrolling': 'touch' }}>
        <div class="data-table-wrapper dash-table">
          <table class="data-table">
            <thead>
              <tr>
                <th style={{ width: '44px' }}>On</th>
                <th style={{ width: '36px' }}></th>
                <th>Service</th>
                <th>Status</th>
                <th>Port</th>
                <th>Protocol</th>
                <th>Env Var</th>
                <th style={{ "text-align": 'right' }}>Requests</th>
                <th style={{ "text-align": 'right' }}>Memory</th>
              </tr>
            </thead>
            <tbody>
              <For each={services()}>
                {(svc) => {
                  const isHealthy = () => svc.status === 'healthy';
                  const isUnknown = () => svc.status === 'unknown';
                  const isDisabled = () => !svc.enabled || svc.status === 'disabled';
                  const isToggling = () => togglingService() === svc.id;
                  const statusClass = () => {
                    if (isDisabled()) return 'disabled';
                    if (isHealthy()) return 'healthy';
                    if (isUnknown()) return 'warning';
                    return 'unhealthy';
                  };
                  const statusLabel = () => {
                    if (isToggling()) return 'Updating\u2026';
                    if (isDisabled()) return 'Disabled';
                    if (isHealthy()) return 'Healthy';
                    if (isUnknown()) return 'Unknown';
                    return 'Unhealthy';
                  };
                  const protocolBadge = () => {
                    if (svc.protocol === 'GRPC' || svc.protocol === 'gRPC') return 'badge-grpc';
                    if (svc.protocol === 'REDIS') return 'badge-redis';
                    return 'badge-rest';
                  };
                  const envVar = svc.env_var || '--';
                  const endpointVal = svc.endpoint || '--';
                  const copied = copiedEnvVar() === envVar;
                  return (
                    <tr class={isDisabled() ? 'service-row-disabled' : ''}>
                      <td>
                        <label class="toggle-switch">
                          <input type="checkbox" checked={svc.enabled}
                            disabled={isToggling() || svc.id === 'firestore' || svc.id === 'vertexai'}
                            onChange={() => handleToggle(svc.id, svc.enabled)}
                            aria-label={`${svc.displayName} is ${svc.enabled ? 'enabled' : 'disabled'}`} />
                          <span class="toggle-slider" />
                        </label>
                      </td>
                      <td style={{ "text-align": 'center' }}>
                        <ServiceIcon id={svc.id} size={18} />
                      </td>
                      <td style={{ "font-weight": "600" }}>
                        <button
                          type="button"
                          class="service-name-link"
                          onClick={() => handleRowClick(svc.id)}
                          aria-label={`Open ${svc.displayName}`}
                        >
                          <span>{svc.displayName}</span>
                          <Show when={svc.id === 'firestore' || svc.id === 'vertexai'}><span class="badge badge-coming-up">Coming up</span></Show>
                          <Show when={svc.id === 'cloudiam'}><span class="badge badge-warning" title="IAM policies are stored but not enforced in LocalCloud">⚠ Not Enforced</span></Show>
                        </button>
                      </td>
                      <td>
                        <span class="status-indicator">
                          <span class={`status-dot ${statusClass()}`} />
                          {statusLabel()}
                        </span>
                      </td>
                      <td style={{ "font-family": "var(--font-mono)" }}>{svc.port || '--'}</td>
                      <td><span class={`badge ${protocolBadge()}`}>{svc.protocol || '--'}</span></td>
                      <td>
                        <div class="env-var-cell">
                          <code class="env-var-text"
                            title={envVar !== '--' ? `export ${envVar}="${endpointVal.startsWith('http') ? endpointVal.replace(/^https?:\/\//, '') : endpointVal}"` : ''}>
                            {envVar}
                          </code>
                          <button class="env-var-copy-btn" disabled={envVar === '--'}
                            onClick={() => handleCopyEnvVar(envVar, endpointVal)}
                            title={copied ? 'Copied!' : `export ${envVar}="${endpointVal.startsWith('http') ? endpointVal.replace(/^https?:\/\//, '') : endpointVal}"`}
                            aria-label={copied ? `Copied ${envVar}` : `Copy ${envVar} export command`}>
                            {copied
                              ? <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></svg>
                              : <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"/></svg>
                            }
                          </button>
                        </div>
                      </td>
                      <td style={{ "text-align": 'right', "font-weight": '600', "font-family": "var(--font-mono)", "font-size": "12px" }}>
                        {formatNumber(svc.request_count || 0)}
                      </td>
                      <td style={{ "text-align": 'right', "font-weight": '600', "font-family": "var(--font-mono)", "font-size": "12px" }}>
                        {svc.memory_mb > 0 ? formatMemory(svc.memory_mb) : '--'}
                      </td>
                    </tr>
                  );
                }}
              </For>
            </tbody>
          </table>
        </div>
        </div>
      </div>


      <div class="actions-bar">
        <button class="btn btn-secondary" onClick={async () => {
          try {
            const envData = await api.env();
            const lines = Object.entries(envData).map(([k, v]) => `export ${k}="${v}"`).join('\n');
            await navigator.clipboard.writeText(lines);
            showAutoConfigCopied();
          } catch (e) { console.error('Copy failed:', e); }
        }}>
          {autoConfigCopied() ? '✓ Copied!' : 'Copy Env Vars'}
        </button>
        <button class="btn btn-secondary" onClick={async () => {
          try {
            const text = await api.export();
            const blob = new Blob([text], { type: 'application/yaml' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `localcloud-state-${new Date().toISOString().slice(0, 10)}.yaml`;
            a.click();
            URL.revokeObjectURL(url);
          } catch (e) { console.error('Export failed:', e); }
        }}>
          Export State
        </button>
      </div>
    </div>
  );
}

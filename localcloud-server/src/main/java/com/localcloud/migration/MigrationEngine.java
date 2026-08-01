package com.localcloud.migration;

import com.localcloud.admin.ExportService;
import com.localcloud.admin.SeedService;
import com.localcloud.runtime.RuntimeBroker;
import com.localcloud.runtime.RuntimeCatalogStore;
import com.localcloud.runtime.RuntimeProfile;
import com.localcloud.runtime.WorkloadResult;
import com.localcloud.runtime.WorkloadSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/** Executes baseline and target cases against equivalent restored LocalCloud state. */
public final class MigrationEngine implements AutoCloseable {
    private static final List<Map.Entry<String, String>> COMPATIBILITY_PATTERNS = List.of(
            Map.entry("ClassNotFoundException", "DEPENDENCY_CLASS_MISSING"),
            Map.entry("NoClassDefFoundError", "DEPENDENCY_CLASS_MISSING"),
            Map.entry("NoSuchMethodError", "BINARY_API_MISMATCH"),
            Map.entry("UnsupportedClassVersionError", "JAVA_VERSION_MISMATCH"),
            Map.entry("AnalysisException", "SPARK_SQL_ANALYSIS_CHANGE"),
            Map.entry("Py4JJavaError", "PYSPARK_JVM_ERROR"));

    private final String projectId;
    private final Path runRoot;
    private final RuntimeCatalogStore catalog;
    private final RuntimeBroker broker;
    private final MigrationRepository repository;
    private final ExportService exportService;
    private final SeedService seedService;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ReentrantLock emulatorStateLock = new ReentrantLock(true);
    private final Map<String, Set<String>> activeWorkloads = new ConcurrentHashMap<>();
    private final Set<String> cancelledRuns = ConcurrentHashMap.newKeySet();
    private static final String ORIGINAL_STATE_FILE = "original-emulator-state.yaml";

    public MigrationEngine(String projectId, Path dataDir, RuntimeCatalogStore catalog, RuntimeBroker broker,
                           MigrationRepository repository, ExportService exportService, SeedService seedService) {
        this.projectId = projectId;
        this.runRoot = dataDir.resolve("runtime-workspaces").resolve("migration-runs").toAbsolutePath().normalize();
        this.catalog = catalog;
        this.broker = broker;
        this.repository = repository;
        this.exportService = exportService;
        this.seedService = seedService;
        recoverInterruptedRuns();
    }

    public MigrationReport start(MigrationSuite suite) {
        RuntimeProfile baseline = catalog.catalog().resolve(suite.baselineProfile());
        List<RuntimeProfile> targets = suite.targetProfiles().stream().map(catalog.catalog()::resolve).toList();
        requireCapability(baseline, suite.capability());
        targets.forEach(profile -> requireCapability(profile, suite.capability()));
        String runId = UUID.randomUUID().toString();
        MigrationReport queued = report(runId, suite, MigrationReport.State.QUEUED, MigrationReport.Verdict.INFRA_ERROR,
                Instant.now(), null, List.of(), List.of(), "Queued", false);
        activeWorkloads.put(runId, ConcurrentHashMap.newKeySet());
        repository.saveRun(queued);
        executor.submit(() -> execute(runId, suite, baseline, targets));
        return queued;
    }

    public boolean cancel(String runId) {
        MigrationReport current = repository.getRun(runId);
        if (current == null || current.state() == MigrationReport.State.COMPLETED
                || current.state() == MigrationReport.State.FAILED || current.state() == MigrationReport.State.CANCELLED) return false;
        cancelledRuns.add(runId);
        activeWorkloads.getOrDefault(runId, Set.of()).forEach(broker::cancel);
        repository.saveRun(new MigrationReport(current.runId(), current.suiteRevision(),
                MigrationReport.State.CANCELLING, MigrationReport.Verdict.CANCELLED,
                current.startedAt(), current.finishedAt(), current.cases(), current.findings(),
                "Cancellation requested", current.cleanupComplete()));
        return true;
    }

    public MigrationReport retry(String runId) {
        MigrationReport previous = repository.getRun(runId);
        if (previous == null) throw new IllegalArgumentException("Migration run not found");
        if (previous.state() == MigrationReport.State.QUEUED || previous.state() == MigrationReport.State.RUNNING
                || previous.state() == MigrationReport.State.CANCELLING) {
            throw new IllegalStateException("Cannot retry an active migration run");
        }
        int separator = previous.suiteRevision().lastIndexOf('@');
        if (separator < 1) throw new IllegalStateException("Run has an invalid suite revision");
        MigrationSuite suite = repository.getSuite(previous.suiteRevision().substring(0, separator),
                Integer.parseInt(previous.suiteRevision().substring(separator + 1)));
        if (suite == null) throw new IllegalStateException("Migration suite revision no longer exists");
        return start(suite);
    }

    public MigrationReport cleanup(String runId) {
        MigrationReport current = repository.getRun(runId);
        if (current == null) throw new IllegalArgumentException("Migration run not found");
        if (activeWorkloads.containsKey(runId)) throw new IllegalStateException("Cannot clean up an active migration run");
        Path root = runRoot.resolve(runId).normalize();
        if (!root.startsWith(runRoot)) throw new IllegalArgumentException("Invalid migration run id");
        try {
            boolean cleanupComplete = current.cleanupComplete();
            Path snapshot = root.resolve(ORIGINAL_STATE_FILE);
            if (!cleanupComplete && Files.isRegularFile(snapshot)) {
                cleanupComplete = restore(Files.readString(snapshot));
            } else if (!cleanupComplete) {
                cleanupComplete = true;
            }
            if (cleanupComplete && Files.exists(root)) {
                try (var paths = Files.walk(root)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
            MigrationReport updated = new MigrationReport(current.runId(), current.suiteRevision(), current.state(),
                    current.verdict(), current.startedAt(), current.finishedAt(), current.cases(), current.findings(),
                    current.message(), cleanupComplete);
            repository.saveRun(updated);
            return updated;
        } catch (Exception e) {
            throw new IllegalStateException("Migration cleanup failed", e);
        }
    }

    private void recoverInterruptedRuns() {
        for (MigrationReport report : repository.listRuns()) {
            if (report.state() != MigrationReport.State.QUEUED
                    && report.state() != MigrationReport.State.RUNNING
                    && report.state() != MigrationReport.State.CANCELLING) {
                continue;
            }
            MigrationSuite suite = suiteFor(report);
            if (suite != null) {
                broker.cancel("migration-" + report.runId() + "-baseline");
                for (int index = 1; index <= suite.targetProfiles().size(); index++) {
                    broker.cancel("migration-" + report.runId() + "-target-" + index);
                }
            }
            List<MigrationReport.Finding> findings = new ArrayList<>(report.findings());
            findings.add(new MigrationReport.Finding(MigrationReport.Dimension.INFRASTRUCTURE,
                    MigrationReport.Severity.ERROR, "CONTROL_PLANE_RESTART",
                    "LocalCloud restarted during this run; any owned runtime was cancelled without replay", ""));
            boolean cleanupComplete = !Files.isRegularFile(
                    runRoot.resolve(report.runId()).resolve(ORIGINAL_STATE_FILE));
            repository.saveRun(new MigrationReport(report.runId(), report.suiteRevision(),
                    MigrationReport.State.FAILED, MigrationReport.Verdict.INFRA_ERROR,
                    report.startedAt(), Instant.now().toString(), report.cases(), findings,
                    "Interrupted by LocalCloud restart", cleanupComplete));
        }
    }

    private MigrationSuite suiteFor(MigrationReport report) {
        int separator = report.suiteRevision().lastIndexOf('@');
        if (separator < 1) return null;
        try {
            return repository.getSuite(report.suiteRevision().substring(0, separator),
                    Integer.parseInt(report.suiteRevision().substring(separator + 1)));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void execute(String runId, MigrationSuite suite, RuntimeProfile baseline, List<RuntimeProfile> targets) {
        Instant started = Instant.now();
        List<MigrationReport.CaseResult> cases = new ArrayList<>();
        List<MigrationReport.Finding> findings = new ArrayList<>();
        boolean cleanup = false;
        emulatorStateLock.lock();
        String originalState = null;
        try {
            Files.createDirectories(runRoot.resolve(runId));
            originalState = exportService.exportYaml(Set.of());
            Files.writeString(runRoot.resolve(runId).resolve(ORIGINAL_STATE_FILE), originalState);
            repository.saveRun(report(runId, suite, MigrationReport.State.RUNNING, MigrationReport.Verdict.INFRA_ERROR,
                    started, null, cases, findings, "Restoring fixtures", false));
            List<RuntimeProfile> profiles = new ArrayList<>();
            profiles.add(baseline);
            profiles.addAll(targets);
            for (int index = 0; index < profiles.size(); index++) {
                RuntimeProfile profile = profiles.get(index);
                if (cancelledRuns.contains(runId)) throw new CancellationException("Migration run cancelled");
                if (!restore(originalState)) throw new IllegalStateException("Unable to restore equivalent emulator state");
                applyFixture(suite.fixtureYaml(), runId, profile, index);
                MigrationReport.CaseResult result = runCase(runId, suite, profile,
                        index == 0 ? "baseline" : "target-" + index);
                cases.add(result);
                findings.addAll(result.findings());
                repository.saveRun(report(runId, suite, MigrationReport.State.RUNNING, MigrationReport.Verdict.INFRA_ERROR,
                        started, null, cases, findings, "Completed " + cases.size() + " of " + profiles.size() + " cases", false));
            }
            compare(cases, suite.performanceTolerance(), findings);
            cleanup = restore(originalState);
            MigrationReport.Verdict verdict = aggregate(cases, findings, cleanup);
            repository.saveRun(report(runId, suite, MigrationReport.State.COMPLETED, verdict, started, Instant.now(),
                    cases, findings, "Completed", cleanup));
        } catch (CancellationException cancelled) {
            if (originalState != null) cleanup = restore(originalState);
            repository.saveRun(report(runId, suite, MigrationReport.State.CANCELLED, MigrationReport.Verdict.CANCELLED,
                    started, Instant.now(), cases, findings, "Cancelled", cleanup));
        } catch (Exception e) {
            findings.add(new MigrationReport.Finding(MigrationReport.Dimension.INFRASTRUCTURE,
                    MigrationReport.Severity.ERROR, "MIGRATION_INFRA_ERROR", e.getMessage(), ""));
            if (originalState != null) cleanup = restore(originalState);
            repository.saveRun(report(runId, suite, MigrationReport.State.FAILED, MigrationReport.Verdict.INFRA_ERROR,
                    started, Instant.now(), cases, findings, e.getMessage(), cleanup));
        } finally {
            activeWorkloads.remove(runId);
            cancelledRuns.remove(runId);
            emulatorStateLock.unlock();
        }
    }

    private MigrationReport.CaseResult runCase(String runId, MigrationSuite suite, RuntimeProfile profile, String caseId) throws Exception {
        Path output = runRoot.resolve(runId).resolve(caseId).normalize();
        if (!output.startsWith(runRoot)) throw new IllegalArgumentException("invalid output path");
        Files.createDirectories(output);
        List<WorkloadSpec.Mount> mounts = new ArrayList<>();
        for (MigrationSuite.Mount mount : suite.mounts()) {
            mounts.add(new WorkloadSpec.Mount(Path.of(mount.source()), mount.target(), mount.readOnly()));
        }
        mounts.add(new WorkloadSpec.Mount(output, "/localcloud/output", false));
        List<String> command = new ArrayList<>();
        command.add(suite.capability());
        command.addAll(suite.command());
        Map<String, String> environment = new LinkedHashMap<>(endpointEnvironment());
        environment.putAll(suite.environment());
        environment.put("LOCALCLOUD_MIGRATION_RUN", runId);
        environment.put("LOCALCLOUD_MIGRATION_CASE", caseId);
        WorkloadSpec spec = new WorkloadSpec("migration-" + runId + "-" + caseId, projectId,
                "migrationSuites/" + suite.revisionId(), runId, profile, suite.capability(), command,
                environment, mounts, output, Duration.ofSeconds(suite.timeoutSeconds()),
                WorkloadSpec.ResourceLimits.defaults());
        activeWorkloads.computeIfAbsent(runId, ignored -> ConcurrentHashMap.newKeySet()).add(spec.id());
        WorkloadResult workload;
        try {
            workload = broker.submit(spec).join();
        } finally {
            activeWorkloads.getOrDefault(runId, Set.of()).remove(spec.id());
        }
        if (cancelledRuns.contains(runId)) throw new CancellationException("Migration run cancelled");
        Map<String, String> outputs = new LinkedHashMap<>(hashOutputs(output, suite.outputPaths()));
        List<MigrationReport.Finding> caseFindings = new ArrayList<>(compatibilityFindings(caseId, workload.logs()));
        try {
            exportService.captureMigrationState().forEach((key, value) -> outputs.put("emulator:" + key, value));
        } catch (Exception comparisonFailure) {
            caseFindings.add(new MigrationReport.Finding(MigrationReport.Dimension.COMPARISON,
                    MigrationReport.Severity.ERROR, "STATE_CAPTURE_FAILED",
                    comparisonFailure.getMessage(), caseId));
        }
        caseFindings.addAll(MigrationComparator.assertions(suite.assertions(), outputs, workload.logs(), caseId));
        MigrationReport.Verdict verdict;
        if (workload.state() == WorkloadResult.State.INFRA_ERROR) verdict = MigrationReport.Verdict.INFRA_ERROR;
        else if (caseFindings.stream().anyMatch(finding -> finding.dimension() == MigrationReport.Dimension.COMPARISON
                && finding.severity() == MigrationReport.Severity.ERROR)) verdict = MigrationReport.Verdict.COMPARISON_ERROR;
        else if (workload.state() != WorkloadResult.State.SUCCEEDED
                || caseFindings.stream().anyMatch(finding -> finding.severity() == MigrationReport.Severity.ERROR)) verdict = MigrationReport.Verdict.FAIL;
        else verdict = caseFindings.isEmpty() ? MigrationReport.Verdict.PASS : MigrationReport.Verdict.PASS_WITH_WARNINGS;
        return new MigrationReport.CaseResult(caseId, profile.revisionId(), profile.image().digest(), verdict,
                workload, outputs, workload.metrics(), caseFindings);
    }

    private void compare(List<MigrationReport.CaseResult> cases, double tolerance,
                         List<MigrationReport.Finding> findings) {
        if (cases.isEmpty()) return;
        MigrationReport.CaseResult baseline = cases.get(0);
        for (int index = 1; index < cases.size(); index++) {
            findings.addAll(MigrationComparator.cases(baseline, cases.get(index), tolerance));
        }
    }

    private List<MigrationReport.Finding> compatibilityFindings(String caseId, List<String> logs) {
        String text = String.join("", logs);
        List<MigrationReport.Finding> findings = new ArrayList<>();
        for (Map.Entry<String, String> pattern : COMPATIBILITY_PATTERNS) {
            if (text.contains(pattern.getKey())) {
                findings.add(new MigrationReport.Finding(MigrationReport.Dimension.COMPATIBILITY,
                        MigrationReport.Severity.WARNING, pattern.getValue(),
                        "Runtime log contains " + pattern.getKey(), caseId));
            }
        }
        return List.copyOf(findings);
    }


    private Map<String, String> hashOutputs(Path output, List<String> declared) throws Exception {
        Map<String, String> hashes = new LinkedHashMap<>();
        List<Path> files;
        if (declared.isEmpty()) {
            try (var stream = Files.walk(output)) { files = stream.filter(Files::isRegularFile).sorted().toList(); }
        } else {
            files = declared.stream().map(relative -> safeOutput(output, relative)).filter(Files::isRegularFile).sorted().toList();
        }
        for (Path file : files) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            hashes.put(output.relativize(file).toString(), "sha256:" + HexFormat.of().formatHex(digest));
        }
        return Map.copyOf(hashes);
    }

    private static Path safeOutput(Path root, String relative) {
        Path value = root.resolve(relative).normalize();
        if (!value.startsWith(root)) throw new IllegalArgumentException("output path escapes case directory: " + relative);
        return value;
    }

    private void applyFixture(String yaml, String runId, RuntimeProfile profile, int index) throws Exception {
        if (yaml == null || yaml.isBlank()) return;
        String rendered = yaml.replace("${PROJECT_ID}", projectId)
                .replace("${RUN_ID}", runId)
                .replace("${CASE_ID}", index == 0 ? "baseline" : "target-" + index)
                .replace("${PROFILE_ID}", profile.revisionId());
        seedService.seedYaml(rendered, false);
    }

    private boolean restore(String yaml) {
        try {
            seedService.resetProjectData(projectId);
            seedService.seedYaml(yaml, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, String> endpointEnvironment() {
        String host = System.getenv().getOrDefault("LOCALCLOUD_RUNTIME_HOST", "host.docker.internal");
        return Map.of(
                "GOOGLE_CLOUD_PROJECT", projectId,
                "CLOUDSDK_CORE_PROJECT", projectId,
                "STORAGE_EMULATOR_HOST", "http://" + host + ":24081",
                "PUBSUB_EMULATOR_HOST", host + ":24082",
                "SPANNER_EMULATOR_HOST", host + ":24085",
                "BIGQUERY_EMULATOR_HOST", "http://" + host + ":24087",
                "GOOGLE_APPLICATION_CREDENTIALS", "");
    }

    private static void requireCapability(RuntimeProfile profile, String capability) {
        if (!profile.supports(capability)) throw new IllegalArgumentException(profile.revisionId() + " does not support " + capability);
    }

    private static MigrationReport.Verdict aggregate(List<MigrationReport.CaseResult> cases,
                                                      List<MigrationReport.Finding> findings, boolean cleanup) {
        if (cases.stream().anyMatch(result -> result.verdict() == MigrationReport.Verdict.INFRA_ERROR)) return MigrationReport.Verdict.INFRA_ERROR;
        if (cases.stream().anyMatch(result -> result.verdict() == MigrationReport.Verdict.COMPARISON_ERROR))
            return MigrationReport.Verdict.COMPARISON_ERROR;
        if (cases.stream().anyMatch(result -> result.verdict() == MigrationReport.Verdict.FAIL)
                || findings.stream().anyMatch(finding -> finding.severity() == MigrationReport.Severity.ERROR)) return MigrationReport.Verdict.FAIL;
        if (!cleanup || !findings.isEmpty() || cases.stream().anyMatch(result -> result.verdict() == MigrationReport.Verdict.PASS_WITH_WARNINGS))
            return MigrationReport.Verdict.PASS_WITH_WARNINGS;
        return MigrationReport.Verdict.PASS;
    }

    private static MigrationReport report(String runId, MigrationSuite suite, MigrationReport.State state,
                                          MigrationReport.Verdict verdict, Instant started, Instant finished,
                                          List<MigrationReport.CaseResult> cases, List<MigrationReport.Finding> findings,
                                          String message, boolean cleanup) {
        return new MigrationReport(runId, suite.revisionId(), state, verdict, started.toString(),
                finished == null ? "" : finished.toString(), List.copyOf(cases), List.copyOf(findings), message, cleanup);
    }

    @Override public void close() { executor.close(); }
}

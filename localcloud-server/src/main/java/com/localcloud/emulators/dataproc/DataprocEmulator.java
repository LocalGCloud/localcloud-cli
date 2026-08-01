package com.localcloud.emulators.dataproc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.google.cloud.dataproc.v1.CancelJobRequest;
import com.google.cloud.dataproc.v1.Cluster;
import com.google.cloud.dataproc.v1.ClusterControllerGrpc;
import com.google.cloud.dataproc.v1.ClusterStatus;
import com.google.cloud.dataproc.v1.CreateClusterRequest;
import com.google.cloud.dataproc.v1.DeleteClusterRequest;
import com.google.cloud.dataproc.v1.GetClusterRequest;
import com.google.cloud.dataproc.v1.GetJobRequest;
import com.google.cloud.dataproc.v1.Job;
import com.google.cloud.dataproc.v1.JobControllerGrpc;
import com.google.cloud.dataproc.v1.JobReference;
import com.google.cloud.dataproc.v1.JobStatus;
import com.google.cloud.dataproc.v1.ListClustersRequest;
import com.google.cloud.dataproc.v1.ListClustersResponse;
import com.google.cloud.dataproc.v1.ListJobsRequest;
import com.google.cloud.dataproc.v1.ListJobsResponse;
import com.google.cloud.dataproc.v1.SubmitJobRequest;
import com.google.longrunning.Operation;
import com.localcloud.docker.ContainerManager;
import com.localcloud.emulators.AbstractEmulator;
import com.localcloud.emulators.common.GrpcSupport;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import com.localcloud.persistence.PostgresDataSource;
import com.localcloud.runtime.MetadataOnlyRuntimeProvider;
import com.localcloud.runtime.RuntimeBroker;
import com.localcloud.runtime.RuntimeCatalogStore;
import com.localcloud.runtime.RuntimeProfile;
import com.localcloud.runtime.WorkloadResult;
import com.localcloud.runtime.WorkloadSpec;
public class DataprocEmulator extends AbstractEmulator {

    /** Runtime state for a running cluster container. */
    private record ClusterRuntime(String containerId, String networkName) {}

    private final DataprocRepository repository;
    private final ClusterService clusterService = new ClusterService();
    private final JobService jobService = new JobService();
    private final DataprocRestService restService;
    private final RuntimeCatalogStore runtimeCatalog;
    private final RuntimeBroker runtimeBroker;
    private final ContainerManager containerManager;
    private final Path runtimeDirectory;
    private final String dataprocRegistry;
    private final Map<String, String> runningJobs = new ConcurrentHashMap<>();
    private final Map<String, ClusterRuntime> clusterRuntimes = new ConcurrentHashMap<>();
    public DataprocEmulator(PostgresDataSource dataSource) {
        this(dataSource,
                new RuntimeCatalogStore(Path.of(System.getProperty("java.io.tmpdir"), "localcloud-dataproc")),
                new RuntimeBroker(new MetadataOnlyRuntimeProvider()),
                Path.of(System.getProperty("java.io.tmpdir"), "localcloud-dataproc"),
                null,
                "docker.io/jaysen2apache/dataproc");
    }

    public DataprocEmulator(PostgresDataSource dataSource, RuntimeCatalogStore runtimeCatalog,
                            RuntimeBroker runtimeBroker, Path runtimeDirectory,
                            ContainerManager containerManager, String dataprocRegistry) {
        super("dataproc", "Dataproc", 24080, "grpc", "DATAPROC_EMULATOR_HOST");
        this.repository = new DataprocRepository(dataSource);
        this.runtimeCatalog = runtimeCatalog;
        this.runtimeBroker = runtimeBroker;
        this.containerManager = containerManager;
        this.runtimeDirectory = runtimeDirectory.toAbsolutePath().normalize();
        this.dataprocRegistry = dataprocRegistry;
        this.restService = new DataprocRestService(repository, this);
    }

    public DataprocRestService getRestService() { return restService; }
    public ClusterService getClusterService() { return clusterService; }
    public JobService getJobService() { return jobService; }
    @Override protected void doStart() {
        logger.info("Dataproc emulator initialized with runtime mode={} containerManager={}",
                runtimeBroker.provider().mode(), containerManager != null ? "available" : "unavailable");
    }

    @Override protected void doStop() {
        runningJobs.values().forEach(runtimeBroker::cancel);
        runningJobs.clear();
        clusterRuntimes.clear();
    }

    /** Substitute the configured dataproc registry into a resolved profile's image reference. */
    private String resolveImageRef(RuntimeProfile profile) {
        String ref = profile.image().immutableReference();
        // ref is like "docker.io/localcloud/dataproc:tag@sha256:digest"
        int tagIdx = ref.indexOf(':');
        if (tagIdx < 0) return dataprocRegistry + ":" + ref.substring(ref.lastIndexOf('/') + 1);
        String tagAndDigest = ref.substring(tagIdx);
        return dataprocRegistry + tagAndDigest;
    }

    /** Return a profile copy with the image reference substituted to use the configured dataproc registry. */
    private RuntimeProfile withRegistryImage(RuntimeProfile profile) {
        String newRef = resolveImageRef(profile);
        var newImage = new RuntimeProfile.Image(newRef, "", profile.image().allowedRegistries(), profile.image().signature());
        return new RuntimeProfile(profile.id(), profile.technology(), profile.upstreamVersion(),
                profile.revision(), profile.status(), newImage, profile.build(), profile.components(),
                profile.capabilities(), profile.environment(), profile.properties(), profile.limitations());
    }

    @Override protected void doReset() {}

    // --- Mode resolution ---
    // Clusters created via CreateCluster are always persistent (cluster mode).
    // Jobs submitted without a cluster use serverless one-shot execution.

    // --- Cluster container lifecycle ---

    void startClusterContainer(String projectId, String region, String clusterName, Cluster cluster) {
        if (containerManager == null) {
            logger.warn("ContainerManager unavailable; cluster {} will run in metadata-only mode", clusterName);
            return;
        }
        try {
            String imageVersion = cluster.getConfig().getSoftwareConfig().getImageVersion();
            String selector = imageVersion.isBlank() ? "dataproc:default" : imageVersion;
            RuntimeProfile profile;
            try {
                profile = runtimeCatalog.catalog().resolve(selector);
            } catch (IllegalArgumentException first) {
                profile = runtimeCatalog.catalog().resolve(
                        selector.startsWith("dataproc:") ? selector : "dataproc:" + selector);
            }
            String imageRef = resolveImageRef(profile);
            Map<String, String> env = new HashMap<>(emulatorEnvironment(projectId));

            String containerName = "localcloud-dataproc-" + safeContainerName(projectId) + "-"
                    + safeContainerName(clusterName);
            var docker = containerManager.getDockerClient();

            // Pull image if not present
            try {
                docker.inspectImageCmd(imageRef).exec();
            } catch (Exception e) {
                logger.info("Pulling Dataproc runtime image: {}", imageRef);
                docker.pullImageCmd(imageRef).start().awaitCompletion();
            }

            var hostConfig = new com.github.dockerjava.api.model.HostConfig()
                    .withNetworkMode("localcloud-runtime")
                    .withExtraHosts("host.docker.internal:host-gateway");

            List<String> envList = new ArrayList<>();
            env.forEach((k, v) -> envList.add(k + "=" + v));

            var created = docker.createContainerCmd(imageRef)
                    .withName(containerName)
                    .withCmd("sleep", "infinity")
                    .withEnv(envList)
                    .withHostConfig(hostConfig)
                    .withLabels(Map.of(
                            "localcloud.managed", "true",
                            "localcloud.service", "dataproc",
                            "localcloud.project", projectId,
                            "localcloud.cluster", clusterName))
                    .exec();

            docker.startContainerCmd(created.getId()).exec();
            String containerId = created.getId();

            String key = clusterKey(projectId, region, clusterName);
            clusterRuntimes.put(key, new ClusterRuntime(containerId, "localcloud-runtime"));
            logger.info("Dataproc cluster container started: {} (id={})", clusterName, containerId);
        } catch (Exception e) {
            logger.error("Failed to start cluster container for {}: {}", clusterName, e.getMessage());
        }
    }

    private void stopClusterContainer(String key, ClusterRuntime rt) {
        if (containerManager == null || rt == null) return;
        try {
            var docker = containerManager.getDockerClient();
            docker.stopContainerCmd(rt.containerId()).withTimeout(10).exec();
            docker.removeContainerCmd(rt.containerId()).withForce(true).exec();
            logger.info("Dataproc cluster container stopped: {}", rt.containerId());
        } catch (Exception e) {
            logger.warn("Failed to stop cluster container {}: {}", rt.containerId(), e.getMessage());
        }
    }

    void stopClusterContainer(String projectId, String region, String clusterName) {
        String key = clusterKey(projectId, region, clusterName);
        ClusterRuntime rt = clusterRuntimes.remove(key);
        if (rt != null) {
            stopClusterContainer(key, rt);
        }
    }

    // --- Job execution in cluster mode ---

    private WorkloadResult execInCluster(String containerId, DataprocJobAdapter.Command command,
                                         Map<String, String> environment, Path outputDir) {
        try {
            var docker = containerManager.getDockerClient();
            List<String> execCmd = new ArrayList<>();
            execCmd.add("/usr/local/bin/localcloud-runtime");
            execCmd.add(command.capability());
            execCmd.addAll(command.arguments());

            ExecCreateCmdResponse exec = docker.execCreateCmd(containerId)
                    .withCmd(execCmd.toArray(new String[0]))
                    .withEnv(environment.entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .toList())
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();

            List<String> logs = new ArrayList<>();
            docker.execStartCmd(exec.getId())
                    .exec(new ExecStartResultCallback() {
                        @Override
                        public void onNext(Frame frame) {
                            logs.add(new String(frame.getPayload(), StandardCharsets.UTF_8));
                        }
                    })
                    .awaitCompletion();

            var inspect = docker.inspectExecCmd(exec.getId()).exec();
            int exitCode = inspect.getExitCode() != null ? inspect.getExitCode() : -1;
            WorkloadResult.State state = exitCode == 0 ? WorkloadResult.State.SUCCEEDED : WorkloadResult.State.FAILED;

            return new WorkloadResult("exec-" + UUID.randomUUID(), state, containerId,
                    "", exitCode,
                    exitCode == 0 ? WorkloadResult.ErrorCategory.NONE : WorkloadResult.ErrorCategory.EXECUTION,
                    exitCode == 0 ? "Completed" : "Exited with code " + exitCode,
                    Instant.now(), Instant.now(), logs, Map.of(), false);
        } catch (Exception e) {
            return new WorkloadResult("exec-" + UUID.randomUUID(), WorkloadResult.State.INFRA_ERROR,
                    containerId, "", null, WorkloadResult.ErrorCategory.AGENT,
                    e.getMessage(), Instant.now(), Instant.now(), List.of(), Map.of(), false);
        }
    }

    // --- gRPC Services ---

    public class ClusterService extends ClusterControllerGrpc.ClusterControllerImplBase {
        @Override public void createCluster(CreateClusterRequest request, StreamObserver<Operation> responseObserver) {
            incrementRequestCount();
            try {
                String projectId = request.getProjectId();
                String region = request.getRegion();
                String clusterName = request.getCluster().getClusterName();
                if (repository.clusterExists(projectId, region, clusterName))
                    throw Status.ALREADY_EXISTS.asRuntimeException();

                Cluster cluster = request.getCluster().toBuilder()
                        .setProjectId(projectId)
                        .setClusterName(clusterName)
                        .setStatus(ClusterStatus.newBuilder()
                                .setState(ClusterStatus.State.RUNNING)
                                .setStateStartTime(GrpcSupport.timestamp(Instant.now())))
                        .build();
                repository.createCluster(projectId, region, clusterName, cluster);

                // Always start a persistent cluster container (cluster mode)
                startClusterContainer(projectId, region, clusterName, cluster);

                responseObserver.onNext(GrpcSupport.doneOperation(
                        "projects/" + projectId + "/regions/" + region + "/operations/create-" + clusterName,
                        cluster));
                responseObserver.onCompleted();
            } catch (io.grpc.StatusRuntimeException e) {
                responseObserver.onError(e);
            } catch (Exception e) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void getCluster(GetClusterRequest request, StreamObserver<Cluster> responseObserver) {
            incrementRequestCount();
            try {
                Cluster cluster = repository.getCluster(request.getProjectId(), request.getRegion(), request.getClusterName());
                if (cluster == null) throw Status.NOT_FOUND.asRuntimeException();
                responseObserver.onNext(cluster);
                responseObserver.onCompleted();
            } catch (io.grpc.StatusRuntimeException e) {
                responseObserver.onError(e);
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void listClusters(ListClustersRequest request, StreamObserver<ListClustersResponse> responseObserver) {
            incrementRequestCount();
            try {
                responseObserver.onNext(ListClustersResponse.newBuilder()
                        .addAllClusters(repository.listClusters(request.getProjectId(), request.getRegion()))
                        .build());
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void deleteCluster(DeleteClusterRequest request, StreamObserver<Operation> responseObserver) {
            incrementRequestCount();
            try {
                String projectId = request.getProjectId();
                String region = request.getRegion();
                String clusterName = request.getClusterName();
                String ck = clusterKey(projectId, region, clusterName);
                ClusterRuntime rt = clusterRuntimes.remove(ck);
                if (rt != null) {
                    stopClusterContainer(ck, rt);
                }
                if (!repository.deleteCluster(projectId, region, clusterName)) {
                    throw Status.NOT_FOUND.asRuntimeException();
                }
                responseObserver.onNext(Operation.newBuilder()
                        .setName("projects/" + projectId + "/regions/" + region
                                + "/operations/delete-" + clusterName)
                        .setDone(true)
                        .build());
                responseObserver.onCompleted();
            } catch (io.grpc.StatusRuntimeException e) {
                responseObserver.onError(e);
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }
    }
    public class JobService extends JobControllerGrpc.JobControllerImplBase {
        @Override public void submitJob(SubmitJobRequest request, StreamObserver<Job> responseObserver) {
            incrementRequestCount();
            try {
                String projectId = request.getProjectId();
                String region = request.getRegion();
                String clusterName = request.getJob().getPlacement().getClusterName();

                // If no cluster specified, use serverless with auto-generated cluster name
                boolean serverless = clusterName.isBlank();
                if (serverless) {
                    clusterName = "serverless-" + UUID.randomUUID().toString().substring(0, 8);
                }

                Cluster cluster = repository.getCluster(projectId, region, clusterName);
                if (!serverless && cluster == null) {
                    throw Status.NOT_FOUND.withDescription("Cluster not found: " + clusterName).asRuntimeException();
                }

                String jobId = request.getJob().hasReference() && !request.getJob().getReference().getJobId().isBlank()
                        ? request.getJob().getReference().getJobId()
                        : "job-" + UUID.randomUUID();
                Path output = Files.createTempFile("localcloud-dataproc-" + jobId + "-", ".log");
                Job job = request.getJob().toBuilder()
                        .setReference(JobReference.newBuilder().setProjectId(projectId).setJobId(jobId))
                        .setJobUuid(UUID.randomUUID().toString())
                        .setStatus(JobStatus.newBuilder()
                                .setState(JobStatus.State.PENDING)
                                .setStateStartTime(GrpcSupport.timestamp(Instant.now())))
                        .setDriverOutputResourceUri(output.toAbsolutePath().toString())
                        .build();

                if (!serverless) {
                    repository.createJob(projectId, region, jobId, clusterName,
                            JobStatus.State.PENDING.name(), output.toString(), job);
                }

                String ck = clusterKey(projectId, region, clusterName);
                ClusterRuntime rt = clusterRuntimes.get(ck);

                if (rt != null) {
                    // Cluster has a running container — exec into it
                    DataprocJobAdapter.Command adapted = DataprocJobAdapter.adapt(job);
                    Map<String, String> env = emulatorEnvironment(projectId);
                    finishJob(projectId, region, jobId, job, JobStatus.State.RUNNING);
                    WorkloadResult result = execInCluster(rt.containerId(), adapted, env,
                            runtimeDirectory.resolve("jobs").resolve(jobId));
                    appendRuntimeLog(output, result);
                    JobStatus.State state = switch (result.state()) {
                        case SUCCEEDED -> JobStatus.State.DONE;
                        case CANCELLED -> JobStatus.State.CANCELLED;
                        default -> JobStatus.State.ERROR;
                    };
                    finishJob(projectId, region, jobId, job, state);
                } else {
                    // Serverless: one-shot container via RuntimeBroker
                    if (serverless) {
                        // Auto-create a virtual cluster record for the serverless job
                        var virtualCluster = Cluster.newBuilder()
                                .setProjectId(projectId)
                                .setClusterName(clusterName)
                                .setStatus(ClusterStatus.newBuilder().setState(ClusterStatus.State.RUNNING).build())
                                .build();
                        repository.createCluster(projectId, region, clusterName, virtualCluster);
                        repository.createJob(projectId, region, jobId, clusterName,
                                JobStatus.State.PENDING.name(), output.toString(), job);
                    }
                    startRuntimeWorkload(projectId, region, jobId, clusterName, job, output);
                }

                responseObserver.onNext(job);
                responseObserver.onCompleted();
            } catch (io.grpc.StatusRuntimeException e) {
                responseObserver.onError(e);
            } catch (Exception e) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void getJob(GetJobRequest request, StreamObserver<Job> responseObserver) {
            incrementRequestCount();
            try {
                Job job = repository.getJob(request.getProjectId(), request.getRegion(), request.getJobId());
                if (job == null) throw Status.NOT_FOUND.asRuntimeException();
                responseObserver.onNext(job);
                responseObserver.onCompleted();
            } catch (io.grpc.StatusRuntimeException e) {
                responseObserver.onError(e);
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void listJobs(ListJobsRequest request, StreamObserver<ListJobsResponse> responseObserver) {
            incrementRequestCount();
            try {
                responseObserver.onNext(ListJobsResponse.newBuilder()
                        .addAllJobs(repository.listJobs(request.getProjectId(), request.getRegion()))
                        .build());
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void cancelJob(CancelJobRequest request, StreamObserver<Job> responseObserver) {
            incrementRequestCount();
            try {
                String jobKey = key(request.getProjectId(), request.getRegion(), request.getJobId());
                String workloadId = runningJobs.remove(jobKey);
                if (workloadId != null) runtimeBroker.cancel(workloadId);
                Job current = repository.getJob(request.getProjectId(), request.getRegion(), request.getJobId());
                if (current == null) throw Status.NOT_FOUND.asRuntimeException();
                Job cancelled = current.toBuilder()
                        .setStatus(JobStatus.newBuilder()
                                .setState(JobStatus.State.CANCELLED)
                                .setStateStartTime(GrpcSupport.timestamp(Instant.now())))
                        .setDone(true)
                        .build();
                repository.updateJob(request.getProjectId(), request.getRegion(), request.getJobId(),
                        JobStatus.State.CANCELLED.name(), cancelled);
                responseObserver.onNext(cancelled);
                responseObserver.onCompleted();
            } catch (io.grpc.StatusRuntimeException e) {
                responseObserver.onError(e);
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }
    }

    private void startRuntimeWorkload(String projectId, String region, String jobId, String clusterName,
                                      Job job, Path output) {
        try {
            Cluster cluster = repository.getCluster(projectId, region, clusterName);
            if (cluster == null) throw new IllegalArgumentException("Cluster not found: " + clusterName);
            String selector = cluster.getConfig().getSoftwareConfig().getImageVersion();
            if (selector.isBlank()) selector = "dataproc:default";
            RuntimeProfile profile;
            try {
                profile = runtimeCatalog.catalog().resolve(selector);
            } catch (IllegalArgumentException first) {
                profile = runtimeCatalog.catalog().resolve(selector.startsWith("dataproc:") ? selector : "dataproc:" + selector);
            }
            DataprocJobAdapter.Command adapted = DataprocJobAdapter.adapt(job);
            List<String> command = new ArrayList<>();
            command.add(adapted.capability());
            Path jobDirectory = runtimeDirectory.resolve("jobs").resolve(jobId).normalize();
            Files.createDirectories(jobDirectory);
            Map<String, String> environment = emulatorEnvironment(projectId);
            WorkloadSpec spec = new WorkloadSpec(
                    "dataproc-" + projectId + "-" + region + "-" + jobId,
                    projectId,
                    "projects/" + projectId + "/regions/" + region + "/jobs/" + jobId,
                    jobId,
                    withRegistryImage(profile),
                    adapted.capability(),
                    command,
                    environment,
                    List.of(new WorkloadSpec.Mount(jobDirectory, "/localcloud/output", false)),
                    jobDirectory,
                    Duration.ofHours(1),
                    WorkloadSpec.ResourceLimits.defaults());
            runningJobs.put(key(projectId, region, jobId), spec.id());
            finishJob(projectId, region, jobId, job, JobStatus.State.RUNNING);
            runtimeBroker.submit(spec).whenComplete((result, failure) -> {
                runningJobs.remove(key(projectId, region, jobId));
                if (failure != null || result == null) {
                    logger.error("Dataproc runtime failed for job {}: {}", jobId,
                            failure == null ? "missing result" : failure.getMessage());
                    finishJob(projectId, region, jobId, job, JobStatus.State.ERROR);
                    return;
                }
                appendRuntimeLog(output, result);
                JobStatus.State state = switch (result.state()) {
                    case SUCCEEDED -> JobStatus.State.DONE;
                    case CANCELLED -> JobStatus.State.CANCELLED;
                    default -> JobStatus.State.ERROR;
                };
                finishJob(projectId, region, jobId, job, state);
            });
        } catch (Exception e) {
            logger.error("Failed to submit Dataproc runtime job {}: {}", jobId, e.getMessage());
            appendRuntimeLog(output, e.getMessage());
            finishJob(projectId, region, jobId, job, JobStatus.State.ERROR);
        }
    }

    private static Map<String, String> emulatorEnvironment(String projectId) {
        String host = System.getenv().getOrDefault("LOCALCLOUD_RUNTIME_HOST", "host.docker.internal");
        Map<String, String> environment = new HashMap<>();
        // Standard GCP emulator env vars
        environment.put("GOOGLE_CLOUD_PROJECT", projectId);
        environment.put("CLOUDSDK_CORE_PROJECT", projectId);
        environment.put("STORAGE_EMULATOR_HOST", "http://" + host + ":24081");
        environment.put("PUBSUB_EMULATOR_HOST", host + ":24082");
        environment.put("SPANNER_EMULATOR_HOST", host + ":24085");
        environment.put("BIGQUERY_EMULATOR_HOST", "http://" + host + ":24087");
        environment.put("GOOGLE_APPLICATION_CREDENTIALS", "");
        // Runtime entrypoint integration variables
        environment.put("LOCALCLOUD_GCS_ENDPOINT", "http://" + host + ":24081");
        environment.put("LOCALCLOUD_BIGQUERY_ENDPOINT", "http://" + host + ":24087");
        environment.put("LOCALCLOUD_BIGQUERY_STORAGE_ENDPOINT", "http://" + host + ":24088");
        environment.put("LOCALCLOUD_GOOGLE_CLOUD_PROJECT", projectId);
        // Enable all integrations for Dataproc jobs
        environment.put("LOCALCLOUD_REQUIRED_INTEGRATIONS", "gcs,bigquery");
        return Map.copyOf(environment);
    }

    private static void appendRuntimeLog(Path output, WorkloadResult result) {
        appendRuntimeLog(output, String.join("", result.logs()) + System.lineSeparator() + result.message());
    }

    private static void appendRuntimeLog(Path output, String message) {
        try {
            Files.writeString(output, message + System.lineSeparator(), java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // Job state remains authoritative if the debug log cannot be written.
        }
    }

    private void finishJob(String projectId, String region, String jobId, Job job, JobStatus.State state) {
        try {
            Job updated = job.toBuilder()
                    .setStatus(JobStatus.newBuilder().setState(state).setStateStartTime(GrpcSupport.timestamp(Instant.now())))
                    .setDone(state == JobStatus.State.DONE || state == JobStatus.State.ERROR || state == JobStatus.State.CANCELLED)
                    .build();
            repository.updateJob(projectId, region, jobId, state.name(), updated);
        } catch (Exception e) {
            logger.warn("Failed to update Dataproc job status: {}", e.getMessage());
        }
    }

    private static String key(String projectId, String region, String jobId) {
        return projectId + "/" + region + "/" + jobId;
    }

    private static String clusterKey(String projectId, String region, String clusterName) {
        return projectId + "/" + region + "/" + clusterName;
    }

    private static String safeContainerName(String value) {
        return value.replaceAll("[^a-zA-Z0-9_.-]", "-");
    }
}

package com.localcloud.emulators.dataproc;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
import com.localcloud.emulators.AbstractEmulator;
import com.localcloud.emulators.common.GrpcSupport;
import com.localcloud.persistence.PostgresDataSource;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

public class DataprocEmulator extends AbstractEmulator {
    private final DataprocRepository repository;
    private final ClusterService clusterService = new ClusterService();
    private final JobService jobService = new JobService();
    private final Map<String, Process> runningJobs = new ConcurrentHashMap<>();

    public DataprocEmulator(PostgresDataSource dataSource) {
        super("dataproc", "Dataproc", 8080, "grpc", "DATAPROC_EMULATOR_HOST");
        this.repository = new DataprocRepository(dataSource);
    }

    public ClusterService getClusterService() {
        return clusterService;
    }

    public JobService getJobService() {
        return jobService;
    }

    @Override protected void doStart() {
        logger.info("Dataproc emulator initialized; spark-submit will be resolved from PATH on job submission");
    }

    @Override protected void doStop() {
        runningJobs.values().forEach(Process::destroyForcibly);
        runningJobs.clear();
    }

    @Override protected void doReset() {}

    public class ClusterService extends ClusterControllerGrpc.ClusterControllerImplBase {
        @Override public void createCluster(CreateClusterRequest request, StreamObserver<Operation> responseObserver) {
            incrementRequestCount();
            try {
                String projectId = request.getProjectId();
                String region = request.getRegion();
                String clusterName = request.getCluster().getClusterName();
                if (repository.clusterExists(projectId, region, clusterName)) throw Status.ALREADY_EXISTS.asRuntimeException();
                Cluster cluster = request.getCluster().toBuilder()
                        .setProjectId(projectId)
                        .setClusterName(clusterName)
                        .setStatus(ClusterStatus.newBuilder()
                                .setState(ClusterStatus.State.RUNNING)
                                .setStateStartTime(GrpcSupport.timestamp(Instant.now())))
                        .build();
                repository.createCluster(projectId, region, clusterName, cluster);
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
                if (!repository.deleteCluster(request.getProjectId(), request.getRegion(), request.getClusterName())) {
                    throw Status.NOT_FOUND.asRuntimeException();
                }
                responseObserver.onNext(Operation.newBuilder()
                        .setName("projects/" + request.getProjectId() + "/regions/" + request.getRegion()
                                + "/operations/delete-" + request.getClusterName())
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
                String clusterName = request.getJob().getPlacement().getClusterName();
                if (repository.getCluster(request.getProjectId(), request.getRegion(), clusterName) == null) {
                    throw Status.NOT_FOUND.withDescription("Cluster not found: " + clusterName).asRuntimeException();
                }
                String jobId = request.getJob().hasReference() && !request.getJob().getReference().getJobId().isBlank()
                        ? request.getJob().getReference().getJobId()
                        : "job-" + UUID.randomUUID();
                Path output = Files.createTempFile("localcloud-dataproc-" + jobId + "-", ".log");
                Job job = request.getJob().toBuilder()
                        .setReference(JobReference.newBuilder().setProjectId(request.getProjectId()).setJobId(jobId))
                        .setJobUuid(UUID.randomUUID().toString())
                        .setStatus(JobStatus.newBuilder()
                                .setState(JobStatus.State.PENDING)
                                .setStateStartTime(GrpcSupport.timestamp(Instant.now())))
                        .setDriverOutputResourceUri(output.toAbsolutePath().toString())
                        .build();
                repository.createJob(request.getProjectId(), request.getRegion(), jobId, clusterName,
                        JobStatus.State.PENDING.name(), output.toString(), job);
                startSparkProcess(request.getProjectId(), request.getRegion(), jobId, job, output);
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
                Process process = runningJobs.remove(jobKey);
                if (process != null) process.destroyForcibly();
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

    private void startSparkProcess(String projectId, String region, String jobId, Job job, Path output) {
        String sparkHome = System.getenv("SPARK_HOME");
        if (sparkHome != null && !sparkHome.isBlank()) {
            logger.info("Using SPARK_HOME={} for job {}", sparkHome, jobId);
        }
        
        List<String> command = new ArrayList<>();
        if (sparkHome != null && !sparkHome.isBlank()) {
            command.add(sparkHome + "/bin/spark-submit");
        } else {
            command.add("spark-submit");
        }
        command.add("--master");
        command.add("local[*]");
        if (job.hasSparkJob()) {
            if (!job.getSparkJob().getMainClass().isBlank()) {
                command.add("--class");
                command.add(job.getSparkJob().getMainClass());
            }
            command.addAll(job.getSparkJob().getJarFileUrisList());
            command.addAll(job.getSparkJob().getArgsList());
        } else if (job.hasPysparkJob()) {
            command.add(job.getPysparkJob().getMainPythonFileUri());
            command.addAll(job.getPysparkJob().getArgsList());
        } else if (job.hasSparkSqlJob()) {
            command.add("--class");
            command.add("org.apache.spark.sql.hive.thriftserver.SparkSQLCLIDriver");
            if (job.getSparkSqlJob().hasQueryFileUri()) {
                command.add(job.getSparkSqlJob().getQueryFileUri());
            }
        } else {
            finishJob(projectId, region, jobId, job, JobStatus.State.ERROR);
            return;
        }
        try {
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(output.toFile()))
                    .redirectError(ProcessBuilder.Redirect.appendTo(output.toFile()))
                    .directory(new File("."))
                    .start();
            runningJobs.put(key(projectId, region, jobId), process);
            finishJob(projectId, region, jobId, job.toBuilder()
                    .setStatus(JobStatus.newBuilder().setState(JobStatus.State.RUNNING)
                            .setStateStartTime(GrpcSupport.timestamp(Instant.now())))
                    .build(), JobStatus.State.RUNNING);
            Thread.ofVirtual().start(() -> {
                try {
                    int exit = process.waitFor();
                    runningJobs.remove(key(projectId, region, jobId));
                    finishJob(projectId, region, jobId, job, exit == 0 ? JobStatus.State.DONE : JobStatus.State.ERROR);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    runningJobs.remove(key(projectId, region, jobId));
                    finishJob(projectId, region, jobId, job, JobStatus.State.CANCELLED);
                }
            });
        } catch (IOException e) {
            logger.error("Failed to start spark-submit. Set SPARK_HOME env var or ensure spark-submit is in PATH. Error: {}", e.getMessage());
            finishJob(projectId, region, jobId, job, JobStatus.State.ERROR);
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
}

package com.localcloud.emulators.gke;

import com.google.container.v1.*;
import com.google.protobuf.Timestamp;
import com.localcloud.admin.UnsupportedOperationResponses;
import com.localcloud.emulators.AbstractEmulator;
import com.localcloud.persistence.PostgresDataSource;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * GKE emulator. Creates real k3d (lightweight k3s) clusters via the k3d CLI.
 */
public class GkeEmulator extends AbstractEmulator {

    private static final AtomicInteger nextK8sPort = new AtomicInteger(6443);

    private final PostgresDataSource dataSource;
    private final K3dManager k3dManager;
    private final GkeStore store;
    private final ClusterManagerServiceImpl clusterManagerService;

    public GkeEmulator(PostgresDataSource dataSource, K3dManager k3dManager) {
        super("gke", "GKE", 8080, "grpc", "GKE_EMULATOR_HOST");
        this.dataSource = dataSource;
        this.k3dManager = k3dManager;
        this.store = new GkeStore(dataSource);
        this.clusterManagerService = new ClusterManagerServiceImpl();
    }

    @Override
    protected void doStart() throws Exception {
        logger.info("GKE emulator gRPC services ready");
    }

    @Override
    protected void doStop() {
        k3dManager.deleteAllClusters();
    }

    @Override
    protected void doReset() {
        doStop();
        try {
            store.deleteAll();
        } catch (Exception e) {
            logger.error("Failed to reset GKE data", e);
        }
    }

    public ClusterManagerServiceImpl getClusterManagerService() { return clusterManagerService; }

    // --- Resource name parsing ---

    private static String[] parseParent(String parent) {
        // projects/{project}/locations/{location}
        String[] parts = parent.split("/");
        if (parts.length >= 4) {
            return new String[]{parts[1], parts[3]};
        }
        return null;
    }

    private static String[] parseClusterName(String name) {
        // projects/{project}/locations/{location}/clusters/{cluster}
        String[] parts = name.split("/");
        if (parts.length >= 6) {
            return new String[]{parts[1], parts[3], parts[5]};
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private Cluster buildClusterProto(GkeStore.Cluster c) {
        Cluster.Builder builder = Cluster.newBuilder()
                .setName(c.clusterId())
                .setSelfLink("projects/" + c.projectId() + "/locations/" + c.location() + "/clusters/" + c.clusterId())
                .setLocation(c.location())
                .setInitialClusterVersion(c.clusterVersion())
                .setCurrentMasterVersion(c.clusterVersion())
                .setCurrentNodeVersion(c.clusterVersion())
                .setInitialNodeCount(c.nodeCount());

        if (c.endpoint() != null) {
            builder.setEndpoint(c.endpoint());
        }

        switch (c.status()) {
            case "RUNNING" -> builder.setStatus(Cluster.Status.RUNNING);
            case "PROVISIONING" -> builder.setStatus(Cluster.Status.PROVISIONING);
            case "STOPPING" -> builder.setStatus(Cluster.Status.STOPPING);
            case "ERROR" -> builder.setStatus(Cluster.Status.ERROR);
            default -> builder.setStatus(Cluster.Status.STATUS_UNSPECIFIED);
        }

        if (c.createdAt() != null) {
            builder.setCreateTime(c.createdAt().toInstant().toString());
        }

        // Add master auth with kubeconfig cluster CA if available
        if (c.kubeconfig() != null && !c.kubeconfig().isEmpty()) {
            builder.setMasterAuth(MasterAuth.newBuilder()
                    .setClusterCaCertificate("emulated-ca-cert")
                    .build());
        }

        return builder.build();
    }

    // --- gRPC Service implementation ---

    public class ClusterManagerServiceImpl extends ClusterManagerGrpc.ClusterManagerImplBase {

        @SuppressWarnings("deprecation")
        @Override
        public void createCluster(CreateClusterRequest request, StreamObserver<Operation> responseObserver) {
            incrementRequestCount();
            try {
                String[] parent = parseParent(request.getParent());
                if (parent == null) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("Invalid parent: " + request.getParent()).asRuntimeException());
                    return;
                }
                String projectId = parent[0];
                String location = parent[1];
                Cluster reqCluster = request.getCluster();
                String clusterId = reqCluster.getName();

                if (clusterId.isEmpty()) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("Cluster name is required").asRuntimeException());
                    return;
                }

                // Check if already exists
                if (store.getCluster(projectId, location, clusterId).isPresent()) {
                    responseObserver.onError(Status.ALREADY_EXISTS
                            .withDescription("Cluster " + clusterId + " already exists").asRuntimeException());
                    return;
                }

                int nodeCount = reqCluster.getInitialNodeCount() > 0 ? reqCluster.getInitialNodeCount() : 1;
                String version = reqCluster.getInitialClusterVersion().isEmpty()
                        ? "1.28" : reqCluster.getInitialClusterVersion();

                // Create k3d cluster with dynamic port allocation
                int apiPort = nextK8sPort.getAndIncrement();
                String k3dName;
                String endpoint;
                String kubeconfig;
                try {
                    k3dName = k3dManager.createCluster(clusterId, apiPort);
                    endpoint = "https://localhost:" + apiPort;
                    kubeconfig = k3dManager.getKubeconfig(k3dName);
                } catch (Exception e) {
                    logger.warn("k3d not available, using simulated cluster: {}", e.getMessage());
                    k3dName = "simulated-" + clusterId;
                    endpoint = "https://localhost:" + apiPort;
                    kubeconfig = "";
                }

                var cluster = new GkeStore.Cluster(
                        projectId, location, clusterId, "RUNNING",
                        k3dName, endpoint, version, nodeCount, kubeconfig, null);
                store.insertCluster(cluster);

                Operation op = Operation.newBuilder()
                        .setName("projects/" + projectId + "/locations/" + location + "/operations/create-" + clusterId)
                        .setStatus(Operation.Status.DONE)
                        .setTargetLink("projects/" + projectId + "/locations/" + location + "/clusters/" + clusterId)
                        .build();

                responseObserver.onNext(op);
                responseObserver.onCompleted();
            } catch (Exception e) {
                logger.error("createCluster failed", e);
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void getCluster(GetClusterRequest request, StreamObserver<Cluster> responseObserver) {
            incrementRequestCount();
            try {
                String[] parsed = parseClusterName(request.getName());
                if (parsed == null) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("Invalid cluster name").asRuntimeException());
                    return;
                }
                var opt = store.getCluster(parsed[0], parsed[1], parsed[2]);
                if (opt.isEmpty()) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Cluster not found: " + request.getName()).asRuntimeException());
                    return;
                }
                responseObserver.onNext(buildClusterProto(opt.get()));
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void listClusters(ListClustersRequest request, StreamObserver<ListClustersResponse> responseObserver) {
            incrementRequestCount();
            try {
                String[] parent = parseParent(request.getParent());
                if (parent == null) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("Invalid parent").asRuntimeException());
                    return;
                }
                var clusters = store.listClusters(parent[0], parent[1]);
                ListClustersResponse.Builder resp = ListClustersResponse.newBuilder();
                for (var c : clusters) {
                    resp.addClusters(buildClusterProto(c));
                }
                responseObserver.onNext(resp.build());
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void deleteCluster(DeleteClusterRequest request, StreamObserver<Operation> responseObserver) {
            incrementRequestCount();
            try {
                String[] parsed = parseClusterName(request.getName());
                if (parsed == null) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("Invalid cluster name").asRuntimeException());
                    return;
                }
                String projectId = parsed[0];
                String location = parsed[1];
                String clusterId = parsed[2];

                var opt = store.getCluster(projectId, location, clusterId);
                if (opt.isEmpty()) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Cluster not found").asRuntimeException());
                    return;
                }

                var cluster = opt.get();
                if (cluster.k3dClusterName() != null && !cluster.k3dClusterName().startsWith("simulated-")) {
                    try {
                        k3dManager.deleteCluster(cluster.k3dClusterName());
                    } catch (Exception e) {
                        logger.warn("Failed to delete k3d cluster: {}", e.getMessage());
                    }
                }
                store.deleteCluster(projectId, location, clusterId);

                Operation op = Operation.newBuilder()
                        .setName("projects/" + projectId + "/locations/" + location + "/operations/delete-" + clusterId)
                        .setStatus(Operation.Status.DONE)
                        .build();

                responseObserver.onNext(op);
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void getServerConfig(GetServerConfigRequest request, StreamObserver<ServerConfig> responseObserver) {
            incrementRequestCount();
            ServerConfig config = ServerConfig.newBuilder()
                    .setDefaultClusterVersion("1.28")
                    .addValidMasterVersions("1.28")
                    .addValidMasterVersions("1.27")
                    .addValidMasterVersions("1.26")
                    .addValidNodeVersions("1.28")
                    .addValidNodeVersions("1.27")
                    .addValidNodeVersions("1.26")
                    .setDefaultImageType("COS_CONTAINERD")
                    .build();

            responseObserver.onNext(config);
            responseObserver.onCompleted();
        }

        @Override
        public void createNodePool(CreateNodePoolRequest request, StreamObserver<Operation> responseObserver) {
            incrementRequestCount();
            UnsupportedOperationResponses.grpc(responseObserver, "gke", "nodepools.create",
                    "Use cluster metadata CRUD until host-runtime GKE support is enabled.");
        }

        @Override
        public void setNodePoolAutoscaling(SetNodePoolAutoscalingRequest request,
                                           StreamObserver<Operation> responseObserver) {
            incrementRequestCount();
            UnsupportedOperationResponses.grpc(responseObserver, "gke", "nodepools.autoscaling",
                    "Use cluster metadata CRUD until host-runtime GKE support is enabled.");
        }
    }
}

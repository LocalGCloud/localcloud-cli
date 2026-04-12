package com.localcloud.emulators.cloudrun;

import com.google.cloud.run.v2.*;
import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import com.google.protobuf.Timestamp;
import com.localcloud.admin.CredentialBroker;
import com.localcloud.docker.ContainerManager;
import com.localcloud.emulators.AbstractEmulator;
import com.localcloud.persistence.PostgresDataSource;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cloud Run emulator. Deploys real Docker containers and routes HTTP traffic.
 */
public class CloudRunEmulator extends AbstractEmulator {

    private final PostgresDataSource dataSource;
    private final ContainerManager containerManager;
    private final CredentialBroker credentialBroker;
    private final CloudRunStore store;
    private final ServicesServiceImpl servicesService;
    private final RevisionsServiceImpl revisionsService;
    private final AtomicInteger nextPort = new AtomicInteger(10000);

    public CloudRunEmulator(PostgresDataSource dataSource, ContainerManager containerManager) {
        this(dataSource, containerManager, null);
    }

    public CloudRunEmulator(PostgresDataSource dataSource, ContainerManager containerManager, CredentialBroker credentialBroker) {
        super("cloudrun", "Cloud Run", 8080, "grpc", "CLOUD_RUN_EMULATOR_HOST");
        this.dataSource = dataSource;
        this.containerManager = containerManager;
        this.credentialBroker = credentialBroker;
        this.store = new CloudRunStore(dataSource);
        this.servicesService = new ServicesServiceImpl();
        this.revisionsService = new RevisionsServiceImpl();
    }

    @Override
    protected void doStart() throws Exception {
        // Initialize port counter from database to avoid conflicts with surviving containers
        int maxPort = store.getMaxUsedPort();
        nextPort.set(Math.max(10000, maxPort + 1));
        logger.info("Cloud Run emulator gRPC services ready (next port: {})", nextPort.get());
    }

    @Override
    protected void doStop() {
        try {
            var containers = containerManager.listByLabel("localcloud.service", "cloudrun");
            for (var container : containers) {
                containerManager.stop(container.getId());
                containerManager.remove(container.getId());
            }
        } catch (Exception e) {
            logger.warn("Error cleaning up Cloud Run containers: {}", e.getMessage());
        }
    }

    @Override
    protected void doReset() {
        doStop();
        try {
            store.deleteAll();
        } catch (Exception e) {
            logger.error("Failed to reset Cloud Run data", e);
        }
    }

    public ServicesServiceImpl getServicesService() { return servicesService; }
    public RevisionsServiceImpl getRevisionsService() { return revisionsService; }

    // --- Parse resource name helpers ---

    private static String[] parseServiceName(String name) {
        // projects/{project}/locations/{location}/services/{service}
        String[] parts = name.split("/");
        if (parts.length >= 6) {
            return new String[]{parts[1], parts[3], parts[5]};
        }
        return null;
    }

    private static String[] parseParent(String parent) {
        // projects/{project}/locations/{location}
        String[] parts = parent.split("/");
        if (parts.length >= 4) {
            return new String[]{parts[1], parts[3]};
        }
        return null;
    }

    private Service buildServiceProto(CloudRunStore.Service svc) {
        String serviceName = "projects/" + svc.projectId() + "/locations/" + svc.location() + "/services/" + svc.serviceId();
        Service.Builder builder = Service.newBuilder()
                .setName(serviceName)
                .setUri(svc.uri() != null ? svc.uri() : "")
                .setGeneration(svc.revisionCount());

        if (svc.createdAt() != null) {
            builder.setCreateTime(Timestamp.newBuilder()
                    .setSeconds(svc.createdAt().getTime() / 1000).build());
        }
        if (svc.updatedAt() != null) {
            builder.setUpdateTime(Timestamp.newBuilder()
                    .setSeconds(svc.updatedAt().getTime() / 1000).build());
        }

        // Set template with container
        RevisionTemplate.Builder template = RevisionTemplate.newBuilder();
        Container container = Container.newBuilder()
                .setImage(svc.containerImage())
                .addPorts(ContainerPort.newBuilder()
                        .setContainerPort(svc.containerPort())
                        .build())
                .build();
        template.addContainers(container);
        builder.setTemplate(template);

        return builder.build();
    }

    private Revision buildRevisionProto(CloudRunStore.Revision rev) {
        String revName = "projects/" + rev.projectId() + "/locations/" + rev.location()
                + "/services/" + rev.serviceId() + "/revisions/" + rev.revisionId();
        Revision.Builder builder = Revision.newBuilder()
                .setName(revName);

        Container container = Container.newBuilder()
                .setImage(rev.containerImage())
                .build();
        builder.addContainers(container);

        if (rev.createdAt() != null) {
            builder.setCreateTime(Timestamp.newBuilder()
                    .setSeconds(rev.createdAt().getTime() / 1000).build());
        }
        return builder.build();
    }

    // --- gRPC Service implementations ---

    public class ServicesServiceImpl extends ServicesGrpc.ServicesImplBase {

        @Override
        public void createService(CreateServiceRequest request, StreamObserver<Operation> responseObserver) {
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
                String serviceId = request.getServiceId();
                Service svc = request.getService();

                String containerImage = "nginx:alpine";
                int containerPort = 8080;
                if (svc.hasTemplate() && svc.getTemplate().getContainersCount() > 0) {
                    Container c = svc.getTemplate().getContainers(0);
                    if (!c.getImage().isEmpty()) {
                        containerImage = c.getImage();
                    }
                    if (c.getPortsCount() > 0) {
                        containerPort = c.getPorts(0).getContainerPort();
                    }
                }

                int hostPort = nextPort.getAndIncrement();
                String containerName = "lc-run-" + projectId + "-" + serviceId;
                String containerId;
                String uri;
                try {
                    String credPath = credentialBroker != null ? credentialBroker.getCredentialFilePath() : null;
                    String credProject = credentialBroker != null && credentialBroker.getProject() != null
                            ? credentialBroker.getProject() : projectId;
                    containerId = containerManager.createAndStart(
                            containerImage, containerName,
                            Map.of(containerPort, hostPort),
                            Map.of(),
                            Map.of("localcloud.service", "cloudrun",
                                   "localcloud.project", projectId,
                                   "localcloud.run-service", serviceId),
                            credPath, credProject);
                    uri = "http://localhost:" + hostPort;
                } catch (Exception e) {
                    logger.warn("Failed to create container for Cloud Run service {}: {}", serviceId, e.getMessage());
                    containerId = "simulated-" + serviceId;
                    uri = "http://localhost:" + hostPort;
                }

                var storeService = new CloudRunStore.Service(
                        projectId, location, serviceId, containerImage, containerPort,
                        containerId, hostPort, uri, "{}", 1, null, null);
                store.insertService(storeService);

                // Create initial revision
                String revisionId = serviceId + "-00001";
                store.insertRevision(new CloudRunStore.Revision(
                        projectId, location, serviceId, revisionId, containerImage, containerId, null));

                var created = store.getService(projectId, location, serviceId).orElse(storeService);
                Service resultProto = buildServiceProto(created);

                Operation operation = Operation.newBuilder()
                        .setName("projects/" + projectId + "/locations/" + location + "/operations/run-create-" + serviceId)
                        .setDone(true)
                        .setResponse(Any.pack(resultProto))
                        .build();

                responseObserver.onNext(operation);
                responseObserver.onCompleted();
            } catch (Exception e) {
                logger.error("createService failed", e);
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void getService(GetServiceRequest request, StreamObserver<Service> responseObserver) {
            incrementRequestCount();
            try {
                String[] parsed = parseServiceName(request.getName());
                if (parsed == null) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("Invalid service name").asRuntimeException());
                    return;
                }
                var opt = store.getService(parsed[0], parsed[1], parsed[2]);
                if (opt.isEmpty()) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Service not found: " + request.getName()).asRuntimeException());
                    return;
                }
                responseObserver.onNext(buildServiceProto(opt.get()));
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void listServices(ListServicesRequest request, StreamObserver<ListServicesResponse> responseObserver) {
            incrementRequestCount();
            try {
                String[] parent = parseParent(request.getParent());
                if (parent == null) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("Invalid parent").asRuntimeException());
                    return;
                }
                var services = store.listServices(parent[0], parent[1]);
                ListServicesResponse.Builder resp = ListServicesResponse.newBuilder();
                for (var svc : services) {
                    resp.addServices(buildServiceProto(svc));
                }
                responseObserver.onNext(resp.build());
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void updateService(UpdateServiceRequest request, StreamObserver<Operation> responseObserver) {
            incrementRequestCount();
            try {
                Service svc = request.getService();
                String[] parsed = parseServiceName(svc.getName());
                if (parsed == null) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("Invalid service name").asRuntimeException());
                    return;
                }
                String projectId = parsed[0];
                String location = parsed[1];
                String serviceId = parsed[2];

                var existing = store.getService(projectId, location, serviceId);
                if (existing.isEmpty()) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Service not found").asRuntimeException());
                    return;
                }

                var old = existing.get();
                String newImage = old.containerImage();
                int containerPort = old.containerPort();
                if (svc.hasTemplate() && svc.getTemplate().getContainersCount() > 0) {
                    Container c = svc.getTemplate().getContainers(0);
                    if (!c.getImage().isEmpty()) {
                        newImage = c.getImage();
                    }
                    if (c.getPortsCount() > 0) {
                        containerPort = c.getPorts(0).getContainerPort();
                    }
                }

                // Stop old container
                if (old.containerId() != null && !old.containerId().startsWith("simulated-")) {
                    containerManager.stop(old.containerId());
                    containerManager.remove(old.containerId());
                }

                // Start new container
                int hostPort = nextPort.getAndIncrement();
                String containerName = "lc-run-" + projectId + "-" + serviceId + "-v" + (old.revisionCount() + 1);
                String containerId;
                String uri;
                try {
                    String credPath = credentialBroker != null ? credentialBroker.getCredentialFilePath() : null;
                    String credProject = credentialBroker != null && credentialBroker.getProject() != null
                            ? credentialBroker.getProject() : projectId;
                    containerId = containerManager.createAndStart(
                            newImage, containerName,
                            Map.of(containerPort, hostPort),
                            Map.of(),
                            Map.of("localcloud.service", "cloudrun",
                                   "localcloud.project", projectId,
                                   "localcloud.run-service", serviceId),
                            credPath, credProject);
                    uri = "http://localhost:" + hostPort;
                } catch (Exception e) {
                    containerId = "simulated-" + serviceId;
                    uri = "http://localhost:" + hostPort;
                }

                store.updateService(projectId, location, serviceId, newImage, containerPort, containerId, hostPort, uri);

                // Create new revision
                String revisionId = serviceId + "-" + String.format("%05d", old.revisionCount() + 1);
                store.insertRevision(new CloudRunStore.Revision(
                        projectId, location, serviceId, revisionId, newImage, containerId, null));

                var updated = store.getService(projectId, location, serviceId).get();
                Service resultProto = buildServiceProto(updated);
                Operation operation = Operation.newBuilder()
                        .setName("projects/" + projectId + "/locations/" + location + "/operations/run-update-" + serviceId)
                        .setDone(true)
                        .setResponse(Any.pack(resultProto))
                        .build();

                responseObserver.onNext(operation);
                responseObserver.onCompleted();
            } catch (Exception e) {
                logger.error("updateService failed", e);
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void deleteService(DeleteServiceRequest request, StreamObserver<Operation> responseObserver) {
            incrementRequestCount();
            try {
                String[] parsed = parseServiceName(request.getName());
                if (parsed == null) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("Invalid service name").asRuntimeException());
                    return;
                }
                String projectId = parsed[0];
                String location = parsed[1];
                String serviceId = parsed[2];

                var opt = store.getService(projectId, location, serviceId);
                if (opt.isEmpty()) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Service not found").asRuntimeException());
                    return;
                }
                var svc = opt.get();
                if (svc.containerId() != null && !svc.containerId().startsWith("simulated-")) {
                    containerManager.stop(svc.containerId());
                    containerManager.remove(svc.containerId());
                }
                store.deleteService(projectId, location, serviceId);

                Operation operation = Operation.newBuilder()
                        .setName("projects/" + projectId + "/locations/" + location + "/operations/run-delete-" + serviceId)
                        .setDone(true)
                        .setResponse(Any.pack(Service.newBuilder().setName(request.getName()).build()))
                        .build();

                responseObserver.onNext(operation);
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }
    }

    public class RevisionsServiceImpl extends RevisionsGrpc.RevisionsImplBase {

        @Override
        public void getRevision(GetRevisionRequest request, StreamObserver<Revision> responseObserver) {
            incrementRequestCount();
            try {
                // projects/{project}/locations/{location}/services/{service}/revisions/{revision}
                String[] parts = request.getName().split("/");
                if (parts.length < 8) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("Invalid revision name").asRuntimeException());
                    return;
                }
                var opt = store.getRevision(parts[1], parts[3], parts[5], parts[7]);
                if (opt.isEmpty()) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Revision not found").asRuntimeException());
                    return;
                }
                responseObserver.onNext(buildRevisionProto(opt.get()));
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void listRevisions(ListRevisionsRequest request, StreamObserver<ListRevisionsResponse> responseObserver) {
            incrementRequestCount();
            try {
                // parent: projects/{project}/locations/{location}/services/{service}
                String[] parsed = parseServiceName(request.getParent());
                if (parsed == null) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("Invalid parent").asRuntimeException());
                    return;
                }
                var revisions = store.listRevisions(parsed[0], parsed[1], parsed[2]);
                ListRevisionsResponse.Builder resp = ListRevisionsResponse.newBuilder();
                for (var rev : revisions) {
                    resp.addRevisions(buildRevisionProto(rev));
                }
                responseObserver.onNext(resp.build());
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }
    }
}

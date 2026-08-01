package com.localcloud.emulators.functions;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import com.google.cloud.functions.v2.CreateFunctionRequest;
import com.google.cloud.functions.v2.DeleteFunctionRequest;
import com.google.cloud.functions.v2.Function;
import com.google.cloud.functions.v2.FunctionServiceGrpc;
import com.google.cloud.functions.v2.GenerateUploadUrlRequest;
import com.google.cloud.functions.v2.GenerateUploadUrlResponse;
import com.google.cloud.functions.v2.GetFunctionRequest;
import com.google.cloud.functions.v2.ListFunctionsRequest;
import com.google.cloud.functions.v2.ListFunctionsResponse;
import com.google.cloud.functions.v2.UpdateFunctionRequest;
import com.google.longrunning.Operation;
import com.localcloud.emulators.AbstractEmulator;
import com.localcloud.emulators.cloudrun.CloudRunStore;
import com.localcloud.emulators.common.GrpcSupport;
import com.localcloud.persistence.PostgresDataSource;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

public class CloudFunctionsEmulator extends AbstractEmulator {
    interface PubSubTriggerRegistrar {
        void register(String topicName, String subscriptionName, String pushEndpoint) throws Exception;
        void unregister(String subscriptionName) throws Exception;
    }

    private static final Set<String> SUPPORTED_RUNTIMES = Set.of(
            "nodejs18", "nodejs20", "nodejs22", "python310", "python311", "python312",
            "java17", "java21", "go121", "go122", "dotnet8", "ruby32", "php82");

    private final CloudFunctionsRepository repository;
    private final CloudRunStore cloudRunStore;
    private final PubSubTriggerRegistrar pubSubTriggerRegistrar;
    private final CloudFunctionsService service = new CloudFunctionsService();
    private final CloudFunctionsRestService restService;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public CloudFunctionsEmulator(PostgresDataSource dataSource) {
        this(dataSource, new CloudRunStore(dataSource), new GrpcPubSubTriggerRegistrar());
    }

    CloudFunctionsEmulator(PostgresDataSource dataSource, CloudRunStore cloudRunStore,
                           PubSubTriggerRegistrar pubSubTriggerRegistrar) {
        super("cloudfunctions", "Cloud Functions (2nd Gen)", 24080, "grpc", "CLOUD_FUNCTIONS_EMULATOR_HOST");
        this.repository = new CloudFunctionsRepository(dataSource);
        this.cloudRunStore = cloudRunStore;
        this.pubSubTriggerRegistrar = pubSubTriggerRegistrar;
        this.restService = new CloudFunctionsRestService(repository, this);
    }

    public CloudFunctionsService getServiceImpl() { return service; }
    public CloudFunctionsRestService getRestService() { return restService; }

    public CloudFunctionsRepository getRepository() {
        return repository;
    }

    @Override protected void doStart() {
        logger.info("Cloud Functions emulator initialized");
    }

    @Override protected void doStop() {}

    @Override protected void doReset() {}

    private String getFallbackEndpoint(String functionId) {
        String envVar = "LOCALCLOUD_FUNCTION_" + functionId.toUpperCase().replace("-", "_") + "_ENDPOINT";
        String envVal = System.getenv(envVar);
        if (envVal != null && !envVal.isBlank()) {
            return envVal;
        }
        return "http://localhost:8081"; // Default port for local functions-framework
    }

    private String resolveTargetEndpoint(String projectId, String locationId, String functionId, Function function)
            throws SQLException {
        if (function.hasServiceConfig()) {
            var serviceConfig = function.getServiceConfig();
            if (!serviceConfig.getService().isBlank()) {
                return resolveCloudRunServiceUri(projectId, locationId, serviceConfig.getService())
                        .orElseThrow(() -> Status.NOT_FOUND
                                .withDescription("Cloud Run service not found: " + serviceConfig.getService())
                                .asRuntimeException());
            }
            if (!serviceConfig.getUri().isBlank()) {
                return serviceConfig.getUri();
            }
        }
        return getFallbackEndpoint(functionId);
    }

    private Optional<String> resolveCloudRunServiceUri(String projectId, String locationId, String serviceName)
            throws SQLException {
        String serviceProject = projectId;
        String serviceLocation = locationId;
        String serviceId = serviceName;
        String[] segments = serviceName.split("/");
        if (segments.length == 6 && "projects".equals(segments[0]) && "locations".equals(segments[2])
                && "services".equals(segments[4])) {
            serviceProject = segments[1];
            serviceLocation = segments[3];
            serviceId = segments[5];
        }
        return cloudRunStore.getService(serviceProject, serviceLocation, serviceId)
                .map(CloudRunStore.Service::uri)
                .filter(uri -> uri != null && !uri.isBlank());
    }

    private void registerPubSubTrigger(String projectId, String locationId, String functionId, Function function) {
        if (!function.hasEventTrigger()) return;
        String topic = resolvePubSubTopicName(projectId, function.getEventTrigger());
        if (topic == null || topic.isEmpty()) return;

        String subName = "projects/" + projectId + "/subscriptions/localcloud-fn-" + functionId;
        String triggerUrl = "http://localhost:24080/functions/trigger/" + projectId + "/" + locationId + "/" + functionId;
        try {
            pubSubTriggerRegistrar.register(topic, subName, triggerUrl);
            logger.info("Created Pub/Sub trigger subscription {} for function {}", subName, functionId);
        } catch (Exception e) {
            logger.warn("Failed to create Pub/Sub trigger subscription for function {}: {}", functionId, e.getMessage());
        }
    }

    private String resolvePubSubTopicName(String projectId, com.google.cloud.functions.v2.EventTrigger trigger) {
        String topic = trigger.getPubsubTopic();
        if (topic == null || topic.isEmpty()) {
            for (var filter : trigger.getEventFiltersList()) {
                if ("topic".equals(filter.getAttribute())) {
                    topic = filter.getValue();
                    break;
                }
            }
        }
        if (topic == null || topic.isEmpty()) return null;
        return topic.startsWith("projects/") ? topic : "projects/" + projectId + "/topics/" + topic;
    }

    private static final class GrpcPubSubTriggerRegistrar implements PubSubTriggerRegistrar {
        @Override
        public void register(String topicName, String subscriptionName, String pushEndpoint) {
        String pubsubHostEnv = System.getenv("PUBSUB_EMULATOR_HOST");
        String host = "localhost";
        int port = 24082;
        if (pubsubHostEnv != null && !pubsubHostEnv.isEmpty()) {
            String[] parts = pubsubHostEnv.split(":", 2);
            host = parts[0];
            if (parts.length > 1) {
                try {
                    port = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
        }

        io.grpc.ManagedChannel channel = io.grpc.ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        try {
            try {
                com.google.pubsub.v1.PublisherGrpc.newBlockingStub(channel).createTopic(
                            com.google.pubsub.v1.Topic.newBuilder().setName(topicName).build()
                );
            } catch (Exception e) {
                // ignore if already exists
            }

            var pushConfig = com.google.pubsub.v1.PushConfig.newBuilder()
                        .setPushEndpoint(pushEndpoint)
                    .build();
            var subscription = com.google.pubsub.v1.Subscription.newBuilder()
                        .setName(subscriptionName)
                        .setTopic(topicName)
                    .setPushConfig(pushConfig)
                    .setAckDeadlineSeconds(60)
                    .build();
            com.google.pubsub.v1.SubscriberGrpc.newBlockingStub(channel).createSubscription(subscription);
        } finally {
            channel.shutdownNow();
        }
    }

        @Override
        public void unregister(String subscriptionName) {
            String pubsubHostEnv = System.getenv("PUBSUB_EMULATOR_HOST");
            String host = "localhost";
            int port = 24082;
            if (pubsubHostEnv != null && !pubsubHostEnv.isEmpty()) {
                String[] parts = pubsubHostEnv.split(":", 2);
                host = parts[0];
                if (parts.length > 1) {
                    try {
                        port = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException e) {
                        // Keep the default Pub/Sub emulator port.
                    }
                }
            }

            io.grpc.ManagedChannel channel = io.grpc.ManagedChannelBuilder.forAddress(host, port)
                    .usePlaintext()
                    .build();
        try {
            com.google.pubsub.v1.SubscriberGrpc.newBlockingStub(channel).deleteSubscription(
                        com.google.pubsub.v1.DeleteSubscriptionRequest.newBuilder().setSubscription(subscriptionName).build()
            );
        } finally {
            channel.shutdownNow();
        }
    }
    }

    private void unregisterPubSubTrigger(String projectId, String functionId) {
        String subName = "projects/" + projectId + "/subscriptions/localcloud-fn-" + functionId;
        try {
            pubSubTriggerRegistrar.unregister(subName);
            logger.info("Deleted Pub/Sub trigger subscription {}", subName);
        } catch (Exception e) {
            logger.debug("Failed to delete subscription (it may not exist): {}", e.getMessage());
        }
    }

    public com.linecorp.armeria.server.HttpService getHttpTriggerService() {
        return (ctx, req) -> {
            String path = ctx.mappedPath();
            if (path.startsWith("/functions/trigger/")) {
                path = path.substring("/functions/trigger/".length());
            }
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            String[] parts = path.split("/");
            if (parts.length < 3) {
                return com.linecorp.armeria.common.HttpResponse.of(com.linecorp.armeria.common.HttpStatus.BAD_REQUEST,
                        com.linecorp.armeria.common.MediaType.PLAIN_TEXT_UTF_8, "Invalid trigger path");
            }
            String projectId = parts[0];
            String locationId = parts[1];
            String functionId = parts[2];
            
            if (projectId.isBlank() || locationId.isBlank() || functionId.isBlank()) {
                return com.linecorp.armeria.common.HttpResponse.of(com.linecorp.armeria.common.HttpStatus.BAD_REQUEST,
                        com.linecorp.armeria.common.MediaType.PLAIN_TEXT_UTF_8, "Invalid trigger path: empty segment");
            }

            try {
                var function = repository.get(projectId, locationId, functionId);
                if (function == null) {
                    return com.linecorp.armeria.common.HttpResponse.of(com.linecorp.armeria.common.HttpStatus.NOT_FOUND,
                            com.linecorp.armeria.common.MediaType.PLAIN_TEXT_UTF_8, "Function not found");
                }
                
                String targetUrl = resolveTargetEndpoint(projectId, locationId, functionId, function);
                logger.info("Forwarding trigger request for {} to local endpoint {}", functionId, targetUrl);

                return com.linecorp.armeria.common.HttpResponse.of(
                        req.aggregate().thenApply(aggregated -> {
                            try {
                                var forwardBuilder = HttpRequest.newBuilder(URI.create(targetUrl))
                                        .header("Content-Type", aggregated.contentType() != null ? aggregated.contentType().toString() : "application/json");
                                for (var header : req.headers()) {
                                    String name = header.getKey().toString();
                                    if (name.startsWith("ce-") || name.equalsIgnoreCase("ce-specversion")) {
                                        forwardBuilder.header(name, header.getValue());
                                    }
                                }
                                forwardBuilder.POST(HttpRequest.BodyPublishers.ofByteArray(aggregated.content().array()));
                                var forwardResponse = httpClient.send(forwardBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
                                return com.linecorp.armeria.common.HttpResponse.of(
                                        com.linecorp.armeria.common.HttpStatus.valueOf(forwardResponse.statusCode()),
                                        com.linecorp.armeria.common.MediaType.JSON_UTF_8,
                                        forwardResponse.body()
                                );
                            } catch (Exception e) {
                                logger.error("Failed to forward trigger to local function", e);
                                return com.linecorp.armeria.common.HttpResponse.of(
                                        com.linecorp.armeria.common.HttpStatus.INTERNAL_SERVER_ERROR,
                                        com.linecorp.armeria.common.MediaType.PLAIN_TEXT_UTF_8,
                                        "Forward failed: " + e.getMessage()
                                );
                            }
                        })
                );
            } catch (Exception e) {
                return com.linecorp.armeria.common.HttpResponse.of(com.linecorp.armeria.common.HttpStatus.INTERNAL_SERVER_ERROR,
                        com.linecorp.armeria.common.MediaType.PLAIN_TEXT_UTF_8, e.getMessage());
            }
        };
    }

    public class CloudFunctionsService extends FunctionServiceGrpc.FunctionServiceImplBase {
        @Override public void createFunction(CreateFunctionRequest request, StreamObserver<Operation> responseObserver) {
            incrementRequestCount();
            try {
                String[] parent = GrpcSupport.parseLocationParent(request.getParent());
                String functionId = request.getFunctionId();
                Function input = request.getFunction();
                String runtime = input.hasBuildConfig() ? input.getBuildConfig().getRuntime() : "";
                if (!SUPPORTED_RUNTIMES.contains(runtime)) {
                    throw Status.INVALID_ARGUMENT.withDescription("Unsupported runtime: " + runtime).asRuntimeException();
                }
                if (repository.exists(parent[0], parent[1], functionId)) {
                    throw Status.ALREADY_EXISTS.withDescription("Function already exists").asRuntimeException();
                }
                String name = request.getParent() + "/functions/" + functionId;
                String targetUrl = resolveTargetEndpoint(parent[0], parent[1], functionId, input);
                
                Function.Builder builder = input.toBuilder()
                        .setName(name)
                        .setState(Function.State.ACTIVE)
                        .setCreateTime(GrpcSupport.timestamp(Instant.now()))
                        .setUpdateTime(GrpcSupport.timestamp(Instant.now()));
                
                if (input.hasServiceConfig()) {
                    builder.setServiceConfig(input.getServiceConfig().toBuilder()
                            .setUri(targetUrl)
                            .build());
                } else {
                    builder.setServiceConfig(com.google.cloud.functions.v2.ServiceConfig.newBuilder()
                            .setUri(targetUrl)
                            .build());
                }
                
                Function function = builder.build();
                repository.create(parent[0], parent[1], functionId, function);
                
                // Register Pub/Sub dynamic trigger if configured
                registerPubSubTrigger(parent[0], parent[1], functionId, function);
                
                responseObserver.onNext(GrpcSupport.doneOperation(name + "/operations/create", function));
                responseObserver.onCompleted();
            } catch (io.grpc.StatusRuntimeException e) {
                responseObserver.onError(e);
            } catch (Exception e) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void getFunction(GetFunctionRequest request, StreamObserver<Function> responseObserver) {
            incrementRequestCount();
            try {
                String[] parts = GrpcSupport.parseNamedResource(request.getName(), "functions");
                Function function = repository.get(parts[0], parts[1], parts[2]);
                if (function == null) throw Status.NOT_FOUND.asRuntimeException();
                responseObserver.onNext(function);
                responseObserver.onCompleted();
            } catch (io.grpc.StatusRuntimeException e) {
                responseObserver.onError(e);
            } catch (Exception e) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void listFunctions(ListFunctionsRequest request, StreamObserver<ListFunctionsResponse> responseObserver) {
            incrementRequestCount();
            try {
                String[] parent = GrpcSupport.parseLocationParent(request.getParent());
                responseObserver.onNext(ListFunctionsResponse.newBuilder()
                        .addAllFunctions(repository.list(parent[0], parent[1]))
                        .build());
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void updateFunction(UpdateFunctionRequest request, StreamObserver<Operation> responseObserver) {
            incrementRequestCount();
            try {
                Function input = request.getFunction();
                String[] parts = GrpcSupport.parseNamedResource(input.getName(), "functions");
                Function existing = repository.get(parts[0], parts[1], parts[2]);
                if (existing == null) throw Status.NOT_FOUND.asRuntimeException();
                
                String targetUrl = resolveTargetEndpoint(parts[0], parts[1], parts[2], input);
                Function.Builder builder = input.toBuilder()
                        .setUpdateTime(GrpcSupport.timestamp(Instant.now()));
                
                if (input.hasServiceConfig()) {
                    builder.setServiceConfig(input.getServiceConfig().toBuilder()
                            .setUri(targetUrl)
                            .build());
                } else {
                    builder.setServiceConfig(com.google.cloud.functions.v2.ServiceConfig.newBuilder()
                            .setUri(targetUrl)
                            .build());
                }
                
                Function function = builder.build();
                if (!repository.update(parts[0], parts[1], parts[2], function)) throw Status.NOT_FOUND.asRuntimeException();
                
                // Re-register Pub/Sub trigger
                if (existing.hasEventTrigger()) {
                    unregisterPubSubTrigger(parts[0], parts[2]);
                }
                if (function.hasEventTrigger()) {
                    registerPubSubTrigger(parts[0], parts[1], parts[2], function);
                }
                
                responseObserver.onNext(GrpcSupport.doneOperation(function.getName() + "/operations/update", function));
                responseObserver.onCompleted();
            } catch (io.grpc.StatusRuntimeException e) {
                responseObserver.onError(e);
            } catch (Exception e) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void deleteFunction(DeleteFunctionRequest request, StreamObserver<Operation> responseObserver) {
            incrementRequestCount();
            try {
                String[] parts = GrpcSupport.parseNamedResource(request.getName(), "functions");
                Function existing = repository.get(parts[0], parts[1], parts[2]);
                if (existing == null) throw Status.NOT_FOUND.asRuntimeException();
                if (!repository.delete(parts[0], parts[1], parts[2])) throw Status.NOT_FOUND.asRuntimeException();
                
                // Clean up Pub/Sub trigger
                if (existing.hasEventTrigger()) {
                    unregisterPubSubTrigger(parts[0], parts[2]);
                }
                
                responseObserver.onNext(Operation.newBuilder()
                        .setName(request.getName() + "/operations/delete")
                        .setDone(true)
                        .build());
                responseObserver.onCompleted();
            } catch (io.grpc.StatusRuntimeException e) {
                responseObserver.onError(e);
            } catch (Exception e) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void generateUploadUrl(GenerateUploadUrlRequest request,
                                                StreamObserver<GenerateUploadUrlResponse> responseObserver) {
            incrementRequestCount();
            String url = "http://localhost:24080/functions/upload/" + Math.abs(request.getParent().hashCode());
            responseObserver.onNext(GenerateUploadUrlResponse.newBuilder().setUploadUrl(url).build());
            responseObserver.onCompleted();
        }
    }
}

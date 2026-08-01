package com.localcloud.emulators.secretmanager;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import com.google.cloud.secretmanager.v1.AccessSecretVersionRequest;
import com.google.cloud.secretmanager.v1.AccessSecretVersionResponse;
import com.google.cloud.secretmanager.v1.AddSecretVersionRequest;
import com.google.cloud.secretmanager.v1.CreateSecretRequest;
import com.google.cloud.secretmanager.v1.DeleteSecretRequest;
import com.google.cloud.secretmanager.v1.DestroySecretVersionRequest;
import com.google.cloud.secretmanager.v1.DisableSecretVersionRequest;
import com.google.cloud.secretmanager.v1.EnableSecretVersionRequest;
import com.google.cloud.secretmanager.v1.GetSecretRequest;
import com.google.cloud.secretmanager.v1.GetSecretVersionRequest;
import com.google.cloud.secretmanager.v1.ListSecretVersionsRequest;
import com.google.cloud.secretmanager.v1.ListSecretVersionsResponse;
import com.google.cloud.secretmanager.v1.ListSecretsRequest;
import com.google.cloud.secretmanager.v1.ListSecretsResponse;
import com.google.cloud.secretmanager.v1.Replication;
import com.google.cloud.secretmanager.v1.Secret;
import com.google.cloud.secretmanager.v1.SecretManagerServiceGrpc;
import com.google.cloud.secretmanager.v1.UpdateSecretRequest;
import com.google.cloud.secretmanager.v1.SecretPayload;
import com.google.cloud.secretmanager.v1.SecretVersion;
import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import com.google.iam.v1.TestIamPermissionsRequest;
import com.google.iam.v1.TestIamPermissionsResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;
import com.localcloud.emulators.AbstractEmulator;
import com.localcloud.emulators.iam.IAMPolicyGrpcHelper;
import com.localcloud.emulators.iam.IAMRepository;
import com.localcloud.persistence.PostgresDataSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/**
 * Cloud Secret Manager gRPC emulator.
 * Implements the SecretManagerService gRPC API backed by PostgreSQL persistence.
 */
public class SecretManagerEmulator extends AbstractEmulator {

    private final SecretManagerStore store;
    private final SecretManagerServiceImpl serviceImpl;
    private final IAMPolicyGrpcHelper iamHelper;

    public SecretManagerEmulator(PostgresDataSource dataSource) {
        super("secretmanager", "Secret Manager", 24080, "grpc", "SECRET_MANAGER_EMULATOR_HOST");
        this.store = new SecretManagerStore(dataSource);
        this.iamHelper = new IAMPolicyGrpcHelper(new IAMRepository(dataSource));
        this.serviceImpl = new SecretManagerServiceImpl();
    }

    @Override
    protected void doStart() throws Exception {
        logger.info("Secret Manager emulator gRPC services ready");
    }

    @Override
    protected void doStop() {
        // Nothing to clean up; the Armeria server manages the gRPC lifecycle
    }

    @Override
    protected void doReset() {
        store.clearAll();
        logger.info("Secret Manager emulator state reset");
    }

    /**
     * Returns the gRPC BindableService for registration with the server.
     */
    public SecretManagerServiceImpl getServiceImpl() {
        return serviceImpl;
    }

    public SecretManagerStore getStore() {
        return store;
    }

    // --- gRPC Service Implementation ---

    public class SecretManagerServiceImpl extends SecretManagerServiceGrpc.SecretManagerServiceImplBase {

        @Override
        public void createSecret(CreateSecretRequest request, StreamObserver<Secret> responseObserver) {
            incrementRequestCount();
            try {
                String parent = request.getParent(); // projects/{project}
                String projectId = SecretManagerStore.extractProject(parent);
                String secretId = request.getSecretId();

                if (secretId == null || secretId.isEmpty()) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("secret_id is required")
                            .asRuntimeException());
                    return;
                }

                if (store.secretExists(projectId, secretId)) {
                    responseObserver.onError(Status.ALREADY_EXISTS
                            .withDescription("Secret already exists: " + parent + "/secrets/" + secretId)
                            .asRuntimeException());
                    return;
                }

                // Extract labels as simple JSON
                String labelsJson = "{}";
                if (request.hasSecret() && request.getSecret().getLabelsCount() > 0) {
                    labelsJson = labelsToJson(request.getSecret().getLabelsMap());
                }

                // Extract replication as JSON
                String replicationJson = "{\"automatic\":{}}";
                if (request.hasSecret() && request.getSecret().hasReplication()) {
                    replicationJson = replicationToJson(request.getSecret().getReplication());
                }

                // Extract expiration
                java.sql.Timestamp expireAt = null;
                Long rotationPeriod = null;
                if (request.hasSecret() && request.getSecret().hasExpireTime()) {
                    var expireTime = request.getSecret().getExpireTime();
                    expireAt = new java.sql.Timestamp(
                            expireTime.getSeconds() * 1000 + expireTime.getNanos() / 1_000_000);
                } else if (request.hasSecret() && request.getSecret().hasTtl()) {
                    // Convert TTL to expire_at: NOW + ttl
                    var ttl = request.getSecret().getTtl();
                    long ttlSeconds = ttl.getSeconds();
                    expireAt = new java.sql.Timestamp(System.currentTimeMillis() + ttlSeconds * 1000);
                }
                if (request.hasSecret() && request.getSecret().hasRotation()) {
                    rotationPeriod = request.getSecret().getRotation().getRotationPeriod().getSeconds();
                }

                store.createSecret(projectId, secretId, labelsJson, replicationJson, expireAt, rotationPeriod);

                String fullName = parent + "/secrets/" + secretId;
                Secret.Builder responseBuilder = Secret.newBuilder()
                        .setName(fullName)
                        .setReplication(request.hasSecret() && request.getSecret().hasReplication()
                                ? request.getSecret().getReplication()
                                : Replication.newBuilder()
                                        .setAutomatic(Replication.Automatic.getDefaultInstance())
                                        .build());

                // Add labels if present
                if (request.hasSecret() && request.getSecret().getLabelsCount() > 0) {
                    responseBuilder.putAllLabels(request.getSecret().getLabelsMap());
                }

                // Add expiration if present (pass through expire_time or ttl from request)
                if (request.hasSecret() && request.getSecret().hasExpireTime()) {
                    responseBuilder.setExpireTime(request.getSecret().getExpireTime());
                }
                if (request.hasSecret() && request.getSecret().hasTtl()) {
                    responseBuilder.setTtl(request.getSecret().getTtl());
                }
                if (request.hasSecret() && request.getSecret().hasRotation()) {
                    responseBuilder.setRotation(request.getSecret().getRotation());
                }

                Secret response = responseBuilder.build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } catch (IllegalArgumentException e) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription(e.getMessage())
                        .asRuntimeException());
            } catch (SQLException e) {
                logger.error("Failed to create secret", e);
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Database error: " + e.getMessage())
                        .asRuntimeException());
            }
        }

        @Override
        public void updateSecret(UpdateSecretRequest request, StreamObserver<Secret> responseObserver) {
            incrementRequestCount();
            try {
                Secret secret = request.getSecret();
                String fullName = secret.getName(); // projects/{project}/secrets/{secret}
                String[] parts = SecretManagerStore.parseSecretName(fullName);

                if (!store.secretExists(parts[0], parts[1])) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Secret not found: " + fullName)
                            .asRuntimeException());
                    return;
                }

                String labelsJson = "{}";
                if (secret.getLabelsCount() > 0) {
                    labelsJson = labelsToJson(secret.getLabelsMap());
                }

                String replicationJson = "{\"automatic\":{}}";
                if (secret.hasReplication()) {
                    replicationJson = replicationToJson(secret.getReplication());
                }

                java.sql.Timestamp expireAt = null;
                Long rotationPeriod = null;
                if (secret.hasExpireTime()) {
                    var et = secret.getExpireTime();
                    expireAt = new java.sql.Timestamp(et.getSeconds() * 1000 + et.getNanos() / 1_000_000);
                } else if (secret.hasTtl()) {
                    var ttl = secret.getTtl();
                    long ttlSeconds = ttl.getSeconds();
                    expireAt = new java.sql.Timestamp(System.currentTimeMillis() + ttlSeconds * 1000);
                }
                if (secret.hasRotation()) {
                    rotationPeriod = secret.getRotation().getRotationPeriod().getSeconds();
                }

                store.updateSecret(parts[0], parts[1], labelsJson, replicationJson, expireAt, rotationPeriod);

                Map<String, Object> data = store.getSecret(parts[0], parts[1]);
                Secret response = buildSecret(fullName, data);

                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } catch (IllegalArgumentException e) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription(e.getMessage())
                        .asRuntimeException());
            } catch (SQLException e) {
                logger.error("Failed to update secret", e);
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Database error: " + e.getMessage())
                        .asRuntimeException());
            }
        }

        @Override
        public void getSecret(GetSecretRequest request, StreamObserver<Secret> responseObserver) {
            incrementRequestCount();
            try {
                String fullName = request.getName();
                String[] parts = SecretManagerStore.parseSecretName(fullName);

                Map<String, Object> data = store.getSecret(parts[0], parts[1]);
                if (data == null) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Secret not found: " + fullName)
                            .asRuntimeException());
                    return;
                }

                Secret response = buildSecret(fullName, data);
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } catch (IllegalArgumentException e) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription(e.getMessage())
                        .asRuntimeException());
            } catch (SQLException e) {
                logger.error("Failed to get secret", e);
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Database error: " + e.getMessage())
                        .asRuntimeException());
            }
        }

        @Override
        public void listSecrets(ListSecretsRequest request, StreamObserver<ListSecretsResponse> responseObserver) {
            incrementRequestCount();
            try {
                String projectId = SecretManagerStore.extractProject(request.getParent());

                int pageSize = request.getPageSize() > 0 ? request.getPageSize() : 100;
                int offset = 0;
                String pageToken = request.getPageToken();
                if (pageToken != null && !pageToken.isEmpty()) {
                    offset = Integer.parseInt(new String(Base64.getDecoder().decode(pageToken), StandardCharsets.UTF_8));
                }

                List<Map<String, Object>> secrets = store.listSecrets(projectId, pageSize, offset);

                ListSecretsResponse.Builder builder = ListSecretsResponse.newBuilder();
                for (Map<String, Object> data : secrets) {
                    String fullName = "projects/" + data.get("project_id") + "/secrets/" + data.get("secret_id");
                    builder.addSecrets(buildSecret(fullName, data));
                }

                if (secrets.size() == pageSize) {
                    String nextToken = Base64.getEncoder().encodeToString(
                            String.valueOf(offset + pageSize).getBytes(StandardCharsets.UTF_8));
                    builder.setNextPageToken(nextToken);
                }

                responseObserver.onNext(builder.build());
                responseObserver.onCompleted();
            } catch (IllegalArgumentException e) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription(e.getMessage())
                        .asRuntimeException());
            } catch (SQLException e) {
                logger.error("Failed to list secrets", e);
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Database error: " + e.getMessage())
                        .asRuntimeException());
            }
        }

        @Override
        public void deleteSecret(DeleteSecretRequest request, StreamObserver<Empty> responseObserver) {
            incrementRequestCount();
            try {
                String fullName = request.getName();
                String[] parts = SecretManagerStore.parseSecretName(fullName);

                if (!store.deleteSecret(parts[0], parts[1])) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Secret not found: " + fullName)
                            .asRuntimeException());
                    return;
                }

                responseObserver.onNext(Empty.getDefaultInstance());
                responseObserver.onCompleted();
            } catch (IllegalArgumentException e) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription(e.getMessage())
                        .asRuntimeException());
            } catch (SQLException e) {
                logger.error("Failed to delete secret", e);
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Database error: " + e.getMessage())
                        .asRuntimeException());
            }
        }

        @Override
        public void addSecretVersion(AddSecretVersionRequest request,
                                     StreamObserver<SecretVersion> responseObserver) {
            incrementRequestCount();
            try {
                String parent = request.getParent(); // projects/{project}/secrets/{secret}
                String[] parts = SecretManagerStore.parseSecretName(parent);

                if (!store.secretExists(parts[0], parts[1])) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Secret not found: " + parent)
                            .asRuntimeException());
                    return;
                }

                byte[] data = request.getPayload().getData().toByteArray();
                Map<String, Object> version = store.addSecretVersion(parts[0], parts[1], data);

                String versionName = parent + "/versions/" + version.get("version_number");
                SecretVersion response = SecretVersion.newBuilder()
                        .setName(versionName)
                        .setState(SecretVersion.State.ENABLED)
                        .build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } catch (IllegalArgumentException e) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription(e.getMessage())
                        .asRuntimeException());
            } catch (SQLException e) {
                logger.error("Failed to add secret version", e);
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Database error: " + e.getMessage())
                        .asRuntimeException());
            }
        }

        @Override
        public void getSecretVersion(GetSecretVersionRequest request,
                                     StreamObserver<SecretVersion> responseObserver) {
            incrementRequestCount();
            try {
                String fullName = request.getName();
                String[] parts = SecretManagerStore.parseVersionName(fullName);

                // Resolve aliases for version lookup
                String versionId = parts[2];
                if (!"latest".equalsIgnoreCase(versionId) && !versionId.matches("\\d+")) {
                    Integer resolved = store.resolveAlias(parts[0], parts[1], versionId);
                    if (resolved != null) {
                        versionId = String.valueOf(resolved);
                    }
                }

                Map<String, Object> data = store.getSecretVersion(parts[0], parts[1], versionId);
                if (data == null) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Secret version not found: " + fullName)
                            .asRuntimeException());
                    return;
                }

                String versionName = "projects/" + parts[0] + "/secrets/" + parts[1] +
                                     "/versions/" + data.get("version_number");
                SecretVersion response = SecretVersion.newBuilder()
                        .setName(versionName)
                        .setState(mapState((String) data.get("state")))
                        .build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } catch (IllegalArgumentException e) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription(e.getMessage())
                        .asRuntimeException());
            } catch (SQLException e) {
                logger.error("Failed to get secret version", e);
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Database error: " + e.getMessage())
                        .asRuntimeException());
            }
        }

        @Override
        public void listSecretVersions(ListSecretVersionsRequest request,
                                       StreamObserver<ListSecretVersionsResponse> responseObserver) {
            incrementRequestCount();
            try {
                String parent = request.getParent(); // projects/{project}/secrets/{secret}
                String[] parts = SecretManagerStore.parseSecretName(parent);

                int pageSize = request.getPageSize() > 0 ? request.getPageSize() : 100;
                int offset = 0;
                String pageToken = request.getPageToken();
                if (pageToken != null && !pageToken.isEmpty()) {
                    offset = Integer.parseInt(new String(Base64.getDecoder().decode(pageToken), StandardCharsets.UTF_8));
                }

                List<Map<String, Object>> versions = store.listSecretVersions(parts[0], parts[1], pageSize, offset);

                ListSecretVersionsResponse.Builder builder = ListSecretVersionsResponse.newBuilder();
                for (Map<String, Object> data : versions) {
                    String versionName = parent + "/versions/" + data.get("version_number");
                    builder.addVersions(SecretVersion.newBuilder()
                            .setName(versionName)
                            .setState(mapState((String) data.get("state")))
                            .build());
                }

                if (versions.size() == pageSize) {
                    String nextToken = Base64.getEncoder().encodeToString(
                            String.valueOf(offset + pageSize).getBytes(StandardCharsets.UTF_8));
                    builder.setNextPageToken(nextToken);
                }

                responseObserver.onNext(builder.build());
                responseObserver.onCompleted();
            } catch (IllegalArgumentException e) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription(e.getMessage())
                        .asRuntimeException());
            } catch (SQLException e) {
                logger.error("Failed to list secret versions", e);
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Database error: " + e.getMessage())
                        .asRuntimeException());
            }
        }

        @Override
        public void accessSecretVersion(AccessSecretVersionRequest request,
                                        StreamObserver<AccessSecretVersionResponse> responseObserver) {
            incrementRequestCount();
            try {
                String fullName = request.getName();
                String[] parts = SecretManagerStore.parseVersionName(fullName);

                // Resolve aliases: if versionId is not "latest" and not numeric, treat as alias
                String versionId = parts[2];
                if (!"latest".equalsIgnoreCase(versionId) && !versionId.matches("\\d+")) {
                    Integer resolved = store.resolveAlias(parts[0], parts[1], versionId);
                    if (resolved != null) {
                        versionId = String.valueOf(resolved);
                        parts[2] = versionId;
                    }
                    // If alias not found, fall through — getSecretVersion will return null
                }

                // First check if version exists
                Map<String, Object> versionData = store.getSecretVersion(parts[0], parts[1], versionId);
                if (versionData == null) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Secret version not found: " + fullName)
                            .asRuntimeException());
                    return;
                }

                String state = (String) versionData.get("state");
                if ("DESTROYED".equals(state)) {
                    responseObserver.onError(Status.FAILED_PRECONDITION
                            .withDescription("Secret version is destroyed: " + fullName)
                            .asRuntimeException());
                    return;
                }
                if ("DISABLED".equals(state)) {
                    responseObserver.onError(Status.FAILED_PRECONDITION
                            .withDescription("Secret version is disabled: " + fullName)
                            .asRuntimeException());
                    return;
                }

                byte[] payload = store.accessSecretVersion(parts[0], parts[1], versionId);
                if (payload == null) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Secret version payload not found: " + fullName)
                            .asRuntimeException());
                    return;
                }

                String versionName = "projects/" + parts[0] + "/secrets/" + parts[1] +
                                     "/versions/" + versionData.get("version_number");
                AccessSecretVersionResponse response = AccessSecretVersionResponse.newBuilder()
                        .setName(versionName)
                        .setPayload(SecretPayload.newBuilder()
                                .setData(ByteString.copyFrom(payload))
                                .build())
                        .build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } catch (IllegalArgumentException e) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription(e.getMessage())
                        .asRuntimeException());
            } catch (SQLException e) {
                logger.error("Failed to access secret version", e);
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Database error: " + e.getMessage())
                        .asRuntimeException());
            }
        }

        @Override
        public void disableSecretVersion(DisableSecretVersionRequest request,
                                         StreamObserver<SecretVersion> responseObserver) {
            incrementRequestCount();
            handleVersionStateChange(request.getName(), "disable", responseObserver);
        }

        @Override
        public void enableSecretVersion(EnableSecretVersionRequest request,
                                        StreamObserver<SecretVersion> responseObserver) {
            incrementRequestCount();
            handleVersionStateChange(request.getName(), "enable", responseObserver);
        }

        @Override
        public void destroySecretVersion(DestroySecretVersionRequest request,
                                         StreamObserver<SecretVersion> responseObserver) {
            incrementRequestCount();
            handleVersionStateChange(request.getName(), "destroy", responseObserver);
        }

        private int resolveVersionNumber(String projectId, String secretId, String versionStr) {
            if ("latest".equalsIgnoreCase(versionStr)) {
                return store.getLatestVersionNumber(projectId, secretId);
            }
            // Try alias resolution for non-numeric version strings
            if (!versionStr.matches("\\d+")) {
                try {
                    Integer resolved = store.resolveAlias(projectId, secretId, versionStr);
                    if (resolved != null) {
                        return resolved;
                    }
                } catch (SQLException e) {
                    // Fall through to parse attempt
                }
            }
            return Integer.parseInt(versionStr);
        }

        private void handleVersionStateChange(String fullName, String action,
                                               StreamObserver<SecretVersion> responseObserver) {
            try {
                String[] parts = SecretManagerStore.parseVersionName(fullName);
                int versionNumber = resolveVersionNumber(parts[0], parts[1], parts[2]);

                boolean success = switch (action) {
                    case "disable" -> store.disableSecretVersion(parts[0], parts[1], versionNumber);
                    case "enable" -> store.enableSecretVersion(parts[0], parts[1], versionNumber);
                    case "destroy" -> store.destroySecretVersion(parts[0], parts[1], versionNumber);
                    default -> false;
                };

                if (!success) {
                    responseObserver.onError(Status.FAILED_PRECONDITION
                            .withDescription("Cannot " + action + " secret version: " + fullName)
                            .asRuntimeException());
                    return;
                }

                // Retrieve updated state
                Map<String, Object> data = store.getSecretVersion(parts[0], parts[1], parts[2]);
                SecretVersion.State newState = data != null ? mapState((String) data.get("state"))
                        : SecretVersion.State.STATE_UNSPECIFIED;

                SecretVersion response = SecretVersion.newBuilder()
                        .setName(fullName)
                        .setState(newState)
                        .build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } catch (IllegalArgumentException e) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription(e.getMessage())
                        .asRuntimeException());
            } catch (SQLException e) {
                logger.error("Failed to {} secret version", action, e);
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Database error: " + e.getMessage())
                        .asRuntimeException());
            }
        }

        // --- Helpers ---

        private String labelsToJson(Map<String, String> labelsMap) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, String> entry : labelsMap.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
                first = false;
            }
            sb.append("}");
            return sb.toString();
        }

        private String replicationToJson(Replication replication) {
            if (replication.hasAutomatic()) {
                return "{\"automatic\":{}}";
            } else if (replication.hasUserManaged()) {
                var um = replication.getUserManaged();
                StringBuilder sb = new StringBuilder("{\"user_managed\":{\"replicas\":[");
                boolean first = true;
                for (var replica : um.getReplicasList()) {
                    if (!first) sb.append(",");
                    sb.append("{\"location\":\"").append(replica.getLocation()).append("\"}");
                    first = false;
                }
                sb.append("]}}");
                return sb.toString();
            }
            return "{\"automatic\":{}}";
        }

        private Secret buildSecret(String fullName, Map<String, Object> data) {
            Secret.Builder builder = Secret.newBuilder()
                    .setName(fullName);

            // Add replication
            builder.setReplication(Replication.newBuilder()
                    .setAutomatic(Replication.Automatic.getDefaultInstance())
                    .build());

            // Add labels
            Object labelsObj = data.get("labels");
            if (labelsObj != null) {
                String labelsJson = labelsObj.toString();
                if (!"{}".equals(labelsJson) && !labelsJson.isEmpty()) {
                    try {
                        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        var labelsMap = mapper.readValue(labelsJson,
                                new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, String>>() {});
                        builder.putAllLabels(labelsMap);
                    } catch (Exception e) {
                        // Non-critical — labels won't appear but won't break
                    }
                }
            }

            // Add expiration
            java.sql.Timestamp expireAt = (java.sql.Timestamp) data.get("expire_at");
            if (expireAt != null) {
                builder.setExpireTime(Timestamp.newBuilder()
                        .setSeconds(expireAt.getTime() / 1000)
                        .setNanos((int) ((expireAt.getTime() % 1000) * 1_000_000))
                        .build());
            }

            // Add rotation
            Object rotObj = data.get("rotation_period");
            if (rotObj != null) {
                long rotationPeriod = ((Number) rotObj).longValue();
                builder.setRotation(com.google.cloud.secretmanager.v1.Rotation.newBuilder()
                        .setRotationPeriod(com.google.protobuf.Duration.newBuilder()
                                .setSeconds(rotationPeriod)
                                .build())
                        .build());
            }

            if (data.get("created_at") != null) {
                java.sql.Timestamp ts = (java.sql.Timestamp) data.get("created_at");
                builder.setCreateTime(Timestamp.newBuilder()
                        .setSeconds(ts.getTime() / 1000)
                        .setNanos((int) ((ts.getTime() % 1000) * 1_000_000))
                        .build());
            }

            return builder.build();
        }

        private SecretVersion.State mapState(String state) {
            if (state == null) return SecretVersion.State.STATE_UNSPECIFIED;
            return switch (state) {
                case "ENABLED" -> SecretVersion.State.ENABLED;
                case "DISABLED" -> SecretVersion.State.DISABLED;
                case "DESTROYED" -> SecretVersion.State.DESTROYED;
                default -> SecretVersion.State.STATE_UNSPECIFIED;
            };
        }

        // ── IAM Policy gRPC methods ────────────────────────────────────────────

        @Override
        public void getIamPolicy(GetIamPolicyRequest request, StreamObserver<Policy> responseObserver) {
            incrementRequestCount();
            iamHelper.getIamPolicy(request, responseObserver);
        }

        @Override
        public void setIamPolicy(SetIamPolicyRequest request, StreamObserver<Policy> responseObserver) {
            incrementRequestCount();
            iamHelper.setIamPolicy(request, responseObserver);
        }

        @Override
        public void testIamPermissions(TestIamPermissionsRequest request,
                                       StreamObserver<TestIamPermissionsResponse> responseObserver) {
            incrementRequestCount();
            iamHelper.testIamPermissions(request, responseObserver);
        }
    }
}

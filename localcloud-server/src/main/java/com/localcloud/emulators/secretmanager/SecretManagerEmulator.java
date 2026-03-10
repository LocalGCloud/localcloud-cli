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
import com.google.cloud.secretmanager.v1.SecretPayload;
import com.google.cloud.secretmanager.v1.SecretVersion;
import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;
import com.localcloud.emulators.AbstractEmulator;
import com.localcloud.persistence.PostgresDataSource;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/**
 * Cloud Secret Manager gRPC emulator.
 * Implements the SecretManagerService gRPC API backed by PostgreSQL persistence.
 */
public class SecretManagerEmulator extends AbstractEmulator {

    private final SecretManagerStore store;
    private final SecretManagerServiceImpl serviceImpl;

    public SecretManagerEmulator(PostgresDataSource dataSource) {
        super("secretmanager", "Secret Manager", 8080, "grpc", "SECRET_MANAGER_EMULATOR_HOST");
        this.store = new SecretManagerStore(dataSource);
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
                    StringBuilder sb = new StringBuilder("{");
                    boolean first = true;
                    for (Map.Entry<String, String> entry : request.getSecret().getLabelsMap().entrySet()) {
                        if (!first) sb.append(",");
                        sb.append("\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
                        first = false;
                    }
                    sb.append("}");
                    labelsJson = sb.toString();
                }

                store.createSecret(projectId, secretId, labelsJson);

                String fullName = parent + "/secrets/" + secretId;
                Secret response = Secret.newBuilder()
                        .setName(fullName)
                        .setReplication(Replication.newBuilder()
                                .setAutomatic(Replication.Automatic.getDefaultInstance())
                                .build())
                        .build();

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
                List<Map<String, Object>> secrets = store.listSecrets(projectId);

                ListSecretsResponse.Builder builder = ListSecretsResponse.newBuilder();
                for (Map<String, Object> data : secrets) {
                    String fullName = "projects/" + data.get("project_id") + "/secrets/" + data.get("secret_id");
                    builder.addSecrets(buildSecret(fullName, data));
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

                Map<String, Object> data = store.getSecretVersion(parts[0], parts[1], parts[2]);
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

                List<Map<String, Object>> versions = store.listSecretVersions(parts[0], parts[1]);

                ListSecretVersionsResponse.Builder builder = ListSecretVersionsResponse.newBuilder();
                for (Map<String, Object> data : versions) {
                    String versionName = parent + "/versions/" + data.get("version_number");
                    builder.addVersions(SecretVersion.newBuilder()
                            .setName(versionName)
                            .setState(mapState((String) data.get("state")))
                            .build());
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

                // First check if version exists
                Map<String, Object> versionData = store.getSecretVersion(parts[0], parts[1], parts[2]);
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

                byte[] payload = store.accessSecretVersion(parts[0], parts[1], parts[2]);
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

        private void handleVersionStateChange(String fullName, String action,
                                               StreamObserver<SecretVersion> responseObserver) {
            try {
                String[] parts = SecretManagerStore.parseVersionName(fullName);
                int versionNumber = Integer.parseInt(parts[2]);

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

        private Secret buildSecret(String fullName, Map<String, Object> data) {
            Secret.Builder builder = Secret.newBuilder()
                    .setName(fullName)
                    .setReplication(Replication.newBuilder()
                            .setAutomatic(Replication.Automatic.getDefaultInstance())
                            .build());

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
    }
}

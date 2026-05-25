package com.localcloud.emulators.alloydb;

import java.time.Instant;

import com.google.cloud.alloydb.v1.AlloyDBAdminGrpc;
import com.google.cloud.alloydb.v1.Backup;
import com.google.cloud.alloydb.v1.Cluster;
import com.google.cloud.alloydb.v1.ConnectionInfo;
import com.google.cloud.alloydb.v1.CreateBackupRequest;
import com.google.cloud.alloydb.v1.CreateClusterRequest;
import com.google.cloud.alloydb.v1.CreateInstanceRequest;
import com.google.cloud.alloydb.v1.CreateUserRequest;
import com.google.cloud.alloydb.v1.DeleteClusterRequest;
import com.google.cloud.alloydb.v1.DeleteUserRequest;
import com.google.cloud.alloydb.v1.GetBackupRequest;
import com.google.cloud.alloydb.v1.GetClusterRequest;
import com.google.cloud.alloydb.v1.GetConnectionInfoRequest;
import com.google.cloud.alloydb.v1.GetInstanceRequest;
import com.google.cloud.alloydb.v1.GetUserRequest;
import com.google.cloud.alloydb.v1.Instance;
import com.google.cloud.alloydb.v1.ListBackupsRequest;
import com.google.cloud.alloydb.v1.ListBackupsResponse;
import com.google.cloud.alloydb.v1.ListClustersRequest;
import com.google.cloud.alloydb.v1.ListClustersResponse;
import com.google.cloud.alloydb.v1.ListInstancesRequest;
import com.google.cloud.alloydb.v1.ListInstancesResponse;
import com.google.cloud.alloydb.v1.ListUsersRequest;
import com.google.cloud.alloydb.v1.ListUsersResponse;
import com.google.cloud.alloydb.v1.User;
import com.google.longrunning.Operation;
import com.google.protobuf.Empty;
import com.localcloud.emulators.AbstractEmulator;
import com.localcloud.emulators.common.GrpcSupport;
import com.localcloud.persistence.PostgresDataSource;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

public class AlloyDBEmulator extends AbstractEmulator {
    private final AlloyDBRepository repository;
    private final AlloyDBService service = new AlloyDBService();

    public AlloyDBEmulator(PostgresDataSource dataSource) {
        super("alloydb", "AlloyDB", 8080, "grpc", "ALLOYDB_EMULATOR_HOST");
        this.repository = new AlloyDBRepository(dataSource);
    }

    public AlloyDBService getServiceImpl() {
        return service;
    }

    @Override protected void doStart() {
        logger.info("AlloyDB emulator initialized");
    }

    @Override protected void doStop() {}

    @Override protected void doReset() {}

    public class AlloyDBService extends AlloyDBAdminGrpc.AlloyDBAdminImplBase {
        @Override public void createCluster(CreateClusterRequest request, StreamObserver<Operation> responseObserver) {
            incrementRequestCount();
            try {
                String[] parent = GrpcSupport.parseLocationParent(request.getParent());
                if (repository.clusterExists(parent[0], parent[1], request.getClusterId())) {
                    throw Status.ALREADY_EXISTS.asRuntimeException();
                }
                String name = request.getParent() + "/clusters/" + request.getClusterId();
                Cluster cluster = request.getCluster().toBuilder()
                        .setName(name)
                        .setState(Cluster.State.READY)
                        .setCreateTime(GrpcSupport.timestamp(Instant.now()))
                        .setUpdateTime(GrpcSupport.timestamp(Instant.now()))
                        .build();
                repository.createCluster(parent[0], parent[1], request.getClusterId(), cluster);
                responseObserver.onNext(GrpcSupport.doneOperation(name + "/operations/create", cluster));
                responseObserver.onCompleted();
            } catch (io.grpc.StatusRuntimeException e) {
                responseObserver.onError(e);
            } catch (Exception e) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void getCluster(GetClusterRequest request, StreamObserver<Cluster> responseObserver) {
            getNamed(request.getName(), "clusters", repository::getCluster, responseObserver);
        }

        @Override public void listClusters(ListClustersRequest request, StreamObserver<ListClustersResponse> responseObserver) {
            incrementRequestCount();
            try {
                String[] parent = GrpcSupport.parseLocationParent(request.getParent());
                responseObserver.onNext(ListClustersResponse.newBuilder()
                        .addAllClusters(repository.listClusters(parent[0], parent[1]))
                        .build());
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void deleteCluster(DeleteClusterRequest request, StreamObserver<Operation> responseObserver) {
            incrementRequestCount();
            try {
                String[] parts = GrpcSupport.parseNamedResource(request.getName(), "clusters");
                if (!repository.deleteCluster(parts[0], parts[1], parts[2])) throw Status.NOT_FOUND.asRuntimeException();
                responseObserver.onNext(Operation.newBuilder().setName(request.getName() + "/operations/delete").setDone(true).build());
                responseObserver.onCompleted();
            } catch (io.grpc.StatusRuntimeException e) {
                responseObserver.onError(e);
            } catch (Exception e) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void createInstance(CreateInstanceRequest request, StreamObserver<Operation> responseObserver) {
            incrementRequestCount();
            try {
                String[] parent = GrpcSupport.parseNamedResource(request.getParent(), "clusters");
                if (repository.getCluster(parent[0], parent[1], parent[2]) == null) throw Status.NOT_FOUND.asRuntimeException();
                String name = request.getParent() + "/instances/" + request.getInstanceId();
                Instance instance = request.getInstance().toBuilder()
                        .setName(name)
                        .setState(Instance.State.READY)
                        .setCreateTime(GrpcSupport.timestamp(Instant.now()))
                        .setUpdateTime(GrpcSupport.timestamp(Instant.now()))
                        .build();
                repository.createInstance(parent[0], parent[1], parent[2], request.getInstanceId(), instance);
                responseObserver.onNext(GrpcSupport.doneOperation(name + "/operations/create", instance));
                responseObserver.onCompleted();
            } catch (io.grpc.StatusRuntimeException e) {
                responseObserver.onError(e);
            } catch (Exception e) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void getInstance(GetInstanceRequest request, StreamObserver<Instance> responseObserver) {
            incrementRequestCount();
            try {
                String[] p = GrpcSupport.parseChildResource(request.getName(), "clusters", "instances");
                Instance instance = repository.getInstance(p[0], p[1], p[2], p[3]);
                if (instance == null) throw Status.NOT_FOUND.asRuntimeException();
                responseObserver.onNext(instance);
                responseObserver.onCompleted();
            } catch (io.grpc.StatusRuntimeException e) {
                responseObserver.onError(e);
            } catch (Exception e) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void listInstances(ListInstancesRequest request, StreamObserver<ListInstancesResponse> responseObserver) {
            incrementRequestCount();
            try {
                String[] p = GrpcSupport.parseNamedResource(request.getParent(), "clusters");
                responseObserver.onNext(ListInstancesResponse.newBuilder()
                        .addAllInstances(repository.listInstances(p[0], p[1], p[2]))
                        .build());
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void getConnectionInfo(GetConnectionInfoRequest request, StreamObserver<ConnectionInfo> responseObserver) {
            incrementRequestCount();
            responseObserver.onNext(ConnectionInfo.newBuilder()
                    .setName(request.getParent() + "/connectionInfo")
                    .setIpAddress("127.0.0.1")
                    .setInstanceUid("localhost")
                    .build());
            responseObserver.onCompleted();
        }

        @Override public void createBackup(CreateBackupRequest request, StreamObserver<Operation> responseObserver) {
            incrementRequestCount();
            try {
                String[] parent = GrpcSupport.parseLocationParent(request.getParent());
                String name = request.getParent() + "/backups/" + request.getBackupId();
                Backup backup = request.getBackup().toBuilder()
                        .setName(name)
                        .setState(Backup.State.READY)
                        .setCreateTime(GrpcSupport.timestamp(Instant.now()))
                        .setUpdateTime(GrpcSupport.timestamp(Instant.now()))
                        .build();
                repository.createBackup(parent[0], parent[1], request.getBackupId(), backup.getClusterName(), backup);
                responseObserver.onNext(GrpcSupport.doneOperation(name + "/operations/create", backup));
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void getBackup(GetBackupRequest request, StreamObserver<Backup> responseObserver) {
            getNamed(request.getName(), "backups", repository::getBackup, responseObserver);
        }

        @Override public void listBackups(ListBackupsRequest request, StreamObserver<ListBackupsResponse> responseObserver) {
            incrementRequestCount();
            try {
                String[] parent = GrpcSupport.parseLocationParent(request.getParent());
                responseObserver.onNext(ListBackupsResponse.newBuilder()
                        .addAllBackups(repository.listBackups(parent[0], parent[1]))
                        .build());
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void createUser(CreateUserRequest request, StreamObserver<User> responseObserver) {
            incrementRequestCount();
            try {
                String[] parent = GrpcSupport.parseNamedResource(request.getParent(), "clusters");
                User user = request.getUser().toBuilder()
                        .setName(request.getParent() + "/users/" + request.getUserId())
                        .build();
                repository.createUser(parent[0], parent[1], parent[2], request.getUserId(), user);
                responseObserver.onNext(user);
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void getUser(GetUserRequest request, StreamObserver<User> responseObserver) {
            incrementRequestCount();
            try {
                String[] p = GrpcSupport.parseChildResource(request.getName(), "clusters", "users");
                User user = repository.getUser(p[0], p[1], p[2], p[3]);
                if (user == null) throw Status.NOT_FOUND.asRuntimeException();
                responseObserver.onNext(user);
                responseObserver.onCompleted();
            } catch (io.grpc.StatusRuntimeException e) {
                responseObserver.onError(e);
            } catch (Exception e) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void listUsers(ListUsersRequest request, StreamObserver<ListUsersResponse> responseObserver) {
            incrementRequestCount();
            try {
                String[] p = GrpcSupport.parseNamedResource(request.getParent(), "clusters");
                responseObserver.onNext(ListUsersResponse.newBuilder()
                        .addAllUsers(repository.listUsers(p[0], p[1], p[2]))
                        .build());
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void deleteUser(DeleteUserRequest request, StreamObserver<Empty> responseObserver) {
            incrementRequestCount();
            try {
                String[] p = GrpcSupport.parseChildResource(request.getName(), "clusters", "users");
                if (!repository.deleteUser(p[0], p[1], p[2], p[3])) throw Status.NOT_FOUND.asRuntimeException();
                responseObserver.onNext(Empty.getDefaultInstance());
                responseObserver.onCompleted();
            } catch (io.grpc.StatusRuntimeException e) {
                responseObserver.onError(e);
            } catch (Exception e) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        private <T> void getNamed(String name, String collection, ResourceGetter<T> getter, StreamObserver<T> observer) {
            incrementRequestCount();
            try {
                String[] p = GrpcSupport.parseNamedResource(name, collection);
                T value = getter.get(p[0], p[1], p[2]);
                if (value == null) throw Status.NOT_FOUND.asRuntimeException();
                observer.onNext(value);
                observer.onCompleted();
            } catch (io.grpc.StatusRuntimeException e) {
                observer.onError(e);
            } catch (Exception e) {
                observer.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            }
        }
    }

    private interface ResourceGetter<T> {
        T get(String projectId, String locationId, String id) throws Exception;
    }
}

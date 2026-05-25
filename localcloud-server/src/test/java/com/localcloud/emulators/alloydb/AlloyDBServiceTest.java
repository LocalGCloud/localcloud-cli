package com.localcloud.emulators.alloydb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.localcloud.integration.TestDataSource;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

class AlloyDBServiceTest {
    @Test
    void managesClustersInstancesBackupsUsersAndConnectionInfo() throws Exception {
        TestDataSource testDataSource = TestDataSource.create("alloydb_service_" + System.nanoTime());
        AlloyDBEmulator emulator = new AlloyDBEmulator(testDataSource.getDataSource());
        emulator.start();
        try {
            var service = emulator.getServiceImpl();
            String parent = "projects/p/locations/us-central1";
            String clusterName = parent + "/clusters/c";
            String instanceName = clusterName + "/instances/primary";
            String backupName = parent + "/backups/b";
            String userName = clusterName + "/users/app";

            var createCluster = new TestObserver<Operation>();
            service.createCluster(CreateClusterRequest.newBuilder()
                    .setParent(parent)
                    .setClusterId("c")
                    .setCluster(Cluster.newBuilder().build())
                    .build(), createCluster);
            Cluster cluster = createCluster.value().getResponse().unpack(Cluster.class);
            assertEquals(clusterName, cluster.getName());
            assertEquals(Cluster.State.READY, cluster.getState());

            var duplicate = new TestObserver<Operation>();
            service.createCluster(CreateClusterRequest.newBuilder()
                    .setParent(parent)
                    .setClusterId("c")
                    .setCluster(Cluster.newBuilder().build())
                    .build(), duplicate);
            assertEquals(Status.Code.ALREADY_EXISTS, duplicate.errorCode());

            var getCluster = new TestObserver<Cluster>();
            service.getCluster(GetClusterRequest.newBuilder().setName(clusterName).build(), getCluster);
            assertEquals(clusterName, getCluster.value().getName());

            var listClusters = new TestObserver<ListClustersResponse>();
            service.listClusters(ListClustersRequest.newBuilder().setParent(parent).build(), listClusters);
            assertEquals(1, listClusters.value().getClustersCount());

            var createInstance = new TestObserver<Operation>();
            service.createInstance(CreateInstanceRequest.newBuilder()
                    .setParent(clusterName)
                    .setInstanceId("primary")
                    .setInstance(Instance.newBuilder().build())
                    .build(), createInstance);
            Instance instance = createInstance.value().getResponse().unpack(Instance.class);
            assertEquals(instanceName, instance.getName());
            assertEquals(Instance.State.READY, instance.getState());

            var getInstance = new TestObserver<Instance>();
            service.getInstance(GetInstanceRequest.newBuilder().setName(instanceName).build(), getInstance);
            assertEquals(instanceName, getInstance.value().getName());

            var listInstances = new TestObserver<ListInstancesResponse>();
            service.listInstances(ListInstancesRequest.newBuilder().setParent(clusterName).build(), listInstances);
            assertEquals(1, listInstances.value().getInstancesCount());

            var connectionInfo = new TestObserver<ConnectionInfo>();
            service.getConnectionInfo(GetConnectionInfoRequest.newBuilder().setParent(clusterName).build(), connectionInfo);
            assertEquals("127.0.0.1", connectionInfo.value().getIpAddress());

            var createBackup = new TestObserver<Operation>();
            service.createBackup(CreateBackupRequest.newBuilder()
                    .setParent(parent)
                    .setBackupId("b")
                    .setBackup(Backup.newBuilder().setClusterName(clusterName).build())
                    .build(), createBackup);
            Backup backup = createBackup.value().getResponse().unpack(Backup.class);
            assertEquals(backupName, backup.getName());

            var getBackup = new TestObserver<Backup>();
            service.getBackup(GetBackupRequest.newBuilder().setName(backupName).build(), getBackup);
            assertEquals(backupName, getBackup.value().getName());

            var listBackups = new TestObserver<ListBackupsResponse>();
            service.listBackups(ListBackupsRequest.newBuilder().setParent(parent).build(), listBackups);
            assertEquals(1, listBackups.value().getBackupsCount());

            var createUser = new TestObserver<User>();
            service.createUser(CreateUserRequest.newBuilder()
                    .setParent(clusterName)
                    .setUserId("app")
                    .setUser(User.newBuilder().build())
                    .build(), createUser);
            assertEquals(userName, createUser.value().getName());

            var getUser = new TestObserver<User>();
            service.getUser(GetUserRequest.newBuilder().setName(userName).build(), getUser);
            assertEquals(userName, getUser.value().getName());

            var listUsers = new TestObserver<ListUsersResponse>();
            service.listUsers(ListUsersRequest.newBuilder().setParent(clusterName).build(), listUsers);
            assertEquals(1, listUsers.value().getUsersCount());

            var deleteUser = new TestObserver<Empty>();
            service.deleteUser(DeleteUserRequest.newBuilder().setName(userName).build(), deleteUser);
            deleteUser.value();

            var deleteCluster = new TestObserver<Operation>();
            service.deleteCluster(DeleteClusterRequest.newBuilder().setName(clusterName).build(), deleteCluster);
            assertTrue(deleteCluster.value().getDone());

            var missing = new TestObserver<Cluster>();
            service.getCluster(GetClusterRequest.newBuilder().setName(clusterName).build(), missing);
            assertEquals(Status.Code.NOT_FOUND, missing.errorCode());
        } finally {
            emulator.stop();
            testDataSource.close();
        }
    }

    private static final class TestObserver<T> implements StreamObserver<T> {
        private T value;
        private Throwable error;
        private boolean completed;

        @Override
        public void onNext(T value) {
            this.value = value;
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
        }

        @Override
        public void onCompleted() {
            this.completed = true;
        }

        T value() {
            assertNull(error, () -> String.valueOf(error));
            assertTrue(completed);
            assertNotNull(value);
            return value;
        }

        Status.Code errorCode() {
            assertNotNull(error);
            return Status.fromThrowable(error).getCode();
        }
    }
}

package com.localcloud.emulators.iam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.iam.v1.Binding;
import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import com.google.iam.v1.TestIamPermissionsRequest;
import com.google.iam.v1.TestIamPermissionsResponse;
import com.localcloud.integration.TestDataSource;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

class IAMServiceTest {
    @Test
    void allowsPermissionsAndStoresPolicies() throws Exception {
        TestDataSource testDataSource = TestDataSource.create("iam_service_" + System.nanoTime());
        IAMEmulator emulator = new IAMEmulator(testDataSource.getDataSource());
        emulator.start();
        try {
            var service = emulator.getServiceImpl();
            String resource = "projects/p/buckets/b";

            var permissions = new TestObserver<TestIamPermissionsResponse>();
            service.testIamPermissions(TestIamPermissionsRequest.newBuilder()
                    .setResource(resource)
                    .addPermissions("storage.buckets.get")
                    .addPermissions("storage.objects.list")
                    .build(), permissions);
            assertEquals(2, permissions.value().getPermissionsCount());

            var empty = new TestObserver<Policy>();
            service.getIamPolicy(GetIamPolicyRequest.newBuilder().setResource(resource).build(), empty);
            assertTrue(empty.value().getBindingsList().isEmpty());

            Policy policy = Policy.newBuilder()
                    .addBindings(Binding.newBuilder()
                            .setRole("roles/storage.objectViewer")
                            .addMembers("user:a@example.com"))
                    .build();
            var set = new TestObserver<Policy>();
            service.setIamPolicy(SetIamPolicyRequest.newBuilder()
                    .setResource(resource)
                    .setPolicy(policy)
                    .build(), set);
            assertEquals(policy, set.value());

            var get = new TestObserver<Policy>();
            service.getIamPolicy(GetIamPolicyRequest.newBuilder().setResource(resource).build(), get);
            assertEquals("roles/storage.objectViewer", get.value().getBindings(0).getRole());
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
    }
}

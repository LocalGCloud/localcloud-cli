package com.localcloud.emulators.iam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.iam.v1.Binding;
import com.google.iam.v1.Policy;
import com.localcloud.integration.TestDataSource;
import org.junit.jupiter.api.Test;

class IAMRepositoryTest {
    @Test
    void getWithoutPolicyReturnsEmptyPolicy() throws Exception {
        TestDataSource testDataSource = TestDataSource.create("iam_empty");
        try {
            IAMRepository repository = new IAMRepository(testDataSource.getDataSource());
            assertTrue(repository.get("projects/p/buckets/b").getBindingsList().isEmpty());
        } finally {
            testDataSource.close();
        }
    }

    @Test
    void setOverwritesExistingPolicy() throws Exception {
        TestDataSource testDataSource = TestDataSource.create("iam_upsert");
        try {
            IAMRepository repository = new IAMRepository(testDataSource.getDataSource());
            repository.set("projects/p/buckets/b", Policy.newBuilder()
                    .addBindings(Binding.newBuilder().setRole("roles/viewer").addMembers("user:a@example.com"))
                    .build());
            repository.set("projects/p/buckets/b", Policy.newBuilder()
                    .addBindings(Binding.newBuilder().setRole("roles/storage.admin").addMembers("user:b@example.com"))
                    .build());

            Policy policy = repository.get("projects/p/buckets/b");
            assertEquals(1, policy.getBindingsCount());
            assertEquals("roles/storage.admin", policy.getBindings(0).getRole());
        } finally {
            testDataSource.close();
        }
    }
}

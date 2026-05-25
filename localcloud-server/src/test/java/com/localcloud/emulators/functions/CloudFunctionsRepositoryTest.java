package com.localcloud.emulators.functions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.cloud.functions.v2.BuildConfig;
import com.google.cloud.functions.v2.Function;
import com.localcloud.integration.TestDataSource;
import org.junit.jupiter.api.Test;

class CloudFunctionsRepositoryTest {
    @Test
    void createListUpdateDeleteFunction() throws Exception {
        TestDataSource testDataSource = TestDataSource.create("functions_repo");
        try {
            CloudFunctionsRepository repository = new CloudFunctionsRepository(testDataSource.getDataSource());
            Function function = Function.newBuilder()
                    .setName("projects/p/locations/us/functions/fn")
                    .setBuildConfig(BuildConfig.newBuilder().setRuntime("nodejs20").setEntryPoint("hello"))
                    .setState(Function.State.ACTIVE)
                    .build();

            repository.create("p", "us", "fn", function);
            assertEquals(function, repository.get("p", "us", "fn"));
            assertEquals(1, repository.list("p", "us").size());

            Function updated = function.toBuilder()
                    .setBuildConfig(BuildConfig.newBuilder().setRuntime("python311").setEntryPoint("main"))
                    .build();
            repository.update("p", "us", "fn", updated);
            assertEquals("python311", repository.get("p", "us", "fn").getBuildConfig().getRuntime());

            repository.delete("p", "us", "fn");
            assertNull(repository.get("p", "us", "fn"));
        } finally {
            testDataSource.close();
        }
    }
}

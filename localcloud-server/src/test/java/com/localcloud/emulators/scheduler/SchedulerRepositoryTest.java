package com.localcloud.emulators.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import com.google.cloud.scheduler.v1.HttpMethod;
import com.google.cloud.scheduler.v1.HttpTarget;
import com.google.cloud.scheduler.v1.Job;
import com.localcloud.integration.TestDataSource;
import org.junit.jupiter.api.Test;

class SchedulerRepositoryTest {
    @Test
    void createUpdateDeleteJobAndRecordExecution() throws Exception {
        TestDataSource testDataSource = TestDataSource.create("scheduler_repo");
        try {
            SchedulerRepository repository = new SchedulerRepository(testDataSource.getDataSource());
            Job job = Job.newBuilder()
                    .setName("projects/p/locations/us/jobs/j")
                    .setSchedule("0 * * * *")
                    .setTimeZone("UTC")
                    .setState(Job.State.ENABLED)
                    .setHttpTarget(HttpTarget.newBuilder().setUri("http://localhost").setHttpMethod(HttpMethod.GET))
                    .build();

            repository.create("p", "us", "j", job, Instant.now());
            assertTrue(repository.exists("p", "us", "j"));
            assertEquals(job, repository.get("p", "us", "j"));
            assertEquals(1, repository.list("p", "us").size());

            Job paused = job.toBuilder().setState(Job.State.PAUSED).build();
            repository.update("p", "us", "j", paused, null);
            assertEquals(Job.State.PAUSED, repository.get("p", "us", "j").getState());

            repository.recordExecution(job.getName(), "OK", "HTTP 200");
            repository.delete("p", "us", "j");
            assertNull(repository.get("p", "us", "j"));
        } finally {
            testDataSource.close();
        }
    }
}

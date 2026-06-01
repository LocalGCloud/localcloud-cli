package com.localcloud.emulators.scheduler;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.scheduler.v1.Job;
import com.google.cloud.scheduler.v1.HttpTarget;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.annotation.*;
import com.localcloud.common.RestResponseHelper;

import java.util.*;

/**
 * REST endpoints for Cloud Scheduler job management.
 */
public class CloudSchedulerRestService {

    private final SchedulerRepository repo;
    private final CloudSchedulerEmulator emulator;

    public CloudSchedulerRestService(SchedulerRepository repo, CloudSchedulerEmulator emulator) {
        this.repo = repo;
        this.emulator = emulator;
    }

    @Post("/projects/{project}/locations/{location}/jobs")
    public HttpResponse createJob(@Param String project, @Param String location, String body) {
        emulator.incrementRequestCount();
        String jobId = "";
        try {
            var root = RestResponseHelper.parseBody(body);
            String jobName = root.path("name").asText(null);
            if (jobName == null || jobName.isBlank()) return RestResponseHelper.error(400, "Missing job name");
            jobId = jobName.contains("/") ? jobName.substring(jobName.lastIndexOf('/') + 1) : jobName;

            try {
                Job existing = repo.get(project, location, jobId);
                if (existing != null) return jobToResponse(project, location, existing);
            } catch (Exception ignored) {}

            String schedule = root.path("schedule").asText("* * * * *");
            String timeZone = root.path("timeZone").asText("UTC");
            String targetUri = root.path("httpTarget").path("uri").asText("");
            String httpMethod = root.path("httpTarget").path("httpMethod").asText("POST");

            com.google.cloud.scheduler.v1.HttpMethod protoMethod = "POST".equalsIgnoreCase(httpMethod)
                    ? com.google.cloud.scheduler.v1.HttpMethod.POST
                    : "GET".equalsIgnoreCase(httpMethod) ? com.google.cloud.scheduler.v1.HttpMethod.GET
                    : com.google.cloud.scheduler.v1.HttpMethod.POST;

            Job proto = Job.newBuilder()
                    .setName(jobName)
                    .setSchedule(schedule)
                    .setTimeZone(timeZone)
                    .setState(Job.State.ENABLED)
                    .setHttpTarget(HttpTarget.newBuilder().setUri(targetUri).setHttpMethod(protoMethod).build())
                    .build();

            repo.create(project, location, jobId, proto, null);
            return jobToResponse(project, location, proto);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("duplicate key")) {
                try {
                    Job existing = repo.get(project, location, jobId);
                    if (existing != null) return jobToResponse(project, location, existing);
                } catch (Exception ignored) {}
            }
            return RestResponseHelper.error(500, e.getMessage());
        }
    }

    @Get("/projects/{project}/locations/{location}/jobs/{job}")
    public HttpResponse getJob(@Param String project, @Param String location, @Param String job) {
        emulator.incrementRequestCount();
        try {
            Job j = repo.get(project, location, job);
            if (j == null) return RestResponseHelper.error(404, "Job not found");
            return jobToResponse(project, location, j);
        } catch (Exception e) {
            return RestResponseHelper.error(500, e.getMessage());
        }
    }

    @Get("/projects/{project}/locations/{location}/jobs")
    public HttpResponse listJobs(@Param String project, @Param String location) {
        emulator.incrementRequestCount();
        try {
            List<Job> jobs = repo.list(project, location);
            ObjectNode result = RestResponseHelper.MAPPER.createObjectNode();
            ArrayNode arr = result.putArray("jobs");
            for (Job j : jobs) {
                ObjectNode node = arr.addObject();
                node.put("name", j.getName());
                node.put("state", j.getState().name());
            }
            return RestResponseHelper.ok(result);
        } catch (Exception e) {
            return RestResponseHelper.error(500, e.getMessage());
        }
    }

    @Delete("/projects/{project}/locations/{location}/jobs/{job}")
    public HttpResponse deleteJob(@Param String project, @Param String location, @Param String job) {
        emulator.incrementRequestCount();
        try { repo.delete(project, location, job); } catch (Exception ignored) {}
        return HttpResponse.of(com.linecorp.armeria.common.HttpStatus.OK,
                com.linecorp.armeria.common.MediaType.JSON, "{}");
    }

    @Post("regex:^/projects/(?<project>[^/]+)/locations/(?<location>[^/]+)/jobs/(?<job>[^:]+):pause$")
    public HttpResponse pauseJob(@Param String project, @Param String location, @Param String job, String body) {
        emulator.incrementRequestCount();
        try {
            Job j = repo.get(project, location, job);
            if (j == null) return RestResponseHelper.error(404, "Job not found");
            return jobToResponse(project, location, j);
        } catch (Exception e) {
            return RestResponseHelper.error(500, e.getMessage());
        }
    }

    @Post("regex:^/projects/(?<project>[^/]+)/locations/(?<location>[^/]+)/jobs/(?<job>[^:]+):resume$")
    public HttpResponse resumeJob(@Param String project, @Param String location, @Param String job, String body) {
        emulator.incrementRequestCount();
        try {
            Job j = repo.get(project, location, job);
            if (j == null) return RestResponseHelper.error(404, "Job not found");
            return jobToResponse(project, location, j);
        } catch (Exception e) {
            return RestResponseHelper.error(500, e.getMessage());
        }
    }

    private HttpResponse jobToResponse(String project, String location, Job job) {
        try {
            ObjectNode result = RestResponseHelper.MAPPER.createObjectNode();
            result.put("name", job.getName());
            result.put("schedule", job.getSchedule());
            result.put("timeZone", job.getTimeZone());
            result.put("state", job.getState().name());
            result.putNull("userUpdateTime");
            result.putNull("lastAttemptTime");
            result.putNull("scheduleTime");
            ObjectNode status = result.putObject("status");
            status.put("code", 0);
            ObjectNode retry = result.putObject("retryConfig");
            retry.put("retryCount", 0);
            retry.putNull("maxRetryDuration");
            retry.putNull("minBackoffDuration");
            retry.putNull("maxBackoffDuration");
            retry.put("maxDoublings", 5);
            if (job.hasHttpTarget()) {
                ObjectNode ht = result.putObject("httpTarget");
                ht.put("uri", job.getHttpTarget().getUri());
                ht.put("httpMethod", job.getHttpTarget().getHttpMethod().name());
            } else if (job.hasPubsubTarget()) {
                ObjectNode pt = result.putObject("pubsubTarget");
                pt.put("topicName", job.getPubsubTarget().getTopicName());
            } else {
                result.putObject("httpTarget");
            }
            return RestResponseHelper.ok(result);
        } catch (Exception e) {
            return RestResponseHelper.error(500, e.getMessage());
        }
    }
}

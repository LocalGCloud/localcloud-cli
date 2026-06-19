package com.localcloud.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.HttpStatus;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

class UnsupportedOperationResponsesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void restResponseContainsCompatibilityFields() throws Exception {
        var response = UnsupportedOperationResponses.rest("compute", "disks.insert", "rest",
                "Use instance metadata workflows.").aggregate().join();

        assertEquals(HttpStatus.NOT_IMPLEMENTED, response.status());
        JsonNode error = MAPPER.readTree(response.contentUtf8()).path("error");
        assertEquals("unsupported_operation", error.path("reason").asText());
        assertEquals("compute", error.path("service").asText());
        assertEquals("disks.insert", error.path("operation").asText());
        assertEquals("/coverage/compute", error.path("coverage_url").asText());
    }

    @Test
    void grpcHelperReturnsUnimplementedError() {
        CapturingObserver<Object> observer = new CapturingObserver<>();

        UnsupportedOperationResponses.grpc(observer, "cloudrun", "jobs.create", "Use service metadata.");

        assertEquals(Status.Code.UNIMPLEMENTED, Status.fromThrowable(observer.error).getCode());
        assertTrue(Status.fromThrowable(observer.error).getDescription().contains("jobs.create"));
    }

    private static class CapturingObserver<T> implements StreamObserver<T> {
        Throwable error;
        @Override public void onNext(T value) {}
        @Override public void onError(Throwable t) { this.error = t; }
        @Override public void onCompleted() {}
    }
}

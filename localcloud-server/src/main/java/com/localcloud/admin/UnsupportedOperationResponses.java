package com.localcloud.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/**
 * Shared helpers for explicit unsupported-operation responses.
 */
public final class UnsupportedOperationResponses {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private UnsupportedOperationResponses() {}

    public static HttpResponse rest(String service, String operation, String surface, String workaround) {
        return rest(HttpStatus.NOT_IMPLEMENTED, service, operation, surface, workaround);
    }

    public static HttpResponse rest(HttpStatus status, String service, String operation,
                                    String surface, String workaround) {
        UnsupportedOperationError error = error(status.code(), service, operation, surface, workaround);
        try {
            return HttpResponse.of(status, MediaType.JSON, MAPPER.writeValueAsString(error.toResponseBody()));
        } catch (Exception e) {
            return HttpResponse.of(status, MediaType.JSON,
                    "{\"error\":{\"reason\":\"unsupported_operation\",\"message\":\"Unsupported operation\"}}");
        }
    }

    public static <T> void grpc(StreamObserver<T> observer, String service, String operation, String workaround) {
        observer.onError(Status.UNIMPLEMENTED
                .withDescription(error(501, service, operation, "grpc", workaround).message())
                .asRuntimeException());
    }

    public static UnsupportedOperationError error(int code, String service, String operation,
                                                  String surface, String workaround) {
        String message = service + " operation " + operation + " is not supported by LocalCloud.";
        return new UnsupportedOperationError(
                code,
                code == 501 ? "UNIMPLEMENTED" : "FAILED_PRECONDITION",
                message,
                "unsupported_operation",
                service,
                operation,
                surface,
                "unsupported",
                workaround,
                "/coverage/" + service);
    }
}

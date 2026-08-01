package com.localcloud.runtime;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface RuntimeProvider extends AutoCloseable {
    CompletableFuture<WorkloadResult> submit(WorkloadSpec spec, Consumer<WorkloadResult> events);
    boolean cancel(String workloadId);
    Optional<WorkloadResult> inspect(String workloadId);
    boolean available();
    String mode();
    @Override default void close() {}
}

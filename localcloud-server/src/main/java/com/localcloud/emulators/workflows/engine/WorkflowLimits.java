package com.localcloud.emulators.workflows.engine;

public final class WorkflowLimits {
    private WorkflowLimits() {}
    public static final int MAX_STEPS_PER_EXECUTION = 100_000;
    public static final int MAX_WORKFLOW_SOURCE_BYTES = 128 * 1024;
    public static final int MAX_EXECUTION_ARGUMENT_BYTES = 32 * 1024;
    public static final int MAX_HTTP_RESPONSE_BYTES = 2 * 1024 * 1024;
}

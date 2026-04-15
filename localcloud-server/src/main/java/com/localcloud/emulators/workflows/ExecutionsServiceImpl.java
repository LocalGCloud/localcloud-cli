package com.localcloud.emulators.workflows;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Placeholder — execution logic is handled by WorkflowsServiceImpl directly.
 * This class exists for API compatibility with the emulator registration pattern.
 */
public class ExecutionsServiceImpl {
    private static final Logger logger = LoggerFactory.getLogger(ExecutionsServiceImpl.class);
    private final WorkflowsStore store;

    public ExecutionsServiceImpl(WorkflowsStore store) {
        this.store = store;
    }
}

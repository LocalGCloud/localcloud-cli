package com.localcloud.emulators.vertexai;

import com.localcloud.emulators.AbstractEmulator;
import com.localcloud.emulators.iam.IAMPolicyRestHandler;
import com.localcloud.persistence.PostgresDataSource;

/**
 * Vertex AI facade emulator focused on local Gemini-style GenAI workflows.
 */
public class VertexAiEmulator extends AbstractEmulator {

    private final VertexAiStore store;
    private final VertexAiRestService restService;

    public VertexAiEmulator(PostgresDataSource dataSource, int gatewayPort) {
        this(dataSource, gatewayPort, null);
    }

    public VertexAiEmulator(PostgresDataSource dataSource, int gatewayPort, IAMPolicyRestHandler iamHandler) {
        super("vertexai", "Vertex AI", gatewayPort, "rest", "AIPLATFORM_EMULATOR_HOST");
        this.store = new VertexAiStore(dataSource);
        this.restService = new VertexAiRestService(store, this, iamHandler);
    }

    public VertexAiRestService getRestService() {
        return restService;
    }

    @Override
    protected void doStart() {
        logger.info("Vertex AI REST facade ready");
    }

    @Override
    protected void doStop() {
    }

    @Override
    protected void doReset() {
        store.clearAll();
    }
}

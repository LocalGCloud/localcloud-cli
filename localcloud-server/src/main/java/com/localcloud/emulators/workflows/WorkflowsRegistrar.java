package com.localcloud.emulators.workflows;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.localcloud.emulators.common.ServiceRegistrar;
import com.localcloud.emulators.common.ServiceRegistrationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorkflowsRegistrar implements ServiceRegistrar {
    private static final Logger logger = LoggerFactory.getLogger(WorkflowsRegistrar.class);

    @Override
    public void registerRoutes(ServerBuilder sb, GrpcServiceBuilder grpc,
                               ServiceRegistrationContext ctx) throws Exception {
        if (!ctx.config().isServiceEnabled("workflows")) return;

        var emulator = new WorkflowsEmulator(ctx.dataSource());
        emulator.start();
        var workflowsGrpc = new WorkflowsGrpcServiceImpl(emulator.getWorkflowsService());
        var executionsGrpc = new ExecutionsGrpcServiceImpl(emulator.getWorkflowsService());
        grpc.addService(workflowsGrpc);
        grpc.addService(executionsGrpc);
        ctx.gateway().registerGrpcEmulator(emulator, workflowsGrpc, executionsGrpc);

        // Workflow env vars and connector services
        var envVarsRepo = new WorkflowEnvVarsRepository(ctx.dataSource());
        emulator.getWorkflowsService().setEnvVarsRepository(envVarsRepo);
        var envVarsService = new WorkflowEnvVarsService(ctx.config(), envVarsRepo);
        sb.annotatedService("/workflow-env", envVarsService);
        var connectorService = new WorkflowConnectorService(ctx.config(), envVarsRepo,
                emulator.getWorkflowsService().getStore());
        sb.annotatedService("/workflow", connectorService);
        sb.annotatedService("/v1", new WorkflowsRestService(
                emulator.getWorkflowsService(), emulator, ctx.iamPolicyRestHandler()));

        // Register callback HTTP endpoint
        WorkflowsCallbackService callbackService = new WorkflowsCallbackService(
                emulator.getWorkflowsService().getCallbackManager());
        sb.annotatedService("/workflows", callbackService);

        // Wire into MutateService for console execution routing
        ctx.mutateService().setWorkflowsService(emulator.getWorkflowsService());
        logger.info("Cloud Workflows facade registered");
    }
}

package com.localcloud.emulators.common;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.localcloud.admin.CredentialBroker;
import com.localcloud.admin.MutateService;
import com.localcloud.admin.SeedService;
import com.localcloud.admin.ProjectService;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.docker.ContainerManager;
import com.localcloud.emulators.iam.IAMPolicyRestHandler;
import com.localcloud.gateway.ApiGateway;
import com.localcloud.persistence.PostgresDataSource;

/**
 * Bundle of shared dependencies passed to each {@link ServiceRegistrar}
 * during route registration. Keeps the registrar interface stable as
 * new cross-cutting dependencies are added.
 */
public record ServiceRegistrationContext(
        LocalCloudConfig config,
        PostgresDataSource dataSource,
        SeedService seedService,
        MutateService mutateService,
        CredentialBroker credentialBroker,
        ContainerManager containerManager,
        ProjectService projectService,
        IAMPolicyRestHandler iamPolicyRestHandler,
        ApiGateway gateway) {
}

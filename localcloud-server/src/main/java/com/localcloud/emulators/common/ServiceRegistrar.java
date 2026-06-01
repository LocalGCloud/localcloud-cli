package com.localcloud.emulators.common;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;

/**
 * Contract for facade emulator route registration.
 * <p>
 * Each GCP service that runs in-process on the gateway implements this
 * interface to self-register its HTTP routes, gRPC services, regex routes,
 * and annotated services. The {@link com.localcloud.LocalCloudApplication}
 * iterates over all registrars and calls this method once during startup.
 * </p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * public class SecretManagerRegistrar implements ServiceRegistrar {
 *     public void registerRoutes(ServerBuilder sb, GrpcServiceBuilder grpc,
 *                                 ServiceRegistrationContext ctx) throws Exception {
 *         var emulator = new SecretManagerEmulator(ctx.dataSource());
 *         emulator.start();
 *         grpc.addService(emulator.getServiceImpl());
 *         sb.annotatedService("/v1", new SecretManagerRestService(...));
 *         ctx.seedService().setSecretManagerEmulator(emulator);
 *     }
 * }
 * }</pre>
 *
 * <p>Registrars are stateless — the {@code registerRoutes} method is
 * called once at startup and does not retain references to its parameters.</p>
 */
@FunctionalInterface
public interface ServiceRegistrar {

    /**
     * Register all routes and services for this emulator.
     *
     * @param sb   Armeria server builder for HTTP routes and annotated services
     * @param grpc shared gRPC service builder for adding gRPC service implementations
     * @param ctx  shared registration context (config, data source, admin services)
     */
    void registerRoutes(ServerBuilder sb, GrpcServiceBuilder grpc,
                        ServiceRegistrationContext ctx) throws Exception;
}

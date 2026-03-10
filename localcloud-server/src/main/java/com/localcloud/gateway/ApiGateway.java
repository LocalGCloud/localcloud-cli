package com.localcloud.gateway;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.linecorp.armeria.server.HttpService;
import com.localcloud.emulators.EmulatorBase;

import io.grpc.BindableService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * API Gateway registry that tracks which emulators are registered and their
 * associated HTTP/gRPC services. The actual Armeria server routing is built
 * in {@link com.localcloud.LocalCloudApplication}; this class provides
 * the metadata and lookups needed during that wiring.
 */
public class ApiGateway {

    private static final Logger logger = LoggerFactory.getLogger(ApiGateway.class);

    /**
     * Registration record for a REST emulator.
     */
    public record RestRegistration(
            String pathPrefix,
            EmulatorBase emulator,
            HttpService service
    ) {}

    /**
     * Registration record for a gRPC emulator.
     */
    public record GrpcRegistration(
            EmulatorBase emulator,
            List<BindableService> grpcServices
    ) {}

    private final Map<String, EmulatorBase> emulators = new LinkedHashMap<>();
    private final Map<String, RestRegistration> restRegistrations = new LinkedHashMap<>();
    private final Map<String, GrpcRegistration> grpcRegistrations = new LinkedHashMap<>();

    /**
     * Register a REST emulator with a path prefix and Armeria HttpService.
     *
     * @param pathPrefix path prefix for routing (e.g., "/storage/v1")
     * @param emulator   the emulator instance
     * @param service    the Armeria HttpService that handles requests
     */
    public void registerRestEmulator(String pathPrefix, EmulatorBase emulator, HttpService service) {
        String name = emulator.getName();
        emulators.put(name, emulator);
        restRegistrations.put(name, new RestRegistration(pathPrefix, emulator, service));
        logger.info("Registered REST emulator: {} -> {}", name, pathPrefix);
    }

    /**
     * Register a gRPC emulator with one or more BindableService implementations.
     *
     * @param emulator     the emulator instance
     * @param grpcServices the gRPC service implementations
     */
    public void registerGrpcEmulator(EmulatorBase emulator, BindableService... grpcServices) {
        String name = emulator.getName();
        emulators.put(name, emulator);
        grpcRegistrations.put(name, new GrpcRegistration(emulator, List.of(grpcServices)));
        logger.info("Registered gRPC emulator: {} ({} services)", name, grpcServices.length);
    }

    /**
     * Get all registered emulators.
     */
    public List<EmulatorBase> getEmulators() {
        return List.copyOf(emulators.values());
    }

    /**
     * Get a specific emulator by its short name.
     */
    public Optional<EmulatorBase> getEmulator(String name) {
        return Optional.ofNullable(emulators.get(name));
    }

    /**
     * Get all REST registrations (unmodifiable).
     */
    public Map<String, RestRegistration> getRestRegistrations() {
        return Collections.unmodifiableMap(restRegistrations);
    }

    /**
     * Get all gRPC registrations (unmodifiable).
     */
    public Map<String, GrpcRegistration> getGrpcRegistrations() {
        return Collections.unmodifiableMap(grpcRegistrations);
    }

    /**
     * Total number of registered emulators.
     */
    public int getEmulatorCount() {
        return emulators.size();
    }
}

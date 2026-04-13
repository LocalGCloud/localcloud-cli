package com.localcloud.emulators;

/**
 * Base interface for all GCP service emulators.
 * Each emulator provides a specific GCP service (e.g., Cloud Storage, Pub/Sub)
 * and exposes lifecycle management, configuration, and request tracking.
 */
public interface EmulatorBase {

    /**
     * Short identifier for this emulator (e.g., "gcs", "pubsub", "firestore").
     */
    String getName();

    /**
     * Human-readable display name (e.g., "Cloud Storage", "Pub/Sub").
     */
    String getDisplayName();

    /**
     * Port this service listens on.
     */
    int getPort();

    /**
     * Protocol supported: "rest", "grpc", or "both".
     */
    String getProtocol();

    /**
     * Environment variable name clients use to connect (e.g., "STORAGE_EMULATOR_HOST").
     */
    String getEnvVarName();

    /**
     * Environment variable value for the given host (e.g., "http://localhost:8080").
     */
    String getEnvVarValue(String host);

    /**
     * Start the emulator. Called during server bootstrap.
     */
    void start() throws Exception;

    /**
     * Stop the emulator. Called during server shutdown.
     */
    void stop();

    /**
     * Whether the emulator is currently running and accepting requests.
     */
    boolean isRunning();

    /**
     * Clear all data managed by this emulator, resetting to a clean state.
     */
    void reset();

    /**
     * Total number of requests handled by this emulator since startup.
     */
    long getRequestCount();

    /**
     * Increment the request counter. Called by the gateway on each routed request.
     */
    void incrementRequestCount();

    /**
     * Atomically get the current request count and reset to zero.
     * Used by the periodic flush task to drain in-memory deltas to persistent storage.
     */
    long getAndResetRequestCount();
}

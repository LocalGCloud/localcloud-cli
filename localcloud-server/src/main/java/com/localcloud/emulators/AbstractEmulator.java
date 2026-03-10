package com.localcloud.emulators;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base implementation for all service emulators.
 * Provides common fields and default behaviour for lifecycle management,
 * request counting, and environment variable configuration.
 */
public abstract class AbstractEmulator implements EmulatorBase {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    private final String name;
    private final String displayName;
    private final int port;
    private final String protocol;
    private final String envVarName;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong requestCount = new AtomicLong(0);

    protected AbstractEmulator(String name,
                               String displayName,
                               int port,
                               String protocol,
                               String envVarName) {
        this.name = name;
        this.displayName = displayName;
        this.port = port;
        this.protocol = protocol;
        this.envVarName = envVarName;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public int getPort() {
        return port;
    }

    @Override
    public String getProtocol() {
        return protocol;
    }

    @Override
    public String getEnvVarName() {
        return envVarName;
    }

    @Override
    public String getEnvVarValue(String host) {
        if ("grpc".equals(protocol)) {
            return host + ":" + port;
        }
        return "http://" + host + ":" + port;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Mark this emulator as running. Subclasses should call this
     * at the end of their {@link #start()} implementation.
     */
    protected void setRunning(boolean value) {
        running.set(value);
    }

    @Override
    public long getRequestCount() {
        return requestCount.get();
    }

    @Override
    public void incrementRequestCount() {
        requestCount.incrementAndGet();
    }

    @Override
    public void start() throws Exception {
        if (running.compareAndSet(false, true)) {
            logger.info("{} emulator starting on port {}", displayName, port);
            doStart();
            logger.info("{} emulator started", displayName);
        } else {
            logger.warn("{} emulator is already running", displayName);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            logger.info("{} emulator stopping", displayName);
            doStop();
            logger.info("{} emulator stopped", displayName);
        }
    }

    @Override
    public void reset() {
        logger.info("{} emulator resetting all data", displayName);
        requestCount.set(0);
        doReset();
    }

    /**
     * Subclass-specific start logic. Called only when transitioning from stopped to running.
     */
    protected abstract void doStart() throws Exception;

    /**
     * Subclass-specific stop logic. Called only when transitioning from running to stopped.
     */
    protected abstract void doStop();

    /**
     * Subclass-specific reset logic. Called after the request counter is zeroed.
     */
    protected abstract void doReset();

    @Override
    public String toString() {
        return displayName + "[" + name + ", port=" + port + ", protocol=" + protocol + ", running=" + running.get() + "]";
    }
}

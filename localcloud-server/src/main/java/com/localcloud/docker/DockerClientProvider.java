package com.localcloud.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;

import java.time.Duration;

/**
 * Singleton provider for the Docker client.
 * Connects to the Docker daemon via the Unix socket.
 */
public final class DockerClientProvider {

    private static volatile DockerClient instance;

    private DockerClientProvider() {}

    public static DockerClient getClient() {
        if (instance == null) {
            synchronized (DockerClientProvider.class) {
                if (instance == null) {
                    DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                            .build();

                    DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                            .dockerHost(config.getDockerHost())
                            .connectionTimeout(Duration.ofSeconds(30))
                            .responseTimeout(Duration.ofSeconds(45))
                            .build();

                    instance = DockerClientImpl.getInstance(config, httpClient);
                }
            }
        }
        return instance;
    }
}

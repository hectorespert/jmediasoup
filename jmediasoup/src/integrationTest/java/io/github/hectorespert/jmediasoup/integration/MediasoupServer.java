/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.integration;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestWatcher;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts the mediasoup demo server of section 0.4 in a container, once for all the tests, and gives
 * it to the tests that declare a {@link Server} parameter. It skips the tests if there is no Docker
 * (or podman socket), or outside Linux, and prints the server logs when a test fails.
 */
public final class MediasoupServer implements BeforeAllCallback, ParameterResolver, TestWatcher {

    /** The image of section 0.4, pinned by digest. */
    static final DockerImageName IMAGE = DockerImageName.parse(
            "docker.io/mmahdisonic/mediasoup-demo@sha256:2e60f70cf77ed9ac8b3ef2adfcdb9df6666412c6c543831d5d97bdc37609c8c2");

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(MediasoupServer.class);

    /**
     * A running server.
     *
     * @param container the container
     * @param httpUrl the URL of its HTTP and WebSocket server
     */
    public record Server(GenericContainer<?> container, String httpUrl) implements AutoCloseable {

        /** The Origin that the server requires, whatever port it listens on. */
        public static final String ORIGIN = "http://localhost:4443";

        /**
         * Returns the logs of the server so far.
         *
         * @return stdout and stderr
         */
        public String logs() {
            return container.getLogs();
        }

        @Override
        public void close() {
            container.stop();
        }
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        server(context);
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == Server.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return server(extensionContext);
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        Optional.ofNullable(context.getRoot().getStore(NAMESPACE).get(Server.class, Server.class))
                .ifPresent(server -> System.err.println(
                        "mediasoup server logs for " + context.getDisplayName() + ":\n" + server.logs()));
    }

    private static Server server(ExtensionContext context) {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        // The server needs the host network, validated only on Linux (0.4.8 and 0.4.9).
        assumeTrue(os.startsWith("linux"), "the mediasoup server tests only run on Linux");
        assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available: start Docker, or the podman socket with DOCKER_HOST pointing to it");
        // The root store closes the server when all the tests have run.
        return context.getRoot().getStore(NAMESPACE).computeIfAbsent(Server.class, key -> start(), Server.class);
    }

    @SuppressWarnings("resource")
    private static Server start() {
        GenericContainer<?> container = new GenericContainer<>(IMAGE)
                // The workers listen on 44444 and the next ports, one per CPU, so the container uses
                // the host network instead of publishing them (0.4.8).
                .withNetworkMode("host")
                .withEnv("MEDIASOUP_LISTEN_IP", "127.0.0.1")
                .withEnv("MEDIASOUP_ANNOUNCED_ADDRESS", "127.0.0.1")
                .withEnv("DEBUG", "*INFO* *WARN* *ERROR*")
                .withCreateContainerCmdModifier(
                        cmd -> cmd.getHostConfig().withCapDrop(com.github.dockerjava.api.model.Capability.ALL))
                .waitingFor(Wait.forLogMessage(".*Server started.*", 1).withStartupTimeout(Duration.ofSeconds(60)));
        container.start();
        return new Server(container, "http://127.0.0.1:4443");
    }
}

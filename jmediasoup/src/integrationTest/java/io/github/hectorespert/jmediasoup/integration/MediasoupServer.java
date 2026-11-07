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

    /** The port of the WebRTC server of the single worker, outside the ephemeral range of Linux. */
    private static final int WEBRTC_SERVER_PORT = 24444;

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
                // The workers listen on their own ports and the plain transports on a range of ports,
                // so the container uses the host network instead of publishing them (0.4.8).
                .withNetworkMode("host")
                // The demo has one worker per CPU, on 44444 and the next ports, which are in the
                // ephemeral range of Linux (32768-60999), where any outgoing connection of the host
                // can take one first. One worker on 24444, and the plain transports below it too.
                .withCommand(
                        "sh",
                        "-c",
                        "sed -i -e 's/port: 44444/port: " + WEBRTC_SERVER_PORT + "/'"
                                + " -e 's/numWorkers: .*/numWorkers: 1,/' /app/config.mjs"
                                + " && exec mediasoup-demo-server")
                .withEnv("MEDIASOUP_MIN_PORT", "20000")
                .withEnv("MEDIASOUP_MAX_PORT", "20999")
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

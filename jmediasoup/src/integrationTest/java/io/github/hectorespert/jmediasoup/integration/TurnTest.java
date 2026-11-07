/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.onvoid.webrtc.RTCStats;
import dev.onvoid.webrtc.RTCStatsReport;
import dev.onvoid.webrtc.RTCStatsType;
import io.github.hectorespert.jmediasoup.Device;
import io.github.hectorespert.jmediasoup.Producer;
import io.github.hectorespert.jmediasoup.ProducerOptions;
import io.github.hectorespert.jmediasoup.SendTransport;
import io.github.hectorespert.jmediasoup.handler.webrtc.WebRtcJavaHandler;
import io.github.hectorespert.jmediasoup.integration.DemoPeer.Direction;
import io.github.hectorespert.jmediasoup.testfixtures.ColorVideoSource;
import io.github.hectorespert.jmediasoup.transport.IceServer;
import io.github.hectorespert.jmediasoup.transport.IceTransportPolicy;
import io.github.hectorespert.jmediasoup.transport.TransportOptions;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

/**
 * Sends through a TURN server, coturn in a container on the host network: with the {@code relay} ICE
 * transport policy, the only way to reach mediasoup is the relay of coturn.
 */
@ExtendWith(MediasoupServer.class)
class TurnTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** coturn 4.18.0, pinned by digest. */
    static final DockerImageName COTURN_IMAGE = DockerImageName.parse(
            "docker.io/coturn/coturn:4.18.0-alpine@sha256:c29135c08565810d37053f384584392b7c24b69570fc81a721b878d6fe63e04e");

    // Outside the ephemeral range of Linux, as the ports of the mediasoup server.
    private static final int TURN_PORT = 3478;

    private GenericContainer<?> coturn;
    private WebRtcJavaHandler.Factory factory;
    private ColorVideoSource videoSource;
    private DemoPeer peer;
    private SendTransport transport;

    @BeforeEach
    @SuppressWarnings("resource")
    void startTurnServer(MediasoupServer.Server server) throws Exception {
        coturn = new GenericContainer<>(COTURN_IMAGE)
                .withNetworkMode("host")
                .withCommand(
                        "-n",
                        "--log-file=stdout",
                        "--listening-ip=127.0.0.1",
                        "--relay-ip=127.0.0.1",
                        "--listening-port=" + TURN_PORT,
                        "--min-port=21000",
                        "--max-port=21099",
                        "--lt-cred-mech",
                        "--user=jmediasoup:secret",
                        "--realm=jmediasoup",
                        // mediasoup listens on 127.0.0.1, which coturn refuses to relay to by default.
                        "--allow-loopback-peers",
                        "--no-tls")
                .waitingFor(Wait.forLogMessage(".*Total auth threads.*", 1).withStartupTimeout(TIMEOUT));
        coturn.start();

        factory = WebRtcJavaHandler.createFactory();
        videoSource = new ColorVideoSource();
        var device = new Device(factory);
        peer = DemoPeer.connect(server, "turn-" + UUID.randomUUID(), "jmediasoup");
        await(device.load(await(peer.routerRtpCapabilities())));
        await(peer.join("jmediasoup", device.recvRtpCapabilities()));
        TransportOptions options = await(peer.createWebRtcTransport(Direction.PRODUCER)).toBuilder()
                .iceServers(List.of(new IceServer(
                        List.of("turn:127.0.0.1:" + TURN_PORT + "?transport=udp"), "jmediasoup", "secret")))
                .iceTransportPolicy(IceTransportPolicy.RELAY)
                .build();
        transport = device.createSendTransport(
                options,
                dtls -> peer.connectWebRtcTransport(options.id(), dtls),
                request -> peer.produce(options.id(), request.kind(), request.rtpParameters(), "webcam"));
    }

    @AfterEach
    void stopTurnServer() throws InterruptedException {
        if (transport != null) {
            transport.close();
        }
        if (peer != null) {
            peer.close();
        }
        if (factory != null) {
            factory.close();
        }
        if (videoSource != null) {
            videoSource.close();
        }
        coturn.stop();
    }

    private static <T> T await(CompletableFuture<T> future) {
        return future.orTimeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).join();
    }

    @Test
    void sendsThroughTheRelayOfTheTurnServer() throws InterruptedException {
        Producer video = await(transport.produce(
                ProducerOptions.builder(factory.peerConnectionFactory().createVideoTrack("video", videoSource.source()))
                        .build()));

        awaitReceived(video);

        // The candidate pair in use has a relay candidate on our side.
        RTCStatsReport stats = await(transport.getStats());
        String localCandidateId = stats.getStats().values().stream()
                .filter(stat -> stat.getType() == RTCStatsType.CANDIDATE_PAIR)
                .filter(stat ->
                        "succeeded".equals(String.valueOf(stat.getAttributes().get("state"))))
                .map(stat -> String.valueOf(stat.getAttributes().get("localCandidateId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no succeeded candidate pair: " + stats));
        RTCStats localCandidate = stats.getStats().get(localCandidateId);
        assertEquals("relay", String.valueOf(localCandidate.getAttributes().get("candidateType")), stats.toString());
    }

    /** Waits for the server to receive RTP from a producer. */
    private void awaitReceived(Producer producer) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        do {
            for (JsonNode stat : await(peer.producerStats(producer.id()))) {
                if (stat.path("type").asString().equals("inbound-rtp")
                        && stat.path("packetCount").asLong() > 0) {
                    return;
                }
            }
            Thread.sleep(200);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("no RTP received through the TURN server; coturn logs:\n" + coturn.getLogs());
    }
}

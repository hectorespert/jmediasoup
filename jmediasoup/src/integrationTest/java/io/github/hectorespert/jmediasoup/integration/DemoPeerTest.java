/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.integration;

import static io.github.hectorespert.jmediasoup.integration.ProtooClient.MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hectorespert.jmediasoup.Consumer;
import io.github.hectorespert.jmediasoup.Device;
import io.github.hectorespert.jmediasoup.RecvTransport;
import io.github.hectorespert.jmediasoup.handler.webrtc.WebRtcJavaHandler;
import io.github.hectorespert.jmediasoup.integration.DemoPeer.Direction;
import io.github.hectorespert.jmediasoup.integration.DemoPeer.NewConsumer;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
import io.github.hectorespert.jmediasoup.transport.ConnectionState;
import io.github.hectorespert.jmediasoup.transport.TransportOptions;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.JsonNode;

/** Tests the signaling of the demo server with a device of {@link WebRtcJavaHandler}. */
@ExtendWith(MediasoupServer.class)
class DemoPeerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    // What a sender would announce for Opus. No media is sent: the server creates the producer anyway.
    private static final RtpParameters OPUS = MAPPER.readValue("""
            {
              "codecs": [{"mimeType": "audio/opus", "payloadType": 100, "clockRate": 48000, "channels": 2}],
              "encodings": [{"ssrc": 11111111}],
              "rtcp": {"cname": "alice"}
            }
            """, RtpParameters.class);

    private WebRtcJavaHandler.Factory factory;
    private Device device;

    @BeforeEach
    void createDevice() {
        factory = WebRtcJavaHandler.createFactory();
        device = new Device(factory);
    }

    @AfterEach
    void closeFactory() {
        factory.close();
    }

    private static <T> T await(CompletableFuture<T> future) {
        return future.orTimeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).join();
    }

    private static String newRoom() {
        return "room-" + UUID.randomUUID();
    }

    @Test
    void theRouterCapabilitiesLoadTheDevice(MediasoupServer.Server server) throws Exception {
        try (var peer = DemoPeer.connect(server, newRoom(), "alice")) {
            await(device.load(await(peer.routerRtpCapabilities())));
        }

        assertTrue(device.loaded());
        assertTrue(device.canProduce(MediaKind.AUDIO));
        assertTrue(device.canProduce(MediaKind.VIDEO));
    }

    @Test
    void aFailedRequestFailsItsFuture(MediasoupServer.Server server) {
        try (var peer = DemoPeer.connect(server, newRoom(), "alice")) {
            CompletionException failure = assertThrows(
                    CompletionException.class, () -> await(peer.produce("unknown", MediaKind.AUDIO, OPUS, "audio")));

            assertInstanceOf(ProtooClient.RequestFailedException.class, failure.getCause());
        }
    }

    @Test
    void aRecvTransportConnectsToConsumeTheProducerOfAnotherPeer(MediasoupServer.Server server) throws Exception {
        String room = newRoom();
        try (var alice = DemoPeer.connect(server, room, "alice");
                var bob = DemoPeer.connect(server, room, "bob")) {
            await(device.load(await(bob.routerRtpCapabilities())));
            // The server consumes for a peer only once it has a consumer transport.
            TransportOptions recvOptions = await(bob.createWebRtcTransport(Direction.CONSUMER));
            await(bob.join("Bob", device.recvRtpCapabilities()));
            assertEquals(java.util.List.of("bob"), await(alice.join("Alice", device.recvRtpCapabilities())));
            TransportOptions sendOptions = await(alice.createWebRtcTransport(Direction.PRODUCER));
            String producerId = await(alice.produce(sendOptions.id(), MediaKind.AUDIO, OPUS, "audio"));

            NewConsumer newConsumer = bob.nextConsumer(TIMEOUT);
            assertEquals(producerId, newConsumer.options().producerId());
            assertEquals("alice", newConsumer.peerId());

            RecvTransport transport =
                    device.createRecvTransport(recvOptions, dtls -> bob.connectWebRtcTransport(recvOptions.id(), dtls));
            BlockingQueue<ConnectionState> states = new LinkedBlockingQueue<>();
            transport.onConnectionStateChange(states::add);
            Consumer consumer = await(transport.consume(newConsumer.options()));
            newConsumer.accept();

            // ICE and DTLS with the server, without media.
            awaitState(states, ConnectionState.CONNECTED);
            JsonNode transportStats = await(bob.transportStats(recvOptions.id()));
            assertEquals("connected", transportStats.get(0).path("dtlsState").asString());
            assertFalse(await(bob.consumerStats(consumer.id())).isEmpty());
            assertFalse(await(alice.producerStats(producerId)).isNull());

            transport.close();
            assertEquals(ConnectionState.CLOSED, transport.connectionState());
            assertTrue(consumer.closed());
        }
    }

    private static void awaitState(BlockingQueue<ConnectionState> states, ConnectionState expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            ConnectionState state = states.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (state == expected) {
                return;
            }
            assertFalse(
                    state == ConnectionState.FAILED || state == ConnectionState.CLOSED,
                    "the transport went " + state + " before " + expected);
        }
        throw new AssertionError("the transport did not reach " + expected + " in " + TIMEOUT);
    }
}

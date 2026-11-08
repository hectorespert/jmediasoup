/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;
import io.github.hectorespert.jmediasoup.Consumer;
import io.github.hectorespert.jmediasoup.Device;
import io.github.hectorespert.jmediasoup.Producer;
import io.github.hectorespert.jmediasoup.ProducerOptions;
import io.github.hectorespert.jmediasoup.RecvTransport;
import io.github.hectorespert.jmediasoup.SendTransport;
import io.github.hectorespert.jmediasoup.handler.webrtc.WebRtcJavaHandler;
import io.github.hectorespert.jmediasoup.integration.DemoPeer.Direction;
import io.github.hectorespert.jmediasoup.integration.DemoPeer.NewConsumer;
import io.github.hectorespert.jmediasoup.rtp.RtpEncodingParameters;
import io.github.hectorespert.jmediasoup.testfixtures.ColorVideoSource;
import io.github.hectorespert.jmediasoup.transport.TransportOptions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.JsonNode;

/**
 * Simulcast end to end: one jmediasoup peer sends three VP8 streams of the same track, and another
 * one receives the one that the server picks. The sender can also stop the streams above a layer.
 */
@ExtendWith(MediasoupServer.class)
class SimulcastTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    // Large enough for libwebrtc to send three streams, which it limits by resolution.
    private static final int WIDTH = 960;
    private static final int HEIGHT = 540;

    private WebRtcJavaHandler.Factory aliceFactory;
    private WebRtcJavaHandler.Factory bobFactory;
    private ColorVideoSource videoSource;
    private DemoPeer alice;
    private DemoPeer bob;
    private RecvTransport recvTransport;
    private SendTransport sendTransport;

    @BeforeEach
    void connect(MediasoupServer.Server server) throws Exception {
        aliceFactory = WebRtcJavaHandler.createFactory();
        bobFactory = WebRtcJavaHandler.createFactory();
        videoSource = new ColorVideoSource(WIDTH, HEIGHT);

        String room = "simulcast-" + UUID.randomUUID();
        alice = DemoPeer.connect(server, room, "alice");
        bob = DemoPeer.connect(server, room, "bob");

        var bobDevice = new Device(bobFactory);
        await(bobDevice.load(await(bob.routerRtpCapabilities())));
        TransportOptions recvOptions = await(bob.createWebRtcTransport(Direction.CONSUMER));
        await(bob.join("bob", bobDevice.recvRtpCapabilities()));
        recvTransport =
                bobDevice.createRecvTransport(recvOptions, dtls -> bob.connectWebRtcTransport(recvOptions.id(), dtls));

        var aliceDevice = new Device(aliceFactory);
        await(aliceDevice.load(await(alice.routerRtpCapabilities())));
        await(alice.join("alice", aliceDevice.recvRtpCapabilities()));
        TransportOptions sendOptions = await(alice.createWebRtcTransport(Direction.PRODUCER));
        sendTransport = aliceDevice.createSendTransport(
                sendOptions,
                dtls -> alice.connectWebRtcTransport(sendOptions.id(), dtls),
                request -> alice.produce(sendOptions.id(), request.kind(), request.rtpParameters(), "webcam"));
    }

    @AfterEach
    void close() throws InterruptedException {
        sendTransport.close();
        recvTransport.close();
        alice.close();
        bob.close();
        aliceFactory.close();
        bobFactory.close();
        videoSource.close();
    }

    private static <T> T await(CompletableFuture<T> future) {
        return future.orTimeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).join();
    }

    private Producer produceSimulcast() {
        return await(sendTransport.produce(ProducerOptions.builder(
                        aliceFactory.peerConnectionFactory().createVideoTrack("video", videoSource.source()))
                .encodings(List.of(
                        RtpEncodingParameters.builder()
                                .scaleResolutionDownBy(4.0)
                                .build(),
                        RtpEncodingParameters.builder()
                                .scaleResolutionDownBy(2.0)
                                .build(),
                        RtpEncodingParameters.builder().build()))
                .build()));
    }

    // The packets that the server received from each stream of a producer, by rid.
    private Map<String, Long> packetCounts(Producer producer) {
        var counts = new TreeMap<String, Long>();
        for (JsonNode stat : await(alice.producerStats(producer.id()))) {
            if (stat.path("type").asString().equals("inbound-rtp")) {
                counts.put(stat.path("rid").asString(), stat.path("packetCount").asLong());
            }
        }
        return counts;
    }

    // Waits for the server to receive packets from the streams of a producer.
    private void awaitReceived(Producer producer, Set<String> rids) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        Map<String, Long> counts;
        do {
            Thread.sleep(500);
            counts = packetCounts(producer);
        } while (!counts.entrySet().stream()
                        .filter(count -> count.getValue() > 0)
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toSet())
                        .containsAll(rids)
                && System.nanoTime() < deadline);

        Map<String, Long> received = counts;
        assertTrue(
                rids.stream().allMatch(rid -> received.getOrDefault(rid, 0L) > 0),
                "received " + received + " instead of " + rids);
    }

    // The streams of a producer from which the server receives packets during a second.
    private Set<String> receiving(Producer producer) throws InterruptedException {
        Map<String, Long> before = packetCounts(producer);
        Thread.sleep(1000);
        Map<String, Long> after = packetCounts(producer);
        return after.keySet().stream()
                .filter(rid -> after.get(rid) > before.getOrDefault(rid, 0L))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void theServerReceivesEveryStream() throws InterruptedException {
        Producer producer = produceSimulcast();

        awaitReceived(producer, Set.of("r0", "r1", "r2"));
    }

    @Test
    void limitingTheSpatialLayerStopsTheStreamsAboveIt() throws InterruptedException {
        Producer producer = produceSimulcast();
        awaitReceived(producer, Set.of("r0", "r1", "r2"));

        await(producer.setMaxSpatialLayer(1));
        // What was sent before the limit may still arrive.
        Thread.sleep(500);
        assertEquals(Set.of("r0", "r1"), receiving(producer));

        await(producer.setMaxSpatialLayer(2));
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        Set<String> receiving;
        do {
            receiving = receiving(producer);
        } while (receiving.size() < 3 && System.nanoTime() < deadline);
        assertEquals(Set.of("r0", "r1", "r2"), receiving);
    }

    @Test
    void theConsumerReceivesTheStreamOfThePreferredLayer() throws Exception {
        Producer producer = produceSimulcast();

        NewConsumer newConsumer = bob.nextConsumer(TIMEOUT);
        Consumer consumer = await(recvTransport.consume(newConsumer.options()));
        newConsumer.accept();
        assertEquals(producer.id(), consumer.producerId());

        var sink = new WidthSink();
        var track = (VideoTrack) consumer.track();
        track.addSink(sink);
        try {
            // The demo ignores the notification for a consumer that it has not stored yet, which it
            // does before resuming it.
            sink.awaitFrame();

            for (int spatialLayer : new int[] {0, 2, 1}) {
                bob.setConsumerPreferredLayers(consumer.id(), spatialLayer);
                sink.awaitWidth(WIDTH >> (2 - spatialLayer), () -> bob.requestConsumerKeyFrame(consumer.id()));
            }
        } finally {
            track.removeSink(sink);
        }
    }

    /** Keeps the width of the last received frame. */
    private static final class WidthSink implements VideoTrackSink {

        private volatile int width;

        @Override
        public void onVideoFrame(VideoFrame frame) {
            width = frame.buffer.getWidth();
        }

        void awaitFrame() throws InterruptedException {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (width == 0) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("no frames received");
                }
                Thread.sleep(100);
            }
        }

        /**
         * Waits for frames of a width, asking for a key frame every two seconds. To switch to another
         * stream, mediasoup 3.19.14 discards a key frame whose timestamp is too far behind those it
         * sent, and asks for another one, which libwebrtc ignores if it comes less than 300 ms after
         * the previous request: the switch then waits for the next key frame.
         */
        void awaitWidth(int expected, Runnable requestKeyFrame) throws InterruptedException {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            long nextRequest = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (width != expected) {
                long now = System.nanoTime();
                if (now > deadline) {
                    throw new AssertionError("frames of " + width + " instead of " + expected + " wide");
                }
                if (now >= nextRequest) {
                    requestKeyFrame.run();
                    nextRequest = now + TimeUnit.SECONDS.toNanos(2);
                }
                Thread.sleep(100);
            }
        }
    }
}

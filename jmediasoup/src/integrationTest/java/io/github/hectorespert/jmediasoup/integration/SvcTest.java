/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;
import io.github.hectorespert.jmediasoup.Consumer;
import io.github.hectorespert.jmediasoup.Device;
import io.github.hectorespert.jmediasoup.InvalidStateException;
import io.github.hectorespert.jmediasoup.Producer;
import io.github.hectorespert.jmediasoup.ProducerOptions;
import io.github.hectorespert.jmediasoup.RecvTransport;
import io.github.hectorespert.jmediasoup.SendTransport;
import io.github.hectorespert.jmediasoup.handler.webrtc.WebRtcJavaHandler;
import io.github.hectorespert.jmediasoup.integration.DemoPeer.Direction;
import io.github.hectorespert.jmediasoup.integration.DemoPeer.NewConsumer;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecCapability;
import io.github.hectorespert.jmediasoup.rtp.RtpEncodingParameters;
import io.github.hectorespert.jmediasoup.testfixtures.ColorVideoSource;
import io.github.hectorespert.jmediasoup.transport.TransportOptions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.JsonNode;

/**
 * SVC end to end: one jmediasoup peer sends VP9 with three spatial layers in a single stream, and
 * another one receives the layers that the server lets through.
 */
@ExtendWith(MediasoupServer.class)
class SvcTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    // Large enough for libwebrtc to encode three spatial layers, which it limits by resolution.
    private static final int WIDTH = 960;
    private static final int HEIGHT = 540;

    private WebRtcJavaHandler.Factory aliceFactory;
    private WebRtcJavaHandler.Factory bobFactory;
    private ColorVideoSource videoSource;
    private Device aliceDevice;
    private DemoPeer alice;
    private DemoPeer bob;
    private RecvTransport recvTransport;
    private SendTransport sendTransport;

    @BeforeEach
    void connect(MediasoupServer.Server server) throws Exception {
        aliceFactory = WebRtcJavaHandler.createFactory();
        bobFactory = WebRtcJavaHandler.createFactory();
        videoSource = new ColorVideoSource(WIDTH, HEIGHT);

        String room = "svc-" + UUID.randomUUID();
        alice = DemoPeer.connect(server, room, "alice");
        bob = DemoPeer.connect(server, room, "bob");

        var bobDevice = new Device(bobFactory);
        await(bobDevice.load(await(bob.routerRtpCapabilities())));
        TransportOptions recvOptions = await(bob.createWebRtcTransport(Direction.CONSUMER));
        await(bob.join("bob", bobDevice.recvRtpCapabilities()));
        recvTransport =
                bobDevice.createRecvTransport(recvOptions, dtls -> bob.connectWebRtcTransport(recvOptions.id(), dtls));

        aliceDevice = new Device(aliceFactory);
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

    private Producer produceSvc() throws InvalidStateException {
        RtpCodecCapability vp9 = aliceDevice.sendRtpCapabilities().codecs().stream()
                .filter(codec -> codec.mimeType().equalsIgnoreCase("video/VP9"))
                .findFirst()
                .orElseThrow();
        return await(sendTransport.produce(ProducerOptions.builder(
                        aliceFactory.peerConnectionFactory().createVideoTrack("video", videoSource.source()))
                .codec(vp9)
                .encodings(List.of(
                        RtpEncodingParameters.builder().scalabilityMode("L3T3").build()))
                .build()));
    }

    @Test
    void theServerReceivesASingleStreamWithThreeSpatialLayers() throws Exception {
        Producer producer = produceSvc();

        RtpEncodingParameters encoding = producer.rtpParameters().encodings().getFirst();
        assertEquals(1, producer.rtpParameters().encodings().size());
        assertEquals("L3T3", encoding.scalabilityMode());
        assertEquals("video/VP9", producer.rtpParameters().codecs().getFirst().mimeType());

        // The bitrate of each layer, by spatial.temporal, shows that the stream has the three.
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        JsonNode stats;
        Set<String> spatialLayers;
        do {
            Thread.sleep(500);
            stats = await(alice.producerStats(producer.id()));
            spatialLayers = new TreeSet<>();
            for (JsonNode stat : stats) {
                for (Map.Entry<String, JsonNode> layer :
                        stat.path("bitrateByLayer").properties()) {
                    if (layer.getValue().asLong() > 0) {
                        spatialLayers.add(
                                layer.getKey().substring(0, layer.getKey().indexOf('.')));
                    }
                }
            }
        } while (spatialLayers.size() < 3 && System.nanoTime() < deadline);

        assertEquals(1, stats.size(), stats.toString());
        assertEquals("video/VP9", stats.get(0).path("mimeType").asString(), stats.toString());
        assertEquals(Set.of("0", "1", "2"), spatialLayers, stats.toString());
    }

    @Test
    void theConsumerReceivesThePreferredSpatialLayer() throws Exception {
        Producer producer = produceSvc();

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
         * Waits for frames of a width, asking for a key frame every two seconds, as {@code
         * SimulcastTest} does: mediasoup needs one to switch to a higher spatial layer, and the
         * bitrate estimation takes some seconds to allow the highest one.
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

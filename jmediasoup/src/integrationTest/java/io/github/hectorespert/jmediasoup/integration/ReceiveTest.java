/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.onvoid.webrtc.RTCStatsReport;
import dev.onvoid.webrtc.RTCStatsType;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;
import io.github.hectorespert.jmediasoup.Consumer;
import io.github.hectorespert.jmediasoup.Device;
import io.github.hectorespert.jmediasoup.RecvTransport;
import io.github.hectorespert.jmediasoup.handler.webrtc.WebRtcJavaHandler;
import io.github.hectorespert.jmediasoup.integration.Broadcaster.Stream;
import io.github.hectorespert.jmediasoup.integration.DemoPeer.Direction;
import io.github.hectorespert.jmediasoup.integration.DemoPeer.NewConsumer;
import io.github.hectorespert.jmediasoup.transport.TransportOptions;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.JsonNode;

/**
 * Receives with {@link WebRtcJavaHandler} the media that ffmpeg publishes in the demo server, as a
 * broadcaster.
 */
@ExtendWith(MediasoupServer.class)
class ReceiveTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

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

    @Test
    void receivesTheAudioOfAProducerOfTheServer(MediasoupServer.Server server) throws Exception {
        receive(server, List.of(Stream.OPUS), (consumers, peer) -> {
            var sink = new ToneSink();
            var track = (AudioTrack) consumers.get(Stream.OPUS).track();
            track.addSink(sink);
            try {
                assertTrue(sink.tone.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "no tone in the received audio");
            } finally {
                track.removeSink(sink);
            }
            assertTrue(sentPackets(
                            await(peer.consumerStats(consumers.get(Stream.OPUS).id())))
                    > 0);
            RTCStatsReport stats = await(consumers.get(Stream.OPUS).getStats());
            assertTrue(SendTest.sum(stats, RTCStatsType.INBOUND_RTP, "packetsReceived") > 0, stats.toString());
        });
    }

    @Test
    void receivesTheVideoOfAProducerOfTheServerInVp8AndH264(MediasoupServer.Server server) throws Exception {
        receive(server, List.of(Stream.VP8, Stream.H264), (consumers, peer) -> {
            for (Stream stream : List.of(Stream.VP8, Stream.H264)) {
                var sink = new FrameSink();
                var track = (VideoTrack) consumers.get(stream).track();
                track.addSink(sink);
                try {
                    assertTrue(
                            sink.frames.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                            "no " + stream + " frames received");
                } finally {
                    track.removeSink(sink);
                }
                assertEquals(Stream.WIDTH, sink.width, stream + " width");
                assertEquals(Stream.HEIGHT, sink.height, stream + " height");
                assertTrue(sentPackets(
                                await(peer.consumerStats(consumers.get(stream).id())))
                        > 0);
            }
        });
    }

    private interface Check {
        void run(Map<Stream, Consumer> consumers, DemoPeer peer) throws Exception;
    }

    /** Consumes the streams of a broadcaster with a receive transport, and runs the check. */
    private void receive(MediasoupServer.Server server, List<Stream> streams, Check check) throws Exception {
        String room = "room-" + UUID.randomUUID();
        try (var peer = DemoPeer.connect(server, room, "jmediasoup")) {
            await(device.load(await(peer.routerRtpCapabilities())));
            TransportOptions options = await(peer.createWebRtcTransport(Direction.CONSUMER));
            await(peer.join("jmediasoup", device.recvRtpCapabilities()));
            RecvTransport transport =
                    device.createRecvTransport(options, dtls -> peer.connectWebRtcTransport(options.id(), dtls));
            try (var broadcaster = Broadcaster.start(server, room, "ffmpeg", streams)) {
                Map<Stream, Consumer> consumers = new EnumMap<>(Stream.class);
                for (int i = 0; i < streams.size(); i++) {
                    NewConsumer newConsumer = peer.nextConsumer(TIMEOUT);
                    Consumer consumer = await(transport.consume(newConsumer.options()));
                    newConsumer.accept();
                    Stream stream = streams.stream()
                            .filter(s -> broadcaster.producerId(s).equals(consumer.producerId()))
                            .findFirst()
                            .orElseThrow();
                    consumers.put(stream, consumer);
                }
                check.run(consumers, peer);
            } finally {
                transport.close();
            }
        }
    }

    /** The packets that the server has sent to a consumer, from its {@code outbound-rtp} stats. */
    private static long sentPackets(JsonNode consumerStats) {
        return consumerStats
                .valueStream()
                .filter(stat -> stat.path("type").asString().equals("outbound-rtp"))
                .mapToLong(stat -> stat.path("packetCount").asLong())
                .sum();
    }

    /** Waits for 16-bit audio that is not silence. */
    private static final class ToneSink implements AudioTrackSink {

        final CountDownLatch tone = new CountDownLatch(1);

        @Override
        public void onData(byte[] data, int bitsPerSample, int sampleRate, int channels, int frames) {
            for (int i = 0; i + 1 < data.length; i += 2) {
                // Little endian.
                int sample = (short) ((data[i] & 0xff) | (data[i + 1] << 8));
                if (Math.abs(sample) > 1000) {
                    tone.countDown();
                    return;
                }
            }
        }
    }

    /** Waits for 30 frames, a second of the test pattern, and keeps their size. */
    private static final class FrameSink implements VideoTrackSink {

        final CountDownLatch frames = new CountDownLatch(30);
        volatile int width;
        volatile int height;

        @Override
        public void onVideoFrame(VideoFrame frame) {
            width = frame.buffer.getWidth();
            height = frame.buffer.getHeight();
            frames.countDown();
        }
    }
}

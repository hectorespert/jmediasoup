/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.RTCStatsReport;
import dev.onvoid.webrtc.RTCStatsType;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import io.github.hectorespert.jmediasoup.Device;
import io.github.hectorespert.jmediasoup.Producer;
import io.github.hectorespert.jmediasoup.ProducerOptions;
import io.github.hectorespert.jmediasoup.SendTransport;
import io.github.hectorespert.jmediasoup.handler.webrtc.WebRtcJavaHandler;
import io.github.hectorespert.jmediasoup.integration.DemoPeer.Direction;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecCapability;
import io.github.hectorespert.jmediasoup.testfixtures.ColorVideoSource;
import io.github.hectorespert.jmediasoup.testfixtures.ToneAudioSource;
import io.github.hectorespert.jmediasoup.transport.ConnectionState;
import io.github.hectorespert.jmediasoup.transport.TransportOptions;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.JsonNode;

/** Sends with {@link WebRtcJavaHandler} the media of the test sources to the demo server. */
@ExtendWith(MediasoupServer.class)
class SendTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private WebRtcJavaHandler.Factory factory;
    private PeerConnectionFactory peerConnectionFactory;
    private ToneAudioSource audioSource;
    private ColorVideoSource videoSource;
    private Device device;
    private DemoPeer peer;
    private SendTransport transport;

    @BeforeEach
    void createTransport(MediasoupServer.Server server) throws Exception {
        factory = WebRtcJavaHandler.createFactory();
        peerConnectionFactory = factory.peerConnectionFactory();
        audioSource = new ToneAudioSource();
        videoSource = new ColorVideoSource();
        device = new Device(factory);

        peer = DemoPeer.connect(server, "room-" + UUID.randomUUID(), "jmediasoup");
        await(device.load(await(peer.routerRtpCapabilities())));
        await(peer.join("jmediasoup", device.recvRtpCapabilities()));
        TransportOptions options = await(peer.createWebRtcTransport(Direction.PRODUCER));
        transport = device.createSendTransport(
                options,
                dtls -> peer.connectWebRtcTransport(options.id(), dtls),
                request -> peer.produce(
                        options.id(),
                        request.kind(),
                        request.rtpParameters(),
                        switch (request.kind()) {
                            case AUDIO -> "mic";
                            case VIDEO -> "webcam";
                        }));
    }

    @AfterEach
    void closeTransport() throws InterruptedException {
        transport.close();
        peer.close();
        factory.close();
        audioSource.close();
        videoSource.close();
    }

    private static <T> T await(CompletableFuture<T> future) {
        return future.orTimeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).join();
    }

    private MediaStreamTrack audioTrack() {
        return peerConnectionFactory.createAudioTrack("audio", audioSource.source());
    }

    private MediaStreamTrack videoTrack() {
        return peerConnectionFactory.createVideoTrack("video", videoSource.source());
    }

    private RtpCodecCapability codec(String mimeType) throws Exception {
        return device.sendRtpCapabilities().codecs().stream()
                .filter(codec -> codec.mimeType().equalsIgnoreCase(mimeType))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void sendsAudio() throws InterruptedException {
        Producer audio =
                await(transport.produce(ProducerOptions.builder(audioTrack()).build()));

        assertEquals("audio/opus", awaitReceived(audio));
    }

    @Test
    void sendsVideoInVp8AndH264() throws Exception {
        Producer vp8 = await(transport.produce(
                ProducerOptions.builder(videoTrack()).codec(codec("video/VP8")).build()));
        Producer h264 = await(transport.produce(
                ProducerOptions.builder(videoTrack()).codec(codec("video/H264")).build()));

        assertEquals("video/VP8", awaitReceived(vp8));
        assertEquals("video/H264", awaitReceived(h264));
    }

    @Test
    void closesAProducerAndCreatesAnotherOnTheSameTransport() throws InterruptedException {
        Producer audio =
                await(transport.produce(ProducerOptions.builder(audioTrack()).build()));
        Producer video =
                await(transport.produce(ProducerOptions.builder(videoTrack()).build()));
        awaitReceived(video);

        video.close();
        peer.closeProducer(video.id());
        Producer again =
                await(transport.produce(ProducerOptions.builder(videoTrack()).build()));

        // The new producer reuses the closed section, and the other one keeps sending.
        assertNotEquals(video.localId(), again.localId());
        assertEquals("video/VP8", awaitReceived(again));
        assertEquals("audio/opus", awaitReceived(audio));
        CompletionException closed =
                assertThrows(CompletionException.class, () -> await(peer.producerStats(video.id())));
        assertInstanceOf(ProtooClient.RequestFailedException.class, closed.getCause());
    }

    @Test
    void pausingWithZeroRtpOnPauseStopsTheRtp() throws InterruptedException {
        Producer video = await(transport.produce(
                ProducerOptions.builder(videoTrack()).zeroRtpOnPause(true).build()));
        awaitReceived(video);

        video.pause();
        // The packets sent before the pause still arrive for a while.
        awaitStopped(video);

        video.resume();
        long paused = packetCount(video);
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (packetCount(video) == paused) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("no RTP received after resuming");
            }
            Thread.sleep(200);
        }
    }

    @Test
    void replacingTheTrackKeepsSending() throws InterruptedException {
        Producer video =
                await(transport.produce(ProducerOptions.builder(videoTrack()).build()));
        awaitReceived(video);

        await(video.replaceTrack(null));
        awaitStopped(video);

        await(video.replaceTrack(videoTrack()));
        long stopped = packetCount(video);
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (packetCount(video) == stopped) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("no RTP received after replacing the track");
            }
            Thread.sleep(200);
        }
    }

    @Test
    void theStatsReportWhatIsSent() throws InterruptedException {
        Producer video =
                await(transport.produce(ProducerOptions.builder(videoTrack()).build()));
        awaitReceived(video);

        RTCStatsReport producerStats = await(video.getStats());
        RTCStatsReport transportStats = await(transport.getStats());

        assertTrue(sum(producerStats, RTCStatsType.OUTBOUND_RTP, "packetsSent") > 0, producerStats.toString());
        assertTrue(sum(transportStats, RTCStatsType.TRANSPORT, "bytesSent") > 0, transportStats.toString());
    }

    /** Adds up an attribute of the stats of a type. */
    static long sum(RTCStatsReport report, RTCStatsType type, String attribute) {
        return report.getStats().values().stream()
                .filter(stats -> stats.getType() == type)
                .map(stats -> stats.getAttributes().get(attribute))
                .filter(Number.class::isInstance)
                .mapToLong(value -> ((Number) value).longValue())
                .sum();
    }

    @Test
    void restartingIceKeepsSending() throws InterruptedException {
        Producer video =
                await(transport.produce(ProducerOptions.builder(videoTrack()).build()));
        awaitReceived(video);

        await(transport.restartIce(await(peer.restartIce(transport.id()))));

        // The packets keep arriving through the new ICE session.
        long restarted = packetCount(video);
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (packetCount(video) <= restarted + 50) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("no RTP received after restarting ICE");
            }
            Thread.sleep(200);
        }
        assertEquals(ConnectionState.CONNECTED, transport.connectionState());
    }

    /**
     * Waits for the server to stop receiving RTP from a producer: two readings of its packet count, a
     * second apart, are equal.
     */
    private void awaitStopped(Producer producer) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        long previous = packetCount(producer);
        do {
            Thread.sleep(1000);
            long current = packetCount(producer);
            if (current == previous) {
                return;
            }
            previous = current;
        } while (System.nanoTime() < deadline);
        throw new AssertionError("the server still receives RTP from the paused producer");
    }

    // The packets that the server received from a producer, in all its streams.
    private long packetCount(Producer producer) {
        long count = 0;
        for (JsonNode stat : await(peer.producerStats(producer.id()))) {
            if (stat.path("type").asString().equals("inbound-rtp")) {
                count += stat.path("packetCount").asLong();
            }
        }
        return count;
    }

    /**
     * Waits for the server to receive RTP from a producer.
     *
     * @return the MIME type of the received stream
     */
    private String awaitReceived(Producer producer) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        JsonNode stats;
        do {
            stats = await(peer.producerStats(producer.id()));
            for (JsonNode stat : stats) {
                if (stat.path("type").asString().equals("inbound-rtp")
                        && stat.path("packetCount").asLong() > 0) {
                    return stat.path("mimeType").asString();
                }
            }
            Thread.sleep(200);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("no RTP received from " + producer.kind().value() + " producer: " + stats);
    }
}

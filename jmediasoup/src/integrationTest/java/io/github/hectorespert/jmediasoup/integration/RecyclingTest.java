/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSink;
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
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.testfixtures.ColorVideoSource;
import io.github.hectorespert.jmediasoup.testfixtures.ToneAudioSource;
import io.github.hectorespert.jmediasoup.transport.TransportOptions;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The recycling of the media sections of a receive transport: a jmediasoup peer produces and closes
 * tracks, and another one consumes them in the sections that the closed consumers leave.
 */
@ExtendWith(MediasoupServer.class)
class RecyclingTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private WebRtcJavaHandler.Factory aliceFactory;
    private WebRtcJavaHandler.Factory bobFactory;
    private ToneAudioSource audioSource;
    private ColorVideoSource videoSource;
    private DemoPeer alice;
    private DemoPeer bob;
    private SendTransport sendTransport;
    private RecvTransport recvTransport;

    @BeforeEach
    void connect(MediasoupServer.Server server) throws Exception {
        aliceFactory = WebRtcJavaHandler.createFactory();
        bobFactory = WebRtcJavaHandler.createFactory();
        audioSource = new ToneAudioSource();
        videoSource = new ColorVideoSource();
        String room = "recycling-" + UUID.randomUUID();
        alice = DemoPeer.connect(server, room, "alice");
        bob = DemoPeer.connect(server, room, "bob");

        // bob joins with a receive transport, so that the server creates consumers for him.
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
                request -> alice.produce(
                        sendOptions.id(),
                        request.kind(),
                        request.rtpParameters(),
                        request.kind() == MediaKind.AUDIO ? "mic" : "webcam"));
    }

    @AfterEach
    void close() throws InterruptedException {
        sendTransport.close();
        recvTransport.close();
        alice.close();
        bob.close();
        aliceFactory.close();
        bobFactory.close();
        audioSource.close();
        videoSource.close();
    }

    private static <T> T await(CompletableFuture<T> future) {
        return future.orTimeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).join();
    }

    /** A track that alice sends and bob receives. */
    private record Sent(Producer producer, Consumer consumer) {

        /** Closes the producer on both sides, which closes the consumer on the server, and bob's one. */
        void close(DemoPeer alice) {
            producer.close();
            alice.closeProducer(producer.id());
            consumer.close();
        }
    }

    private Sent send(MediaKind kind) throws Exception {
        MediaStreamTrack track = kind == MediaKind.AUDIO
                ? aliceFactory.peerConnectionFactory().createAudioTrack("audio", audioSource.source())
                : aliceFactory.peerConnectionFactory().createVideoTrack("video", videoSource.source());
        Producer producer =
                await(sendTransport.produce(ProducerOptions.builder(track).build()));
        NewConsumer newConsumer = bob.nextConsumer(TIMEOUT);
        Consumer consumer = await(recvTransport.consume(newConsumer.options()));
        newConsumer.accept();
        assertEquals(producer.id(), consumer.producerId());
        return new Sent(producer, consumer);
    }

    @Test
    void consumersCreatedAndClosedRepeatedlyKeepReceiving() throws Exception {
        // The first section stays, so that the next ones are closed and reused.
        Sent first = send(MediaKind.AUDIO);

        for (int i = 0; i < 5; i++) {
            Sent video = send(MediaKind.VIDEO);
            assertReceives(video.consumer());
            video.close(alice);
        }

        assertReceives(first.consumer());
    }

    @Test
    void aClosedAudioSectionIsReusedForVideo() throws Exception {
        Sent first = send(MediaKind.VIDEO);
        Sent audio = send(MediaKind.AUDIO);
        assertReceives(audio.consumer());

        audio.close(alice);
        Sent video = send(MediaKind.VIDEO);

        assertReceives(video.consumer());
        assertReceives(first.consumer());
    }

    @Test
    void closingTheConsumerOfTheFirstSectionKeepsTheOthersReceiving() throws Exception {
        Sent first = send(MediaKind.AUDIO);
        Sent video = send(MediaKind.VIDEO);

        // The first section is disabled instead of closed, as it carries the BUNDLE transport.
        first.close(alice);
        Sent audio = send(MediaKind.AUDIO);

        assertReceives(video.consumer());
        assertReceives(audio.consumer());
    }

    /** Waits for audio that is not silence, or for 30 frames of video. */
    private static void assertReceives(Consumer consumer) throws InterruptedException {
        var received = new CountDownLatch(consumer.kind() == MediaKind.AUDIO ? 1 : 30);
        if (consumer.track() instanceof AudioTrack track) {
            AudioTrackSink sink = (data, bitsPerSample, sampleRate, channels, frames) -> {
                for (int i = 0; i + 1 < data.length; i += 2) {
                    // Little endian.
                    if (Math.abs((short) ((data[i] & 0xff) | (data[i + 1] << 8))) > 1000) {
                        received.countDown();
                        return;
                    }
                }
            };
            track.addSink(sink);
            try {
                assertTrue(received.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "no tone received");
            } finally {
                track.removeSink(sink);
            }
        } else {
            var track = (VideoTrack) consumer.track();
            VideoTrackSink sink = (VideoFrame frame) -> received.countDown();
            track.addSink(sink);
            try {
                assertTrue(received.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "no frames received");
            } finally {
                track.removeSink(sink);
            }
        }
    }
}

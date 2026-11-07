/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import dev.onvoid.webrtc.media.video.I420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;
import io.github.hectorespert.jmediasoup.Consumer;
import io.github.hectorespert.jmediasoup.Device;
import io.github.hectorespert.jmediasoup.Producer;
import io.github.hectorespert.jmediasoup.ProducerOptions;
import io.github.hectorespert.jmediasoup.RecvTransport;
import io.github.hectorespert.jmediasoup.SendTransport;
import io.github.hectorespert.jmediasoup.Transport;
import io.github.hectorespert.jmediasoup.handler.webrtc.WebRtcJavaHandler;
import io.github.hectorespert.jmediasoup.integration.DemoPeer.Direction;
import io.github.hectorespert.jmediasoup.integration.DemoPeer.NewConsumer;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.testfixtures.ColorVideoSource;
import io.github.hectorespert.jmediasoup.testfixtures.ToneAudioSource;
import io.github.hectorespert.jmediasoup.transport.ConnectionState;
import io.github.hectorespert.jmediasoup.transport.TransportOptions;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The MVP end to end: one jmediasoup peer sends the tone and the green frames of the test sources to
 * the demo server, and another one, in the same process, receives them. Each peer has its own device
 * and handler factory, as two applications would.
 */
@ExtendWith(MediasoupServer.class)
class LoopbackTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    // The tolerance of the frequency of the received tone, in Hz.
    private static final int FREQUENCY_TOLERANCE = 20;

    // The tolerance of the luma of the received frames, which VP8 compresses.
    private static final int LUMA_TOLERANCE = 15;

    private WebRtcJavaHandler.Factory aliceFactory;
    private WebRtcJavaHandler.Factory bobFactory;
    private ToneAudioSource audioSource;
    private ColorVideoSource videoSource;

    @BeforeEach
    void createFactories() {
        aliceFactory = WebRtcJavaHandler.createFactory();
        bobFactory = WebRtcJavaHandler.createFactory();
        audioSource = new ToneAudioSource();
        videoSource = new ColorVideoSource();
    }

    @AfterEach
    void closeFactories() throws InterruptedException {
        aliceFactory.close();
        bobFactory.close();
        audioSource.close();
        videoSource.close();
    }

    private static <T> T await(CompletableFuture<T> future) {
        return future.orTimeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).join();
    }

    @RepeatedTest(3)
    void aPeerReceivesTheAudioAndVideoThatAnotherOneSends(MediasoupServer.Server server, RepetitionInfo repetition)
            throws Exception {
        String room = "loopback-" + repetition.getCurrentRepetition() + "-" + UUID.randomUUID();
        try (var alice = DemoPeer.connect(server, room, "alice");
                var bob = DemoPeer.connect(server, room, "bob")) {
            // bob joins with a receive transport, so that the server creates consumers for him.
            var bobDevice = new Device(bobFactory);
            await(bobDevice.load(await(bob.routerRtpCapabilities())));
            TransportOptions recvOptions = await(bob.createWebRtcTransport(Direction.CONSUMER));
            await(bob.join("bob", bobDevice.recvRtpCapabilities()));
            RecvTransport recvTransport = bobDevice.createRecvTransport(
                    recvOptions, dtls -> bob.connectWebRtcTransport(recvOptions.id(), dtls));
            var recvStates = new States(recvTransport);

            // alice joins and sends.
            var aliceDevice = new Device(aliceFactory);
            await(aliceDevice.load(await(alice.routerRtpCapabilities())));
            await(alice.join("alice", aliceDevice.recvRtpCapabilities()));
            TransportOptions sendOptions = await(alice.createWebRtcTransport(Direction.PRODUCER));
            SendTransport sendTransport = aliceDevice.createSendTransport(
                    sendOptions,
                    dtls -> alice.connectWebRtcTransport(sendOptions.id(), dtls),
                    request -> alice.produce(
                            sendOptions.id(),
                            request.kind(),
                            request.rtpParameters(),
                            request.kind() == MediaKind.AUDIO ? "mic" : "webcam"));
            var sendStates = new States(sendTransport);

            var peerConnectionFactory = aliceFactory.peerConnectionFactory();
            Producer audioProducer = await(sendTransport.produce(
                    ProducerOptions.builder(peerConnectionFactory.createAudioTrack("audio", audioSource.source()))
                            .build()));
            Producer videoProducer = await(sendTransport.produce(
                    ProducerOptions.builder(peerConnectionFactory.createVideoTrack("video", videoSource.source()))
                            .build()));

            // bob consumes both.
            Consumer audioConsumer = null;
            Consumer videoConsumer = null;
            for (int i = 0; i < 2; i++) {
                NewConsumer newConsumer = bob.nextConsumer(TIMEOUT);
                Consumer consumer = await(recvTransport.consume(newConsumer.options()));
                newConsumer.accept();
                if (consumer.producerId().equals(audioProducer.id())) {
                    audioConsumer = consumer;
                } else {
                    assertEquals(videoProducer.id(), consumer.producerId());
                    videoConsumer = consumer;
                }
            }
            assertTrue(audioConsumer != null && videoConsumer != null, "a consumer of each producer");

            // The received audio is the tone.
            var toneSink = new ToneSink();
            var audioTrack = (AudioTrack) audioConsumer.track();
            audioTrack.addSink(toneSink);
            try {
                assertTrue(toneSink.measured.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "no tone received");
            } finally {
                audioTrack.removeSink(toneSink);
            }
            assertEquals(ToneAudioSource.FREQUENCY, toneSink.frequency, FREQUENCY_TOLERANCE, "tone frequency");

            // The received frames are the green ones.
            var frameSink = new FrameSink();
            var videoTrack = (VideoTrack) videoConsumer.track();
            videoTrack.addSink(frameSink);
            try {
                assertTrue(frameSink.frames.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "no frames received");
            } finally {
                videoTrack.removeSink(frameSink);
            }
            assertEquals(ColorVideoSource.WIDTH, frameSink.width, "width");
            assertEquals(ColorVideoSource.HEIGHT, frameSink.height, "height");
            assertEquals(ColorVideoSource.Y, frameSink.luma, LUMA_TOLERANCE, "luma");

            // Both transports went through connecting to connected, and nothing else.
            assertEquals(List.of(ConnectionState.CONNECTING, ConnectionState.CONNECTED), sendStates.states);
            assertEquals(List.of(ConnectionState.CONNECTING, ConnectionState.CONNECTED), recvStates.states);

            // Closing the transports closes their producers and consumers.
            sendTransport.close();
            recvTransport.close();
            assertEquals(ConnectionState.CLOSED, sendTransport.connectionState());
            assertEquals(ConnectionState.CLOSED, recvTransport.connectionState());
            assertTrue(audioProducer.closed() && videoProducer.closed(), "producers closed");
            assertTrue(audioConsumer.closed() && videoConsumer.closed(), "consumers closed");
        }
    }

    /** The connection states that a transport reports. */
    private static final class States {

        final List<ConnectionState> states = new CopyOnWriteArrayList<>();

        States(Transport transport) {
            transport.onConnectionStateChange(states::add);
        }
    }

    /**
     * Measures the frequency of the received audio from its zero crossings, over a second of audio
     * after the first that is not silence.
     */
    private static final class ToneSink implements AudioTrackSink {

        final CountDownLatch measured = new CountDownLatch(1);
        volatile double frequency;

        private boolean started;
        private long samples;
        private long crossings;
        private int previous;

        @Override
        public void onData(byte[] data, int bitsPerSample, int sampleRate, int channels, int frames) {
            if (measured.getCount() == 0) {
                return;
            }
            for (int frame = 0; frame < frames; frame++) {
                // The first channel, 16-bit little endian.
                int i = frame * channels * 2;
                int sample = (short) ((data[i] & 0xff) | (data[i + 1] << 8));
                if (!started) {
                    if (Math.abs(sample) < 1000) {
                        continue;
                    }
                    started = true;
                } else if ((sample < 0) != (previous < 0)) {
                    crossings++;
                }
                previous = sample;
                if (++samples == sampleRate) {
                    // Two crossings per period, in a second.
                    frequency = crossings / 2.0;
                    measured.countDown();
                    return;
                }
            }
        }
    }

    /** Waits for 30 frames, a second of video, and keeps the size and the central luma of the last. */
    private static final class FrameSink implements VideoTrackSink {

        final CountDownLatch frames = new CountDownLatch(30);
        volatile int width;
        volatile int height;
        volatile int luma;

        @Override
        public void onVideoFrame(VideoFrame frame) {
            width = frame.buffer.getWidth();
            height = frame.buffer.getHeight();
            I420Buffer i420 = frame.buffer.toI420();
            try {
                luma = i420.getDataY().get(height / 2 * i420.getStrideY() + width / 2) & 0xff;
            } finally {
                i420.release();
            }
            frames.countDown();
        }
    }
}

/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import io.github.hectorespert.jmediasoup.Consumer;
import io.github.hectorespert.jmediasoup.Device;
import io.github.hectorespert.jmediasoup.ProducerCodecOptions;
import io.github.hectorespert.jmediasoup.ProducerOptions;
import io.github.hectorespert.jmediasoup.RecvTransport;
import io.github.hectorespert.jmediasoup.SendTransport;
import io.github.hectorespert.jmediasoup.handler.webrtc.WebRtcJavaHandler;
import io.github.hectorespert.jmediasoup.integration.DemoPeer.Direction;
import io.github.hectorespert.jmediasoup.integration.DemoPeer.NewConsumer;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecParameters;
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
 * The codec options of a producer reach mediasoup, which gives them to the consumers of other peers:
 * one jmediasoup peer sends Opus in stereo, and another one receives it so.
 */
@ExtendWith(MediasoupServer.class)
class CodecOptionsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private WebRtcJavaHandler.Factory aliceFactory;
    private WebRtcJavaHandler.Factory bobFactory;
    private ToneAudioSource audioSource;

    @BeforeEach
    void createFactories() {
        aliceFactory = WebRtcJavaHandler.createFactory();
        bobFactory = WebRtcJavaHandler.createFactory();
        audioSource = new ToneAudioSource();
    }

    @AfterEach
    void closeFactories() throws InterruptedException {
        aliceFactory.close();
        bobFactory.close();
        audioSource.close();
    }

    private static <T> T await(CompletableFuture<T> future) {
        return future.orTimeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).join();
    }

    @Test
    void opusOptionsReachTheConsumersOfOtherPeers(MediasoupServer.Server server) throws Exception {
        String room = "codec-options-" + UUID.randomUUID();
        try (var alice = DemoPeer.connect(server, room, "alice");
                var bob = DemoPeer.connect(server, room, "bob")) {
            var bobDevice = new Device(bobFactory);
            await(bobDevice.load(await(bob.routerRtpCapabilities())));
            TransportOptions recvOptions = await(bob.createWebRtcTransport(Direction.CONSUMER));
            await(bob.join("bob", bobDevice.recvRtpCapabilities()));
            RecvTransport recvTransport = bobDevice.createRecvTransport(
                    recvOptions, dtls -> bob.connectWebRtcTransport(recvOptions.id(), dtls));

            var aliceDevice = new Device(aliceFactory);
            await(aliceDevice.load(await(alice.routerRtpCapabilities())));
            await(alice.join("alice", aliceDevice.recvRtpCapabilities()));
            TransportOptions sendOptions = await(alice.createWebRtcTransport(Direction.PRODUCER));
            SendTransport sendTransport = aliceDevice.createSendTransport(
                    sendOptions,
                    dtls -> alice.connectWebRtcTransport(sendOptions.id(), dtls),
                    request -> alice.produce(sendOptions.id(), request.kind(), request.rtpParameters(), "mic"));

            await(sendTransport.produce(ProducerOptions.builder(
                            aliceFactory.peerConnectionFactory().createAudioTrack("audio", audioSource.source()))
                    .codecOptions(ProducerCodecOptions.builder()
                            .opusStereo(true)
                            .opusDtx(true)
                            .build())
                    .build()));

            NewConsumer newConsumer = bob.nextConsumer(TIMEOUT);
            Consumer consumer = await(recvTransport.consume(newConsumer.options()));
            newConsumer.accept();

            // mediasoup gives the consumer the parameters with which alice produced.
            RtpCodecParameters codec = consumer.rtpParameters().codecs().getFirst();
            assertEquals("audio/opus", codec.mimeType());
            assertEquals(1, ((Number) codec.parameters().get("sprop-stereo")).intValue(), codec.toString());
            assertEquals(1, ((Number) codec.parameters().get("usedtx")).intValue(), codec.toString());

            // And bob decodes it in stereo.
            var sink = new ChannelsSink();
            var track = (AudioTrack) consumer.track();
            track.addSink(sink);
            try {
                assertTrue(sink.received.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "no audio received");
            } finally {
                track.removeSink(sink);
            }
            assertEquals(2, sink.channels, "channels");

            sendTransport.close();
            recvTransport.close();
        }
    }

    /** Keeps the channels of the received audio, once it is not silence. */
    private static final class ChannelsSink implements AudioTrackSink {

        final CountDownLatch received = new CountDownLatch(1);
        volatile int channels;

        @Override
        public void onData(byte[] data, int bitsPerSample, int sampleRate, int channels, int frames) {
            if (received.getCount() == 0) {
                return;
            }
            for (int i = 0; i + 1 < data.length; i += 2) {
                int sample = (short) ((data[i] & 0xff) | (data[i + 1] << 8));
                if (Math.abs(sample) >= 1000) {
                    this.channels = channels;
                    received.countDown();
                    return;
                }
            }
        }
    }
}

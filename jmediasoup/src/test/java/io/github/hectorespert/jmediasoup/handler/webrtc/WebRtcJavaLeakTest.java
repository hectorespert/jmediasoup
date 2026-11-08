/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.handler.webrtc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;
import io.github.hectorespert.jmediasoup.Consumer;
import io.github.hectorespert.jmediasoup.Device;
import io.github.hectorespert.jmediasoup.InvalidStateException;
import io.github.hectorespert.jmediasoup.Producer;
import io.github.hectorespert.jmediasoup.ProducerOptions;
import io.github.hectorespert.jmediasoup.RecvTransport;
import io.github.hectorespert.jmediasoup.SendTransport;
import io.github.hectorespert.jmediasoup.fake.FakeParameters;
import io.github.hectorespert.jmediasoup.testfixtures.ColorVideoSource;
import io.github.hectorespert.jmediasoup.testfixtures.ToneAudioSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Creates and closes transports with producers and consumers in a loop, and checks that they release
 * their native objects: webrtc-java fails to dispose a track that a native sender still references.
 *
 * <p>The build runs a few iterations. To watch the native memory, run more of them, which on Linux
 * also report the resident memory of the process: {@code ./gradlew :jmediasoup:test --tests
 * '*WebRtcJavaLeakTest' -PleakTestIterations=1000}.
 */
class WebRtcJavaLeakTest {

    private static final int ITERATIONS = Integer.getInteger("jmediasoup.leakTest.iterations", 10);

    private static PeerConnectionFactory peerConnectionFactory;
    private static ToneAudioSource audioSource;
    private static ColorVideoSource videoSource;

    private WebRtcJavaHandler.Factory factory;
    private Device device;

    @BeforeAll
    static void createPeerConnectionFactory() {
        peerConnectionFactory = new PeerConnectionFactory(new HeadlessAudioDeviceModule());
        audioSource = new ToneAudioSource();
        videoSource = new ColorVideoSource();
    }

    @AfterAll
    static void disposePeerConnectionFactory() throws InterruptedException {
        audioSource.close();
        videoSource.close();
        peerConnectionFactory.dispose();
    }

    @BeforeEach
    void loadDevice() {
        factory = WebRtcJavaHandler.createFactory(peerConnectionFactory);
        device = new Device(factory);
        await(device.load(FakeParameters.routerRtpCapabilities()));
    }

    @AfterEach
    void closeFactory() {
        factory.close();
    }

    private static <T> T await(CompletableFuture<T> future) {
        return future.orTimeout(10, TimeUnit.SECONDS).join();
    }

    private SendTransport sendTransport() throws InvalidStateException {
        return device.createSendTransport(
                FakeParameters.transportRemoteParameters(),
                dtls -> CompletableFuture.completedFuture(null),
                request -> CompletableFuture.completedFuture(UUID.randomUUID().toString()));
    }

    private RecvTransport recvTransport() throws InvalidStateException {
        return device.createRecvTransport(
                FakeParameters.transportRemoteParameters(), dtls -> CompletableFuture.completedFuture(null));
    }

    @Test
    void transportsReleaseWhatTheirProducersAndConsumersUsed() throws Exception {
        Report report = new Report();

        for (int i = 1; i <= ITERATIONS; i++) {
            SendTransport sendTransport = sendTransport();
            RecvTransport recvTransport = recvTransport();

            MediaStreamTrack audioTrack = peerConnectionFactory.createAudioTrack("audio", audioSource.source());
            MediaStreamTrack videoTrack = peerConnectionFactory.createVideoTrack("video", videoSource.source());
            MediaStreamTrack otherVideoTrack = peerConnectionFactory.createVideoTrack("other", videoSource.source());

            Producer audio = await(
                    sendTransport.produce(ProducerOptions.builder(audioTrack).build()));
            Producer video = await(
                    sendTransport.produce(ProducerOptions.builder(videoTrack).build()));
            await(video.replaceTrack(otherVideoTrack));
            await(audio.getStats());
            await(video.getStats());

            Consumer audioConsumer =
                    await(recvTransport.consume(FakeParameters.consumerRemoteParameters("audio/opus")));
            Consumer videoConsumer = await(recvTransport.consume(FakeParameters.consumerRemoteParameters("video/VP8")));
            await(audioConsumer.getStats());
            await(videoConsumer.getStats());

            // Closing a producer releases its track once the transport stops sending it, which the
            // stats of the transport wait for, since they come after it in its queue.
            video.close();
            audioConsumer.close();
            await(sendTransport.getStats());
            await(recvTransport.getStats());
            assertDoesNotThrow(videoTrack::dispose, "the track replaced in a closed producer");
            assertDoesNotThrow(otherVideoTrack::dispose, "the track of a closed producer");

            // Closing the transports releases the rest.
            sendTransport.close();
            recvTransport.close();
            assertDoesNotThrow(audioTrack::dispose, "the track of a producer of a closed transport");

            report.iteration(i);
        }

        report.end();
    }

    /** Reports, on Linux, the resident memory of the process every tenth of the iterations. */
    private static final class Report {

        private final long start = residentMemoryKb();

        void iteration(int i) throws IOException {
            if (ITERATIONS >= 100 && i % (ITERATIONS / 10) == 0) {
                System.out.printf("leak test: %d iterations, resident memory %d MB%n", i, residentMemoryKb() / 1024);
            }
        }

        void end() {
            if (start > 0 && ITERATIONS >= 100) {
                System.out.printf(
                        "leak test: resident memory from %d MB to %d MB%n", start / 1024, residentMemoryKb() / 1024);
            }
        }

        // The VmRSS of /proc/self/status, or -1 outside Linux.
        private static long residentMemoryKb() {
            Path status = Path.of("/proc/self/status");
            try {
                if (!Files.exists(status)) {
                    return -1;
                }
                return Files.readAllLines(status).stream()
                        .filter(line -> line.startsWith("VmRSS:"))
                        .mapToLong(line -> Long.parseLong(line.replaceAll("[^0-9]", "")))
                        .findFirst()
                        .orElse(-1);
            } catch (IOException e) {
                return -1;
            }
        }
    }
}

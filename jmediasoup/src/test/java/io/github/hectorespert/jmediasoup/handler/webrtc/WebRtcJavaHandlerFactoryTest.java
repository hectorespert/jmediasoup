/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.handler.webrtc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;
import io.github.hectorespert.jmediasoup.Device;
import io.github.hectorespert.jmediasoup.InvalidStateException;
import io.github.hectorespert.jmediasoup.fake.FakeParameters;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtcpFeedback;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecCapability;
import io.github.hectorespert.jmediasoup.rtp.RtpHeaderExtension;
import io.github.hectorespert.jmediasoup.sctp.NumSctpStreams;
import io.github.hectorespert.jmediasoup.transport.TransportDirection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Tests the factory of {@link WebRtcJavaHandler} with the webrtc-java natives. */
class WebRtcJavaHandlerFactoryTest {

    private static PeerConnectionFactory peerConnectionFactory;

    @BeforeAll
    static void createPeerConnectionFactory() {
        peerConnectionFactory = new PeerConnectionFactory(new HeadlessAudioDeviceModule());
    }

    @AfterAll
    static void disposePeerConnectionFactory() {
        peerConnectionFactory.dispose();
    }

    private static <T> T await(CompletableFuture<T> future) {
        return future.orTimeout(10, TimeUnit.SECONDS).join();
    }

    private static List<String> mimeTypes(RtpCapabilities capabilities) {
        return capabilities.codecs().stream()
                .map(codec -> codec.mimeType().toLowerCase(Locale.ROOT))
                .toList();
    }

    private static RtpCodecCapability codec(RtpCapabilities capabilities, String mimeType) {
        return capabilities.codecs().stream()
                .filter(codec -> codec.mimeType().equalsIgnoreCase(mimeType))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void nativeCapabilitiesHaveTheCodecsAndExtensionsOfLibwebrtc() {
        WebRtcJavaHandler.Factory factory = WebRtcJavaHandler.createFactory(peerConnectionFactory);

        for (TransportDirection direction : TransportDirection.values()) {
            RtpCapabilities capabilities = await(factory.getNativeRtpCapabilities(direction));

            assertTrue(
                    mimeTypes(capabilities).containsAll(List.of("audio/opus", "video/vp8", "video/rtx")),
                    direction + ": " + mimeTypes(capabilities));
            RtpCodecCapability opus = codec(capabilities, "audio/opus");
            assertEquals(MediaKind.AUDIO, opus.kind());
            assertEquals(48000, opus.clockRate());
            assertEquals(2, opus.channels());
            // Added because libwebrtc does not announce it.
            assertTrue(opus.rtcpFeedback().contains(new RtcpFeedback("nack", null)), opus.toString());
            assertTrue(
                    capabilities.headerExtensions().stream()
                            .map(RtpHeaderExtension::uri)
                            .anyMatch("urn:ietf:params:rtp-hdrext:sdes:mid"::equals),
                    capabilities.headerExtensions().toString());
        }
    }

    @Test
    void nativeSctpCapabilitiesHave1024StreamsEachWay() {
        WebRtcJavaHandler.Factory factory = WebRtcJavaHandler.createFactory(peerConnectionFactory);

        assertEquals(
                new NumSctpStreams(1024, 1024),
                await(factory.getNativeSctpCapabilities()).numStreams());
    }

    @Test
    void aDeviceLoadsWithTheNativeCapabilities() throws Exception {
        var device = new Device(WebRtcJavaHandler.createFactory(peerConnectionFactory));

        await(device.load(FakeParameters.routerRtpCapabilities()));

        assertEquals("WebRtcJava", device.handlerName());
        assertTrue(device.canProduce(MediaKind.AUDIO));
        assertTrue(device.canProduce(MediaKind.VIDEO));
        assertTrue(mimeTypes(device.recvRtpCapabilities()).containsAll(List.of("audio/opus", "video/vp8")));
    }

    @Test
    void closingAFactoryOfTheApplicationDoesNotDisposeItsPeerConnectionFactory() {
        WebRtcJavaHandler.Factory factory = WebRtcJavaHandler.createFactory(peerConnectionFactory);
        assertSame(peerConnectionFactory, factory.peerConnectionFactory());

        factory.close();
        factory.close();

        assertTrue(factory.closed());
        assertInstanceOf(
                InvalidStateException.class,
                assertThrows(
                                CompletionException.class,
                                () -> await(factory.getNativeRtpCapabilities(TransportDirection.SEND)))
                        .getCause());
        // Still usable by the application.
        assertFalse(mimeTypes(await(WebRtcJavaHandler.createFactory(peerConnectionFactory)
                        .getNativeRtpCapabilities(TransportDirection.RECV)))
                .isEmpty());
    }

    @Test
    void aFactoryWithItsOwnPeerConnectionFactoryWorksUntilClosed() {
        try (WebRtcJavaHandler.Factory factory = WebRtcJavaHandler.createFactory()) {
            assertFalse(mimeTypes(await(factory.getNativeRtpCapabilities(TransportDirection.SEND)))
                    .isEmpty());
        }
    }

    @Test
    void nullsAreRejected() {
        assertThrows(NullPointerException.class, () -> WebRtcJavaHandler.createFactory(null));
        assertThrows(
                NullPointerException.class,
                () -> WebRtcJavaHandler.createFactory(peerConnectionFactory).getNativeRtpCapabilities(null));
    }
}

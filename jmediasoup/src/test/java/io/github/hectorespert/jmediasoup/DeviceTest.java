/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hectorespert.jmediasoup.fake.FakeHandler;
import io.github.hectorespert.jmediasoup.fake.FakeParameters;
import io.github.hectorespert.jmediasoup.handler.Handler;
import io.github.hectorespert.jmediasoup.handler.HandlerFactory;
import io.github.hectorespert.jmediasoup.handler.HandlerOptions;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecCapability;
import io.github.hectorespert.jmediasoup.sctp.NumSctpStreams;
import io.github.hectorespert.jmediasoup.sctp.SctpCapabilities;
import io.github.hectorespert.jmediasoup.transport.TransportDirection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Ports the Device tests of mediasoup-client ({@code test.ts}) and pymediasoup ({@code
 * tests/__init__.py}) that do not need transports.
 */
class DeviceTest {

    /** A factory whose native RTP capabilities the test chooses. */
    private record Factory(Function<TransportDirection, CompletableFuture<RtpCapabilities>> nativeRtpCapabilities)
            implements HandlerFactory {

        @Override
        public String name() {
            return "TestHandler";
        }

        @Override
        public CompletableFuture<RtpCapabilities> getNativeRtpCapabilities(TransportDirection direction) {
            return nativeRtpCapabilities.apply(direction);
        }

        @Override
        public CompletableFuture<SctpCapabilities> getNativeSctpCapabilities() {
            return CompletableFuture.completedFuture(new SctpCapabilities(new NumSctpStreams(16, 16)));
        }

        @Override
        public Handler create(HandlerOptions options) {
            throw new UnsupportedOperationException();
        }
    }

    private static Device loadedDevice() {
        var device = new Device(FakeHandler.createFactory());
        device.load(FakeParameters.routerRtpCapabilities()).join();
        return device;
    }

    private static Throwable cause(CompletableFuture<?> future) {
        return assertThrows(CompletionException.class, future::join).getCause();
    }

    private static List<String> mimeTypes(RtpCapabilities capabilities) {
        return capabilities.codecs().stream().map(RtpCodecCapability::mimeType).toList();
    }

    @Test
    void aNewDeviceIsNotLoaded() {
        var device = new Device(FakeHandler.createFactory());

        assertFalse(device.loaded());
        assertEquals("FakeHandler", device.handlerName());
    }

    @Test
    void gettersThrowIfNotLoaded() {
        var device = new Device(FakeHandler.createFactory());

        assertThrows(InvalidStateException.class, device::rtpCapabilities);
        assertThrows(InvalidStateException.class, device::recvRtpCapabilities);
        assertThrows(InvalidStateException.class, device::sendRtpCapabilities);
        assertThrows(InvalidStateException.class, device::sctpCapabilities);
        assertThrows(InvalidStateException.class, () -> device.canProduce(MediaKind.AUDIO));
    }

    @Test
    void loadSucceeds() throws Exception {
        Device device = loadedDevice();

        assertTrue(device.loaded());
        assertEquals(List.of("audio/opus", "video/VP8", "video/rtx"), mimeTypes(device.rtpCapabilities()));
        assertEquals(device.rtpCapabilities(), device.recvRtpCapabilities());
        assertEquals(
                List.of(100, 101, 102),
                device.recvRtpCapabilities().codecs().stream()
                        .map(RtpCodecCapability::preferredPayloadType)
                        .toList());
        assertEquals(
                List.of(111, 96, 97),
                device.sendRtpCapabilities().codecs().stream()
                        .map(RtpCodecCapability::preferredPayloadType)
                        .toList());
        assertEquals(new NumSctpStreams(1024, 1024), device.sctpCapabilities().numStreams());
    }

    @Test
    void canProduceAudioAndVideo() throws Exception {
        Device device = loadedDevice();

        assertTrue(device.canProduce(MediaKind.AUDIO));
        assertTrue(device.canProduce(MediaKind.VIDEO));
        assertThrows(NullPointerException.class, () -> device.canProduce(null));
    }

    @Test
    void loadingTwiceFails() {
        Device device = loadedDevice();

        assertInstanceOf(InvalidStateException.class, cause(device.load(FakeParameters.routerRtpCapabilities())));
        assertTrue(device.loaded());
    }

    @Test
    void loadingWhileLoadingFails() {
        var nativeRtpCapabilities = new CompletableFuture<RtpCapabilities>();
        var device = new Device(new Factory(direction -> nativeRtpCapabilities));

        CompletableFuture<Void> first = device.load(FakeParameters.routerRtpCapabilities());
        assertInstanceOf(InvalidStateException.class, cause(device.load(FakeParameters.routerRtpCapabilities())));

        nativeRtpCapabilities.complete(FakeParameters.nativeRtpCapabilities());
        first.join();
        assertTrue(device.loaded());
    }

    @Test
    void aFailedLoadLeavesTheDeviceUnloadedAndCanBeRetried() {
        var error = new IllegalStateException("no WebRTC");
        var failing = new Factory(direction -> CompletableFuture.failedFuture(error));
        var device = new Device(failing);

        assertSame(error, cause(device.load(FakeParameters.routerRtpCapabilities())));
        assertFalse(device.loaded());
        assertSame(error, cause(device.load(FakeParameters.routerRtpCapabilities())));
    }

    @Test
    void aFactoryThatThrowsFailsTheLoad() {
        var error = new IllegalStateException("no natives");
        var device = new Device(new Factory(direction -> {
            throw error;
        }));

        assertSame(error, cause(device.load(FakeParameters.routerRtpCapabilities())));
        assertFalse(device.loaded());
    }

    @Test
    void routerCapabilitiesThatCannotBeMatchedFailTheLoad() {
        RtpCapabilities router = FakeParameters.routerRtpCapabilities();
        var withoutPayloadTypes = new RtpCapabilities(
                router.codecs().stream()
                        .map(codec -> new RtpCodecCapability(
                                codec.kind(),
                                codec.mimeType(),
                                null,
                                codec.clockRate(),
                                codec.channels(),
                                codec.parameters(),
                                codec.rtcpFeedback()))
                        .toList(),
                router.headerExtensions(),
                null);
        var device = new Device(FakeHandler.createFactory());

        assertInstanceOf(IllegalArgumentException.class, cause(device.load(withoutPayloadTypes)));
        assertFalse(device.loaded());
        assertThrows(NullPointerException.class, () -> device.load(null));
    }

    @Test
    void sendingCodecsCanKeepTheLocalOrder() throws Exception {
        RtpCapabilities fakeNative = FakeParameters.nativeRtpCapabilities();
        // VP8 and its RTX before Opus.
        var videoFirst = new RtpCapabilities(
                List.of(
                        fakeNative.codecs().get(3),
                        fakeNative.codecs().get(4),
                        fakeNative.codecs().get(0)),
                fakeNative.headerExtensions(),
                null);
        var factory = new Factory(direction -> CompletableFuture.completedFuture(videoFirst));

        var routerOrder = new Device(factory);
        routerOrder.load(FakeParameters.routerRtpCapabilities()).join();
        var localOrder = new Device(factory);
        localOrder.load(FakeParameters.routerRtpCapabilities(), true).join();

        assertEquals(List.of("audio/opus", "video/VP8", "video/rtx"), mimeTypes(routerOrder.sendRtpCapabilities()));
        assertEquals(List.of("video/VP8", "video/rtx", "audio/opus"), mimeTypes(localOrder.sendRtpCapabilities()));
        // Receiving always keeps the order of the router.
        assertEquals(List.of("audio/opus", "video/VP8", "video/rtx"), mimeTypes(localOrder.recvRtpCapabilities()));
    }

    @Test
    void aDeviceThatOnlySharesAudioCannotProduceVideo() throws Exception {
        RtpCapabilities fakeNative = FakeParameters.nativeRtpCapabilities();
        var audioOnly = new RtpCapabilities(List.of(fakeNative.codecs().getFirst()), null, null);
        var device = new Device(new Factory(direction -> CompletableFuture.completedFuture(audioOnly)));

        device.load(FakeParameters.routerRtpCapabilities()).join();

        assertTrue(device.canProduce(MediaKind.AUDIO));
        assertFalse(device.canProduce(MediaKind.VIDEO));
    }

    @Test
    void theHandlerFactoryIsRequired() {
        assertThrows(NullPointerException.class, () -> new Device(null));
    }
}

/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.handler.webrtc;

import static java.util.Objects.requireNonNull;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.RTCBundlePolicy;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceTransportPolicy;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCRtcpMuxPolicy;
import dev.onvoid.webrtc.RTCRtpEncodingParameters;
import dev.onvoid.webrtc.RTCRtpTransceiverDirection;
import dev.onvoid.webrtc.RTCRtpTransceiverInit;
import dev.onvoid.webrtc.media.MediaType;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;
import io.github.hectorespert.jmediasoup.InvalidStateException;
import io.github.hectorespert.jmediasoup.handler.Handler;
import io.github.hectorespert.jmediasoup.handler.HandlerFactory;
import io.github.hectorespert.jmediasoup.handler.HandlerOptions;
import io.github.hectorespert.jmediasoup.internal.ortc.OrtcUtils;
import io.github.hectorespert.jmediasoup.internal.sdp.CommonUtils;
import io.github.hectorespert.jmediasoup.internal.sdptransform.Parser;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.sctp.NumSctpStreams;
import io.github.hectorespert.jmediasoup.sctp.SctpCapabilities;
import io.github.hectorespert.jmediasoup.transport.TransportDirection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The handler for <a href="https://github.com/devopvoid/webrtc-java">webrtc-java</a>, the
 * counterpart of the {@code Chrome111} handler of mediasoup-client and the {@code AiortcHandler} of
 * pymediasoup. It needs the webrtc-java natives for the platform.
 *
 * <p>Create its factory with {@link #createFactory(PeerConnectionFactory)} to use a {@link
 * PeerConnectionFactory} of the application, or with {@link #createFactory()} to let jmediasoup create
 * one. The tracks to send must come from the same {@link PeerConnectionFactory}.
 */
public final class WebRtcJavaHandler {

    private static final Logger logger = LoggerFactory.getLogger(WebRtcJavaHandler.class);

    /** The name of the handler and its factory. */
    public static final String NAME = "WebRtcJava";

    // As mediasoup-client, which offers the maximum that SCTP allows to negotiate.
    private static final int SCTP_NUM_STREAMS = 1024;

    private WebRtcJavaHandler() {}

    /**
     * Returns a factory of handlers that use a {@link PeerConnectionFactory} of the application, which
     * keeps owning it: closing the returned factory does not dispose it.
     *
     * <p>Without audio devices, as in a server, create it with a {@link HeadlessAudioDeviceModule}.
     *
     * @param peerConnectionFactory the factory of the peer connections and of the tracks to send
     * @return the handler factory
     */
    public static Factory createFactory(PeerConnectionFactory peerConnectionFactory) {
        return new Factory(requireNonNull(peerConnectionFactory, "peerConnectionFactory"), false);
    }

    /**
     * Returns a factory of handlers that creates its own {@link PeerConnectionFactory}, with a {@link
     * HeadlessAudioDeviceModule} so that it works without audio devices. Closing the returned factory
     * disposes it.
     *
     * @return the handler factory
     */
    public static Factory createFactory() {
        return new Factory(new PeerConnectionFactory(new HeadlessAudioDeviceModule()), true);
    }

    /**
     * Creates the handlers of a device, and gives the native capabilities of webrtc-java.
     *
     * <p>Close it once the transports that use it are closed. It disposes the {@link
     * PeerConnectionFactory} only if it created it.
     */
    public static final class Factory implements HandlerFactory, AutoCloseable {

        private final PeerConnectionFactory peerConnectionFactory;
        // Whether the factory created the PeerConnectionFactory, and so disposes it.
        private final boolean owned;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Factory(PeerConnectionFactory peerConnectionFactory, boolean owned) {
            this.peerConnectionFactory = peerConnectionFactory;
            this.owned = owned;
        }

        @Override
        public String name() {
            return NAME;
        }

        /**
         * Returns the {@link PeerConnectionFactory} of the handlers, with which the application creates
         * the tracks to send.
         *
         * @return the factory of the peer connections
         */
        public PeerConnectionFactory peerConnectionFactory() {
            return peerConnectionFactory;
        }

        /**
         * Returns whether the factory is closed.
         *
         * @return whether {@link #close()} was called
         */
        public boolean closed() {
            return closed.get();
        }

        /**
         * {@inheritDoc}
         *
         * <p>They come from an offer of a temporary peer connection with an audio and a video
         * transceiver in the given direction, as in mediasoup-client.
         */
        @Override
        public CompletableFuture<RtpCapabilities> getNativeRtpCapabilities(TransportDirection direction) {
            requireNonNull(direction, "direction");
            logger.debug("getNativeRtpCapabilities() [direction:{}]", direction);

            if (closed.get()) {
                return CompletableFuture.failedFuture(new InvalidStateException("closed"));
            }

            return CompletableFuture.supplyAsync(this::createTemporaryPeerConnection, PeerConnections.EXECUTOR)
                    .thenCompose(pc -> {
                        try {
                            RTCRtpTransceiverDirection transceiverDirection = direction == TransportDirection.SEND
                                    ? RTCRtpTransceiverDirection.SEND_ONLY
                                    : RTCRtpTransceiverDirection.RECV_ONLY;
                            var audio = new RTCRtpTransceiverInit();
                            audio.direction = transceiverDirection;
                            pc.addTransceiver(MediaType.AUDIO, audio);

                            // Create video transceiver with scalability mode in order to retrieve
                            // Dependency Descriptor header extension.
                            var video = new RTCRtpTransceiverInit();
                            video.direction = transceiverDirection;
                            var encoding = new RTCRtpEncodingParameters();
                            encoding.scalabilityMode = "L3T3";
                            video.sendEncodings = List.of(encoding);
                            pc.addTransceiver(MediaType.VIDEO, video);

                            return PeerConnections.createOffer(pc, new RTCOfferOptions())
                                    .whenComplete((offer, error) -> pc.close());
                        } catch (RuntimeException e) {
                            pc.close();
                            throw e;
                        }
                    })
                    .thenApply(offer -> getLocalRtpCapabilities(Parser.parse(offer.sdp)));
        }

        /**
         * {@inheritDoc}
         *
         * <p>webrtc-java negotiates up to 1024 streams in each direction.
         */
        @Override
        public CompletableFuture<SctpCapabilities> getNativeSctpCapabilities() {
            return CompletableFuture.completedFuture(
                    new SctpCapabilities(new NumSctpStreams(SCTP_NUM_STREAMS, SCTP_NUM_STREAMS)));
        }

        @Override
        public Handler create(HandlerOptions options) {
            requireNonNull(options, "options");
            throw new UnsupportedOperationException("not implemented yet");
        }

        /**
         * Closes the factory, disposing the {@link PeerConnectionFactory} if it created it. Calling it
         * again does nothing.
         */
        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }

            logger.debug("close()");

            if (owned) {
                peerConnectionFactory.dispose();
            }
        }

        private RTCPeerConnection createTemporaryPeerConnection() {
            var configuration = new RTCConfiguration();
            configuration.iceServers = List.of();
            configuration.iceTransportPolicy = RTCIceTransportPolicy.ALL;
            configuration.bundlePolicy = RTCBundlePolicy.MAX_BUNDLE;
            configuration.rtcpMuxPolicy = RTCRtcpMuxPolicy.REQUIRE;
            return peerConnectionFactory.createPeerConnection(configuration, candidate -> {});
        }
    }

    private static RtpCapabilities getLocalRtpCapabilities(Map<String, Object> localSdpObject) {
        RtpCapabilities nativeRtpCapabilities = CommonUtils.extractRtpCapabilities(localSdpObject);

        // libwebrtc supports NACK for OPUS but doesn't announce it.
        return OrtcUtils.addNackSupportForOpus(nativeRtpCapabilities);
    }
}

/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.handler.webrtc;

import static java.util.Objects.requireNonNull;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCBundlePolicy;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceGatheringState;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCIceTransportPolicy;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionIceErrorEvent;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtcpMuxPolicy;
import dev.onvoid.webrtc.RTCRtpEncodingParameters;
import dev.onvoid.webrtc.RTCRtpTransceiverDirection;
import dev.onvoid.webrtc.RTCRtpTransceiverInit;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.RTCStatsReport;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.MediaType;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;
import io.github.hectorespert.jmediasoup.InvalidStateException;
import io.github.hectorespert.jmediasoup.MediasoupClientException;
import io.github.hectorespert.jmediasoup.UnsupportedException;
import io.github.hectorespert.jmediasoup.handler.Handler;
import io.github.hectorespert.jmediasoup.handler.HandlerFactory;
import io.github.hectorespert.jmediasoup.handler.HandlerListener;
import io.github.hectorespert.jmediasoup.handler.HandlerOptions;
import io.github.hectorespert.jmediasoup.handler.HandlerReceiveDataChannelOptions;
import io.github.hectorespert.jmediasoup.handler.HandlerReceiveDataChannelResult;
import io.github.hectorespert.jmediasoup.handler.HandlerReceiveOptions;
import io.github.hectorespert.jmediasoup.handler.HandlerReceiveResult;
import io.github.hectorespert.jmediasoup.handler.HandlerSendDataChannelOptions;
import io.github.hectorespert.jmediasoup.handler.HandlerSendDataChannelResult;
import io.github.hectorespert.jmediasoup.handler.HandlerSendOptions;
import io.github.hectorespert.jmediasoup.handler.HandlerSendResult;
import io.github.hectorespert.jmediasoup.internal.ortc.OrtcUtils;
import io.github.hectorespert.jmediasoup.internal.sdp.CommonUtils;
import io.github.hectorespert.jmediasoup.internal.sdp.RemoteSdp;
import io.github.hectorespert.jmediasoup.internal.sdptransform.Parser;
import io.github.hectorespert.jmediasoup.rtp.ExtendedRtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.sctp.NumSctpStreams;
import io.github.hectorespert.jmediasoup.sctp.SctpCapabilities;
import io.github.hectorespert.jmediasoup.sctp.SctpParameters;
import io.github.hectorespert.jmediasoup.transport.ConnectionState;
import io.github.hectorespert.jmediasoup.transport.DtlsParameters;
import io.github.hectorespert.jmediasoup.transport.DtlsRole;
import io.github.hectorespert.jmediasoup.transport.IceGatheringState;
import io.github.hectorespert.jmediasoup.transport.IceParameters;
import io.github.hectorespert.jmediasoup.transport.IceServer;
import io.github.hectorespert.jmediasoup.transport.IceTransportPolicy;
import io.github.hectorespert.jmediasoup.transport.TransportDirection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
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
 *
 * <p>Each handler has one peer connection. It reports the state of the peer connection to its
 * transport from the signalling thread of webrtc-java, and runs the rest of its work off that thread.
 */
public final class WebRtcJavaHandler implements Handler {

    private static final Logger logger = LoggerFactory.getLogger(WebRtcJavaHandler.class);

    /** The name of the handler and its factory. */
    public static final String NAME = "WebRtcJava";

    // As mediasoup-client, which offers the maximum that SCTP allows to negotiate.
    private static final int SCTP_NUM_STREAMS = 1024;

    // The factory that created the handler.
    private final Factory factory;
    // Handler direction.
    private final TransportDirection direction;
    // Where the handler reports to its transport.
    private final HandlerListener listener;
    // SCTP parameters of the mediasoup transport, for the data channels.
    @SuppressWarnings("unused")
    private final @Nullable SctpParameters sctpParameters;
    // Remote SDP handler.
    private final RemoteSdp remoteSdp;
    // Callback to request sending extended RTP capabilities on demand.
    @SuppressWarnings("unused")
    private final Function<RtpCapabilities, ExtendedRtpCapabilities> getSendExtendedRtpCapabilities;
    // Initial server side DTLS role. If not 'auto', it will force the opposite value in client side.
    private final @Nullable DtlsRole forcedLocalDtlsRole;
    // RTCPeerConnection instance.
    private final RTCPeerConnection pc;
    // Closed flag.
    private final AtomicBoolean closed = new AtomicBoolean();
    // Got transport local and remote parameters.
    private volatile boolean transportReady;

    private WebRtcJavaHandler(Factory factory, HandlerOptions options) {
        logger.debug("constructor()");

        this.factory = factory;
        this.direction = options.direction();
        this.listener = options.listener();
        this.sctpParameters = options.sctpParameters();
        this.remoteSdp = new RemoteSdp(options.iceParameters(), options.iceCandidates(), options.dtlsParameters());
        this.getSendExtendedRtpCapabilities = options.getSendExtendedRtpCapabilities();

        DtlsRole remoteRole = options.dtlsParameters().role();
        this.forcedLocalDtlsRole = remoteRole == null || remoteRole == DtlsRole.AUTO
                ? null
                : remoteRole == DtlsRole.SERVER ? DtlsRole.CLIENT : DtlsRole.SERVER;

        var configuration = new RTCConfiguration();
        configuration.iceServers = toRtcIceServers(options.iceServers());
        configuration.iceTransportPolicy = toRtcIceTransportPolicy(options.iceTransportPolicy());
        configuration.bundlePolicy = RTCBundlePolicy.MAX_BUNDLE;
        configuration.rtcpMuxPolicy = RTCRtcpMuxPolicy.REQUIRE;
        this.pc = factory.peerConnectionFactory.createPeerConnection(configuration, new Events());
    }

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
     * <p>Close it once the transports that use it are closed: closing it closes the handlers that are
     * still open, and disposes the {@link PeerConnectionFactory} only if it created it.
     */
    public static final class Factory implements HandlerFactory, AutoCloseable {

        private final PeerConnectionFactory peerConnectionFactory;
        // Whether the factory created the PeerConnectionFactory, and so disposes it.
        private final boolean owned;
        private final AtomicBoolean closed = new AtomicBoolean();
        // The handlers that are not closed.
        private final Set<WebRtcJavaHandler> handlers = ConcurrentHashMap.newKeySet();

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

        /**
         * {@inheritDoc}
         *
         * @throws IllegalStateException if the factory is closed
         */
        @Override
        public WebRtcJavaHandler create(HandlerOptions options) {
            requireNonNull(options, "options");
            if (closed.get()) {
                throw new IllegalStateException("closed");
            }
            var handler = new WebRtcJavaHandler(this, options);
            handlers.add(handler);
            return handler;
        }

        /**
         * Closes the factory: closes the handlers that are still open, and disposes the {@link
         * PeerConnectionFactory} if it created it. Calling it again does nothing.
         */
        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }

            logger.debug("close()");

            for (WebRtcJavaHandler handler : handlers) {
                handler.close();
            }

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

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        logger.debug("close()");

        // Close RTCPeerConnection.
        try {
            pc.close();
        } catch (RuntimeException e) {
            logger.warn("close() | failed to close the peer connection", e);
        }

        factory.handlers.remove(this);
    }

    @Override
    public CompletableFuture<Void> updateIceServers(List<IceServer> iceServers) {
        return notImplemented("updateIceServers");
    }

    @Override
    public CompletableFuture<Void> restartIce(IceParameters iceParameters) {
        return notImplemented("restartIce");
    }

    @Override
    public CompletableFuture<RTCStatsReport> getTransportStats() {
        return notImplemented("getTransportStats");
    }

    @Override
    public CompletableFuture<HandlerSendResult> send(HandlerSendOptions options) {
        return notImplemented("send");
    }

    @Override
    public CompletableFuture<Void> stopSending(String localId) {
        return notImplemented("stopSending");
    }

    @Override
    public CompletableFuture<Void> pauseSending(String localId) {
        return notImplemented("pauseSending");
    }

    @Override
    public CompletableFuture<Void> resumeSending(String localId) {
        return notImplemented("resumeSending");
    }

    @Override
    public CompletableFuture<Void> replaceTrack(String localId, @Nullable MediaStreamTrack track) {
        return notImplemented("replaceTrack");
    }

    @Override
    public CompletableFuture<Void> setMaxSpatialLayer(String localId, int spatialLayer) {
        return notImplemented("setMaxSpatialLayer");
    }

    @Override
    public CompletableFuture<Void> setRtpEncodingParameters(String localId, RTCRtpEncodingParameters params) {
        return notImplemented("setRtpEncodingParameters");
    }

    @Override
    public CompletableFuture<RTCStatsReport> getSenderStats(String localId) {
        return notImplemented("getSenderStats");
    }

    @Override
    public CompletableFuture<HandlerSendDataChannelResult> sendDataChannel(HandlerSendDataChannelOptions options) {
        return notImplemented("sendDataChannel");
    }

    @Override
    public CompletableFuture<List<HandlerReceiveResult>> receive(List<HandlerReceiveOptions> optionsList) {
        return notImplemented("receive");
    }

    @Override
    public CompletableFuture<Void> stopReceiving(List<String> localIds) {
        return notImplemented("stopReceiving");
    }

    @Override
    public CompletableFuture<Void> pauseReceiving(List<String> localIds) {
        return notImplemented("pauseReceiving");
    }

    @Override
    public CompletableFuture<Void> resumeReceiving(List<String> localIds) {
        return notImplemented("resumeReceiving");
    }

    @Override
    public CompletableFuture<RTCStatsReport> getReceiverStats(String localId) {
        return notImplemented("getReceiverStats");
    }

    @Override
    public CompletableFuture<HandlerReceiveDataChannelResult> receiveDataChannel(
            HandlerReceiveDataChannelOptions options) {
        return notImplemented("receiveDataChannel");
    }

    /**
     * {@inheritDoc}
     *
     * <p>webrtc-java does not expose the SCTP transport of the peer connection, so it is not known.
     */
    @Override
    public @Nullable Integer getDataChannelMaxMessageSize() {
        return null;
    }

    /** Returns whether the handler is closed. */
    boolean closed() {
        return closed.get();
    }

    /** Returns the peer connection, for the tests. */
    RTCPeerConnection peerConnection() {
        return pc;
    }

    /**
     * Gives the local DTLS parameters to the transport, which connects the mediasoup side, and sets the
     * opposite role in the remote SDP.
     *
     * @param localDtlsRole the DTLS role of this side
     * @param localSdpObject the parsed local SDP, or {@code null} to parse the local description
     */
    CompletableFuture<Void> setupTransport(DtlsRole localDtlsRole, @Nullable Map<String, Object> localSdpObject) {
        DtlsParameters dtlsParameters;
        try {
            if (localSdpObject == null) {
                RTCSessionDescription localDescription = pc.getLocalDescription();
                if (localDescription == null) {
                    throw new MediasoupClientException("no local description");
                }
                localSdpObject = Parser.parse(localDescription.sdp);
            }

            // Get our local DTLS parameters, and set our DTLS role.
            DtlsParameters local = CommonUtils.extractDtlsParameters(localSdpObject);
            dtlsParameters = new DtlsParameters(localDtlsRole, local.fingerprints());
        } catch (MediasoupClientException | RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }

        // Update the remote DTLS role in the SDP.
        remoteSdp.updateDtlsRole(localDtlsRole == DtlsRole.CLIENT ? DtlsRole.SERVER : DtlsRole.CLIENT);

        // Need to tell the remote transport about our parameters.
        return listener.connect(dtlsParameters).toCompletableFuture().thenRun(() -> transportReady = true);
    }

    /** Returns whether the transport is connected, so that the next operations do not connect it. */
    boolean transportReady() {
        return transportReady;
    }

    /** Returns the DTLS role of this side when the mediasoup side gave one, or {@code client}. */
    DtlsRole localDtlsRole() {
        return forcedLocalDtlsRole != null ? forcedLocalDtlsRole : DtlsRole.CLIENT;
    }

    /** Returns the remote SDP. */
    RemoteSdp remoteSdp() {
        return remoteSdp;
    }

    /** Fails if the handler is closed. */
    void assertNotClosed() throws InvalidStateException {
        if (closed.get()) {
            throw new InvalidStateException("method called in a closed handler");
        }
    }

    /** Fails if the handler does not send. */
    void assertSendDirection() throws MediasoupClientException {
        if (direction != TransportDirection.SEND) {
            throw new MediasoupClientException("method can just be called for handlers with \"send\" direction");
        }
    }

    /** Fails if the handler does not receive. */
    void assertRecvDirection() throws MediasoupClientException {
        if (direction != TransportDirection.RECV) {
            throw new MediasoupClientException("method can just be called for handlers with \"recv\" direction");
        }
    }

    static List<RTCIceServer> toRtcIceServers(List<IceServer> iceServers) {
        return iceServers.stream()
                .map(iceServer -> {
                    var rtcIceServer = new RTCIceServer();
                    rtcIceServer.urls = iceServer.urls();
                    rtcIceServer.username = iceServer.username();
                    rtcIceServer.password = iceServer.credential();
                    return rtcIceServer;
                })
                .toList();
    }

    static RTCIceTransportPolicy toRtcIceTransportPolicy(@Nullable IceTransportPolicy policy) {
        return policy == IceTransportPolicy.RELAY ? RTCIceTransportPolicy.RELAY : RTCIceTransportPolicy.ALL;
    }

    static ConnectionState toConnectionState(RTCPeerConnectionState state) {
        return switch (state) {
            case NEW -> ConnectionState.NEW;
            case CONNECTING -> ConnectionState.CONNECTING;
            case CONNECTED -> ConnectionState.CONNECTED;
            case DISCONNECTED -> ConnectionState.DISCONNECTED;
            case FAILED -> ConnectionState.FAILED;
            case CLOSED -> ConnectionState.CLOSED;
        };
    }

    static IceGatheringState toIceGatheringState(RTCIceGatheringState state) {
        return switch (state) {
            case NEW -> IceGatheringState.NEW;
            case GATHERING -> IceGatheringState.GATHERING;
            case COMPLETE -> IceGatheringState.COMPLETE;
        };
    }

    private static <T> CompletableFuture<T> notImplemented(String method) {
        return CompletableFuture.failedFuture(new UnsupportedException(method + "() is not implemented yet"));
    }

    private static RtpCapabilities getLocalRtpCapabilities(Map<String, Object> localSdpObject) {
        RtpCapabilities nativeRtpCapabilities = CommonUtils.extractRtpCapabilities(localSdpObject);

        // libwebrtc supports NACK for OPUS but doesn't announce it.
        return OrtcUtils.addNackSupportForOpus(nativeRtpCapabilities);
    }

    // The events of the peer connection, which webrtc-java calls on its signalling thread. The
    // listener only hands them to the executor of the transport, so they do not block.
    private final class Events implements PeerConnectionObserver {

        @Override
        public void onIceCandidate(RTCIceCandidate candidate) {
            // mediasoup gives its candidates in the remote SDP, and does not need ours.
        }

        @Override
        public void onConnectionChange(RTCPeerConnectionState state) {
            listener.connectionStateChange(toConnectionState(state));
        }

        @Override
        public void onIceGatheringChange(RTCIceGatheringState state) {
            listener.iceGatheringStateChange(toIceGatheringState(state));
        }

        @Override
        public void onIceCandidateError(RTCPeerConnectionIceErrorEvent event) {
            listener.iceCandidateError(event);
        }
    }
}

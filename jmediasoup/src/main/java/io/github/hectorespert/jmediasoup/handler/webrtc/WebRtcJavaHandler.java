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
import dev.onvoid.webrtc.RTCPriorityType;
import dev.onvoid.webrtc.RTCRtcpMuxPolicy;
import dev.onvoid.webrtc.RTCRtpEncodingParameters;
import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCRtpTransceiverDirection;
import dev.onvoid.webrtc.RTCRtpTransceiverInit;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.RTCSignalingState;
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
import io.github.hectorespert.jmediasoup.internal.ortc.Ortc;
import io.github.hectorespert.jmediasoup.internal.ortc.OrtcUtils;
import io.github.hectorespert.jmediasoup.internal.sdp.CommonUtils;
import io.github.hectorespert.jmediasoup.internal.sdp.RemoteSdp;
import io.github.hectorespert.jmediasoup.internal.sdp.UnifiedPlanUtils;
import io.github.hectorespert.jmediasoup.internal.sdptransform.JavaScript;
import io.github.hectorespert.jmediasoup.internal.sdptransform.Parser;
import io.github.hectorespert.jmediasoup.internal.sdptransform.Writer;
import io.github.hectorespert.jmediasoup.rtp.ExtendedRtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.Priority;
import io.github.hectorespert.jmediasoup.rtp.RtcpParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpEncodingParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
    private final Function<RtpCapabilities, ExtendedRtpCapabilities> getSendExtendedRtpCapabilities;
    // Initial server side DTLS role. If not 'auto', it will force the opposite value in client side.
    private final @Nullable DtlsRole forcedLocalDtlsRole;
    // RTCPeerConnection instance.
    private final RTCPeerConnection pc;
    // Closed flag.
    private final AtomicBoolean closed = new AtomicBoolean();
    // Got transport local and remote parameters.
    private volatile boolean transportReady;
    // Map of RTCTransceivers indexed by MID.
    private final Map<String, RTCRtpTransceiver> mapMidTransceiver = new ConcurrentHashMap<>();
    // The next local id to try for a receiving whose RTP parameters have no MID.
    private int nextLocalId;
    // The stream of the tracks to send, as the MediaStream of mediasoup-client.
    private final String sendStreamId = UUID.randomUUID().toString();

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

    /**
     * {@inheritDoc}
     *
     * <p>As in mediasoup-client, the local id is the MID of the transceiver, and the stream of the
     * {@code msid} of the RTP parameters is the given one or, if none, the one that all the tracks of
     * the handler share. Simulcast, more than one encoding, is not supported yet, and the codec options
     * are ignored.
     */
    @Override
    public CompletableFuture<HandlerSendResult> send(HandlerSendOptions options) {
        requireNonNull(options, "options");
        MediaStreamTrack track = options.track();
        MediaKind kind;
        RTCRtpTransceiver transceiver;
        RemoteSdp.MediaSectionIdx mediaSectionIdx;
        try {
            assertNotClosed();
            assertSendDirection();

            logger.debug(
                    "send() [kind:{}, track.id:{}, streamId:{}]", track.getKind(), track.getId(), options.streamId());

            // The answer cannot negotiate simulcast yet (section 11.3 of TODO.md).
            if (options.encodings().size() > 1) {
                throw new UnsupportedException("simulcast is not supported yet");
            }

            // The answer cannot apply them yet (section 11.1 of TODO.md).
            if (options.codecOptions() != null) {
                logger.warn("send() | codecOptions are not supported yet, ignoring them");
            }

            kind = MediaKind.fromValue(track.getKind());

            var init = new RTCRtpTransceiverInit();
            init.direction = RTCRtpTransceiverDirection.SEND_ONLY;
            init.streamIds = List.of(sendStreamId);
            init.sendEncodings = options.encodings().stream()
                    .map(WebRtcJavaHandler::toRtcRtpEncodingParameters)
                    .toList();
            transceiver = pc.addTransceiver(track, init);

            mediaSectionIdx = remoteSdp.getNextMediaSectionIdx();
        } catch (MediasoupClientException | RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }

        return PeerConnections.createOffer(pc, new RTCOfferOptions())
                .exceptionallyCompose(error -> {
                    stopQuietly(transceiver);
                    return CompletableFuture.failedFuture(error);
                })
                // Through a completed future, so that what negotiateSending() throws also cleans up.
                .thenCompose(offer -> CompletableFuture.completedFuture(offer)
                        .thenCompose(ignored -> negotiateSending(options, kind, transceiver, mediaSectionIdx, offer))
                        .exceptionallyCompose(
                                error -> cleanUpSending(error, kind, transceiver, mediaSectionIdx, offer)));
    }

    // Leaves the peer connection in sync with the remote SDP after a failed send(), and fails with the
    // cause of the failure.
    private CompletableFuture<HandlerSendResult> cleanUpSending(
            Throwable error,
            MediaKind kind,
            RTCRtpTransceiver transceiver,
            RemoteSdp.MediaSectionIdx mediaSectionIdx,
            RTCSessionDescription offer) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;

        if (closed.get()) {
            return CompletableFuture.failedFuture(cause);
        }

        return CompletableFuture.completedFuture(offer)
                .thenCompose(ignored -> abortSending(kind, transceiver, mediaSectionIdx, offer))
                .handle((ignored, abortError) -> {
                    if (abortError != null) {
                        logger.warn("send() | failed to close the section", abortError);
                    }
                    return abortError;
                })
                .thenCompose(abortError ->
                        abortError != null ? rollbackSending(transceiver) : CompletableFuture.completedFuture(null))
                .thenCompose(ignored -> CompletableFuture.failedFuture(cause));
    }

    // The part of send() after the offer: the RTP parameters, the local and the remote descriptions.
    private CompletableFuture<HandlerSendResult> negotiateSending(
            HandlerSendOptions options,
            MediaKind kind,
            RTCRtpTransceiver transceiver,
            RemoteSdp.MediaSectionIdx mediaSectionIdx,
            RTCSessionDescription offer) {
        Map<String, Object> offerSdpObject = Parser.parse(offer.sdp);

        if (JavaScript.truthy(offerSdpObject.get("extmapAllowMixed"))) {
            remoteSdp.setSessionExtmapAllowMixed();
        }

        // With the capabilities of this offer, and not with the native ones of the device.
        RtpCapabilities nativeRtpCapabilities = getLocalRtpCapabilities(offerSdpObject);

        ExtendedRtpCapabilities sendExtendedRtpCapabilities =
                getSendExtendedRtpCapabilities.apply(nativeRtpCapabilities);

        // Generic sending RTP parameters.
        RtpParameters sendingRtpParameters = Ortc.getSendingRtpParameters(kind, sendExtendedRtpCapabilities);

        // Generic sending RTP parameters suitable for the SDP remote answer.
        RtpParameters sendingRemoteRtpParameters =
                Ortc.getSendingRemoteRtpParameters(kind, sendExtendedRtpCapabilities);

        List<RtpCodecParameters> codecs;
        List<RtpCodecParameters> remoteCodecs;
        try {
            codecs = Ortc.reduceCodecs(sendingRtpParameters.codecs(), options.codec());
            remoteCodecs = Ortc.reduceCodecs(sendingRemoteRtpParameters.codecs(), options.codec());
        } catch (UnsupportedException e) {
            throw new CompletionException(e);
        }

        RtpParameters answerRtpParameters = new RtpParameters(
                sendingRemoteRtpParameters.mid(),
                remoteCodecs,
                sendingRemoteRtpParameters.headerExtensions(),
                sendingRemoteRtpParameters.encodings(),
                sendingRemoteRtpParameters.rtcp(),
                sendingRemoteRtpParameters.msid());

        CompletableFuture<Void> ready = transportReady
                ? CompletableFuture.completedFuture(null)
                : setupTransport(localDtlsRole(), offerSdpObject);

        return ready.thenCompose(ignored -> {
                    logger.debug("send() | calling pc.setLocalDescription() [offer:{}]", offer.sdp);
                    return PeerConnections.setLocalDescription(pc, offer);
                })
                .thenCompose(ignored -> {
                    // We can now get the transceiver.mid.
                    String localId = transceiver.getMid();

                    RtpParameters rtpParameters;
                    RTCSessionDescription answer;
                    try {
                        RTCSessionDescription localDescription = pc.getLocalDescription();
                        if (localId == null || localDescription == null) {
                            throw new MediasoupClientException(
                                    "no local description " + localId + " " + (localDescription == null) + " "
                                            + java.util.Arrays.stream(pc.getTransceivers())
                                                    .map(t -> t.getMid() + "/" + t.stopped())
                                                    .toList());
                        }

                        Map<String, Object> localSdpObject = Parser.parse(localDescription.sdp);

                        // By MID, since the index of the section may not be the one of the remote SDP.
                        Map<String, Object> offerMediaObject = mediaSections(localSdpObject).stream()
                                .filter(m -> JavaScript.string(m.get("mid")).equals(localId))
                                .findFirst()
                                .orElseThrow(() -> new MediasoupClientException("offer media section not found"));

                        // Set RTCP CNAME.
                        RtcpParameters rtcp = sendingRtpParameters.rtcp();
                        var cnameRtcp = new RtcpParameters(
                                CommonUtils.getCname(offerMediaObject),
                                rtcp != null ? rtcp.reducedSize() : null,
                                rtcp != null ? rtcp.mux() : null);

                        // Set RTP encodings by parsing the SDP offer, and complete them with the given
                        // one if any.
                        List<RtpEncodingParameters> encodings =
                                UnifiedPlanUtils.getRtpEncodings(offerMediaObject, codecs);
                        if (options.encodings().size() == 1) {
                            List<RtpEncodingParameters> merged = new ArrayList<>(encodings);
                            merged.set(
                                    0,
                                    merge(
                                            encodings.getFirst(),
                                            options.encodings().getFirst()));
                            encodings = List.copyOf(merged);
                        }

                        rtpParameters = new RtpParameters(
                                localId,
                                codecs,
                                sendingRtpParameters.headerExtensions(),
                                encodings,
                                cnameRtcp,
                                (options.streamId() != null ? options.streamId() : sendStreamId) + " "
                                        + options.track().getId());

                        remoteSdp.send(offerMediaObject, mediaSectionIdx.reuseMid(), answerRtpParameters);

                        answer = new RTCSessionDescription(RTCSdpType.ANSWER, remoteSdp.getSdp());
                    } catch (MediasoupClientException e) {
                        throw new CompletionException(e);
                    }

                    logger.debug("send() | calling pc.setRemoteDescription() [answer:{}]", answer.sdp);

                    return PeerConnections.setRemoteDescription(pc, answer).thenApply(ignored2 -> {
                        // Store in the map.
                        mapMidTransceiver.put(localId, transceiver);

                        return new HandlerSendResult(localId, rtpParameters, transceiver.getSender());
                    });
                });
    }

    // Completes the negotiation of a failed send() with its section closed, as stopSending() does.
    // mediasoup-client rolls the offer back and stops the transceiver instead, but libwebrtc keeps the
    // index of the section that the offer gave to the transceiver, and gives it the section of the next
    // send() that gets the same index, which then has no MID.
    private CompletableFuture<Void> abortSending(
            MediaKind kind,
            RTCRtpTransceiver transceiver,
            RemoteSdp.MediaSectionIdx mediaSectionIdx,
            RTCSessionDescription offer) {
        CompletableFuture<Void> applied = pc.getSignalingState() == RTCSignalingState.HAVE_LOCAL_OFFER
                ? CompletableFuture.completedFuture(null)
                : PeerConnections.setLocalDescription(pc, offer);

        return applied.thenCompose(ignored -> {
            RTCSessionDescription answer;
            try {
                String mid = transceiver.getMid();
                RTCSessionDescription localDescription = pc.getLocalDescription();
                if (mid == null || localDescription == null) {
                    throw new MediasoupClientException("no local description");
                }

                Map<String, Object> localSdpObject = Parser.parse(localDescription.sdp);
                Map<String, Object> offerMediaObject = mediaSections(localSdpObject).stream()
                        .filter(m -> JavaScript.string(m.get("mid")).equals(mid))
                        .findFirst()
                        .orElseThrow(() -> new MediasoupClientException("offer media section not found"));

                // Any codec does for a section that is closed.
                RtpParameters answerRtpParameters = Ortc.getSendingRemoteRtpParameters(
                        kind, getSendExtendedRtpCapabilities.apply(getLocalRtpCapabilities(localSdpObject)));

                remoteSdp.send(offerMediaObject, mediaSectionIdx.reuseMid(), answerRtpParameters);

                pc.removeTrack(transceiver.getSender());

                remoteSdp.closeMediaSection(mid);

                // The DTLS role of the answer, if no send() got to connect the transport, which the
                // next one will do with the same role.
                if (!transportReady) {
                    remoteSdp.updateDtlsRole(localDtlsRole() == DtlsRole.CLIENT ? DtlsRole.SERVER : DtlsRole.CLIENT);
                }

                answer = new RTCSessionDescription(RTCSdpType.ANSWER, remoteSdp.getSdp());
            } catch (MediasoupClientException | RuntimeException e) {
                throw new CompletionException(e);
            }

            logger.debug("send() | closing the section, calling pc.setRemoteDescription() [answer:{}]", answer.sdp);

            return PeerConnections.setRemoteDescription(pc, answer);
        });
    }

    // The last resort if the section cannot be closed: rolls the offer back and stops the transceiver,
    // as mediasoup-client does, which leaves the next send() that gets the same index without a MID.
    private CompletableFuture<Void> rollbackSending(RTCRtpTransceiver transceiver) {
        CompletableFuture<Void> rollback;
        try {
            rollback = pc.getSignalingState() == RTCSignalingState.HAVE_LOCAL_OFFER
                    ? PeerConnections.setLocalDescription(pc, new RTCSessionDescription(RTCSdpType.ROLLBACK, ""))
                    : CompletableFuture.completedFuture(null);
        } catch (RuntimeException e) {
            rollback = CompletableFuture.completedFuture(null);
        }

        return rollback.handle((ignored, error) -> {
            stopQuietly(transceiver);
            return null;
        });
    }

    private static void stopQuietly(RTCRtpTransceiver transceiver) {
        try {
            transceiver.stop();
        } catch (RuntimeException e) {
            logger.debug("failed to stop the transceiver", e);
        }
    }

    @Override
    public CompletableFuture<Void> stopSending(String localId) {
        requireNonNull(localId, "localId");
        try {
            assertSendDirection();

            logger.debug("stopSending() [localId:{}]", localId);

            if (closed.get()) {
                return CompletableFuture.completedFuture(null);
            }

            RTCRtpTransceiver transceiver = mapMidTransceiver.get(localId);

            if (transceiver == null) {
                throw new MediasoupClientException("associated RTCRtpTransceiver not found");
            }

            transceiver.getSender().replaceTrack(null);

            pc.removeTrack(transceiver.getSender());

            boolean mediaSectionClosed = remoteSdp.closeMediaSection(transceiver.getMid());

            if (mediaSectionClosed) {
                transceiver.stop();
            }
        } catch (MediasoupClientException | RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }

        return PeerConnections.createOffer(pc, new RTCOfferOptions())
                .thenCompose(offer -> {
                    logger.debug("stopSending() | calling pc.setLocalDescription() [offer:{}]", offer.sdp);
                    return PeerConnections.setLocalDescription(pc, offer);
                })
                .thenCompose(ignored -> {
                    var answer = new RTCSessionDescription(RTCSdpType.ANSWER, remoteSdp.getSdp());
                    logger.debug("stopSending() | calling pc.setRemoteDescription() [answer:{}]", answer.sdp);
                    return PeerConnections.setRemoteDescription(pc, answer);
                })
                .thenRun(() -> mapMidTransceiver.remove(localId));
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

    /**
     * {@inheritDoc}
     *
     * <p>As in mediasoup-client, the local id is the MID that mediasoup gives the consumer, and the
     * stream of the track is the given one, the one of the {@code a=msid} of mediasoup, or its CNAME.
     */
    @Override
    public CompletableFuture<List<HandlerReceiveResult>> receive(List<HandlerReceiveOptions> optionsList) {
        requireNonNull(optionsList, "optionsList");
        Map<String, String> mapLocalId = new LinkedHashMap<>();
        RTCSessionDescription offer;
        try {
            assertNotClosed();
            assertRecvDirection();

            for (HandlerReceiveOptions options : optionsList) {
                RtpParameters rtpParameters = options.rtpParameters();

                logger.debug("receive() [trackId:{}, kind:{}]", options.trackId(), options.kind());

                String localId = rtpParameters.mid() != null ? rtpParameters.mid() : nextLocalId(mapLocalId);

                mapLocalId.put(options.trackId(), localId);

                // We ignore MSID `trackId` when consuming and always use our computed `trackId` which
                // matches the `consumer.id`.
                OrtcUtils.MsidIds msid = OrtcUtils.getMsidStreamIdAndTrackId(rtpParameters.msid());
                String streamId = options.streamId() != null
                        ? options.streamId()
                        : msid != null
                                ? msid.streamId()
                                : rtpParameters.rtcp() != null
                                                && rtpParameters.rtcp().cname() != null
                                        ? rtpParameters.rtcp().cname()
                                        : "-";

                remoteSdp.receive(localId, options.kind().value(), rtpParameters, streamId, options.trackId());
            }

            offer = new RTCSessionDescription(RTCSdpType.OFFER, remoteSdp.getSdp());
        } catch (MediasoupClientException | RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }

        logger.debug("receive() | calling pc.setRemoteDescription() [offer:{}]", offer.sdp);

        return PeerConnections.setRemoteDescription(pc, offer)
                .thenCompose(ignored -> PeerConnections.createAnswer(pc))
                .thenCompose(createdAnswer -> {
                    Map<String, Object> localSdpObject = Parser.parse(createdAnswer.sdp);

                    for (HandlerReceiveOptions options : optionsList) {
                        String localId = mapLocalId.get(options.trackId());
                        Map<String, Object> answerMediaObject = mediaSections(localSdpObject).stream()
                                .filter(m -> JavaScript.string(m.get("mid")).equals(localId))
                                .findFirst()
                                .orElseThrow(() -> new CompletionException(
                                        new MediasoupClientException("answer media section not found")));

                        // May need to modify codec parameters in the answer based on codec parameters
                        // in the offer.
                        CommonUtils.applyCodecParameters(options.rtpParameters(), answerMediaObject);
                    }

                    var answer = new RTCSessionDescription(RTCSdpType.ANSWER, Writer.write(localSdpObject));

                    CompletableFuture<Void> ready = transportReady
                            ? CompletableFuture.completedFuture(null)
                            : setupTransport(localDtlsRole(), localSdpObject);

                    return ready.thenCompose(ignored -> {
                        logger.debug("receive() | calling pc.setLocalDescription() [answer:{}]", answer.sdp);
                        return PeerConnections.setLocalDescription(pc, answer);
                    });
                })
                .thenApply(ignored -> {
                    List<HandlerReceiveResult> results = new ArrayList<>();
                    for (HandlerReceiveOptions options : optionsList) {
                        String localId = mapLocalId.get(options.trackId());
                        RTCRtpTransceiver transceiver = findTransceiver(localId);

                        if (transceiver == null) {
                            throw new CompletionException(
                                    new MediasoupClientException("new RTCRtpTransceiver not found"));
                        }

                        // Store in the map.
                        mapMidTransceiver.put(localId, transceiver);

                        RTCRtpReceiver receiver = transceiver.getReceiver();
                        results.add(new HandlerReceiveResult(localId, receiver.getTrack(), receiver));
                    }
                    return List.copyOf(results);
                });
    }

    @Override
    public CompletableFuture<Void> stopReceiving(List<String> localIds) {
        requireNonNull(localIds, "localIds");
        RTCSessionDescription offer;
        try {
            assertRecvDirection();

            if (closed.get()) {
                return CompletableFuture.completedFuture(null);
            }

            for (String localId : localIds) {
                logger.debug("stopReceiving() [localId:{}]", localId);

                RTCRtpTransceiver transceiver = mapMidTransceiver.get(localId);

                if (transceiver == null) {
                    throw new MediasoupClientException("associated RTCRtpTransceiver not found");
                }

                remoteSdp.closeMediaSection(localId);
            }

            offer = new RTCSessionDescription(RTCSdpType.OFFER, remoteSdp.getSdp());
        } catch (MediasoupClientException | RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }

        logger.debug("stopReceiving() | calling pc.setRemoteDescription() [offer:{}]", offer.sdp);

        return PeerConnections.setRemoteDescription(pc, offer)
                .thenCompose(ignored -> PeerConnections.createAnswer(pc))
                .thenCompose(answer -> {
                    logger.debug("stopReceiving() | calling pc.setLocalDescription() [answer:{}]", answer.sdp);
                    return PeerConnections.setLocalDescription(pc, answer);
                })
                .thenRun(() -> localIds.forEach(mapMidTransceiver::remove));
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

    // A local id that is neither in use nor taken by the receivings being added.
    private String nextLocalId(Map<String, String> mapLocalId) {
        String localId;
        do {
            localId = String.valueOf(nextLocalId++);
        } while (mapMidTransceiver.containsKey(localId) || mapLocalId.containsValue(localId));
        return localId;
    }

    private @Nullable RTCRtpTransceiver findTransceiver(String mid) {
        for (RTCRtpTransceiver transceiver : pc.getTransceivers()) {
            if (mid.equals(transceiver.getMid())) {
                return transceiver;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mediaSections(Map<String, Object> sdpObject) {
        Object media = sdpObject.get("media");
        return media == null ? List.of() : (List<Map<String, Object>>) media;
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

    // The fields that the transports keep in the encodings to send (SendTransport.normalizeEncodings).
    static RTCRtpEncodingParameters toRtcRtpEncodingParameters(RtpEncodingParameters encoding) {
        var rtcEncoding = new RTCRtpEncodingParameters();
        rtcEncoding.rid = encoding.rid();
        rtcEncoding.active = encoding.active();
        rtcEncoding.maxBitrate = encoding.maxBitrate();
        rtcEncoding.maxFramerate = encoding.maxFramerate();
        rtcEncoding.scaleResolutionDownBy = encoding.scaleResolutionDownBy();
        rtcEncoding.scalabilityMode = encoding.scalabilityMode();
        rtcEncoding.adaptivePtime = encoding.adaptivePtime();
        rtcEncoding.bitratePriority = encoding.priority() != null ? toBitratePriority(encoding.priority()) : null;
        rtcEncoding.networkPriority =
                encoding.networkPriority() != null ? toRtcPriorityType(encoding.networkPriority()) : null;
        return rtcEncoding;
    }

    // As the browsers: relative to the default bitrate priority of WebRTC, which is low.
    private static double toBitratePriority(Priority priority) {
        return switch (priority) {
            case VERY_LOW -> 0.5;
            case LOW -> 1.0;
            case MEDIUM -> 2.0;
            case HIGH -> 4.0;
        };
    }

    private static RTCPriorityType toRtcPriorityType(Priority priority) {
        return switch (priority) {
            case VERY_LOW -> RTCPriorityType.VERY_LOW;
            case LOW -> RTCPriorityType.LOW;
            case MEDIUM -> RTCPriorityType.MEDIUM;
            case HIGH -> RTCPriorityType.HIGH;
        };
    }

    // The encoding of the SDP with the fields that the given one has, as the Object.assign of
    // mediasoup-client.
    static RtpEncodingParameters merge(RtpEncodingParameters sdpEncoding, RtpEncodingParameters given) {
        RtpEncodingParameters.Builder builder = sdpEncoding.toBuilder();
        if (given.active() != null) {
            builder.active(given.active());
        }
        if (given.ssrc() != null) {
            builder.ssrc(given.ssrc());
        }
        if (given.rid() != null) {
            builder.rid(given.rid());
        }
        if (given.codecPayloadType() != null) {
            builder.codecPayloadType(given.codecPayloadType());
        }
        if (given.rtx() != null) {
            builder.rtx(given.rtx());
        }
        if (given.dtx() != null) {
            builder.dtx(given.dtx());
        }
        if (given.scalabilityMode() != null) {
            builder.scalabilityMode(given.scalabilityMode());
        }
        if (given.scaleResolutionDownBy() != null) {
            builder.scaleResolutionDownBy(given.scaleResolutionDownBy());
        }
        if (given.maxBitrate() != null) {
            builder.maxBitrate(given.maxBitrate());
        }
        if (given.maxFramerate() != null) {
            builder.maxFramerate(given.maxFramerate());
        }
        if (given.adaptivePtime() != null) {
            builder.adaptivePtime(given.adaptivePtime());
        }
        if (given.priority() != null) {
            builder.priority(given.priority());
        }
        if (given.networkPriority() != null) {
            builder.networkPriority(given.networkPriority());
        }
        return builder.build();
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

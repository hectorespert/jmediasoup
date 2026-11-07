/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.fake;

import dev.onvoid.webrtc.RTCRtpEncodingParameters;
import dev.onvoid.webrtc.RTCStats;
import dev.onvoid.webrtc.RTCStatsReport;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import io.github.hectorespert.jmediasoup.InvalidStateException;
import io.github.hectorespert.jmediasoup.MediasoupClientException;
import io.github.hectorespert.jmediasoup.handler.Handler;
import io.github.hectorespert.jmediasoup.handler.HandlerFactory;
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
import io.github.hectorespert.jmediasoup.rtp.ExtendedRtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtcpParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpEncodingParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
import io.github.hectorespert.jmediasoup.rtp.Rtx;
import io.github.hectorespert.jmediasoup.sctp.NumSctpStreams;
import io.github.hectorespert.jmediasoup.sctp.SctpCapabilities;
import io.github.hectorespert.jmediasoup.sctp.SctpStreamParameters;
import io.github.hectorespert.jmediasoup.transport.ConnectionState;
import io.github.hectorespert.jmediasoup.transport.DtlsParameters;
import io.github.hectorespert.jmediasoup.transport.DtlsRole;
import io.github.hectorespert.jmediasoup.transport.IceGatheringState;
import io.github.hectorespert.jmediasoup.transport.IceParameters;
import io.github.hectorespert.jmediasoup.transport.IceServer;
import io.github.hectorespert.jmediasoup.transport.TransportDirection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * A handler without WebRTC, for tests. Port of {@code FakeHandler.ts} of mediasoup-client 3.26: it
 * negotiates nothing, but it asks its transport to connect before the first send or receive, and
 * generates the RTP parameters that a real handler would give.
 *
 * <p>Besides the handler contract, it lets tests simulate the stack: {@link #setConnectionState},
 * {@link #setIceGatheringState} and {@link #failNextOperation}.
 */
public final class FakeHandler implements Handler {

    /** The name of the handler and its factory. */
    public static final String NAME = "FakeHandler";

    private static final int DATA_CHANNEL_MAX_MESSAGE_SIZE = 500000;

    private final HandlerOptions options;
    // Local RTCP CNAME.
    private final String cname = "CNAME-" + randomNumber();
    // Default sending MediaStream id.
    private final String defaultSendStreamId = String.valueOf(randomNumber());
    // Next localId.
    private final AtomicInteger nextLocalId = new AtomicInteger(1);
    // Sending and receiving tracks indexed by localId.
    private final Map<Integer, @Nullable MediaStreamTrack> tracks = Collections.synchronizedMap(new HashMap<>());
    // DataChannel id value counter. It must be incremented for each new DataChannel.
    private final AtomicInteger nextSctpStreamId = new AtomicInteger();
    private final AtomicReference<@Nullable Throwable> nextFailure = new AtomicReference<>();
    private final AtomicReference<@Nullable Throwable> nextReceiveFailure = new AtomicReference<>();
    // The number of receivings in each call to receive and stopReceiving.
    private final List<Integer> receiveBatches = new CopyOnWriteArrayList<>();
    private final List<Integer> stopReceivingBatches = new CopyOnWriteArrayList<>();
    private final List<String> pauseCalls = new CopyOnWriteArrayList<>();
    private volatile boolean closed;
    // Got transport local and remote parameters.
    private volatile boolean transportReady;

    private FakeHandler(HandlerOptions options) {
        this.options = options;
    }

    /**
     * Returns a factory of fake handlers, with the native capabilities of {@link
     * FakeParameters#nativeRtpCapabilities()} in both directions.
     *
     * @return the factory
     */
    public static Factory createFactory() {
        return new Factory();
    }

    /** A factory that remembers the handlers it creates, so that tests can drive them. */
    public static final class Factory implements HandlerFactory {

        private final List<FakeHandler> handlers = new CopyOnWriteArrayList<>();

        private Factory() {}

        @Override
        public String name() {
            return NAME;
        }

        @Override
        public CompletableFuture<RtpCapabilities> getNativeRtpCapabilities(TransportDirection direction) {
            return CompletableFuture.completedFuture(FakeParameters.nativeRtpCapabilities());
        }

        @Override
        public CompletableFuture<SctpCapabilities> getNativeSctpCapabilities() {
            return CompletableFuture.completedFuture(new SctpCapabilities(new NumSctpStreams(1024, 1024)));
        }

        @Override
        public FakeHandler create(HandlerOptions options) {
            var handler = new FakeHandler(options);
            handlers.add(handler);
            return handler;
        }

        /**
         * Returns the handlers created so far.
         *
         * @return the handlers, in the order they were created
         */
        public List<FakeHandler> handlers() {
            return List.copyOf(handlers);
        }
    }

    /**
     * Returns the options the handler was created with.
     *
     * @return the options
     */
    public HandlerOptions options() {
        return options;
    }

    /**
     * Returns whether the handler is closed.
     *
     * @return whether {@link #close()} was called
     */
    public boolean closed() {
        return closed;
    }

    /**
     * Returns the local ids of the tracks being sent or received.
     *
     * @return the local ids, in no particular order
     */
    public Set<String> localIds() {
        synchronized (tracks) {
            return tracks.keySet().stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
        }
    }

    /**
     * Returns the track being sent or received with a local id.
     *
     * @param localId the local id
     * @return the track, or {@code null} if there is none or it was replaced with none
     */
    public @Nullable MediaStreamTrack track(String localId) {
        return tracks.get(Integer.parseInt(localId));
    }

    /**
     * Returns how many receivings each call to {@link #receive} asked for.
     *
     * @return the sizes, in the order of the calls
     */
    public List<Integer> receiveBatches() {
        return List.copyOf(receiveBatches);
    }

    /**
     * Returns how many receivings each call to {@link #stopReceiving} stopped, while not closed.
     *
     * @return the sizes, in the order of the calls
     */
    public List<Integer> stopReceivingBatches() {
        return List.copyOf(stopReceivingBatches);
    }

    /**
     * Returns the calls to pause and resume sending and receiving, such as {@code pauseSending 1} or
     * {@code resumeReceiving [1, 2]}.
     *
     * @return the method and its local ids, in the order of the calls
     */
    public List<String> pauseCalls() {
        return List.copyOf(pauseCalls);
    }

    /**
     * Simulates a change in the connection state.
     *
     * @param connectionState the new state
     */
    public void setConnectionState(ConnectionState connectionState) {
        options.listener().connectionStateChange(connectionState);
    }

    /**
     * Simulates a change in the ICE gathering state.
     *
     * @param iceGatheringState the new state
     */
    public void setIceGatheringState(IceGatheringState iceGatheringState) {
        options.listener().iceGatheringStateChange(iceGatheringState);
    }

    /**
     * Makes the next asynchronous operation fail, as when the stack fails.
     *
     * @param error the cause of the failure
     */
    public void failNextOperation(Throwable error) {
        nextFailure.set(error);
    }

    /**
     * Makes the next {@link #receive} of a given track fail, such as the {@code probator} one that the
     * transport receives after the first video consumer.
     *
     * @param trackId the track id of the receiving
     * @param error the failure
     */
    public void failNextReceive(String trackId, Throwable error) {
        nextReceiveFailure.set(new TrackFailure(trackId, error));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public CompletableFuture<Void> updateIceServers(List<IceServer> iceServers) {
        return run(() -> null);
    }

    @Override
    public CompletableFuture<Void> restartIce(IceParameters iceParameters) {
        return run(() -> null);
    }

    @Override
    public CompletableFuture<RTCStatsReport> getTransportStats() {
        return run(EmptyStatsReport::new);
    }

    @Override
    public CompletableFuture<HandlerSendResult> send(HandlerSendOptions sendOptions) {
        return start().thenCompose(ignored -> setupTransport(DtlsRole.SERVER))
                .thenApply(ignored -> unchecked(() -> doSend(sendOptions)));
    }

    @Override
    public CompletableFuture<Void> stopSending(String localId) {
        if (closed) {
            return CompletableFuture.completedFuture(null);
        }
        return run(() -> {
            Integer id = localId(localId);
            if (id == null || !tracks.containsKey(id)) {
                throw new MediasoupClientException("local track not found");
            }
            tracks.remove(id);
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> pauseSending(String localId) {
        return run(() -> {
            pauseCalls.add("pauseSending " + localId);
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> resumeSending(String localId) {
        return run(() -> {
            pauseCalls.add("resumeSending " + localId);
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> replaceTrack(String localId, @Nullable MediaStreamTrack track) {
        return run(() -> {
            tracks.put(Integer.parseInt(localId), track);
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> setMaxSpatialLayer(String localId, int spatialLayer) {
        return run(() -> null);
    }

    @Override
    public CompletableFuture<Void> setRtpEncodingParameters(String localId, RTCRtpEncodingParameters params) {
        return run(() -> null);
    }

    @Override
    public CompletableFuture<RTCStatsReport> getSenderStats(String localId) {
        return run(EmptyStatsReport::new);
    }

    @Override
    public CompletableFuture<HandlerSendDataChannelResult> sendDataChannel(
            HandlerSendDataChannelOptions channelOptions) {
        return start().thenCompose(ignored -> setupTransport(DtlsRole.SERVER)).thenApply(ignored -> {
            SctpStreamParameters requested = channelOptions.sctpStreamParameters();
            // ⚠️ The original gives the channel one id and returns the next one; both are the same here.
            int streamId = nextSctpStreamId.getAndIncrement();
            var dataChannel = new FakeDataChannel(text(requested.label()), text(requested.protocol()));
            var sctpStreamParameters = new SctpStreamParameters(
                    streamId,
                    requested.ordered(),
                    requested.maxPacketLifeTime(),
                    requested.maxRetransmits(),
                    null,
                    null);
            return new HandlerSendDataChannelResult(dataChannel, sctpStreamParameters);
        });
    }

    @Override
    public CompletableFuture<List<HandlerReceiveResult>> receive(List<HandlerReceiveOptions> optionsList) {
        receiveBatches.add(optionsList.size());
        if (nextReceiveFailure.get() instanceof TrackFailure failure
                && optionsList.stream().anyMatch(options -> options.trackId().equals(failure.trackId()))
                && nextReceiveFailure.compareAndSet(failure, null)) {
            return CompletableFuture.failedFuture(failure.getCause());
        }
        return start().thenCompose(ignored -> setupTransport(DtlsRole.CLIENT)).thenApply(ignored -> {
            var results = new ArrayList<HandlerReceiveResult>();
            for (HandlerReceiveOptions receiveOptions : optionsList) {
                int localId = nextLocalId.getAndIncrement();
                MediaStreamTrack track = receiveOptions.kind() == MediaKind.AUDIO
                        ? FakeMediaStreamTrack.audio()
                        : FakeMediaStreamTrack.video();
                tracks.put(localId, track);
                results.add(new HandlerReceiveResult(String.valueOf(localId), track, null));
            }
            return List.copyOf(results);
        });
    }

    @Override
    public CompletableFuture<Void> stopReceiving(List<String> localIds) {
        if (closed) {
            return CompletableFuture.completedFuture(null);
        }
        stopReceivingBatches.add(localIds.size());
        return run(() -> {
            localIds.forEach(localId -> tracks.remove(localId(localId)));
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> pauseReceiving(List<String> localIds) {
        return run(() -> {
            pauseCalls.add("pauseReceiving " + localIds);
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> resumeReceiving(List<String> localIds) {
        return run(() -> {
            pauseCalls.add("resumeReceiving " + localIds);
            return null;
        });
    }

    @Override
    public CompletableFuture<RTCStatsReport> getReceiverStats(String localId) {
        return run(EmptyStatsReport::new);
    }

    @Override
    public CompletableFuture<HandlerReceiveDataChannelResult> receiveDataChannel(
            HandlerReceiveDataChannelOptions channelOptions) {
        return start().thenCompose(ignored -> setupTransport(DtlsRole.CLIENT))
                .thenApply(ignored -> new HandlerReceiveDataChannelResult(
                        new FakeDataChannel(text(channelOptions.label()), text(channelOptions.protocol()))));
    }

    @Override
    public @Nullable Integer getDataChannelMaxMessageSize() {
        return DATA_CHANNEL_MAX_MESSAGE_SIZE;
    }

    private HandlerSendResult doSend(HandlerSendOptions sendOptions) throws MediasoupClientException {
        MediaStreamTrack track = sendOptions.track();
        ExtendedRtpCapabilities sendExtendedRtpCapabilities =
                options.getSendExtendedRtpCapabilities().apply(FakeParameters.nativeRtpCapabilities());

        // Generic sending RTP parameters.
        RtpParameters sendingRtpParameters =
                Ortc.getSendingRtpParameters(MediaKind.fromValue(track.getKind()), sendExtendedRtpCapabilities);

        // This may throw.
        List<RtpCodecParameters> codecs = Ortc.reduceCodecs(sendingRtpParameters.codecs(), sendOptions.codec());

        boolean useRtx = codecs.stream().anyMatch(Ortc::isRtxCodec);

        List<RtpEncodingParameters> encodings = sendOptions.encodings().isEmpty()
                ? List.of(RtpEncodingParameters.builder().build())
                : sendOptions.encodings();
        encodings = encodings.stream()
                .map(encoding -> {
                    RtpEncodingParameters.Builder builder = encoding.toBuilder().ssrc(randomNumber());
                    if (useRtx) {
                        builder.rtx(new Rtx(randomNumber()));
                    }
                    return builder.build();
                })
                .toList();

        var rtpParameters = new RtpParameters(
                "mid-" + randomNumber(),
                codecs,
                sendingRtpParameters.headerExtensions(),
                encodings,
                new RtcpParameters(cname, true, true),
                (sendOptions.streamId() != null ? sendOptions.streamId() : defaultSendStreamId) + " " + track.getId());

        int localId = nextLocalId.getAndIncrement();
        tracks.put(localId, track);

        return new HandlerSendResult(String.valueOf(localId), rtpParameters, null);
    }

    private CompletableFuture<Void> setupTransport(DtlsRole localDtlsRole) {
        if (transportReady) {
            return CompletableFuture.completedFuture(null);
        }

        DtlsParameters dtlsParameters = new DtlsParameters(
                localDtlsRole, FakeParameters.localDtlsParameters().fingerprints());

        // Assume we are connecting now.
        options.listener().connectionStateChange(ConnectionState.CONNECTING);

        // Need to tell the remote transport about our parameters.
        return options.listener().connect(dtlsParameters).toCompletableFuture().thenRun(() -> transportReady = true);
    }

    // Fails if the handler is closed or a failure was requested; completes otherwise.
    private CompletableFuture<Void> start() {
        if (closed) {
            return CompletableFuture.failedFuture(new InvalidStateException("method called in a closed handler"));
        }
        Throwable failure = nextFailure.getAndSet(null);
        if (failure != null) {
            return CompletableFuture.failedFuture(failure);
        }
        return CompletableFuture.completedFuture(null);
    }

    private <T> CompletableFuture<T> run(Operation<T> operation) {
        return start().thenApply(ignored -> unchecked(operation));
    }

    private static <T> T unchecked(Operation<T> operation) {
        try {
            return operation.run();
        } catch (MediasoupClientException e) {
            throw new CompletionException(e);
        }
    }

    @FunctionalInterface
    private interface Operation<T> {
        T run() throws MediasoupClientException;
    }

    // The local id as a number, or null if it is not one, like Number(localId) in the original.
    private static @Nullable Integer localId(String localId) {
        try {
            return Integer.valueOf(localId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long randomNumber() {
        return ThreadLocalRandom.current().nextLong(10_000_001);
    }

    private static String text(@Nullable String value) {
        return value != null ? value : "";
    }

    private static final class EmptyStatsReport extends RTCStatsReport {

        EmptyStatsReport() {
            super(Map.<String, RTCStats>of(), 0);
        }
    }

    // The failure of the receive of a track.
    private static final class TrackFailure extends Exception {

        private final String trackId;

        TrackFailure(String trackId, Throwable cause) {
            super(cause);
            this.trackId = trackId;
        }

        String trackId() {
            return trackId;
        }
    }
}

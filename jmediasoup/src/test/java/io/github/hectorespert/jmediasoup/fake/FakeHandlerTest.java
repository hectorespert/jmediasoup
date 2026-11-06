/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.onvoid.webrtc.RTCPeerConnectionIceErrorEvent;
import io.github.hectorespert.jmediasoup.InvalidStateException;
import io.github.hectorespert.jmediasoup.MediasoupClientException;
import io.github.hectorespert.jmediasoup.UnsupportedException;
import io.github.hectorespert.jmediasoup.handler.HandlerListener;
import io.github.hectorespert.jmediasoup.handler.HandlerOptions;
import io.github.hectorespert.jmediasoup.handler.HandlerReceiveDataChannelOptions;
import io.github.hectorespert.jmediasoup.handler.HandlerReceiveOptions;
import io.github.hectorespert.jmediasoup.handler.HandlerReceiveResult;
import io.github.hectorespert.jmediasoup.handler.HandlerSendDataChannelOptions;
import io.github.hectorespert.jmediasoup.handler.HandlerSendDataChannelResult;
import io.github.hectorespert.jmediasoup.handler.HandlerSendOptions;
import io.github.hectorespert.jmediasoup.handler.HandlerSendResult;
import io.github.hectorespert.jmediasoup.internal.ortc.Ortc;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecCapability;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpEncodingParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
import io.github.hectorespert.jmediasoup.sctp.NumSctpStreams;
import io.github.hectorespert.jmediasoup.sctp.SctpStreamParameters;
import io.github.hectorespert.jmediasoup.transport.ConnectionState;
import io.github.hectorespert.jmediasoup.transport.DtlsParameters;
import io.github.hectorespert.jmediasoup.transport.DtlsRole;
import io.github.hectorespert.jmediasoup.transport.IceGatheringState;
import io.github.hectorespert.jmediasoup.transport.TransportDirection;
import io.github.hectorespert.jmediasoup.transport.TransportOptions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class FakeHandlerTest {

    /** Records what the handler reports, and answers connect with a stage the test chooses. */
    static final class RecordingListener implements HandlerListener {

        final List<DtlsParameters> connects = new CopyOnWriteArrayList<>();
        final List<Object> states = new CopyOnWriteArrayList<>();
        Supplier<CompletionStage<Void>> connectResult = () -> CompletableFuture.completedFuture(null);

        @Override
        public CompletionStage<Void> connect(DtlsParameters dtlsParameters) {
            connects.add(dtlsParameters);
            return connectResult.get();
        }

        @Override
        public void connectionStateChange(ConnectionState connectionState) {
            states.add(connectionState);
        }

        @Override
        public void iceGatheringStateChange(IceGatheringState iceGatheringState) {
            states.add(iceGatheringState);
        }

        @Override
        public void iceCandidateError(RTCPeerConnectionIceErrorEvent event) {
            states.add(event);
        }
    }

    private final RecordingListener listener = new RecordingListener();
    private final FakeHandler.Factory factory = FakeHandler.createFactory();

    private FakeHandler handler(TransportDirection direction) {
        TransportOptions transport = FakeParameters.transportRemoteParameters();
        return factory.create(new HandlerOptions(
                direction,
                transport.iceParameters(),
                transport.iceCandidates(),
                transport.dtlsParameters(),
                transport.sctpParameters(),
                null,
                null,
                native_ -> Ortc.getExtendedRtpCapabilities(native_, FakeParameters.routerRtpCapabilities(), false),
                listener));
    }

    private static HandlerSendOptions send(FakeMediaStreamTrack track) {
        return new HandlerSendOptions(track, null, null, null, null);
    }

    private static Throwable cause(CompletableFuture<?> future) {
        return assertThrows(CompletionException.class, future::join).getCause();
    }

    @Test
    void theFactoryGivesTheFakeCapabilities() {
        assertEquals("FakeHandler", factory.name());
        assertEquals(
                FakeParameters.nativeRtpCapabilities(),
                factory.getNativeRtpCapabilities(TransportDirection.SEND).join());
        assertEquals(
                FakeParameters.nativeRtpCapabilities(),
                factory.getNativeRtpCapabilities(TransportDirection.RECV).join());
        assertEquals(
                new NumSctpStreams(1024, 1024),
                factory.getNativeSctpCapabilities().join().numStreams());
    }

    @Test
    void theFactoryRemembersItsHandlers() {
        FakeHandler first = handler(TransportDirection.SEND);
        FakeHandler second = handler(TransportDirection.RECV);

        assertEquals(List.of(first, second), factory.handlers());
        assertEquals(TransportDirection.RECV, second.options().direction());
        assertEquals("FakeHandler", first.name());
    }

    @Test
    void theFirstSendConnectsAsDtlsServer() {
        FakeHandler handler = handler(TransportDirection.SEND);

        handler.send(send(FakeMediaStreamTrack.audio())).join();
        handler.send(send(FakeMediaStreamTrack.video())).join();

        assertEquals(1, listener.connects.size());
        assertEquals(DtlsRole.SERVER, listener.connects.getFirst().role());
        assertEquals(
                FakeParameters.localDtlsParameters().fingerprints(),
                listener.connects.getFirst().fingerprints());
        assertEquals(List.of(ConnectionState.CONNECTING), listener.states);
    }

    @Test
    void theFirstReceiveConnectsAsDtlsClient() {
        FakeHandler handler = handler(TransportDirection.RECV);

        handler.receive(List.of()).join();

        assertEquals(DtlsRole.CLIENT, listener.connects.getFirst().role());
    }

    @Test
    void aFailedConnectFailsTheOperationAndIsRetried() {
        FakeHandler handler = handler(TransportDirection.SEND);
        var error = new MediasoupClientException("signaling failed");
        listener.connectResult = () -> CompletableFuture.failedFuture(error);

        assertSame(error, cause(handler.send(send(FakeMediaStreamTrack.audio()))));

        listener.connectResult = () -> CompletableFuture.completedFuture(null);
        handler.send(send(FakeMediaStreamTrack.audio())).join();
        assertEquals(2, listener.connects.size());
    }

    @Test
    void sendingVideoGivesRtpParametersWithRtx() {
        FakeHandler handler = handler(TransportDirection.SEND);
        FakeMediaStreamTrack track = FakeMediaStreamTrack.video();

        HandlerSendResult result = handler.send(new HandlerSendOptions(track, "stream", null, null, null))
                .join();

        RtpParameters rtpParameters = result.rtpParameters();
        assertEquals("1", result.localId());
        assertNull(result.rtpSender());
        assertTrue(rtpParameters.mid().startsWith("mid-"));
        assertEquals("stream " + track.getId(), rtpParameters.msid());
        assertEquals(
                List.of("video/VP8", "video/rtx"),
                rtpParameters.codecs().stream()
                        .map(RtpCodecParameters::mimeType)
                        .toList());
        RtpEncodingParameters encoding = rtpParameters.encodings().getFirst();
        assertNotNull(encoding.ssrc());
        assertNotNull(encoding.rtx());
        assertTrue(rtpParameters.rtcp().cname().startsWith("CNAME-"));
        assertEquals(Boolean.TRUE, rtpParameters.rtcp().reducedSize());
    }

    @Test
    void sendingAudioGivesRtpParametersWithoutRtx() {
        FakeHandler handler = handler(TransportDirection.SEND);
        FakeMediaStreamTrack track = FakeMediaStreamTrack.audio();

        RtpParameters first = handler.send(send(track)).join().rtpParameters();
        RtpParameters second =
                handler.send(send(FakeMediaStreamTrack.audio())).join().rtpParameters();

        assertEquals("audio/opus", first.codecs().getFirst().mimeType());
        assertNull(first.encodings().getFirst().rtx());
        // Without a stream id, all of them share the default stream of the handler.
        assertEquals(first.msid().split(" ")[0], second.msid().split(" ")[0]);
        assertEquals(first.rtcp().cname(), second.rtcp().cname());
    }

    @Test
    void eachEncodingGetsItsSsrc() {
        FakeHandler handler = handler(TransportDirection.SEND);
        List<RtpEncodingParameters> encodings = List.of(
                RtpEncodingParameters.builder().rid("r0").build(),
                RtpEncodingParameters.builder().rid("r1").build());

        List<RtpEncodingParameters> result = handler.send(
                        new HandlerSendOptions(FakeMediaStreamTrack.video(), null, encodings, null, null))
                .join()
                .rtpParameters()
                .encodings();

        assertEquals(
                List.of("r0", "r1"),
                result.stream().map(RtpEncodingParameters::rid).toList());
        assertTrue(result.stream().allMatch(encoding -> encoding.ssrc() != null && encoding.rtx() != null));
    }

    @Test
    void sendingAnUnsupportedCodecFails() {
        FakeHandler handler = handler(TransportDirection.SEND);
        var h264 = new RtpCodecCapability(MediaKind.VIDEO, "video/H264", null, 90000, null, Map.of(), List.of());

        assertInstanceOf(
                UnsupportedException.class,
                cause(handler.send(new HandlerSendOptions(FakeMediaStreamTrack.video(), null, null, null, h264))));
    }

    @Test
    void stoppingAnUnknownSendingFails() {
        FakeHandler handler = handler(TransportDirection.SEND);
        String localId = handler.send(send(FakeMediaStreamTrack.audio())).join().localId();

        handler.replaceTrack(localId, null).join();
        handler.stopSending(localId).join();

        assertEquals(
                "local track not found", cause(handler.stopSending(localId)).getMessage());
        assertEquals("local track not found", cause(handler.stopSending("x")).getMessage());
    }

    @Test
    void receivingGivesATrackOfEachKind() {
        FakeHandler handler = handler(TransportDirection.RECV);
        RtpParameters rtpParameters =
                FakeParameters.consumerRemoteParameters("audio/opus").rtpParameters();

        List<HandlerReceiveResult> results = handler.receive(List.of(
                        new HandlerReceiveOptions("a", MediaKind.AUDIO, rtpParameters, null),
                        new HandlerReceiveOptions("v", MediaKind.VIDEO, rtpParameters, null)))
                .join();

        assertEquals(
                List.of("1", "2"),
                results.stream().map(HandlerReceiveResult::localId).toList());
        assertEquals("audio", results.get(0).track().getKind());
        assertEquals("video", results.get(1).track().getKind());
        handler.stopReceiving(List.of("1", "2", "x")).join();
    }

    @Test
    void dataChannelsGetConsecutiveStreamIds() {
        FakeHandler handler = handler(TransportDirection.SEND);
        var requested = new SctpStreamParameters(null, false, null, 3, "chat", "json");

        HandlerSendDataChannelResult first = handler.sendDataChannel(new HandlerSendDataChannelOptions(requested))
                .join();
        HandlerSendDataChannelResult second = handler.sendDataChannel(new HandlerSendDataChannelOptions(requested))
                .join();

        assertEquals(new SctpStreamParameters(0, false, null, 3, null, null), first.sctpStreamParameters());
        assertEquals(1, second.sctpStreamParameters().streamId());
        assertEquals("chat", first.dataChannel().label());
        assertEquals("json", first.dataChannel().protocol());
        assertEquals(500000, handler.getDataChannelMaxMessageSize());
    }

    @Test
    void receivingADataChannelUsesItsLabelAndProtocol() {
        FakeHandler handler = handler(TransportDirection.RECV);

        var result = handler.receiveDataChannel(new HandlerReceiveDataChannelOptions(
                        null, new SctpStreamParameters(4, true, null, null, null, null), "chat", null))
                .join();

        assertEquals("chat", result.dataChannel().label());
        assertEquals("", result.dataChannel().protocol());
        assertEquals(DtlsRole.CLIENT, listener.connects.getFirst().role());
    }

    @Test
    void statsAreEmpty() {
        FakeHandler handler = handler(TransportDirection.SEND);

        assertTrue(handler.getTransportStats().join().getStats().isEmpty());
        assertTrue(handler.getSenderStats("1").join().getStats().isEmpty());
        assertTrue(handler.getReceiverStats("1").join().getStats().isEmpty());
    }

    @Test
    void aClosedHandlerRejectsOperationsButStopping() {
        FakeHandler handler = handler(TransportDirection.SEND);

        handler.close();
        handler.close();

        assertTrue(handler.closed());
        assertInstanceOf(InvalidStateException.class, cause(handler.send(send(FakeMediaStreamTrack.audio()))));
        assertInstanceOf(
                InvalidStateException.class,
                cause(handler.restartIce(
                        FakeParameters.transportRemoteParameters().iceParameters())));
        assertInstanceOf(InvalidStateException.class, cause(handler.pauseReceiving(List.of())));
        handler.stopSending("1").join();
        handler.stopReceiving(List.of("1")).join();
    }

    @Test
    void theStackCanBeSimulated() {
        FakeHandler handler = handler(TransportDirection.SEND);
        var error = new IllegalStateException("native failure");

        handler.setConnectionState(ConnectionState.CONNECTED);
        handler.setIceGatheringState(IceGatheringState.COMPLETE);
        handler.failNextOperation(error);

        assertEquals(List.of(ConnectionState.CONNECTED, IceGatheringState.COMPLETE), listener.states);
        assertSame(error, cause(handler.updateIceServers(List.of())));
        handler.updateIceServers(List.of()).join();
        assertFalse(handler.closed());
    }
}

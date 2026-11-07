/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.handler.webrtc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCRtpTransceiverDirection;
import dev.onvoid.webrtc.RTCSignalingState;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;
import dev.onvoid.webrtc.media.video.VideoTrack;
import io.github.hectorespert.jmediasoup.Consumer;
import io.github.hectorespert.jmediasoup.ConsumerOptions;
import io.github.hectorespert.jmediasoup.Device;
import io.github.hectorespert.jmediasoup.InvalidStateException;
import io.github.hectorespert.jmediasoup.MediasoupClientException;
import io.github.hectorespert.jmediasoup.RecvTransport;
import io.github.hectorespert.jmediasoup.fake.FakeParameters;
import io.github.hectorespert.jmediasoup.handler.HandlerReceiveOptions;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
import io.github.hectorespert.jmediasoup.transport.DtlsParameters;
import io.github.hectorespert.jmediasoup.transport.DtlsRole;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests receiving with {@link WebRtcJavaHandler} and the webrtc-java natives, without a server: the
 * negotiation completes, but no media arrives.
 */
class WebRtcJavaReceiveTest {

    private static PeerConnectionFactory peerConnectionFactory;

    private final List<DtlsParameters> connects = new CopyOnWriteArrayList<>();
    private WebRtcJavaHandler.Factory factory;
    private RecvTransport transport;

    @BeforeAll
    static void createPeerConnectionFactory() {
        peerConnectionFactory = new PeerConnectionFactory(new HeadlessAudioDeviceModule());
    }

    @AfterAll
    static void disposePeerConnectionFactory() {
        peerConnectionFactory.dispose();
    }

    @BeforeEach
    void createTransport() throws Exception {
        factory = WebRtcJavaHandler.createFactory(peerConnectionFactory);
        var device = new Device(factory);
        await(device.load(FakeParameters.routerRtpCapabilities()));
        transport = device.createRecvTransport(FakeParameters.transportRemoteParameters(), dtls -> {
            connects.add(dtls);
            return CompletableFuture.completedFuture(null);
        });
    }

    @AfterEach
    void closeTransport() {
        transport.close();
        factory.close();
    }

    private static <T> T await(CompletableFuture<T> future) {
        return future.orTimeout(10, TimeUnit.SECONDS).join();
    }

    private WebRtcJavaHandler handler() {
        return (WebRtcJavaHandler) transport.handler();
    }

    private RTCRtpTransceiver transceiver(String mid) {
        for (RTCRtpTransceiver transceiver : handler().peerConnection().getTransceivers()) {
            if (mid.equals(transceiver.getMid())) {
                return transceiver;
            }
        }
        throw new AssertionError("no transceiver with mid " + mid);
    }

    private static ConsumerOptions withSsrc(ConsumerOptions options, long ssrc) {
        RtpParameters rtpParameters = options.rtpParameters();
        return new ConsumerOptions(
                options.id(),
                options.producerId(),
                options.kind(),
                new RtpParameters(
                        rtpParameters.mid(),
                        rtpParameters.codecs(),
                        rtpParameters.headerExtensions(),
                        rtpParameters.encodings().stream()
                                .map(encoding -> encoding.toBuilder().ssrc(ssrc).build())
                                .toList(),
                        rtpParameters.rtcp(),
                        rtpParameters.msid()),
                options.streamId(),
                options.appData());
    }

    @Test
    void consumeAudioAndVideo() {
        Consumer audio = await(transport.consume(FakeParameters.consumerRemoteParameters("audio/opus")));
        Consumer video = await(transport.consume(FakeParameters.consumerRemoteParameters("video/VP8")));

        // The first receive connects the transport, as DTLS client.
        assertEquals(1, connects.size());
        assertEquals(DtlsRole.CLIENT, connects.getFirst().role());

        // The consumer RTP parameters have no MID, so the local ids are counted.
        assertEquals("0", audio.localId());
        assertEquals("1", video.localId());
        assertInstanceOf(AudioTrack.class, audio.track());
        assertInstanceOf(VideoTrack.class, video.track());
        assertNotNull(audio.rtpReceiver());
        assertEquals(
                audio.track().getId(), transceiver("0").getReceiver().getTrack().getId());
        // The remote track id is the consumer id.
        assertEquals(audio.id(), audio.track().getId());
    }

    @Test
    void consumesInTheSameBatchShareANegotiation() {
        CompletableFuture<Consumer> audio = transport.consume(FakeParameters.consumerRemoteParameters("audio/opus"));
        CompletableFuture<Consumer> video = transport.consume(FakeParameters.consumerRemoteParameters("video/VP8"));

        assertEquals(MediaKind.AUDIO, await(audio).kind());
        assertEquals(MediaKind.VIDEO, await(video).kind());
        assertEquals(2, handler().peerConnection().getTransceivers().length);
    }

    @Test
    void theMidOfMediasoupIsTheLocalId() {
        ConsumerOptions options = FakeParameters.consumerRemoteParameters("audio/opus");
        var withMid = new ConsumerOptions(
                options.id(),
                options.producerId(),
                options.kind(),
                new RtpParameters(
                        "7",
                        options.rtpParameters().codecs(),
                        options.rtpParameters().headerExtensions(),
                        options.rtpParameters().encodings(),
                        options.rtpParameters().rtcp(),
                        options.rtpParameters().msid()),
                null,
                null);

        Consumer consumer = await(transport.consume(withMid));

        assertEquals("7", consumer.localId());
        assertEquals(
                consumer.track().getId(),
                transceiver("7").getReceiver().getTrack().getId());
    }

    @Test
    void theStreamIsTheGivenOneOrTheCname() {
        ConsumerOptions options = FakeParameters.consumerRemoteParameters("audio/opus");
        var withStream = new ConsumerOptions(
                options.id(), options.producerId(), options.kind(), options.rtpParameters(), "my-stream", null);

        await(transport.consume(withStream));
        Consumer withoutStream = await(transport.consume(FakeParameters.consumerRemoteParameters("video/VP8")));

        String sdp = handler().remoteSdp().getSdp();
        assertTrue(sdp.contains("a=msid:my-stream " + options.id()), sdp);
        String cname = withoutStream.rtpParameters().rtcp().cname();
        assertTrue(sdp.contains("a=msid:" + cname + " " + withoutStream.id()), sdp);
    }

    @Test
    void closingAConsumerStopsItsTransceiver() {
        Consumer audio = await(transport.consume(FakeParameters.consumerRemoteParameters("audio/opus")));
        Consumer video = await(transport.consume(FakeParameters.consumerRemoteParameters("video/VP8")));

        audio.close();
        // Waits for the stopReceiving in the queue, by consuming after it. mediasoup gives each consumer
        // its own SSRC, and the first section keeps the one of the closed consumer.
        Consumer again =
                await(transport.consume(withSsrc(FakeParameters.consumerRemoteParameters("audio/opus"), 1234)));

        // The first section is disabled instead of closed, as in mediasoup-client, because it keeps the
        // BUNDLE transport.
        assertEquals(RTCRtpTransceiverDirection.INACTIVE, transceiver("0").getCurrentDirection());
        assertFalse(video.closed());
        assertFalse(again.localId().equals("1"));
        assertEquals(MediaKind.AUDIO, again.kind());
    }

    @Test
    void closingAConsumerOfAnotherSectionClosesIt() {
        await(transport.consume(FakeParameters.consumerRemoteParameters("audio/opus")));
        Consumer video = await(transport.consume(FakeParameters.consumerRemoteParameters("video/VP8")));

        video.close();
        await(transport.consume(withSsrc(FakeParameters.consumerRemoteParameters("audio/opus"), 1234)));

        // webrtc-java removes the stopped transceiver once negotiated, and the new consumer reuses its
        // section.
        List<String> mids = java.util.Arrays.stream(handler().peerConnection().getTransceivers())
                .map(RTCRtpTransceiver::getMid)
                .toList();
        assertFalse(mids.contains("1"), mids.toString());
        assertEquals(2, mids.size(), mids.toString());
    }

    @Test
    void pausingAndResumingReceivingRenegotiatesTheDirection() {
        Consumer audio = await(transport.consume(FakeParameters.consumerRemoteParameters("audio/opus")));
        Consumer video = await(transport.consume(FakeParameters.consumerRemoteParameters("video/VP8")));

        await(handler().pauseReceiving(List.of(audio.localId(), video.localId())));

        assertEquals(
                RTCRtpTransceiverDirection.INACTIVE,
                transceiver(audio.localId()).getCurrentDirection());
        assertEquals(
                RTCRtpTransceiverDirection.INACTIVE,
                transceiver(video.localId()).getCurrentDirection());
        assertEquals(RTCSignalingState.STABLE, handler().peerConnection().getSignalingState());

        await(handler().resumeReceiving(List.of(video.localId())));

        assertEquals(
                RTCRtpTransceiverDirection.INACTIVE,
                transceiver(audio.localId()).getCurrentDirection());
        assertEquals(
                RTCRtpTransceiverDirection.RECV_ONLY,
                transceiver(video.localId()).getCurrentDirection());
    }

    @Test
    void pausingAConsumerPausesItsTransceiver() {
        Consumer audio = await(transport.consume(FakeParameters.consumerRemoteParameters("audio/opus")));

        audio.pause();
        // Waits for the pauseReceiving in the queue, by consuming after it.
        await(transport.consume(FakeParameters.consumerRemoteParameters("video/VP8")));

        assertFalse(audio.track().isEnabled());
        assertEquals(
                RTCRtpTransceiverDirection.INACTIVE,
                transceiver(audio.localId()).getCurrentDirection());

        audio.resume();
        await(transport.consume(withSsrc(FakeParameters.consumerRemoteParameters("audio/opus"), 1234)));

        assertTrue(audio.track().isEnabled());
        assertEquals(
                RTCRtpTransceiverDirection.RECV_ONLY,
                transceiver(audio.localId()).getCurrentDirection());
    }

    @Test
    void pausingOrResumingAnUnknownLocalIdFails() {
        CompletionException pause =
                assertThrows(CompletionException.class, () -> await(handler().pauseReceiving(List.of("42"))));
        CompletionException resume =
                assertThrows(CompletionException.class, () -> await(handler().resumeReceiving(List.of("42"))));

        assertInstanceOf(MediasoupClientException.class, pause.getCause());
        assertInstanceOf(MediasoupClientException.class, resume.getCause());
    }

    @Test
    void pausingReceivingOnAClosedHandlerFails() {
        handler().close();

        CompletionException failure =
                assertThrows(CompletionException.class, () -> await(handler().pauseReceiving(List.of("0"))));

        assertInstanceOf(InvalidStateException.class, failure.getCause());
    }

    @Test
    void stopReceivingAnUnknownLocalIdFails() {
        CompletionException failure =
                assertThrows(CompletionException.class, () -> await(handler().stopReceiving(List.of("42"))));

        assertInstanceOf(MediasoupClientException.class, failure.getCause());
    }

    @Test
    void stopReceivingOnAClosedHandlerDoesNothing() {
        handler().close();

        await(handler().stopReceiving(List.of("42")));
    }

    @Test
    void receiveFailsOnAClosedHandler() {
        handler().close();
        HandlerReceiveOptions options = new HandlerReceiveOptions(
                "track",
                MediaKind.AUDIO,
                FakeParameters.consumerRemoteParameters("audio/opus").rtpParameters(),
                null);

        CompletionException failure =
                assertThrows(CompletionException.class, () -> await(handler().receive(List.of(options))));

        assertInstanceOf(io.github.hectorespert.jmediasoup.InvalidStateException.class, failure.getCause());
    }
}

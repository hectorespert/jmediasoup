/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.handler.webrtc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCRtpTransceiverDirection;
import dev.onvoid.webrtc.RTCSignalingState;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;
import io.github.hectorespert.jmediasoup.Device;
import io.github.hectorespert.jmediasoup.InvalidStateException;
import io.github.hectorespert.jmediasoup.MediasoupClientException;
import io.github.hectorespert.jmediasoup.Producer;
import io.github.hectorespert.jmediasoup.ProducerCodecOptions;
import io.github.hectorespert.jmediasoup.ProducerOptions;
import io.github.hectorespert.jmediasoup.SendTransport;
import io.github.hectorespert.jmediasoup.UnsupportedException;
import io.github.hectorespert.jmediasoup.fake.FakeParameters;
import io.github.hectorespert.jmediasoup.handler.HandlerSendOptions;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.Priority;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecCapability;
import io.github.hectorespert.jmediasoup.rtp.RtpEncodingParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
import io.github.hectorespert.jmediasoup.testfixtures.ColorVideoSource;
import io.github.hectorespert.jmediasoup.testfixtures.ToneAudioSource;
import io.github.hectorespert.jmediasoup.transport.DtlsParameters;
import io.github.hectorespert.jmediasoup.transport.DtlsRole;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Tests sending with {@link WebRtcJavaHandler} and the webrtc-java natives, without a server: the
 * negotiation completes, but no media leaves.
 */
class WebRtcJavaSendTest {

    private static PeerConnectionFactory peerConnectionFactory;
    private static ToneAudioSource audioSource;
    private static ColorVideoSource videoSource;

    private final List<DtlsParameters> connects = new CopyOnWriteArrayList<>();
    private WebRtcJavaHandler.Factory factory;
    private SendTransport transport;

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
    void createTransport() throws Exception {
        factory = WebRtcJavaHandler.createFactory(peerConnectionFactory);
        var device = new Device(factory);
        await(device.load(FakeParameters.routerRtpCapabilities()));
        transport = device.createSendTransport(
                FakeParameters.transportRemoteParameters(),
                dtls -> {
                    connects.add(dtls);
                    return CompletableFuture.completedFuture(null);
                },
                request -> CompletableFuture.completedFuture(UUID.randomUUID().toString()));
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

    private MediaStreamTrack audioTrack() {
        return peerConnectionFactory.createAudioTrack("audio", audioSource.source());
    }

    private MediaStreamTrack videoTrack() {
        return peerConnectionFactory.createVideoTrack("video", videoSource.source());
    }

    private Producer produce(MediaStreamTrack track) {
        return await(transport.produce(ProducerOptions.builder(track).build()));
    }

    private List<String> mids() {
        return Arrays.stream(handler().peerConnection().getTransceivers())
                .map(RTCRtpTransceiver::getMid)
                .toList();
    }

    @Test
    void produceAudioAndVideo() {
        MediaStreamTrack audioTrack = audioTrack();
        Producer audio = produce(audioTrack);
        Producer video = produce(videoTrack());

        // The first send connects the transport, as DTLS client.
        assertEquals(1, connects.size());
        assertEquals(DtlsRole.CLIENT, connects.getFirst().role());

        // The local id is the MID of the transceiver.
        assertEquals("0", audio.localId());
        assertEquals("1", video.localId());
        assertEquals(audioTrack.getId(), audio.rtpSender().getTrack().getId());

        RtpParameters audioParameters = audio.rtpParameters();
        assertEquals("0", audioParameters.mid());
        assertEquals("audio/opus", audioParameters.codecs().getFirst().mimeType());
        assertEquals(1, audioParameters.codecs().size());
        assertEquals(1, audioParameters.encodings().size());
        assertNotNull(audioParameters.encodings().getFirst().ssrc());
        assertFalse(audioParameters.rtcp().cname().isEmpty());

        // VP8 and its RTX, with an RTX SSRC.
        RtpParameters videoParameters = video.rtpParameters();
        assertEquals(
                List.of("video/VP8", "video/rtx"),
                videoParameters.codecs().stream().map(codec -> codec.mimeType()).toList());
        assertNotNull(videoParameters.encodings().getFirst().rtx());
        assertEquals(audioParameters.rtcp().cname(), videoParameters.rtcp().cname());
        assertEquals(RTCSignalingState.STABLE, handler().peerConnection().getSignalingState());
    }

    @Test
    void theStreamIsTheGivenOneOrTheOneOfTheHandler() {
        MediaStreamTrack audioTrack = audioTrack();
        MediaStreamTrack videoTrack = videoTrack();
        MediaStreamTrack otherTrack = audioTrack();

        Producer audio = produce(audioTrack);
        Producer video = produce(videoTrack);
        Producer other = await(transport.produce(
                ProducerOptions.builder(otherTrack).streamId("my-stream").build()));

        String audioStream = audio.rtpParameters().msid().split(" ")[0];
        assertEquals(
                audioStream + " " + audioTrack.getId(), audio.rtpParameters().msid());
        assertEquals(
                audioStream + " " + videoTrack.getId(), video.rtpParameters().msid());
        assertEquals("my-stream " + otherTrack.getId(), other.rtpParameters().msid());
    }

    @Test
    void aGivenEncodingCompletesTheOneOfTheOffer() {
        Producer video = await(transport.produce(ProducerOptions.builder(videoTrack())
                .encodings(List.of(RtpEncodingParameters.builder()
                        .maxBitrate(100_000)
                        .priority(Priority.HIGH)
                        .build()))
                .build()));

        RtpEncodingParameters encoding = video.rtpParameters().encodings().getFirst();
        assertNotNull(encoding.ssrc());
        assertNotNull(encoding.rtx());
        assertEquals(100_000, encoding.maxBitrate());
        assertEquals(Priority.HIGH, encoding.priority());
        // And the sender sends with it.
        assertEquals(100_000, video.rtpSender().getParameters().encodings.getFirst().maxBitrate);
    }

    @Test
    void simulcastIsNotSupportedYet() {
        var encoding = RtpEncodingParameters.builder().build();
        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> await(transport.produce(ProducerOptions.builder(videoTrack())
                        .encodings(List.of(encoding, encoding))
                        .build())));

        assertInstanceOf(UnsupportedException.class, failure.getCause());
        assertEquals(List.of(), mids());
    }

    @Test
    void codecOptionsAreIgnored() {
        Producer audio = await(transport.produce(ProducerOptions.builder(audioTrack())
                .codecOptions(ProducerCodecOptions.builder().opusStereo(true).build())
                .build()));

        assertEquals("audio/opus", audio.rtpParameters().codecs().getFirst().mimeType());
    }

    // A codec of another kind, which no codec matches.
    private static final RtpCodecCapability VP8 =
            new RtpCodecCapability(MediaKind.VIDEO, "video/VP8", null, 90_000, null, Map.of(), List.of());

    private void produceFails(Class<? extends Throwable> cause) {
        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> await(transport.produce(
                        ProducerOptions.builder(audioTrack()).codec(VP8).build())));

        assertInstanceOf(cause, failure.getCause());
        assertEquals(RTCSignalingState.STABLE, handler().peerConnection().getSignalingState());
    }

    @Test
    void aFailedFirstSendDisablesItsSection() {
        produceFails(UnsupportedException.class);

        // The section of the failed send is negotiated disabled, since the first one keeps the BUNDLE
        // transport, and the next send gets its own section.
        assertEquals(List.of("0"), mids());
        Producer audio = produce(audioTrack());
        assertEquals("1", audio.localId());
        assertEquals(List.of("0", "1"), mids());
        assertEquals(RTCSignalingState.STABLE, handler().peerConnection().getSignalingState());
    }

    @Test
    void aFailedSendClosesItsSectionForTheNextOne() {
        produce(audioTrack());

        produceFails(UnsupportedException.class);

        // The next send reuses the closed section of the failed one, with another MID.
        Producer video = produce(videoTrack());
        assertEquals(2, mids().size(), mids().toString());
        assertTrue(mids().contains(video.localId()));
        assertNotEquals("1", video.localId());
        assertEquals(RTCSignalingState.STABLE, handler().peerConnection().getSignalingState());
    }

    @Test
    void aFailedConnectFailsTheSendAndTheNextOneConnects() throws Exception {
        transport.close();
        var device = new Device(factory);
        await(device.load(FakeParameters.routerRtpCapabilities()));
        transport = device.createSendTransport(
                FakeParameters.transportRemoteParameters(),
                dtls -> {
                    connects.add(dtls);
                    return connects.size() == 1
                            ? CompletableFuture.failedFuture(new IllegalStateException("signaling down"))
                            : CompletableFuture.completedFuture(null);
                },
                request -> CompletableFuture.completedFuture(UUID.randomUUID().toString()));

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> await(
                        transport.produce(ProducerOptions.builder(audioTrack()).build())));
        assertFalse(
                failure.getCause() instanceof UnsupportedException,
                failure.getCause().toString());

        Producer audio = produce(audioTrack());
        assertEquals(2, connects.size());
        assertEquals(connects.getFirst(), connects.getLast());
        assertEquals("1", audio.localId());
    }

    @Test
    void closingAProducerStopsSendingItsTrack() {
        Producer audio = produce(audioTrack());
        Producer video = produce(videoTrack());

        video.close();
        // Waits for the stopSending in the queue, by producing after it.
        Producer again = produce(videoTrack());

        // The closed section is reused, with another MID.
        List<String> mids = mids();
        assertFalse(mids.contains(video.localId()), mids.toString());
        assertEquals(2, mids.size(), mids.toString());
        assertNotEquals(video.localId(), again.localId());
        assertFalse(audio.closed());
        assertEquals(
                2,
                handler()
                        .remoteSdp()
                        .getSdp()
                        .lines()
                        .filter(line -> line.startsWith("m="))
                        .count());
    }

    @Test
    void closingTheFirstProducerDisablesItsSection() {
        Producer audio = produce(audioTrack());

        audio.close();
        Producer again = produce(audioTrack());

        // The first section keeps the BUNDLE transport, so it is disabled instead of closed, and the
        // new producer gets a section of its own.
        RTCRtpTransceiver first = handler().peerConnection().getTransceivers()[0];
        assertNull(first.getSender().getTrack());
        assertEquals("1", again.localId());
    }

    private RTCRtpTransceiver transceiver(String mid) {
        for (RTCRtpTransceiver transceiver : handler().peerConnection().getTransceivers()) {
            if (mid.equals(transceiver.getMid())) {
                return transceiver;
            }
        }
        throw new AssertionError("no transceiver with mid " + mid);
    }

    @Test
    void pausingAndResumingSendingRenegotiatesTheDirection() {
        Producer audio = produce(audioTrack());
        Producer video = produce(videoTrack());

        await(handler().pauseSending(video.localId()));

        assertEquals(
                RTCRtpTransceiverDirection.INACTIVE,
                transceiver(video.localId()).getCurrentDirection());
        assertEquals(
                RTCRtpTransceiverDirection.SEND_ONLY,
                transceiver(audio.localId()).getCurrentDirection());
        assertEquals(RTCSignalingState.STABLE, handler().peerConnection().getSignalingState());

        await(handler().resumeSending(video.localId()));

        assertEquals(
                RTCRtpTransceiverDirection.SEND_ONLY,
                transceiver(video.localId()).getCurrentDirection());
        assertEquals(RTCSignalingState.STABLE, handler().peerConnection().getSignalingState());
    }

    @Test
    void aProducerWithZeroRtpOnPausePausesItsTransceiver() {
        Producer video = await(transport.produce(
                ProducerOptions.builder(videoTrack()).zeroRtpOnPause(true).build()));

        video.pause();
        // Waits for the pauseSending in the queue, by producing after it.
        produce(audioTrack());

        assertEquals(
                RTCRtpTransceiverDirection.INACTIVE,
                transceiver(video.localId()).getCurrentDirection());

        video.resume();
        produce(audioTrack());

        assertEquals(
                RTCRtpTransceiverDirection.SEND_ONLY,
                transceiver(video.localId()).getCurrentDirection());
    }

    @Test
    void replaceTrackChangesTheTrackOfTheSender() {
        Producer video = produce(videoTrack());
        MediaStreamTrack other = videoTrack();

        await(video.replaceTrack(other));

        assertEquals(
                other.getId(),
                transceiver(video.localId()).getSender().getTrack().getId());

        await(video.replaceTrack(null));

        assertNull(transceiver(video.localId()).getSender().getTrack());
        // Replacing the track does not renegotiate.
        assertEquals(RTCSignalingState.STABLE, handler().peerConnection().getSignalingState());
    }

    @Test
    void replacingTheTrackOfAnUnknownLocalIdFails() {
        CompletionException failure =
                assertThrows(CompletionException.class, () -> await(handler().replaceTrack("42", null)));

        assertInstanceOf(MediasoupClientException.class, failure.getCause());
    }

    @Test
    void pausingOrResumingAnUnknownLocalIdFails() {
        CompletionException pause =
                assertThrows(CompletionException.class, () -> await(handler().pauseSending("42")));
        CompletionException resume =
                assertThrows(CompletionException.class, () -> await(handler().resumeSending("42")));

        assertInstanceOf(MediasoupClientException.class, pause.getCause());
        assertInstanceOf(MediasoupClientException.class, resume.getCause());
    }

    @Test
    void pausingSendingOnAClosedHandlerFails() {
        handler().close();

        CompletionException failure =
                assertThrows(CompletionException.class, () -> await(handler().pauseSending("0")));

        assertInstanceOf(InvalidStateException.class, failure.getCause());
    }

    @Test
    void stopSendingAnUnknownLocalIdFails() {
        CompletionException failure =
                assertThrows(CompletionException.class, () -> await(handler().stopSending("42")));

        assertInstanceOf(MediasoupClientException.class, failure.getCause());
    }

    @Test
    void stopSendingOnAClosedHandlerDoesNothing() {
        handler().close();

        await(handler().stopSending("42"));
    }

    @Test
    void sendFailsOnAClosedHandler() {
        handler().close();
        var options = new HandlerSendOptions(audioTrack(), null, List.of(), null, null);

        CompletionException failure =
                assertThrows(CompletionException.class, () -> await(handler().send(options)));

        assertInstanceOf(InvalidStateException.class, failure.getCause());
    }

    @Test
    void encodingsAreConverted() {
        var rtcEncoding = WebRtcJavaHandler.toRtcRtpEncodingParameters(RtpEncodingParameters.builder()
                .active(false)
                .maxBitrate(500_000)
                .maxFramerate(15.0)
                .scaleResolutionDownBy(2.0)
                .scalabilityMode("L1T3")
                .priority(Priority.VERY_LOW)
                .networkPriority(Priority.MEDIUM)
                .build());

        assertEquals(false, rtcEncoding.active);
        assertEquals(500_000, rtcEncoding.maxBitrate);
        assertEquals(15.0, rtcEncoding.maxFramerate);
        assertEquals(2.0, rtcEncoding.scaleResolutionDownBy);
        assertEquals("L1T3", rtcEncoding.scalabilityMode);
        assertEquals(0.5, rtcEncoding.bitratePriority);
        assertEquals(dev.onvoid.webrtc.RTCPriorityType.MEDIUM, rtcEncoding.networkPriority);
        assertNull(rtcEncoding.rid);
    }
}

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
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCIceTransportPolicy;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCRtpEncodingParameters;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCRtpTransceiverDirection;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.RTCSignalingState;
import dev.onvoid.webrtc.RTCStats;
import dev.onvoid.webrtc.RTCStatsReport;
import dev.onvoid.webrtc.RTCStatsType;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;
import io.github.hectorespert.jmediasoup.DataProducer;
import io.github.hectorespert.jmediasoup.DataProducerOptions;
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
import io.github.hectorespert.jmediasoup.internal.sdptransform.JavaScript;
import io.github.hectorespert.jmediasoup.internal.sdptransform.Parser;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.Priority;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecCapability;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpEncodingParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
import io.github.hectorespert.jmediasoup.testfixtures.ColorVideoSource;
import io.github.hectorespert.jmediasoup.testfixtures.ToneAudioSource;
import io.github.hectorespert.jmediasoup.transport.DtlsParameters;
import io.github.hectorespert.jmediasoup.transport.DtlsRole;
import io.github.hectorespert.jmediasoup.transport.IceParameters;
import io.github.hectorespert.jmediasoup.transport.IceServer;
import io.github.hectorespert.jmediasoup.transport.IceTransportPolicy;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
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
                request -> CompletableFuture.completedFuture(UUID.randomUUID().toString()),
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

    private DataProducer produceData(DataProducerOptions options) {
        return await(transport.produceData(options));
    }

    @Test
    void produceDataNegotiatesTheSctpAssociationOnce() {
        DataProducer first =
                produceData(DataProducerOptions.builder().label("chat").build());
        String remoteSdp = handler().peerConnection().getRemoteDescription().sdp;
        DataProducer second =
                produceData(DataProducerOptions.builder().maxRetransmits(2).build());

        // The first data channel connects the transport, as DTLS client.
        assertEquals(1, connects.size());
        assertEquals(DtlsRole.CLIENT, connects.getFirst().role());

        assertTrue(handler().peerConnection().getLocalDescription().sdp.contains("m=application"));
        assertTrue(remoteSdp.contains("UDP/DTLS/SCTP webrtc-datachannel"), remoteSdp);
        assertTrue(remoteSdp.contains("a=sctp-port:5000"), remoteSdp);
        // The second one needs no negotiation.
        assertEquals(remoteSdp, handler().peerConnection().getRemoteDescription().sdp);
        assertEquals(RTCSignalingState.STABLE, handler().peerConnection().getSignalingState());

        // Each one has the next stream, and its reliability reaches the native channel.
        assertEquals(0, first.sctpStreamParameters().streamId());
        assertEquals(true, first.sctpStreamParameters().ordered());
        assertEquals("chat", first.label());
        assertEquals(1, second.sctpStreamParameters().streamId());
        assertEquals(false, second.sctpStreamParameters().ordered());
        assertEquals(2, second.sctpStreamParameters().maxRetransmits());
    }

    @Test
    void dataChannelsAndTracksShareTheTransport() {
        produceData(DataProducerOptions.builder().build());
        Producer audio = produce(audioTrack());

        String remoteSdp = handler().peerConnection().getRemoteDescription().sdp;
        assertEquals("1", audio.localId());
        assertTrue(remoteSdp.contains("a=group:BUNDLE 0 1"), remoteSdp);
        assertTrue(remoteSdp.contains("m=application"), remoteSdp);
        assertEquals(1, connects.size());
    }

    @Test
    void closingTheTransportClosesTheDataProducers() {
        DataProducer dataProducer = produceData(DataProducerOptions.builder().build());

        transport.close();

        assertTrue(dataProducer.closed());
        assertEquals(dev.onvoid.webrtc.RTCDataChannelState.CLOSED, dataProducer.readyState());
    }

    @Test
    void sendDataChannelFailsOnAClosedHandler() {
        handler().close();

        Throwable error = assertThrows(
                        CompletionException.class,
                        () -> await(transport.produceData(
                                DataProducerOptions.builder().build())))
                .getCause();
        assertInstanceOf(InvalidStateException.class, error);
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
    void severalEncodingsAreSentInSimulcast() {
        Producer video = await(transport.produce(ProducerOptions.builder(videoTrack())
                .encodings(List.of(
                        RtpEncodingParameters.builder()
                                .scaleResolutionDownBy(4.0)
                                .maxBitrate(100_000)
                                .build(),
                        RtpEncodingParameters.builder()
                                .scaleResolutionDownBy(2.0)
                                .maxBitrate(300_000)
                                .build(),
                        RtpEncodingParameters.builder().maxBitrate(900_000).build()))
                .build()));

        // The encodings as given, with a rid and three temporal layers each, and no SSRC.
        List<RtpEncodingParameters> encodings = video.rtpParameters().encodings();
        assertEquals(
                List.of("r0", "r1", "r2"),
                encodings.stream().map(RtpEncodingParameters::rid).toList());
        assertTrue(encodings.stream().allMatch(e -> "L1T3".equals(e.scalabilityMode())), encodings.toString());
        assertTrue(encodings.stream().allMatch(e -> e.ssrc() == null), encodings.toString());
        assertEquals(4.0, encodings.getFirst().scaleResolutionDownBy());

        // The sender has them.
        var senderEncodings = video.rtpSender().getParameters().encodings;
        assertEquals(
                List.of("r0", "r1", "r2"),
                senderEncodings.stream().map(e -> e.rid).toList());
        assertEquals(300_000, senderEncodings.get(1).maxBitrate);

        // And the answer receives them.
        Map<String, Object> answer = answerSection(video.localId());
        assertEquals("recv", ((Map<?, ?>) answer.get("simulcast")).get("dir1"));
        assertEquals("r0;r1;r2", ((Map<?, ?>) answer.get("simulcast")).get("list1"));
        assertEquals(RTCSignalingState.STABLE, handler().peerConnection().getSignalingState());
    }

    private Producer produceSimulcast() {
        return await(transport.produce(ProducerOptions.builder(videoTrack())
                .encodings(List.of(
                        RtpEncodingParameters.builder()
                                .scaleResolutionDownBy(4.0)
                                .build(),
                        RtpEncodingParameters.builder()
                                .scaleResolutionDownBy(2.0)
                                .build(),
                        RtpEncodingParameters.builder().build()))
                .build()));
    }

    private static List<Boolean> active(Producer producer) {
        return producer.rtpSender().getParameters().encodings.stream()
                .map(e -> e.active)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private String answerSimulcast(Producer producer) {
        return (String) ((Map<String, Object>) answerSection(producer.localId()).get("simulcast")).get("list1");
    }

    @Test
    void setMaxSpatialLayerDeactivatesTheEncodingsAboveIt() {
        Producer video = produceSimulcast();

        await(video.setMaxSpatialLayer(1));

        assertEquals(List.of(true, true, false), active(video));
        assertEquals("r0;r1;~r2", answerSimulcast(video));

        await(video.setMaxSpatialLayer(2));

        assertEquals(List.of(true, true, true), active(video));
        assertEquals("r0;r1;r2", answerSimulcast(video));
        assertEquals(RTCSignalingState.STABLE, handler().peerConnection().getSignalingState());
    }

    @Test
    void setRtpEncodingParametersChangesEveryEncoding() {
        Producer video = produceSimulcast();
        var params = new RTCRtpEncodingParameters();
        params.maxBitrate = 200_000;
        params.maxFramerate = 15.0;

        await(video.setRtpEncodingParameters(params));

        var encodings = video.rtpSender().getParameters().encodings;
        assertEquals(
                List.of(200_000, 200_000, 200_000),
                encodings.stream().map(e -> e.maxBitrate).toList());
        assertEquals(
                List.of(15.0, 15.0, 15.0),
                encodings.stream().map(e -> e.maxFramerate).toList());
        // What is not given stays.
        assertEquals(4.0, encodings.getFirst().scaleResolutionDownBy);

        // Deactivating them all pauses every stream of the answer.
        var inactive = new RTCRtpEncodingParameters();
        inactive.active = false;
        await(video.setRtpEncodingParameters(inactive));

        assertEquals(List.of(false, false, false), active(video));
        assertEquals("~r0;~r1;~r2", answerSimulcast(video));
    }

    @Test
    void changingTheEncodingsOfAnUnknownLocalIdFails() {
        produce(videoTrack());

        CompletionException maxSpatialLayer =
                assertThrows(CompletionException.class, () -> await(handler().setMaxSpatialLayer("9", 0)));
        CompletionException encodingParameters = assertThrows(
                CompletionException.class,
                () -> await(handler().setRtpEncodingParameters("9", new RTCRtpEncodingParameters())));

        assertInstanceOf(MediasoupClientException.class, maxSpatialLayer.getCause());
        assertInstanceOf(MediasoupClientException.class, encodingParameters.getCause());
    }

    @Test
    void aSingleEncodingKeepsItsScalabilityMode() {
        Producer video = await(transport.produce(ProducerOptions.builder(videoTrack())
                .encodings(List.of(
                        RtpEncodingParameters.builder().scalabilityMode("L1T3").build()))
                .build()));

        assertEquals("L1T3", video.rtpParameters().encodings().getFirst().scalabilityMode());
        // The answer has the x-google-start-bitrate of the router, with which the encoder factory of
        // webrtc-java 0.20.0 on Linux and Windows drops the scalability mode; that of macOS keeps it.
        // This fails once it no longer drops it. The payload type of the codec depends on the natives:
        // on macOS VideoToolbox lists its codecs first.
        int payloadType = video.rtpParameters().codecs().getFirst().payloadType();
        assertTrue(answerParameters(video.localId(), payloadType).containsKey("x-google-start-bitrate"));
        boolean macos = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("mac");
        assertEquals(
                macos ? "L1T3" : "L1T1",
                video.rtpSender().getParameters().encodings.getFirst().scalabilityMode);
    }

    @Test
    void simulcastEncodingsShareTheHighestNumberOfTemporalLayers() {
        assertEquals(
                List.of("L1T3", "L1T3"),
                WebRtcJavaHandler.simulcastEncodings(List.of(
                                RtpEncodingParameters.builder()
                                        .scalabilityMode("L1T2")
                                        .build(),
                                RtpEncodingParameters.builder().build()))
                        .stream()
                        .map(RtpEncodingParameters::scalabilityMode)
                        .toList());
        assertEquals(
                List.of("L1T2", "L1T2"),
                WebRtcJavaHandler.simulcastEncodings(List.of(
                                RtpEncodingParameters.builder()
                                        .scalabilityMode("L1T2")
                                        .build(),
                                RtpEncodingParameters.builder()
                                        .scalabilityMode("L1T1")
                                        .build()))
                        .stream()
                        .map(RtpEncodingParameters::scalabilityMode)
                        .toList());
    }

    // A section of the remote description, the answer of mediasoup.
    @SuppressWarnings("unchecked")
    private Map<String, Object> answerSection(String mid) {
        RTCSessionDescription answer = handler().peerConnection().getRemoteDescription();
        return ((List<Map<String, Object>>) Parser.parse(answer.sdp).get("media"))
                .stream()
                        .filter(m -> JavaScript.string(m.get("mid")).equals(mid))
                        .findFirst()
                        .orElseThrow();
    }

    // The parameters of a codec in the fmtp of a section of the answer.
    @SuppressWarnings("unchecked")
    private Map<String, @Nullable Object> answerParameters(String mid, int payloadType) {
        return ((List<Map<String, Object>>) answerSection(mid).get("fmtp"))
                .stream()
                        .filter(fmtp -> ((Number) fmtp.get("payload")).intValue() == payloadType)
                        .map(fmtp -> Parser.parseParams(JavaScript.string(fmtp.get("config"))))
                        .findFirst()
                        .orElseThrow();
    }

    @Test
    void opusOptionsGoToTheAnswerAndToTheRtpParameters() {
        Producer audio = await(transport.produce(ProducerOptions.builder(audioTrack())
                .codecOptions(ProducerCodecOptions.builder()
                        .opusStereo(true)
                        .opusFec(false)
                        .opusDtx(true)
                        .opusMaxPlaybackRate(24000)
                        .opusMaxAverageBitrate(64000)
                        .opusPtime(20)
                        .build())
                .build()));

        RtpCodecParameters codec = audio.rtpParameters().codecs().getFirst();
        Map<String, @Nullable Object> answer = answerParameters(audio.localId(), codec.payloadType());
        assertEquals(1, answer.get("stereo"));
        assertEquals(0, answer.get("useinbandfec"));
        assertEquals(1, answer.get("usedtx"));
        assertEquals(24000, answer.get("maxplaybackrate"));
        assertEquals(64000, answer.get("maxaveragebitrate"));
        // libwebrtc moves the ptime of the fmtp to its own line.
        assertEquals(20, ((Number) answerSection(audio.localId()).get("ptime")).intValue());

        // What mediasoup receives in produce.
        assertEquals(1, codec.parameters().get("sprop-stereo"));
        assertEquals(0, codec.parameters().get("useinbandfec"));
        assertEquals(1, codec.parameters().get("usedtx"));
        assertEquals(20, codec.parameters().get("ptime"));
        assertTrue(codec.rtcpFeedback().stream().noneMatch(fb -> fb.type().equals("nack")));
    }

    @Test
    void videoOptionsGoToTheAnswer() {
        Producer video = await(transport.produce(ProducerOptions.builder(videoTrack())
                .codecOptions(ProducerCodecOptions.builder()
                        .videoGoogleStartBitrate(1000)
                        .videoGoogleMaxBitrate(3000)
                        .videoGoogleMinBitrate(500)
                        .build())
                .build()));

        RtpCodecParameters codec = video.rtpParameters().codecs().getFirst();
        Map<String, @Nullable Object> answer = answerParameters(video.localId(), codec.payloadType());
        assertEquals(1000, answer.get("x-google-start-bitrate"));
        assertEquals(3000, answer.get("x-google-max-bitrate"));
        assertEquals(500, answer.get("x-google-min-bitrate"));
        assertFalse(codec.parameters().containsKey("x-google-start-bitrate"));
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
    void statsComeFromThePeerConnectionAndTheSender() {
        Producer video = produce(videoTrack());

        RTCStatsReport transportStats = await(transport.getStats());
        RTCStatsReport senderStats = await(video.getStats());

        assertTrue(
                types(transportStats).contains(RTCStatsType.PEER_CONNECTION),
                types(transportStats).toString());
        assertTrue(
                types(senderStats).contains(RTCStatsType.OUTBOUND_RTP),
                types(senderStats).toString());
        assertFalse(
                types(senderStats).contains(RTCStatsType.PEER_CONNECTION),
                types(senderStats).toString());
    }

    @Test
    void senderStatsOfAnUnknownLocalIdFail() {
        CompletionException failure =
                assertThrows(CompletionException.class, () -> await(handler().getSenderStats("42")));

        assertInstanceOf(MediasoupClientException.class, failure.getCause());
    }

    private static java.util.Set<RTCStatsType> types(RTCStatsReport report) {
        return report.getStats().values().stream().map(RTCStats::getType).collect(java.util.stream.Collectors.toSet());
    }

    private static String iceUfrag(RTCSessionDescription description) {
        return description
                .sdp
                .lines()
                .filter(line -> line.startsWith("a=ice-ufrag:"))
                .findFirst()
                .orElseThrow()
                .substring("a=ice-ufrag:".length());
    }

    @Test
    void restartIceNegotiatesNewIceParameters() {
        produce(audioTrack());
        String localUfrag = iceUfrag(handler().peerConnection().getLocalDescription());

        await(transport.restartIce(new IceParameters("new-ufrag", "new-password-1234567890", true)));

        RTCPeerConnection pc = handler().peerConnection();
        assertNotEquals(localUfrag, iceUfrag(pc.getLocalDescription()));
        assertEquals("new-ufrag", iceUfrag(pc.getRemoteDescription()));
        assertEquals(RTCSignalingState.STABLE, pc.getSignalingState());
    }

    @Test
    void restartIceBeforeConnectingOnlyKeepsTheParameters() {
        await(transport.restartIce(new IceParameters("new-ufrag", "new-password-1234567890", true)));

        // Nothing is negotiated: the remote SDP has no media section yet.
        assertNull(handler().peerConnection().getLocalDescription());

        // The first send uses them.
        produce(audioTrack());

        assertEquals("new-ufrag", iceUfrag(handler().peerConnection().getRemoteDescription()));
    }

    @Test
    void updateIceServersChangesTheConfiguration() {
        await(transport.updateIceServers(List.of(IceServer.of("stun:stun.example.com:3478"))));

        List<RTCIceServer> servers = handler().peerConnection().getConfiguration().iceServers;
        assertEquals(1, servers.size());
        assertEquals(List.of("stun:stun.example.com:3478"), servers.getFirst().urls);
    }

    @Test
    void theIceTransportPolicyReachesThePeerConnection() throws Exception {
        var device = new Device(factory);
        await(device.load(FakeParameters.routerRtpCapabilities()));
        SendTransport relay = device.createSendTransport(
                FakeParameters.transportRemoteParameters().toBuilder()
                        .iceTransportPolicy(IceTransportPolicy.RELAY)
                        .build(),
                dtls -> CompletableFuture.completedFuture(null),
                request -> CompletableFuture.completedFuture(UUID.randomUUID().toString()));
        try {
            assertEquals(
                    RTCIceTransportPolicy.RELAY,
                    ((WebRtcJavaHandler) relay.handler()).peerConnection().getConfiguration().iceTransportPolicy);
        } finally {
            relay.close();
        }
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

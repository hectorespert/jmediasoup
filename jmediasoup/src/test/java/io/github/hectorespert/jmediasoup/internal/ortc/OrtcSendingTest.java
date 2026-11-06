/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.ortc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hectorespert.jmediasoup.UnsupportedException;
import io.github.hectorespert.jmediasoup.fake.FakeParameters;
import io.github.hectorespert.jmediasoup.rtp.ExtendedCodec;
import io.github.hectorespert.jmediasoup.rtp.ExtendedHeaderExtension;
import io.github.hectorespert.jmediasoup.rtp.ExtendedRtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtcpFeedback;
import io.github.hectorespert.jmediasoup.rtp.RtcpParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecCapability;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpEncodingParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpHeaderExtensionDirection;
import io.github.hectorespert.jmediasoup.rtp.RtpHeaderExtensionParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrtcSendingTest {

    private static final String TRANSPORT_WIDE_CC =
            "http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01";
    private static final String ABS_SEND_TIME = "http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time";

    private static final RtcpFeedback NACK = new RtcpFeedback("nack", null);
    private static final RtcpFeedback PLI = new RtcpFeedback("nack", "pli");
    private static final RtcpFeedback FIR = new RtcpFeedback("ccm", "fir");
    private static final RtcpFeedback REMB = new RtcpFeedback("goog-remb", null);
    private static final RtcpFeedback TRANSPORT_CC = new RtcpFeedback("transport-cc", null);

    private static ExtendedRtpCapabilities fixtures() {
        return Ortc.getExtendedRtpCapabilities(
                FakeParameters.nativeRtpCapabilities(), FakeParameters.routerRtpCapabilities(), false);
    }

    private static ExtendedCodec vp8(Integer rtxPayloadType, RtcpFeedback... rtcpFeedback) {
        return new ExtendedCodec(
                MediaKind.VIDEO,
                "video/VP8",
                96,
                rtxPayloadType,
                101,
                rtxPayloadType == null ? null : 102,
                90000,
                null,
                Map.of("side", "local"),
                Map.of("side", "remote"),
                List.of(rtcpFeedback));
    }

    private static ExtendedHeaderExtension extension(
            MediaKind kind, String uri, int sendId, RtpHeaderExtensionDirection direction) {
        return new ExtendedHeaderExtension(kind, uri, sendId, 20 + sendId, false, direction);
    }

    private static RtpCodecParameters codec(String mimeType, int payloadType, Map<String, Object> parameters) {
        return new RtpCodecParameters(mimeType, payloadType, 90000, null, parameters, List.of());
    }

    private static RtpCodecParameters rtx(int payloadType, int apt) {
        return codec("video/rtx", payloadType, Map.of("apt", apt));
    }

    private static RtpCodecCapability capability(String mimeType, Map<String, Object> parameters) {
        return new RtpCodecCapability(MediaKind.VIDEO, mimeType, null, 90000, null, parameters, List.of());
    }

    @Test
    void sendingParametersFromTheFixturesUseTheLocalSide() {
        RtpParameters video = Ortc.getSendingRtpParameters(MediaKind.VIDEO, fixtures());

        assertNull(video.mid());
        assertTrue(video.encodings().isEmpty());
        assertEquals(new RtcpParameters(null, null, null), video.rtcp());
        assertEquals(
                List.of(
                        new RtpCodecParameters(
                                "video/VP8",
                                96,
                                90000,
                                null,
                                Map.of("baz", "1234abcd"),
                                List.of(REMB, TRANSPORT_CC, FIR, NACK, PLI)),
                        new RtpCodecParameters("video/rtx", 97, 90000, null, Map.of("apt", 96), List.of())),
                video.codecs());
        assertEquals(
                List.of(
                        new RtpHeaderExtensionParameters("urn:ietf:params:rtp-hdrext:sdes:mid", 1, false, Map.of()),
                        new RtpHeaderExtensionParameters(ABS_SEND_TIME, 3, false, Map.of()),
                        new RtpHeaderExtensionParameters(TRANSPORT_WIDE_CC, 5, false, Map.of()),
                        new RtpHeaderExtensionParameters("urn:3gpp:video-orientation", 4, false, Map.of()),
                        new RtpHeaderExtensionParameters("urn:ietf:params:rtp-hdrext:toffset", 2, false, Map.of())),
                video.headerExtensions());

        RtpParameters audio = Ortc.getSendingRtpParameters(MediaKind.AUDIO, fixtures());

        assertEquals(
                List.of("audio/opus"),
                audio.codecs().stream().map(RtpCodecParameters::mimeType).toList());
        assertEquals(111, audio.codecs().getFirst().payloadType());
        assertEquals(
                List.of(1, 10),
                audio.headerExtensions().stream()
                        .map(RtpHeaderExtensionParameters::id)
                        .toList());
    }

    @Test
    void sendingParametersOnlyHaveHeaderExtensionsOfTheKindThatCanBeSent() {
        var extended = new ExtendedRtpCapabilities(
                List.of(),
                List.of(
                        extension(MediaKind.VIDEO, "video", 1, RtpHeaderExtensionDirection.SENDRECV),
                        extension(MediaKind.AUDIO, "audio", 2, RtpHeaderExtensionDirection.SENDRECV),
                        extension(null, "any kind", 3, RtpHeaderExtensionDirection.SENDONLY),
                        extension(MediaKind.VIDEO, "recvonly", 4, RtpHeaderExtensionDirection.RECVONLY),
                        extension(MediaKind.VIDEO, "inactive", 5, RtpHeaderExtensionDirection.INACTIVE),
                        extension(MediaKind.VIDEO, "absent", 6, null)));

        for (RtpParameters parameters : List.of(
                Ortc.getSendingRtpParameters(MediaKind.VIDEO, extended),
                Ortc.getSendingRemoteRtpParameters(MediaKind.VIDEO, extended))) {
            assertEquals(
                    List.of("video", "any kind", "absent"),
                    parameters.headerExtensions().stream()
                            .map(RtpHeaderExtensionParameters::uri)
                            .toList());
        }
    }

    @Test
    void codecsWithoutRtxHaveNoRtxCodec() {
        var extended = new ExtendedRtpCapabilities(List.of(vp8(null)), List.of());

        assertEquals(
                List.of("video/VP8"),
                Ortc.getSendingRtpParameters(MediaKind.VIDEO, extended).codecs().stream()
                        .map(RtpCodecParameters::mimeType)
                        .toList());
    }

    @Test
    void remoteSendingParametersUseTheCodecParametersOfTheRouter() {
        var extended = new ExtendedRtpCapabilities(List.of(vp8(97)), List.of());

        RtpParameters local = Ortc.getSendingRtpParameters(MediaKind.VIDEO, extended);
        RtpParameters remote = Ortc.getSendingRemoteRtpParameters(MediaKind.VIDEO, extended);

        assertEquals(Map.of("side", "local"), local.codecs().getFirst().parameters());
        assertEquals(Map.of("side", "remote"), remote.codecs().getFirst().parameters());
        assertEquals(96, remote.codecs().getFirst().payloadType());
        assertEquals(rtx(97, 96), remote.codecs().getLast());
    }

    @Test
    void remoteSendingParametersUseTransportCcIfItsHeaderExtensionCanBeSent() {
        var extended = new ExtendedRtpCapabilities(
                List.of(vp8(97, NACK, REMB, TRANSPORT_CC)),
                List.of(
                        extension(MediaKind.VIDEO, ABS_SEND_TIME, 3, RtpHeaderExtensionDirection.SENDRECV),
                        extension(MediaKind.VIDEO, TRANSPORT_WIDE_CC, 5, RtpHeaderExtensionDirection.SENDRECV)));

        assertEquals(
                List.of(NACK, TRANSPORT_CC),
                Ortc.getSendingRemoteRtpParameters(MediaKind.VIDEO, extended)
                        .codecs()
                        .getFirst()
                        .rtcpFeedback());
        assertEquals(
                List.of(NACK, REMB, TRANSPORT_CC),
                Ortc.getSendingRtpParameters(MediaKind.VIDEO, extended)
                        .codecs()
                        .getFirst()
                        .rtcpFeedback());
    }

    @Test
    void remoteSendingParametersUseRembIfOnlyAbsSendTimeCanBeSent() {
        var extended = new ExtendedRtpCapabilities(
                List.of(vp8(97, NACK, REMB, TRANSPORT_CC)),
                List.of(
                        extension(MediaKind.VIDEO, ABS_SEND_TIME, 3, RtpHeaderExtensionDirection.SENDRECV),
                        extension(MediaKind.VIDEO, TRANSPORT_WIDE_CC, 5, RtpHeaderExtensionDirection.RECVONLY)));

        assertEquals(
                List.of(NACK, REMB),
                Ortc.getSendingRemoteRtpParameters(MediaKind.VIDEO, extended)
                        .codecs()
                        .getFirst()
                        .rtcpFeedback());
    }

    @Test
    void remoteSendingParametersUseNoCongestionControlWithoutItsHeaderExtensions() {
        var extended = new ExtendedRtpCapabilities(List.of(vp8(97, NACK, REMB, TRANSPORT_CC)), List.of());

        assertEquals(
                List.of(NACK),
                Ortc.getSendingRemoteRtpParameters(MediaKind.VIDEO, extended)
                        .codecs()
                        .getFirst()
                        .rtcpFeedback());
    }

    @Test
    void remoteSendingParametersFromTheFixtures() {
        assertEquals(
                List.of(TRANSPORT_CC, FIR, NACK, PLI),
                Ortc.getSendingRemoteRtpParameters(MediaKind.VIDEO, fixtures())
                        .codecs()
                        .getFirst()
                        .rtcpFeedback());
        assertEquals(
                List.of(),
                Ortc.getSendingRemoteRtpParameters(MediaKind.AUDIO, fixtures())
                        .codecs()
                        .getFirst()
                        .rtcpFeedback());
    }

    @Test
    void withoutChosenCodecTheFirstOneIsSentWithItsRtx() throws Exception {
        var vp8 = codec("video/VP8", 96, Map.of());
        var h264 = codec("video/H264", 98, Map.of());

        assertEquals(List.of(vp8, rtx(97, 96)), Ortc.reduceCodecs(List.of(vp8, rtx(97, 96), h264), null));
        assertEquals(List.of(vp8), Ortc.reduceCodecs(List.of(vp8, h264, rtx(99, 98)), null));
        assertEquals(List.of(vp8), Ortc.reduceCodecs(List.of(vp8), null));
    }

    @Test
    void theChosenCodecIsMatchedStrictly() throws Exception {
        var h264Mode0 = codec("video/H264", 96, Map.of("packetization-mode", 0, "profile-level-id", "42e01f"));
        var h264Mode1 = codec("video/H264", 98, Map.of("packetization-mode", 1, "profile-level-id", "42e01f"));
        List<RtpCodecParameters> codecs = List.of(h264Mode0, rtx(97, 96), h264Mode1, rtx(99, 98));

        assertEquals(
                List.of(h264Mode1, rtx(99, 98)),
                Ortc.reduceCodecs(
                        codecs,
                        capability("video/H264", Map.of("packetization-mode", 1, "profile-level-id", "42e01f"))));
    }

    @Test
    void onlyTheFirstMatchingCodecIsSentEvenWithoutRtx() throws Exception {
        var first = codec("video/VP8", 96, Map.of());
        var second = codec("video/VP8", 98, Map.of());

        assertEquals(
                List.of(first),
                Ortc.reduceCodecs(List.of(first, second, rtx(99, 98)), capability("video/vp8", Map.of())));
    }

    @Test
    void aChosenCodecThatCannotBeSentIsUnsupported() {
        List<RtpCodecParameters> codecs = List.of(codec("video/VP8", 96, Map.of()), rtx(97, 96));

        assertThrows(UnsupportedException.class, () -> Ortc.reduceCodecs(codecs, capability("video/VP9", Map.of())));
        assertThrows(UnsupportedException.class, () -> Ortc.reduceCodecs(List.of(), null));
    }

    @Test
    void probatorParametersTakeTheFirstVideoCodec() {
        RtpParameters video =
                FakeParameters.consumerRemoteParameters("video/VP8").rtpParameters();

        RtpParameters probator = Ortc.generateProbatorRtpParameters(video);

        RtpCodecParameters vp8 = video.codecs().getFirst();
        assertEquals("probator", probator.mid());
        assertEquals(
                List.of(new RtpCodecParameters(
                        vp8.mimeType(), 127, vp8.clockRate(), vp8.channels(), vp8.parameters(), vp8.rtcpFeedback())),
                probator.codecs());
        assertEquals(video.headerExtensions(), probator.headerExtensions());
        assertEquals(List.of(RtpEncodingParameters.builder().ssrc(1234L).build()), probator.encodings());
        assertEquals(new RtcpParameters("probator", null, null), probator.rtcp());
        assertNull(probator.msid());
    }

    @Test
    void probatorParametersNeedAVideoCodec() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Ortc.generateProbatorRtpParameters(new RtpParameters(null, null, null, null, null, null)));
    }
}

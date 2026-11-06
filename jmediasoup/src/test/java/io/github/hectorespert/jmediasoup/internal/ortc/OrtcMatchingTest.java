/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.ortc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hectorespert.jmediasoup.fake.FakeParameters;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtcpFeedback;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecCapability;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpHeaderExtension;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrtcMatchingTest {

    private static RtpCodecCapability codec(
            String mimeType, int clockRate, Integer channels, Map<String, Object> parameters) {
        MediaKind kind = mimeType.toLowerCase().startsWith("audio/") ? MediaKind.AUDIO : MediaKind.VIDEO;
        return new RtpCodecCapability(kind, mimeType, 100, clockRate, channels, parameters, List.of());
    }

    private static RtpCodecCapability h264(Map<String, Object> parameters) {
        return codec("video/H264", 90000, null, parameters);
    }

    @Test
    void probatorConstants() {
        assertEquals("probator", Ortc.RTP_PROBATOR_MID);
        assertEquals(1234, Ortc.RTP_PROBATOR_SSRC);
        assertEquals(127, Ortc.RTP_PROBATOR_CODEC_PAYLOAD_TYPE);
    }

    @Test
    void rtxCodecsAreRecognizedInAnyCase() {
        assertTrue(Ortc.isRtxCodec(codec("video/rtx", 90000, null, Map.of("apt", 101))));
        assertTrue(Ortc.isRtxCodec(new RtpCodecParameters("audio/RTX", 97, 48000, null, null, null)));
        assertFalse(Ortc.isRtxCodec(codec("video/VP8", 90000, null, Map.of())));
        assertFalse(Ortc.isRtxCodec(null));
    }

    @Test
    void codecsMatchByMimeTypeInAnyCaseAndClockRate() {
        assertNotNull(Ortc.matchCodecs(
                codec("video/VP8", 90000, null, Map.of()), codec("video/vp8", 90000, null, Map.of()), true));
        assertNull(Ortc.matchCodecs(
                codec("video/VP8", 90000, null, Map.of()), codec("video/VP9", 90000, null, Map.of()), true));
        assertNull(Ortc.matchCodecs(
                codec("audio/opus", 48000, 2, Map.of()), codec("audio/opus", 16000, 2, Map.of()), false));
    }

    @Test
    void audioWithoutChannelsHasOneAndVideoChannelsAreIgnored() {
        assertNotNull(Ortc.matchCodecs(
                codec("audio/PCMU", 8000, null, Map.of()), codec("audio/PCMU", 8000, 1, Map.of()), false));
        assertNull(Ortc.matchCodecs(
                codec("audio/opus", 48000, null, Map.of()), codec("audio/opus", 48000, 2, Map.of()), false));
        assertNotNull(Ortc.matchCodecs(
                codec("video/VP8", 90000, 1, Map.of()), codec("video/VP8", 90000, null, Map.of()), false));
    }

    @Test
    void codecParametersAndRtpCodecParametersMatchEachOther() {
        var parameters = new RtpCodecParameters("audio/opus", 111, 48000, 2, Map.of("useinbandfec", 1), List.of());

        CodecMatch match = Ortc.matchCodecs(parameters, codec("audio/opus", 48000, 2, Map.of()), true);

        assertEquals(Map.of("useinbandfec", 1), match.aParameters());
    }

    @Test
    void h264PacketizationModeIsOnlyComparedInStrictMode() {
        RtpCodecCapability mode0 = h264(Map.of("profile-level-id", "42e01f"));
        RtpCodecCapability mode1 = h264(Map.of("packetization-mode", 1, "profile-level-id", "42e01f"));

        assertNotNull(Ortc.matchCodecs(mode0, mode1, false));
        assertNull(Ortc.matchCodecs(mode0, mode1, true));
        assertNotNull(
                Ortc.matchCodecs(mode1, h264(Map.of("packetization-mode", "1", "profile-level-id", "42e01f")), true));
    }

    @Test
    void h264StrictNeedsTheSameProfile() {
        RtpCodecCapability constrainedBaseline = h264(Map.of("packetization-mode", 1, "profile-level-id", "42e01f"));
        RtpCodecCapability high = h264(Map.of("packetization-mode", 1, "profile-level-id", "64001f"));

        assertNull(Ortc.matchCodecs(constrainedBaseline, high, true));
        assertNotNull(Ortc.matchCodecs(constrainedBaseline, high, false));
    }

    @Test
    void h264StrictNegotiatesTheLevelForBothCodecsWithoutChangingThem() {
        RtpCodecCapability remote = h264(Map.of("packetization-mode", 1, "profile-level-id", "42e01f"));
        var localParameters = new LinkedHashMap<String, Object>();
        localParameters.put("packetization-mode", 1);
        localParameters.put("profile-level-id", "42e015");
        localParameters.put("x", "y");
        RtpCodecCapability local = h264(localParameters);

        CodecMatch match = Ortc.matchCodecs(remote, local, true);

        // Without level-asymmetry-allowed on both sides, the answer takes the lower level.
        assertEquals("42e015", match.aParameters().get("profile-level-id"));
        assertEquals("42e015", match.bParameters().get("profile-level-id"));
        assertEquals(
                List.of("packetization-mode", "profile-level-id", "x"),
                List.copyOf(match.bParameters().keySet()));
        assertEquals("42e01f", remote.parameters().get("profile-level-id"));
    }

    @Test
    void h264WithLevelAsymmetryKeepsTheLevelOfTheFirstCodec() {
        RtpCodecCapability a =
                h264(Map.of("level-asymmetry-allowed", 1, "packetization-mode", 1, "profile-level-id", "42e01f"));
        RtpCodecCapability b =
                h264(Map.of("level-asymmetry-allowed", 1, "packetization-mode", 1, "profile-level-id", "42e015"));

        assertEquals("42e01f", Ortc.matchCodecs(a, b, true).bParameters().get("profile-level-id"));
    }

    @Test
    void h264WithoutProfileLevelIdOnEitherSideMatchesWithoutOne() {
        CodecMatch match = Ortc.matchCodecs(h264(Map.of()), h264(Map.of()), true);

        assertEquals(Map.of(), match.aParameters());
        assertEquals(Map.of(), match.bParameters());
    }

    @Test
    void vp9StrictComparesTheProfileWithZeroByDefault() {
        RtpCodecCapability profile0 = codec("video/VP9", 90000, null, Map.of());
        RtpCodecCapability explicitProfile0 = codec("video/VP9", 90000, null, Map.of("profile-id", "0"));
        RtpCodecCapability profile2 = codec("video/VP9", 90000, null, Map.of("profile-id", 2));

        assertNotNull(Ortc.matchCodecs(profile0, explicitProfile0, true));
        assertNull(Ortc.matchCodecs(profile0, profile2, true));
        assertNotNull(Ortc.matchCodecs(profile0, profile2, false));
    }

    @Test
    void rtcpFeedbackKeepsWhatBothSupportInTheOrderOfTheFirst() {
        RtpCodecCapability routerVp8 =
                FakeParameters.routerRtpCapabilities().codecs().get(1);
        RtpCodecCapability nativeVp8 =
                FakeParameters.nativeRtpCapabilities().codecs().get(3);

        assertEquals(
                List.of(
                        new RtcpFeedback("nack", null),
                        new RtcpFeedback("nack", "pli"),
                        new RtcpFeedback("ccm", "fir"),
                        new RtcpFeedback("goog-remb", null),
                        new RtcpFeedback("transport-cc", null)),
                Ortc.reduceRtcpFeedback(routerVp8, nativeVp8));
    }

    @Test
    void rtcpFeedbackTreatsAnEmptyParameterAsAbsent() {
        var a = new RtpCodecParameters("video/VP8", 96, 90000, null, null, List.of(new RtcpFeedback("nack", "")));
        var b = new RtpCodecParameters("video/VP8", 96, 90000, null, null, List.of(new RtcpFeedback("nack", null)));

        assertEquals(List.of(new RtcpFeedback("nack", null)), Ortc.reduceRtcpFeedback(a, b));
        assertEquals(
                List.of(),
                Ortc.reduceRtcpFeedback(
                        a,
                        new RtpCodecParameters(
                                "video/VP8", 96, 90000, null, null, List.of(new RtcpFeedback("nack", "pli")))));
    }

    @Test
    void headerExtensionsMatchByUriAndKindIfBothHaveOne() {
        var audioMid = new RtpHeaderExtension(MediaKind.AUDIO, "urn:ietf:params:rtp-hdrext:sdes:mid", 1, null, null);
        var videoMid = new RtpHeaderExtension(MediaKind.VIDEO, "urn:ietf:params:rtp-hdrext:sdes:mid", 1, null, null);
        var anyMid = new RtpHeaderExtension(null, "urn:ietf:params:rtp-hdrext:sdes:mid", 3, null, null);
        var audioLevel =
                new RtpHeaderExtension(MediaKind.AUDIO, "urn:ietf:params:rtp-hdrext:ssrc-audio-level", 10, null, null);

        assertFalse(Ortc.matchHeaderExtensions(audioMid, videoMid));
        assertTrue(Ortc.matchHeaderExtensions(audioMid, anyMid));
        assertFalse(Ortc.matchHeaderExtensions(audioMid, audioLevel));
    }
}

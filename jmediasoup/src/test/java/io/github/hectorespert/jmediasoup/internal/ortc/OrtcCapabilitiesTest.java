/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.ortc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hectorespert.jmediasoup.fake.FakeParameters;
import io.github.hectorespert.jmediasoup.rtp.ExtendedCodec;
import io.github.hectorespert.jmediasoup.rtp.ExtendedHeaderExtension;
import io.github.hectorespert.jmediasoup.rtp.ExtendedRtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtcpFeedback;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecCapability;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpHeaderExtension;
import io.github.hectorespert.jmediasoup.rtp.RtpHeaderExtensionDirection;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class OrtcCapabilitiesTest {

    private static final String MID = "urn:ietf:params:rtp-hdrext:sdes:mid";

    private static RtpCodecCapability codec(
            String mimeType, @Nullable Integer payloadType, Map<String, Object> parameters) {
        boolean audio = mimeType.startsWith("audio/");
        return new RtpCodecCapability(
                audio ? MediaKind.AUDIO : MediaKind.VIDEO,
                mimeType,
                payloadType,
                audio ? 48000 : 90000,
                audio ? 2 : null,
                parameters,
                List.of());
    }

    private static RtpCodecCapability rtx(int payloadType, int apt) {
        return codec("video/rtx", payloadType, Map.of("apt", apt));
    }

    private static RtpCapabilities caps(List<RtpCodecCapability> codecs) {
        return new RtpCapabilities(codecs, List.of(), List.of());
    }

    private static RtpCapabilities caps(RtpHeaderExtension... headerExtensions) {
        return new RtpCapabilities(List.of(), List.of(headerExtensions), List.of());
    }

    private static ExtendedRtpCapabilities fixtures() {
        return Ortc.getExtendedRtpCapabilities(
                FakeParameters.nativeRtpCapabilities(), FakeParameters.routerRtpCapabilities(), false);
    }

    @Test
    void codecsFromTheFixturesKeepTheOrderOfTheRouter() {
        List<ExtendedCodec> codecs = fixtures().codecs();

        assertEquals(2, codecs.size());
        ExtendedCodec opus = codecs.get(0);
        assertEquals("audio/opus", opus.mimeType());
        assertEquals(111, opus.localPayloadType());
        assertEquals(100, opus.remotePayloadType());
        assertNull(opus.localRtxPayloadType());
        assertNull(opus.remoteRtxPayloadType());
        assertEquals(2, opus.channels());
        assertEquals(Map.of("minptime", 10, "useinbandfec", 1), opus.localParameters());
        assertEquals(Map.of("useinbandfec", 1, "foo", "bar"), opus.remoteParameters());

        ExtendedCodec vp8 = codecs.get(1);
        assertEquals("video/VP8", vp8.mimeType());
        assertEquals(96, vp8.localPayloadType());
        assertEquals(97, vp8.localRtxPayloadType());
        assertEquals(101, vp8.remotePayloadType());
        assertEquals(102, vp8.remoteRtxPayloadType());
    }

    @Test
    void headerExtensionsFromTheFixturesKeepTheOrderOfTheRouter() {
        List<String> uris = fixtures().headerExtensions().stream()
                .map(extension -> extension.kind().value() + " " + extension.uri())
                .toList();

        assertEquals(
                List.of(
                        "audio urn:ietf:params:rtp-hdrext:sdes:mid",
                        "video urn:ietf:params:rtp-hdrext:sdes:mid",
                        "video http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time",
                        "video http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01",
                        "audio urn:ietf:params:rtp-hdrext:ssrc-audio-level",
                        "video urn:3gpp:video-orientation",
                        "video urn:ietf:params:rtp-hdrext:toffset"),
                uris);
        ExtendedHeaderExtension toffset = fixtures().headerExtensions().getLast();
        assertEquals(2, toffset.sendId());
        assertEquals(12, toffset.recvId());
        assertFalse(toffset.encrypt());
        assertEquals(RtpHeaderExtensionDirection.SENDRECV, toffset.direction());
    }

    @Test
    void codecsCanKeepTheOrderOfTheLocalSide() {
        var local = caps(List.of(codec("video/VP8", 96, Map.of()), codec("audio/opus", 111, Map.of())));
        var remote = caps(List.of(codec("audio/opus", 100, Map.of()), codec("video/VP8", 101, Map.of())));

        assertEquals(
                List.of("video/VP8", "audio/opus"),
                Ortc.getExtendedRtpCapabilities(local, remote, true).codecs().stream()
                        .map(ExtendedCodec::mimeType)
                        .toList());
        assertEquals(
                List.of("audio/opus", "video/VP8"),
                Ortc.getExtendedRtpCapabilities(local, remote, false).codecs().stream()
                        .map(ExtendedCodec::mimeType)
                        .toList());
    }

    @Test
    void h264CodecsCarryTheNegotiatedProfileLevelIdOnEachSide() {
        var local = caps(List.of(codec(
                "video/H264", 96, Map.of("packetization-mode", 1, "profile-level-id", "42e034", "side", "local"))));
        var remote = caps(List.of(codec(
                "video/H264", 103, Map.of("packetization-mode", 1, "profile-level-id", "42e01f", "side", "remote"))));

        for (boolean preferLocalCodecsOrder : List.of(true, false)) {
            ExtendedCodec h264 = Ortc.getExtendedRtpCapabilities(local, remote, preferLocalCodecsOrder)
                    .codecs()
                    .getFirst();

            assertEquals(
                    Map.of("packetization-mode", 1, "profile-level-id", "42e01f", "side", "local"),
                    h264.localParameters());
            assertEquals(
                    Map.of("packetization-mode", 1, "profile-level-id", "42e01f", "side", "remote"),
                    h264.remoteParameters());
        }
    }

    @Test
    void codecsAreMatchedStrictly() {
        var local = caps(List.of(codec("video/VP9", 98, Map.of("profile-id", 2))));
        var remote = caps(List.of(codec("video/VP9", 103, Map.of())));

        assertTrue(
                Ortc.getExtendedRtpCapabilities(local, remote, false).codecs().isEmpty());
    }

    @Test
    void rtxIsOnlySetIfBothSidesHaveIt() {
        var local = caps(List.of(codec("video/VP8", 96, Map.of())));
        var remote = caps(List.of(codec("video/VP8", 101, Map.of()), rtx(102, 101)));

        ExtendedCodec vp8 =
                Ortc.getExtendedRtpCapabilities(local, remote, false).codecs().getFirst();

        assertNull(vp8.localRtxPayloadType());
        assertNull(vp8.remoteRtxPayloadType());
    }

    @Test
    void rtxIsMatchedByTheNumericValueOfApt() {
        var local = caps(List.of(codec("video/VP8", 96, Map.of()), codec("video/rtx", 97, Map.of("apt", "96"))));
        var remote = caps(List.of(codec("video/VP8", 101, Map.of()), rtx(102, 101)));

        ExtendedCodec vp8 =
                Ortc.getExtendedRtpCapabilities(local, remote, false).codecs().getFirst();

        assertEquals(97, vp8.localRtxPayloadType());
        assertEquals(102, vp8.remoteRtxPayloadType());
    }

    @Test
    void rtcpFeedbackIsReduced() {
        var nack = new RtcpFeedback("nack", null);
        var pli = new RtcpFeedback("nack", "pli");
        var local = caps(List.of(
                new RtpCodecCapability(MediaKind.VIDEO, "video/VP8", 96, 90000, null, Map.of(), List.of(nack, pli))));
        var remote = caps(List.of(
                new RtpCodecCapability(MediaKind.VIDEO, "video/VP8", 101, 90000, null, Map.of(), List.of(pli))));

        assertEquals(
                List.of(pli),
                Ortc.getExtendedRtpCapabilities(local, remote, false)
                        .codecs()
                        .getFirst()
                        .rtcpFeedback());
    }

    @Test
    void aMatchingCodecWithoutPayloadTypeIsRejected() {
        var withPayloadType = caps(List.of(codec("video/VP8", 96, Map.of())));
        var withoutPayloadType = caps(List.of(codec("video/VP8", null, Map.of())));

        assertThrows(
                IllegalArgumentException.class,
                () -> Ortc.getExtendedRtpCapabilities(withoutPayloadType, withPayloadType, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> Ortc.getExtendedRtpCapabilities(withPayloadType, withoutPayloadType, true));
    }

    @Test
    void anRtxCodecWithoutPayloadTypeIsRejected() {
        var local = caps(List.of(codec("video/VP8", 96, Map.of()), codec("video/rtx", null, Map.of("apt", 96))));
        var remote = caps(List.of(codec("video/VP8", 101, Map.of()), rtx(102, 101)));

        assertThrows(IllegalArgumentException.class, () -> Ortc.getExtendedRtpCapabilities(local, remote, false));
    }

    @Test
    void codecsThatDoNotMatchNeedNoPayloadType() {
        var local = caps(List.of(codec("video/VP8", 96, Map.of()), codec("audio/opus", null, Map.of())));
        var remote = caps(List.of(codec("video/VP8", 101, Map.of())));

        assertEquals(
                1,
                Ortc.getExtendedRtpCapabilities(local, remote, false).codecs().size());
    }

    @Test
    void headerExtensionsTakeTheOppositeDirectionOfTheRouter() {
        var local = caps(new RtpHeaderExtension(null, MID, 3, true, null));

        for (var direction : List.of(
                new RtpHeaderExtensionDirection[] {null, RtpHeaderExtensionDirection.SENDRECV},
                new RtpHeaderExtensionDirection[] {
                    RtpHeaderExtensionDirection.SENDRECV, RtpHeaderExtensionDirection.SENDRECV
                },
                new RtpHeaderExtensionDirection[] {
                    RtpHeaderExtensionDirection.RECVONLY, RtpHeaderExtensionDirection.SENDONLY
                },
                new RtpHeaderExtensionDirection[] {
                    RtpHeaderExtensionDirection.SENDONLY, RtpHeaderExtensionDirection.RECVONLY
                },
                new RtpHeaderExtensionDirection[] {
                    RtpHeaderExtensionDirection.INACTIVE, RtpHeaderExtensionDirection.INACTIVE
                })) {
            var remote = caps(new RtpHeaderExtension(MediaKind.VIDEO, MID, 1, null, direction[0]));

            ExtendedHeaderExtension extension = Ortc.getExtendedRtpCapabilities(local, remote, false)
                    .headerExtensions()
                    .getFirst();

            assertEquals(new ExtendedHeaderExtension(MediaKind.VIDEO, MID, 3, 1, true, direction[1]), extension);
        }
    }

    @Test
    void headerExtensionsOfAnotherKindDoNotMatch() {
        var local = caps(new RtpHeaderExtension(MediaKind.AUDIO, MID, 1, null, null));
        var remote = caps(new RtpHeaderExtension(MediaKind.VIDEO, MID, 1, null, null));

        assertTrue(Ortc.getExtendedRtpCapabilities(local, remote, false)
                .headerExtensions()
                .isEmpty());
    }

    @Test
    void receivingCapabilitiesUseThePayloadTypesOfTheRouter() {
        RtpCapabilities recv = Ortc.getRecvRtpCapabilities(fixtures());

        assertEquals(
                List.of(
                        new RtpCodecCapability(
                                MediaKind.AUDIO,
                                "audio/opus",
                                100,
                                48000,
                                2,
                                Map.of("minptime", 10, "useinbandfec", 1),
                                fixtures().codecs().get(0).rtcpFeedback()),
                        new RtpCodecCapability(
                                MediaKind.VIDEO,
                                "video/VP8",
                                101,
                                90000,
                                null,
                                Map.of("baz", "1234abcd"),
                                fixtures().codecs().get(1).rtcpFeedback()),
                        new RtpCodecCapability(
                                MediaKind.VIDEO, "video/rtx", 102, 90000, null, Map.of("apt", 101), List.of())),
                recv.codecs());
        assertEquals(
                new RtpHeaderExtension(
                        MediaKind.VIDEO,
                        "urn:ietf:params:rtp-hdrext:toffset",
                        12,
                        false,
                        RtpHeaderExtensionDirection.SENDRECV),
                recv.headerExtensions().getLast());
    }

    @Test
    void sendingCapabilitiesUseTheLocalPayloadTypes() {
        RtpCapabilities send = Ortc.getSendRtpCapabilities(fixtures());

        assertEquals(
                List.of(111, 96, 97),
                send.codecs().stream()
                        .map(RtpCodecCapability::preferredPayloadType)
                        .toList());
        assertEquals(Map.of("apt", 96), send.codecs().getLast().parameters());
        assertEquals(2, send.headerExtensions().getLast().preferredId());
    }

    @Test
    void capabilitiesOnlyHaveTheHeaderExtensionsValidInTheirDirection() {
        var extended = new ExtendedRtpCapabilities(
                List.of(),
                List.of(
                        new ExtendedHeaderExtension(
                                null, "sendrecv", 1, 1, false, RtpHeaderExtensionDirection.SENDRECV),
                        new ExtendedHeaderExtension(
                                null, "sendonly", 2, 2, false, RtpHeaderExtensionDirection.SENDONLY),
                        new ExtendedHeaderExtension(
                                null, "recvonly", 3, 3, false, RtpHeaderExtensionDirection.RECVONLY),
                        new ExtendedHeaderExtension(
                                null, "inactive", 4, 4, false, RtpHeaderExtensionDirection.INACTIVE),
                        new ExtendedHeaderExtension(null, "absent", 5, 5, false, null)));

        assertEquals(
                List.of("sendrecv", "recvonly", "absent"),
                Ortc.getRecvRtpCapabilities(extended).headerExtensions().stream()
                        .map(RtpHeaderExtension::uri)
                        .toList());
        assertEquals(
                List.of("sendrecv", "sendonly", "absent"),
                Ortc.getSendRtpCapabilities(extended).headerExtensions().stream()
                        .map(RtpHeaderExtension::uri)
                        .toList());
    }

    @Test
    void canSendTheKindsWithACodec() {
        RtpCapabilities send = Ortc.getSendRtpCapabilities(fixtures());

        assertTrue(Ortc.canSend(MediaKind.AUDIO, send));
        assertTrue(Ortc.canSend(MediaKind.VIDEO, send));
        assertFalse(Ortc.canSend(MediaKind.VIDEO, caps(List.of(codec("audio/opus", 100, Map.of())))));
    }

    @Test
    void canReceiveByThePayloadTypeOfTheFirstCodec() {
        RtpCapabilities recv = Ortc.getRecvRtpCapabilities(fixtures());

        assertTrue(Ortc.canReceive(
                FakeParameters.consumerRemoteParameters("audio/opus").rtpParameters(), recv));
        assertFalse(Ortc.canReceive(
                FakeParameters.consumerRemoteParameters("audio/ISAC").rtpParameters(), recv));
        assertFalse(Ortc.canReceive(rtpParameters(), recv));
        assertFalse(
                Ortc.canReceive(rtpParameters(new RtpCodecParameters("video/VP8", 96, 90000, null, null, null)), recv));
    }

    private static RtpParameters rtpParameters(RtpCodecParameters... codecs) {
        return new RtpParameters(null, List.of(codecs), null, null, null, null);
    }
}

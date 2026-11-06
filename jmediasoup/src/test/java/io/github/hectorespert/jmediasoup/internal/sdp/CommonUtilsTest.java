/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.sdp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.hectorespert.jmediasoup.MediasoupClientException;
import io.github.hectorespert.jmediasoup.internal.sdptransform.Parser;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtcpFeedback;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecCapability;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpHeaderExtension;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
import io.github.hectorespert.jmediasoup.transport.DtlsFingerprint;
import io.github.hectorespert.jmediasoup.transport.DtlsParameters;
import io.github.hectorespert.jmediasoup.transport.DtlsRole;
import io.github.hectorespert.jmediasoup.transport.FingerprintAlgorithm;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommonUtilsTest {

    private static final String ABS_SEND_TIME = "http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time";

    static Map<String, Object> offer(String file) {
        try (InputStream in = CommonUtilsTest.class.getResourceAsStream("/sdp/webrtc-java-0.20.0/" + file)) {
            return Parser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> sdp(String... lines) {
        return Parser.parse(String.join("\r\n", lines) + "\r\n");
    }

    private static RtpCodecCapability codec(RtpCapabilities capabilities, int payloadType) {
        return capabilities.codecs().stream()
                .filter(codec -> codec.preferredPayloadType() == payloadType)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void capabilitiesOfAWebrtcJavaOffer() {
        RtpCapabilities capabilities = CommonUtils.extractRtpCapabilities(offer("offer-native-capabilities.sdp"));

        assertEquals(31, capabilities.codecs().size());
        assertEquals(
                new RtpCodecCapability(
                        MediaKind.AUDIO,
                        "audio/opus",
                        111,
                        48000,
                        2,
                        Map.of("minptime", 10, "useinbandfec", 1),
                        List.of(new RtcpFeedback("transport-cc", null))),
                capabilities.codecs().getFirst());
        assertEquals(
                new RtpCodecCapability(
                        MediaKind.VIDEO,
                        "video/VP8",
                        96,
                        90000,
                        null,
                        Map.of(),
                        List.of(
                                new RtcpFeedback("goog-remb", null),
                                new RtcpFeedback("transport-cc", null),
                                new RtcpFeedback("ccm", "fir"),
                                new RtcpFeedback("nack", null),
                                new RtcpFeedback("nack", "pli"))),
                codec(capabilities, 96));
        assertEquals(
                new RtpCodecCapability(MediaKind.VIDEO, "video/rtx", 97, 90000, null, Map.of("apt", 96), List.of()),
                codec(capabilities, 97));
        assertEquals(
                Map.of("level-asymmetry-allowed", 1, "packetization-mode", 1, "profile-level-id", "42001f"),
                codec(capabilities, 102).parameters());
    }

    @Test
    void headerExtensionsAreKeptForEachKind() {
        RtpCapabilities capabilities = CommonUtils.extractRtpCapabilities(offer("offer-native-capabilities.sdp"));

        assertEquals(15, capabilities.headerExtensions().size());
        assertEquals(
                List.of(
                        new RtpHeaderExtension(MediaKind.AUDIO, ABS_SEND_TIME, 2, null, null),
                        new RtpHeaderExtension(MediaKind.VIDEO, ABS_SEND_TIME, 2, null, null)),
                capabilities.headerExtensions().stream()
                        .filter(extension -> extension.uri().equals(ABS_SEND_TIME))
                        .toList());
    }

    @Test
    void codecsOfAllTheBundledSectionsAreRead() {
        RtpCapabilities capabilities = CommonUtils.extractRtpCapabilities(sdp(
                "v=0",
                "m=audio 9 UDP/TLS/RTP/SAVPF 111",
                "a=rtpmap:111 opus/48000/2",
                "m=video 9 UDP/TLS/RTP/SAVPF 96",
                "a=rtpmap:96 VP8/90000",
                "m=application 9 UDP/DTLS/SCTP webrtc-datachannel",
                "a=sctp-port:5000",
                "m=video 9 UDP/TLS/RTP/SAVPF 96 98",
                "a=rtpmap:96 VP8/90000",
                "a=fmtp:96 x-google-start-bitrate=1000",
                "a=rtpmap:98 VP9/90000"));

        assertEquals(
                List.of("audio/opus", "video/VP8", "video/VP9"),
                capabilities.codecs().stream().map(RtpCodecCapability::mimeType).toList());
        // A later section replaces the codec of an earlier one with the same payload type.
        assertEquals(
                Map.of("x-google-start-bitrate", 1000), codec(capabilities, 96).parameters());
    }

    @Test
    void feedbackForAllPayloadsAppliesToTheCodecsOfItsKindButRtx() {
        RtpCapabilities capabilities = CommonUtils.extractRtpCapabilities(sdp(
                "v=0",
                "m=audio 9 UDP/TLS/RTP/SAVPF 111",
                "a=rtpmap:111 opus/48000/2",
                "m=video 9 UDP/TLS/RTP/SAVPF 96 97 98",
                "a=rtpmap:96 VP8/90000",
                "a=rtpmap:97 rtx/90000",
                "a=fmtp:97 apt=96",
                "a=rtpmap:98 VP9/90000",
                "a=rtcp-fb:* nack",
                "a=rtcp-fb:98 nack pli"));

        assertEquals(List.of(), codec(capabilities, 111).rtcpFeedback());
        assertEquals(
                List.of(new RtcpFeedback("nack", null)), codec(capabilities, 96).rtcpFeedback());
        assertEquals(List.of(), codec(capabilities, 97).rtcpFeedback());
        assertEquals(
                List.of(new RtcpFeedback("nack", null), new RtcpFeedback("nack", "pli")),
                codec(capabilities, 98).rtcpFeedback());
    }

    @Test
    void parametersWithoutValueAreEmptyAndProfileLevelIdIsAText() {
        RtpCapabilities capabilities = CommonUtils.extractRtpCapabilities(sdp(
                "v=0",
                "m=video 9 UDP/TLS/RTP/SAVPF 102",
                "a=rtpmap:102 H264/90000",
                "a=fmtp:102 packetization-mode=1;profile-level-id=640032;flag"));

        assertEquals(
                Map.of("packetization-mode", 1, "profile-level-id", "640032", "flag", ""),
                codec(capabilities, 102).parameters());
    }

    @Test
    void encryptedHeaderExtensionsAreLeftOut() {
        RtpCapabilities capabilities = CommonUtils.extractRtpCapabilities(sdp(
                "v=0",
                "m=audio 9 UDP/TLS/RTP/SAVPF 111",
                "a=rtpmap:111 opus/48000/2",
                "a=extmap:1 urn:ietf:params:rtp-hdrext:encrypt urn:ietf:params:rtp-hdrext:ssrc-audio-level",
                "a=extmap:2 urn:ietf:params:rtp-hdrext:sdes:mid"));

        assertEquals(
                List.of(new RtpHeaderExtension(MediaKind.AUDIO, "urn:ietf:params:rtp-hdrext:sdes:mid", 2, null, null)),
                capabilities.headerExtensions());
    }

    @Test
    void aCodecWithoutClockRateIsRejected() {
        Map<String, Object> sdp = sdp("v=0", "m=audio 9 RTP/AVP 96", "a=rtpmap:96 AppleLossless");

        assertThrows(IllegalArgumentException.class, () -> CommonUtils.extractRtpCapabilities(sdp));
    }

    @Test
    void dtlsParametersOfAWebrtcJavaOffer() throws Exception {
        assertEquals(
                new DtlsParameters(
                        DtlsRole.AUTO,
                        List.of(
                                new DtlsFingerprint(
                                        FingerprintAlgorithm.SHA_256,
                                        "26:35:EB:92:9B:EB:A4:DF:1B:E1:7C:40:F0:B3:06:09:1F:86:B7:81:71:4D:F0:91:DE:BB:25:5A:63:91:CC:10"))),
                CommonUtils.extractDtlsParameters(offer("offer-native-capabilities.sdp")));
    }

    @Test
    void dtlsParametersPreferTheSessionLevel() throws Exception {
        DtlsParameters parameters = CommonUtils.extractDtlsParameters(sdp(
                "v=0",
                "a=setup:passive",
                "a=fingerprint:sha-1 AA:BB",
                "m=audio 0 UDP/TLS/RTP/SAVPF 111",
                "a=setup:active",
                "a=fingerprint:sha-256 CC:DD"));

        assertEquals(
                new DtlsParameters(DtlsRole.SERVER, List.of(new DtlsFingerprint(FingerprintAlgorithm.SHA_1, "AA:BB"))),
                parameters);
    }

    @Test
    void dtlsParametersSkipMediaSectionsWithoutPort() throws Exception {
        DtlsParameters parameters = CommonUtils.extractDtlsParameters(sdp(
                "v=0",
                "a=fingerprint:sha-256 AA:BB",
                "m=audio 0 UDP/TLS/RTP/SAVPF 111",
                "a=setup:passive",
                "m=video 9 UDP/TLS/RTP/SAVPF 96",
                "a=setup:active"));

        assertEquals(DtlsRole.CLIENT, parameters.role());
        assertEquals("AA:BB", parameters.fingerprints().getFirst().value());
    }

    @Test
    void anUnknownSetupHasNoRole() throws Exception {
        DtlsParameters parameters =
                CommonUtils.extractDtlsParameters(sdp("v=0", "a=setup:holdconn", "a=fingerprint:sha-256 AA:BB"));

        assertNull(parameters.role());
    }

    @Test
    void dtlsParametersNeedSetupAndFingerprint() {
        Map<String, Object> withoutSetup = sdp("v=0", "m=audio 9 RTP/AVP 0", "a=fingerprint:sha-256 AA:BB");
        Map<String, Object> withoutFingerprint = sdp("v=0", "m=audio 9 RTP/AVP 0", "a=setup:active");

        assertEquals(
                "no a=setup found at SDP session or media level",
                assertThrows(MediasoupClientException.class, () -> CommonUtils.extractDtlsParameters(withoutSetup))
                        .getMessage());
        assertEquals(
                "no a=fingerprint found at SDP session or media level",
                assertThrows(
                                MediasoupClientException.class,
                                () -> CommonUtils.extractDtlsParameters(withoutFingerprint))
                        .getMessage());
    }

    @Test
    void cnameOfAMediaSection() {
        @SuppressWarnings("unchecked")
        var media = (List<Map<String, Object>>) offer("offer-sendonly.sdp").get("media");

        assertEquals("gKAr85oLwDym9w05", CommonUtils.getCname(media.getFirst()));
        assertEquals("", CommonUtils.getCname(Map.of()));
    }

    private static Map<String, Object> answerMediaObject(String fmtpConfig) {
        Map<String, Object> rtp = new java.util.LinkedHashMap<>(Map.of("payload", 100, "codec", "opus", "rate", 48000));
        Map<String, Object> media = new java.util.LinkedHashMap<>();
        media.put("rtp", new java.util.ArrayList<>(List.of(rtp)));
        if (fmtpConfig != null) {
            Map<String, Object> fmtp = new java.util.LinkedHashMap<>(Map.of("payload", 100, "config", fmtpConfig));
            media.put("fmtp", new java.util.ArrayList<>(List.of(fmtp)));
        }
        return media;
    }

    private static RtpParameters offerWithOpus(Map<String, Object> parameters) {
        return new RtpParameters(
                null,
                List.of(new RtpCodecParameters("audio/opus", 100, 48000, 2, parameters, List.of())),
                List.of(),
                List.of(),
                null,
                null);
    }

    @SuppressWarnings("unchecked")
    private static String fmtpConfig(Map<String, Object> media) {
        return (String)
                ((List<Map<String, Object>>) media.get("fmtp")).getFirst().get("config");
    }

    @Test
    void applyCodecParametersReceivesOpusInStereoIfTheOfferSendsInStereo() {
        Map<String, Object> media = answerMediaObject("minptime=10;useinbandfec=1");

        CommonUtils.applyCodecParameters(offerWithOpus(Map.of("sprop-stereo", 1)), media);

        assertEquals("minptime=10;useinbandfec=1;stereo=1", fmtpConfig(media));
    }

    @Test
    void applyCodecParametersReceivesOpusInMonoIfTheOfferSendsInMono() {
        Map<String, Object> media = answerMediaObject("stereo=1");

        CommonUtils.applyCodecParameters(offerWithOpus(Map.of("sprop-stereo", "0")), media);

        assertEquals("stereo=0", fmtpConfig(media));
    }

    @Test
    void applyCodecParametersAddsTheFmtpIfMissing() {
        Map<String, Object> media = answerMediaObject(null);

        CommonUtils.applyCodecParameters(offerWithOpus(Map.of("sprop-stereo", 1)), media);

        assertEquals("stereo=1", fmtpConfig(media));
    }

    @Test
    void applyCodecParametersLeavesTheAnswerWithoutSpropStereoOrOtherCodecs() {
        Map<String, Object> media = answerMediaObject("useinbandfec=1;usedtx");

        CommonUtils.applyCodecParameters(offerWithOpus(Map.of()), media);
        CommonUtils.applyCodecParameters(
                new RtpParameters(
                        null,
                        List.of(new RtpCodecParameters("video/VP8", 100, 90000, null, Map.of(), List.of())),
                        List.of(),
                        List.of(),
                        null,
                        null),
                media);

        // A parameter without value is written without "=undefined".
        assertEquals("useinbandfec=1;usedtx", fmtpConfig(media));
    }
}

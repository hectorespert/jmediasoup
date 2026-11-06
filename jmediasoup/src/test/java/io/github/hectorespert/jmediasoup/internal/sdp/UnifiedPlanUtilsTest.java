/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.sdp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.hectorespert.jmediasoup.MediasoupClientException;
import io.github.hectorespert.jmediasoup.internal.sdptransform.Parser;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpEncodingParameters;
import io.github.hectorespert.jmediasoup.rtp.Rtx;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UnifiedPlanUtilsTest {

    private static final RtpCodecParameters VP8 = new RtpCodecParameters("video/VP8", 96, 90000, null, null, null);
    private static final RtpCodecParameters RTX =
            new RtpCodecParameters("video/rtx", 97, 90000, null, Map.of("apt", 96), null);

    @SuppressWarnings("unchecked")
    private static Map<String, Object> media(Map<String, Object> sdp, int index) {
        return ((List<Map<String, Object>>) sdp.get("media")).get(index);
    }

    private static Map<String, Object> section(String... lines) {
        return media(Parser.parse("v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96 97\r\n" + String.join("\r\n", lines)), 0);
    }

    private static RtpEncodingParameters encoding(long ssrc, Long rtxSsrc) {
        return RtpEncodingParameters.builder()
                .ssrc(ssrc)
                .rtx(rtxSsrc == null ? null : new Rtx(rtxSsrc))
                .build();
    }

    @Test
    void encodingsOfAWebrtcJavaOffer() throws Exception {
        Map<String, Object> offer = CommonUtilsTest.offer("offer-sendonly.sdp");

        assertEquals(
                List.of(encoding(2308156550L, null)),
                UnifiedPlanUtils.getRtpEncodings(
                        media(offer, 0), List.of(new RtpCodecParameters("audio/opus", 111, 48000, 2, null, null))));
        assertEquals(
                List.of(encoding(1513815280L, 3057968183L)),
                UnifiedPlanUtils.getRtpEncodings(media(offer, 1), List.of(VP8, RTX)));
    }

    @Test
    void rtxIsOnlyAddedWithMoreThanOneCodec() throws Exception {
        Map<String, Object> video = media(CommonUtilsTest.offer("offer-sendonly.sdp"), 1);

        assertEquals(List.of(encoding(1513815280L, null)), UnifiedPlanUtils.getRtpEncodings(video, List.of(VP8)));
    }

    @Test
    void encodingsKeepTheOrderOfTheSsrcs() throws Exception {
        Map<String, Object> section = section(
                "a=ssrc-group:FID 3 4",
                "a=ssrc-group:FID 9 10",
                "a=ssrc:5 cname:a",
                "a=ssrc:1 cname:a",
                "a=ssrc:2 cname:a",
                "a=ssrc:3 cname:a",
                "a=ssrc:4 cname:a",
                "a=ssrc:1 msid:s t");

        // The SSRCs in a FID group go first, then those without RTX; a group of an unknown SSRC is
        // ignored.
        assertEquals(
                List.of(encoding(3, 4L), encoding(5, null), encoding(1, null), encoding(2, null)),
                UnifiedPlanUtils.getRtpEncodings(section, List.of(VP8, RTX)));
    }

    @Test
    void aSectionWithoutSsrcsIsAnError() {
        Map<String, Object> section = section("a=sendonly");

        assertEquals(
                "no a=ssrc lines found",
                assertThrows(
                                MediasoupClientException.class,
                                () -> UnifiedPlanUtils.getRtpEncodings(section, List.of(VP8)))
                        .getMessage());
    }
}

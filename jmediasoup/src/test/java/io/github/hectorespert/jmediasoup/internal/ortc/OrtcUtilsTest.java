/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.ortc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtcpFeedback;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecCapability;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrtcUtilsTest {

    private static RtpCodecCapability codec(MediaKind kind, String mimeType, List<RtcpFeedback> rtcpFeedback) {
        return new RtpCodecCapability(kind, mimeType, 100, 48000, 2, Map.of(), rtcpFeedback);
    }

    @Test
    void addsNackToOpusCodecsWithoutIt() {
        var capabilities = new RtpCapabilities(
                List.of(
                        codec(MediaKind.AUDIO, "audio/opus", List.of(new RtcpFeedback("transport-cc", null))),
                        codec(MediaKind.AUDIO, "AUDIO/MULTIOPUS", List.of()),
                        codec(MediaKind.VIDEO, "video/VP8", List.of())),
                null,
                null);

        RtpCapabilities result = OrtcUtils.addNackSupportForOpus(capabilities);

        assertEquals(
                List.of(new RtcpFeedback("transport-cc", null), new RtcpFeedback("nack", null)),
                result.codecs().get(0).rtcpFeedback());
        assertEquals(
                List.of(new RtcpFeedback("nack", null)), result.codecs().get(1).rtcpFeedback());
        assertSame(capabilities.codecs().get(2), result.codecs().get(2));
    }

    @Test
    void keepsOpusCodecsThatAlreadyHaveNack() {
        RtpCodecCapability withNack = codec(MediaKind.AUDIO, "audio/opus", List.of(new RtcpFeedback("nack", "")));
        RtpCodecCapability withPliOnly = codec(MediaKind.AUDIO, "audio/opus", List.of(new RtcpFeedback("nack", "pli")));

        RtpCapabilities result =
                OrtcUtils.addNackSupportForOpus(new RtpCapabilities(List.of(withNack, withPliOnly), null, null));

        assertSame(withNack, result.codecs().get(0));
        // NACK PLI is not plain NACK.
        assertEquals(
                List.of(new RtcpFeedback("nack", "pli"), new RtcpFeedback("nack", null)),
                result.codecs().get(1).rtcpFeedback());
    }

    @Test
    void splitsTheMsidIntoStreamAndTrack() {
        assertEquals(new OrtcUtils.MsidIds("stream", "track"), OrtcUtils.getMsidStreamIdAndTrackId(" stream  track "));
        assertEquals(new OrtcUtils.MsidIds("-", null), OrtcUtils.getMsidStreamIdAndTrackId("-"));
        assertNull(OrtcUtils.getMsidStreamIdAndTrackId(""));
        assertNull(OrtcUtils.getMsidStreamIdAndTrackId(null));
    }
}

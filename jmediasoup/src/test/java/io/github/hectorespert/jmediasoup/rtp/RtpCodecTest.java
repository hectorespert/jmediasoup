/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RtpCodecTest {

    private static final RtpCodecCapability VP8_CAPABILITY = new RtpCodecCapability(
            MediaKind.VIDEO,
            "video/VP8",
            101,
            90000,
            null,
            Map.of(),
            List.of(new RtcpFeedback("nack", null), new RtcpFeedback("nack", "pli")));

    private static final RtpCodecParameters RTX_PARAMETERS =
            new RtpCodecParameters("video/rtx", 102, 90000, null, Map.of("apt", 101), List.of());

    @Test
    void capabilityAndParametersShareTheCodecInterface() {
        List<RtpCodec> codecs = List.of(VP8_CAPABILITY, RTX_PARAMETERS);

        assertEquals(
                List.of("capability 101", "parameters 102"),
                codecs.stream().map(RtpCodecTest::describe).toList());
    }

    // No default branch: it compiles only because RtpCodec is sealed.
    private static String describe(RtpCodec codec) {
        return switch (codec) {
            case RtpCodecCapability capability -> "capability " + capability.preferredPayloadType();
            case RtpCodecParameters parameters -> "parameters " + parameters.payloadType();
        };
    }

    @Test
    void absentCollectionsAreEmpty() {
        var codec = new RtpCodecParameters("audio/opus", 100, 48000, 2, null, null);

        assertEquals(Map.of(), codec.parameters());
        assertEquals(List.of(), codec.rtcpFeedback());
    }

    @Test
    void equalCodecsAreEqual() {
        assertEquals(
                RTX_PARAMETERS, new RtpCodecParameters("video/rtx", 102, 90000, null, Map.of("apt", 101), List.of()));
    }

    @Test
    void invalidFieldsFailWithTheirName() {
        assertEquals(
                "payloadType must be between 0 and 127: 128",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> new RtpCodecParameters("audio/opus", 128, 48000, 2, null, null))
                        .getMessage());
        assertEquals(
                "clockRate must be between 1 and 2147483647: 0",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> new RtpCodecParameters("audio/opus", 100, 0, 2, null, null))
                        .getMessage());
        assertThrows(
                IllegalArgumentException.class,
                () -> new RtpCodecCapability(MediaKind.AUDIO, "opus", 100, 48000, 2, null, null));
        assertEquals(
                "kind",
                assertThrows(
                                NullPointerException.class,
                                () -> new RtpCodecCapability(null, "audio/opus", 100, 48000, 2, null, null))
                        .getMessage());
        assertEquals(
                "type",
                assertThrows(NullPointerException.class, () -> new RtcpFeedback(null, "pli"))
                        .getMessage());
    }
}

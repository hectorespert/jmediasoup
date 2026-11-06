/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RtpEncodingParametersTest {

    @Test
    void builderLeavesUnsetFieldsAbsent() {
        RtpEncodingParameters encoding = RtpEncodingParameters.builder()
                .rid("r0")
                .maxBitrate(100_000)
                .scaleResolutionDownBy(4.0)
                .build();

        assertEquals("r0", encoding.rid());
        assertEquals(100_000, encoding.maxBitrate());
        assertEquals(4.0, encoding.scaleResolutionDownBy());
        assertNull(encoding.ssrc());
        assertNull(encoding.active());
    }

    @Test
    void toBuilderCopiesEveryField() {
        RtpEncodingParameters encoding = new RtpEncodingParameters(
                true,
                1111L,
                "r1",
                101,
                new Rtx(2222L),
                false,
                "L1T3",
                2.0,
                300_000,
                30.0,
                false,
                Priority.HIGH,
                Priority.MEDIUM);

        assertEquals(encoding, encoding.toBuilder().build());
        assertEquals(500_000, encoding.toBuilder().maxBitrate(500_000).build().maxBitrate());
    }

    @Test
    void ssrcsUseTheWholeUnsignedRange() {
        RtpEncodingParameters encoding = RtpEncodingParameters.builder()
                .ssrc(4_294_967_295L)
                .rtx(new Rtx(3_000_000_000L))
                .build();

        assertEquals(4_294_967_295L, encoding.ssrc());
        assertThrows(
                IllegalArgumentException.class,
                () -> RtpEncodingParameters.builder().ssrc(4_294_967_296L).build());
        assertThrows(IllegalArgumentException.class, () -> new Rtx(-1));
    }

    @Test
    void resolutionCannotBeScaledUp() {
        var error = assertThrows(
                IllegalArgumentException.class,
                () -> RtpEncodingParameters.builder().scaleResolutionDownBy(0.5).build());

        assertEquals("scaleResolutionDownBy must be at least 1.0: 0.5", error.getMessage());
    }
}

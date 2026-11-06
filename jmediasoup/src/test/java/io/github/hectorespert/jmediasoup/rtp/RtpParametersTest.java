/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RtpParametersTest {

    private static final RtpCodecParameters OPUS =
            new RtpCodecParameters("audio/opus", 100, 48000, 2, Map.of("useinbandfec", 1), List.of());

    @Test
    void absentListsAreEmpty() {
        var parameters = new RtpParameters(null, List.of(OPUS), null, null, null, null);

        assertEquals(List.of(), parameters.headerExtensions());
        assertEquals(List.of(), parameters.encodings());
        assertEquals(List.of(), new RtpCapabilities(null, null, null).codecs());
        assertEquals(List.of(), new ExtendedRtpCapabilities(null, null).headerExtensions());
    }

    @Test
    void listsAreDefensiveCopies() {
        var codecs = new ArrayList<>(List.of(OPUS));
        var parameters = new RtpParameters("0", codecs, List.of(), List.of(), null, null);
        codecs.clear();

        assertEquals(List.of(OPUS), parameters.codecs());
        assertThrows(
                UnsupportedOperationException.class, () -> parameters.codecs().clear());
    }

    @Test
    void nullElementFailsWithItsPosition() {
        var error = assertThrows(
                NullPointerException.class,
                () -> new RtpParameters(null, Arrays.asList(OPUS, null), null, null, null, null));

        assertEquals("codecs[1]", error.getMessage());
    }

    @Test
    void headerExtensionIdsAreBetweenOneAnd255() {
        assertEquals(
                "id must be between 1 and 255: 0",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> new RtpHeaderExtensionParameters(
                                        "urn:ietf:params:rtp-hdrext:sdes:mid", 0, null, null))
                        .getMessage());
        assertThrows(
                IllegalArgumentException.class,
                () -> new RtpHeaderExtension(MediaKind.AUDIO, "urn:ietf:params:rtp-hdrext:sdes:mid", 256, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtendedHeaderExtension(null, "urn:ietf:params:rtp-hdrext:sdes:mid", 1, 0, false, null));
        assertThrows(IllegalArgumentException.class, () -> new RtpHeaderExtensionParameters("", 1, null, null));
    }

    @Test
    void extendedCodecChecksBothSides() {
        var codec = new ExtendedCodec(
                MediaKind.VIDEO, "video/VP8", 96, 97, 101, 102, 90000, null, null, Map.of("x", "y"), null);

        assertEquals(Map.of(), codec.localParameters());
        assertEquals(Map.of("x", "y"), codec.remoteParameters());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtendedCodec(
                        MediaKind.VIDEO, "video/VP8", 96, 200, 101, null, 90000, null, null, null, null));
    }
}

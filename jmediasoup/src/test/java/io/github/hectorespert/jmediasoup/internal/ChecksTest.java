/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChecksTest {

    @Test
    void absentListIsEmpty() {
        assertEquals(List.of(), Checks.list(null, "codecs"));
    }

    @Test
    void listIsAnUnmodifiableCopy() {
        var original = new ArrayList<>(List.of("a", "b"));
        List<String> copy = Checks.list(original, "names");
        original.add("c");

        assertEquals(List.of("a", "b"), copy);
        assertThrows(UnsupportedOperationException.class, () -> copy.add("d"));
    }

    @Test
    void nullListElementNamesItsPosition() {
        var error = assertThrows(NullPointerException.class, () -> Checks.list(Arrays.asList("a", null), "codecs"));

        assertEquals("codecs[1]", error.getMessage());
    }

    @Test
    void parametersKeepTheirOrderAndTypes() {
        var original = new LinkedHashMap<String, Object>();
        original.put("profile-level-id", "42e01f");
        original.put("packetization-mode", 1);
        original.put("level-asymmetry-allowed", 1L);

        Map<String, Object> copy = Checks.parameters(original, "parameters");

        assertEquals(
                List.of("profile-level-id", "packetization-mode", "level-asymmetry-allowed"),
                List.copyOf(copy.keySet()));
        assertEquals(1L, copy.get("level-asymmetry-allowed"));
        assertThrows(UnsupportedOperationException.class, () -> copy.put("apt", 100));
    }

    @Test
    void parametersOnlyAcceptTextAndNumbers() {
        var error = assertThrows(
                IllegalArgumentException.class, () -> Checks.parameters(Map.of("apt", List.of(100)), "parameters"));

        assertEquals("parameters.apt must be a string or a number: [100]", error.getMessage());
    }

    @Test
    void parametersRejectNullValues() {
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("apt", null);

        assertThrows(IllegalArgumentException.class, () -> Checks.parameters(parameters, "parameters"));
    }

    @Test
    void rangeChecksBothEnds() {
        assertEquals(0, Checks.range(0, 0, 127, "payloadType"));
        assertEquals(127, Checks.range(127, 0, 127, "payloadType"));
        assertNull(Checks.range(null, 0, 127, "payloadType"));

        var error = assertThrows(IllegalArgumentException.class, () -> Checks.range(128, 0, 127, "payloadType"));
        assertEquals("payloadType must be between 0 and 127: 128", error.getMessage());
    }

    @Test
    void ssrcFitsInThirtyTwoUnsignedBits() {
        assertEquals(4_294_967_295L, Checks.ssrc(4_294_967_295L, "ssrc"));
        assertThrows(IllegalArgumentException.class, () -> Checks.ssrc(4_294_967_296L, "ssrc"));
        assertThrows(IllegalArgumentException.class, () -> Checks.ssrc(-1L, "ssrc"));
    }

    @Test
    void mimeTypeNeedsAudioOrVideoAndASubtype() {
        assertEquals("video/H264", Checks.mimeType("video/H264"));
        assertThrows(IllegalArgumentException.class, () -> Checks.mimeType("opus"));
        assertThrows(IllegalArgumentException.class, () -> Checks.mimeType("application/opus"));
        assertThrows(IllegalArgumentException.class, () -> Checks.mimeType("audio/"));
    }

    @Test
    void atLeastAcceptsAbsentValues() {
        assertNull(Checks.atLeast(null, 1.0, "scaleResolutionDownBy"));
        assertEquals(1.5, Checks.atLeast(1.5, 1.0, "scaleResolutionDownBy"));
        assertThrows(IllegalArgumentException.class, () -> Checks.atLeast(0.5, 1.0, "scaleResolutionDownBy"));
        assertThrows(IllegalArgumentException.class, () -> Checks.atLeast(Double.NaN, 1.0, "scaleResolutionDownBy"));
    }

    @Test
    void appDataKeepsNullValuesAndOrder() {
        var original = new LinkedHashMap<String, Object>();
        original.put("b", null);
        original.put("a", 1);

        Map<String, Object> copy = Checks.appData(original);

        assertEquals(List.of("b", "a"), List.copyOf(copy.keySet()));
        assertNull(copy.get("b"));
        assertEquals(Map.of(), Checks.appData(null));
        assertThrows(UnsupportedOperationException.class, () -> copy.put("c", 2));
    }
}

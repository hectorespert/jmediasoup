/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RtpEnumsTest {

    @Test
    void mediaKindUsesTheMediasoupValues() {
        for (MediaKind kind : MediaKind.values()) {
            assertEquals(kind, MediaKind.fromValue(kind.value()));
        }
        assertEquals("audio", MediaKind.AUDIO.value());
        assertThrows(IllegalArgumentException.class, () -> MediaKind.fromValue("application"));
    }

    @Test
    void directionUsesTheMediasoupValues() {
        for (RtpHeaderExtensionDirection direction : RtpHeaderExtensionDirection.values()) {
            assertEquals(direction, RtpHeaderExtensionDirection.fromValue(direction.value()));
        }
        assertEquals("recvonly", RtpHeaderExtensionDirection.RECVONLY.value());
        assertThrows(IllegalArgumentException.class, () -> RtpHeaderExtensionDirection.fromValue("SENDRECV"));
    }

    @Test
    void priorityUsesTheMediasoupValues() {
        for (Priority priority : Priority.values()) {
            assertEquals(priority, Priority.fromValue(priority.value()));
        }
        assertEquals("very-low", Priority.VERY_LOW.value());
        assertThrows(IllegalArgumentException.class, () -> Priority.fromValue("very_low"));
    }
}

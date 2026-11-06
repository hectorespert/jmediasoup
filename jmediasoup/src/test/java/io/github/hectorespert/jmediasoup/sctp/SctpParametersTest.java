/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.sctp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SctpParametersTest {

    @Test
    void streamCountsAreBetweenOneAnd65535() {
        var streams = new NumSctpStreams(1024, 65535);

        assertEquals(1024, new SctpCapabilities(streams).numStreams().os());
        assertEquals(
                "mis must be between 1 and 65535: 0",
                assertThrows(IllegalArgumentException.class, () -> new NumSctpStreams(1024, 0))
                        .getMessage());
        assertEquals(
                "numStreams",
                assertThrows(NullPointerException.class, () -> new SctpCapabilities(null))
                        .getMessage());
    }

    @Test
    void acceptsTheFieldsOfOldAndNewServers() {
        var old = new SctpParameters(5000, 1024, 1024, 262144, null, null);
        var current = new SctpParameters(5000, null, null, null, 262144, 262144);

        assertEquals(262144, old.maxMessageSize());
        assertNull(current.maxMessageSize());
        assertEquals(262144, current.maxReceiveMessageSize());
        assertThrows(IllegalArgumentException.class, () -> new SctpParameters(70000, null, null, null, null, null));
    }

    @Test
    void streamWithoutReliabilitySettingsKeepsThemAbsent() {
        var stream = new SctpStreamParameters(null, null, null, null, "chat", null);

        assertNull(stream.ordered());
        assertNull(stream.streamId());
        assertEquals("chat", stream.label());
    }

    @Test
    void unorderedStreamTakesOneLimit() {
        assertEquals(500, new SctpStreamParameters(1, false, 500, null, null, null).maxPacketLifeTime());
        assertEquals(3, new SctpStreamParameters(1, null, null, 3, null, null).maxRetransmits());
    }

    @Test
    void contradictoryReliabilityFails() {
        assertEquals(
                "cannot provide both maxPacketLifeTime and maxRetransmits",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> new SctpStreamParameters(1, false, 500, 3, null, null))
                        .getMessage());
        assertEquals(
                "cannot be ordered with maxPacketLifeTime or maxRetransmits",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> new SctpStreamParameters(1, true, null, 3, null, null))
                        .getMessage());
    }

    @Test
    void streamIdIsBetweenZeroAnd65534() {
        assertEquals(0, new SctpStreamParameters(0, null, null, null, null, null).streamId());
        assertThrows(
                IllegalArgumentException.class, () -> new SctpStreamParameters(65535, null, null, null, null, null));
    }
}

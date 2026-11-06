/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class IceCandidateTest {

    @Test
    void readsTheAddressFromEitherField() {
        assertEquals("10.0.0.1", candidate("10.0.0.1", "10.0.0.1").effectiveAddress());
        assertEquals("10.0.0.2", candidate("10.0.0.2", null).effectiveAddress());
        assertEquals("10.0.0.3", candidate(null, "10.0.0.3").effectiveAddress());
    }

    @Test
    void needsAnAddress() {
        var error = assertThrows(IllegalArgumentException.class, () -> candidate(null, null));

        assertEquals("address or ip is required", error.getMessage());
    }

    @Test
    void priorityUsesTheWholeUnsignedRange() {
        var candidate = new IceCandidate(
                "udpcandidate",
                4_294_967_295L,
                "10.0.0.1",
                null,
                TransportProtocol.UDP,
                40000,
                IceCandidateType.HOST,
                null);

        assertEquals(4_294_967_295L, candidate.priority());
        assertThrows(
                IllegalArgumentException.class,
                () -> new IceCandidate(
                        "udpcandidate",
                        -1,
                        "10.0.0.1",
                        null,
                        TransportProtocol.UDP,
                        40000,
                        IceCandidateType.HOST,
                        null));
    }

    @Test
    void portIsBetweenZeroAnd65535() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new IceCandidate(
                        "tcpcandidate",
                        1076302079,
                        "10.0.0.1",
                        null,
                        TransportProtocol.TCP,
                        65536,
                        IceCandidateType.HOST,
                        IceCandidateTcpType.PASSIVE));
    }

    private static IceCandidate candidate(String address, String ip) {
        return new IceCandidate(
                "udpcandidate", 1076302079, address, ip, TransportProtocol.UDP, 40000, IceCandidateType.HOST, null);
    }
}

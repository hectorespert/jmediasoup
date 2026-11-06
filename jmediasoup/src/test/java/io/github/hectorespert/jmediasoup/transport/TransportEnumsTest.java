/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.function.Function;
import org.junit.jupiter.api.Test;

class TransportEnumsTest {

    @Test
    void textEnumsUseTheMediasoupValues() {
        assertRoundTrip(TransportProtocol.values(), TransportProtocol::value, TransportProtocol::fromValue);
        assertRoundTrip(IceCandidateType.values(), IceCandidateType::value, IceCandidateType::fromValue);
        assertRoundTrip(IceCandidateTcpType.values(), IceCandidateTcpType::value, IceCandidateTcpType::fromValue);
        assertRoundTrip(FingerprintAlgorithm.values(), FingerprintAlgorithm::value, FingerprintAlgorithm::fromValue);
        assertRoundTrip(DtlsRole.values(), DtlsRole::value, DtlsRole::fromValue);
        assertRoundTrip(ConnectionState.values(), ConnectionState::value, ConnectionState::fromValue);
        assertRoundTrip(IceGatheringState.values(), IceGatheringState::value, IceGatheringState::fromValue);
        assertRoundTrip(TransportDirection.values(), TransportDirection::value, TransportDirection::fromValue);
        assertRoundTrip(IceTransportPolicy.values(), IceTransportPolicy::value, IceTransportPolicy::fromValue);

        assertEquals("sha-256", FingerprintAlgorithm.SHA_256.value());
        assertEquals("disconnected", ConnectionState.DISCONNECTED.value());
        assertEquals("gathering", IceGatheringState.GATHERING.value());
        assertThrows(IllegalArgumentException.class, () -> DtlsRole.fromValue("AUTO"));
    }

    @Test
    void ipVersionIsANumber() {
        assertEquals(6, IpVersion.IPV6.value());
        assertEquals(IpVersion.IPV4, IpVersion.fromValue(4));
        assertThrows(IllegalArgumentException.class, () -> IpVersion.fromValue(5));
    }

    private static <E> void assertRoundTrip(E[] constants, Function<E, String> value, Function<String, E> fromValue) {
        for (E constant : constants) {
            assertEquals(constant, fromValue.apply(value.apply(constant)));
        }
    }
}

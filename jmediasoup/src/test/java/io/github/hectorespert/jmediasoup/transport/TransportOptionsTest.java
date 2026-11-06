/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hectorespert.jmediasoup.sctp.SctpParameters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TransportOptionsTest {

    private static final IceParameters ICE = new IceParameters("ufrag", "password", true);
    private static final List<IceCandidate> CANDIDATES = List.of(new IceCandidate(
            "udpcandidate", 1076302079, "10.0.0.1", null, TransportProtocol.UDP, 40000, IceCandidateType.HOST, null));
    private static final DtlsParameters DTLS =
            new DtlsParameters(DtlsRole.AUTO, List.of(new DtlsFingerprint(FingerprintAlgorithm.SHA_256, "A1:B2:C3")));

    @Test
    void builderLeavesTheLocalSettingsAbsent() {
        TransportOptions options =
                TransportOptions.builder("transport-1", ICE, CANDIDATES, DTLS).build();

        assertEquals("transport-1", options.id());
        assertNull(options.sctpParameters());
        assertNull(options.iceTransportPolicy());
        assertEquals(List.of(), options.iceServers());
        assertEquals(Map.of(), options.appData());
    }

    @Test
    void toBuilderAddsLocalSettingsToTheServerParameters() {
        var fromServer = new TransportOptions("transport-1", ICE, CANDIDATES, DTLS, null, null, null, null);

        TransportOptions options = fromServer.toBuilder()
                .sctpParameters(new SctpParameters(5000, 1024, 1024, 262144, null, null))
                .iceServers(List.of(new IceServer(List.of("turn:turn.example.com"), "user", "secret")))
                .iceTransportPolicy(IceTransportPolicy.RELAY)
                .appData(Map.of("room", "lobby"))
                .build();

        assertEquals(CANDIDATES, options.iceCandidates());
        assertEquals(IceTransportPolicy.RELAY, options.iceTransportPolicy());
        assertEquals("lobby", options.appData().get("room"));
        assertEquals(options, options.toBuilder().build());
    }

    @Test
    void appDataAllowsNullValuesAndIsImmutable() {
        var appData = new HashMap<String, Object>();
        appData.put("peer", null);
        TransportOptions options = TransportOptions.builder("transport-1", ICE, CANDIDATES, DTLS)
                .appData(appData)
                .build();
        appData.put("late", "value");

        assertTrue(options.appData().containsKey("peer"));
        assertFalse(options.appData().containsKey("late"));
        assertThrows(
                UnsupportedOperationException.class, () -> options.appData().put("x", 1));
    }

    @Test
    void serverParametersAreRequired() {
        assertEquals(
                "iceParameters",
                assertThrows(
                                NullPointerException.class,
                                () -> TransportOptions.builder("transport-1", null, CANDIDATES, DTLS)
                                        .build())
                        .getMessage());
        assertThrows(
                IllegalArgumentException.class,
                () -> TransportOptions.builder("", ICE, CANDIDATES, DTLS).build());
    }

    @Test
    void iceServerNeedsAUrlAndHidesItsCredential() {
        var turn = new IceServer(List.of("turn:turn.example.com"), "user", "secret");

        assertEquals("IceServer[urls=[turn:turn.example.com], username=user, credential=***]", turn.toString());
        assertEquals(
                List.of("stun:stun.example.com"),
                IceServer.of("stun:stun.example.com").urls());
        assertThrows(IllegalArgumentException.class, () -> new IceServer(List.of(), null, null));
        assertThrows(IllegalArgumentException.class, () -> new IceServer(null, null, null));
    }

    @Test
    void dtlsAndIceParametersCheckTheirFields() {
        assertNull(new DtlsParameters(null, null).role());
        assertEquals(List.of(), new DtlsParameters(null, null).fingerprints());
        assertThrows(IllegalArgumentException.class, () -> new DtlsFingerprint(FingerprintAlgorithm.SHA_256, ""));
        assertThrows(IllegalArgumentException.class, () -> new IceParameters("", "password", null));
        assertThrows(IllegalArgumentException.class, () -> new PlainRtpParameters("10.0.0.1", IpVersion.IPV4, -1));
    }
}

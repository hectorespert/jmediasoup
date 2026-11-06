/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.transport;

import static java.util.Objects.requireNonNull;

import io.github.hectorespert.jmediasoup.internal.Checks;
import org.jspecify.annotations.Nullable;

/**
 * An ICE candidate of a mediasoup transport.
 *
 * <p>mediasoup sends the address both as {@code address} and as the deprecated {@code ip}. Both are
 * optional, so that older and future servers work, but at least one must be present; use
 * {@link #effectiveAddress()} to read it.
 *
 * @param foundation the identifier that lets ICE correlate candidates across transports
 * @param priority the priority of the candidate, between 0 and 2<sup>32</sup>-1
 * @param address the IP address or hostname of the candidate, or {@code null} if absent
 * @param ip the deprecated name of {@code address}, or {@code null} if absent
 * @param protocol the transport protocol
 * @param port the port, between 0 and 65535
 * @param type the candidate type
 * @param tcpType the TCP candidate type, only for TCP, or {@code null} if absent
 */
public record IceCandidate(
        String foundation,
        long priority,
        @Nullable String address,
        @Nullable String ip,
        TransportProtocol protocol,
        int port,
        IceCandidateType type,
        @Nullable IceCandidateTcpType tcpType) {

    /** Checks the fields. */
    public IceCandidate {
        Checks.notEmpty(foundation, "foundation");
        Checks.uint32(priority, "priority");
        if (address == null && ip == null) {
            throw new IllegalArgumentException("address or ip is required");
        }
        requireNonNull(protocol, "protocol");
        Checks.range(port, 0, 65535, "port");
        requireNonNull(type, "type");
    }

    /**
     * Returns the address of the candidate.
     *
     * @return {@code address}, or {@code ip} if it is absent
     */
    public String effectiveAddress() {
        return address != null ? address : requireNonNull(ip);
    }
}

/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.transport;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Transport protocol of an ICE candidate. */
public enum TransportProtocol {
    /** UDP. */
    UDP("udp"),
    /** TCP. */
    TCP("tcp");

    private final String value;

    TransportProtocol(String value) {
        this.value = value;
    }

    /**
     * Returns the value used by mediasoup.
     *
     * @return {@code udp}, {@code tcp}
     */
    @JsonValue
    public String value() {
        return value;
    }

    /**
     * Returns the transport protocol for a value used by mediasoup.
     *
     * @param value {@code udp}, {@code tcp}
     * @return the transport protocol
     * @throws IllegalArgumentException if the value is not a transport protocol
     */
    @JsonCreator
    public static TransportProtocol fromValue(String value) {
        for (TransportProtocol constant : values()) {
            if (constant.value.equals(value)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("Unknown transport protocol: " + value);
    }
}

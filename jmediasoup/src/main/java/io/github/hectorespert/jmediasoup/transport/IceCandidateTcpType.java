/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.transport;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Type of a TCP ICE candidate. */
public enum IceCandidateTcpType {
    /** An active TCP candidate. */
    ACTIVE("active"),
    /** A passive TCP candidate, the only type mediasoup gathers. */
    PASSIVE("passive"),
    /** A simultaneous-open TCP candidate. */
    SO("so");

    private final String value;

    IceCandidateTcpType(String value) {
        this.value = value;
    }

    /**
     * Returns the value used by mediasoup.
     *
     * @return {@code active}, {@code passive}, {@code so}
     */
    @JsonValue
    public String value() {
        return value;
    }

    /**
     * Returns the TCP candidate type for a value used by mediasoup.
     *
     * @param value {@code active}, {@code passive}, {@code so}
     * @return the TCP candidate type
     * @throws IllegalArgumentException if the value is not a TCP candidate type
     */
    @JsonCreator
    public static IceCandidateTcpType fromValue(String value) {
        for (IceCandidateTcpType constant : values()) {
            if (constant.value.equals(value)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("Unknown TCP candidate type: " + value);
    }
}

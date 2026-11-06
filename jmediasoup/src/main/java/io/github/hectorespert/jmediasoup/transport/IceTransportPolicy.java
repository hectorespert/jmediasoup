/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.transport;

/** Which ICE candidates may be used. */
public enum IceTransportPolicy {
    /** Any candidate. */
    ALL("all"),
    /** Only relayed candidates, through a TURN server. */
    RELAY("relay");

    private final String value;

    IceTransportPolicy(String value) {
        this.value = value;
    }

    /**
     * Returns the value used by mediasoup.
     *
     * @return {@code all}, {@code relay}
     */
    public String value() {
        return value;
    }

    /**
     * Returns the ICE transport policy for a value used by mediasoup.
     *
     * @param value {@code all}, {@code relay}
     * @return the ICE transport policy
     * @throws IllegalArgumentException if the value is not an ICE transport policy
     */
    public static IceTransportPolicy fromValue(String value) {
        for (IceTransportPolicy constant : values()) {
            if (constant.value.equals(value)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("Unknown ICE transport policy: " + value);
    }
}

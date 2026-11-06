/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.transport;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Type of an ICE candidate. */
public enum IceCandidateType {
    /** A host candidate, the only type mediasoup gathers. */
    HOST("host"),
    /** A server reflexive candidate. */
    SRFLX("srflx"),
    /** A peer reflexive candidate. */
    PRFLX("prflx"),
    /** A relayed candidate. */
    RELAY("relay");

    private final String value;

    IceCandidateType(String value) {
        this.value = value;
    }

    /**
     * Returns the value used by mediasoup.
     *
     * @return {@code host}, {@code srflx}, {@code prflx}, {@code relay}
     */
    @JsonValue
    public String value() {
        return value;
    }

    /**
     * Returns the ICE candidate type for a value used by mediasoup.
     *
     * @param value {@code host}, {@code srflx}, {@code prflx}, {@code relay}
     * @return the ICE candidate type
     * @throws IllegalArgumentException if the value is not an ICE candidate type
     */
    @JsonCreator
    public static IceCandidateType fromValue(String value) {
        for (IceCandidateType constant : values()) {
            if (constant.value.equals(value)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("Unknown ICE candidate type: " + value);
    }
}

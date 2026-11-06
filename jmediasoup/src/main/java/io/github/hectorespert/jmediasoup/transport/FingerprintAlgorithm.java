/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.transport;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Hash function of a DTLS certificate fingerprint, from the "Hash function Textual Names" registry of RFC 4572. */
public enum FingerprintAlgorithm {
    /** SHA-1. */
    SHA_1("sha-1"),
    /** SHA-224. */
    SHA_224("sha-224"),
    /** SHA-256. */
    SHA_256("sha-256"),
    /** SHA-384. */
    SHA_384("sha-384"),
    /** SHA-512. */
    SHA_512("sha-512");

    private final String value;

    FingerprintAlgorithm(String value) {
        this.value = value;
    }

    /**
     * Returns the value used by mediasoup.
     *
     * @return {@code sha-1}, {@code sha-224}, {@code sha-256}, {@code sha-384}, {@code sha-512}
     */
    @JsonValue
    public String value() {
        return value;
    }

    /**
     * Returns the fingerprint algorithm for a value used by mediasoup.
     *
     * @param value {@code sha-1}, {@code sha-224}, {@code sha-256}, {@code sha-384}, {@code sha-512}
     * @return the fingerprint algorithm
     * @throws IllegalArgumentException if the value is not a fingerprint algorithm
     */
    @JsonCreator
    public static FingerprintAlgorithm fromValue(String value) {
        for (FingerprintAlgorithm constant : values()) {
            if (constant.value.equals(value)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("Unknown fingerprint algorithm: " + value);
    }
}

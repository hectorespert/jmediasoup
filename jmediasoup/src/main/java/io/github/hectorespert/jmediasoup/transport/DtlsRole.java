/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.transport;

/** DTLS role of an endpoint. */
public enum DtlsRole {
    /** Decided during the negotiation. */
    AUTO("auto"),
    /** DTLS client. */
    CLIENT("client"),
    /** DTLS server. */
    SERVER("server");

    private final String value;

    DtlsRole(String value) {
        this.value = value;
    }

    /**
     * Returns the value used by mediasoup.
     *
     * @return {@code auto}, {@code client}, {@code server}
     */
    public String value() {
        return value;
    }

    /**
     * Returns the DTLS role for a value used by mediasoup.
     *
     * @param value {@code auto}, {@code client}, {@code server}
     * @return the DTLS role
     * @throws IllegalArgumentException if the value is not a DTLS role
     */
    public static DtlsRole fromValue(String value) {
        for (DtlsRole constant : values()) {
            if (constant.value.equals(value)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("Unknown DTLS role: " + value);
    }
}

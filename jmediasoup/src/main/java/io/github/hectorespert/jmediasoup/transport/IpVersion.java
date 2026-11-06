/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.transport;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** IP version of an address. mediasoup represents it as a number. */
public enum IpVersion {
    /** IPv4. */
    IPV4(4),
    /** IPv6. */
    IPV6(6);

    private final int value;

    IpVersion(int value) {
        this.value = value;
    }

    /**
     * Returns the value used by mediasoup.
     *
     * @return {@code 4} or {@code 6}
     */
    @JsonValue
    public int value() {
        return value;
    }

    /**
     * Returns the IP version for a value used by mediasoup.
     *
     * @param value {@code 4} or {@code 6}
     * @return the IP version
     * @throws IllegalArgumentException if the value is not an IP version
     */
    @JsonCreator
    public static IpVersion fromValue(int value) {
        for (IpVersion version : values()) {
            if (version.value == value) {
                return version;
            }
        }
        throw new IllegalArgumentException("Unknown IP version: " + value);
    }
}

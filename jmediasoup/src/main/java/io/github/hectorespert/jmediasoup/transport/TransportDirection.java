/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.transport;

/** Direction of a transport, from the point of view of this endpoint. */
public enum TransportDirection {
    /** Sends media to mediasoup. */
    SEND("send"),
    /** Receives media from mediasoup. */
    RECV("recv");

    private final String value;

    TransportDirection(String value) {
        this.value = value;
    }

    /**
     * Returns the value used by mediasoup.
     *
     * @return {@code send}, {@code recv}
     */
    public String value() {
        return value;
    }

    /**
     * Returns the transport direction for a value used by mediasoup.
     *
     * @param value {@code send}, {@code recv}
     * @return the transport direction
     * @throws IllegalArgumentException if the value is not a transport direction
     */
    public static TransportDirection fromValue(String value) {
        for (TransportDirection constant : values()) {
            if (constant.value.equals(value)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("Unknown transport direction: " + value);
    }
}

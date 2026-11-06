/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.rtp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Direction in which mediasoup supports an RTP header extension. Only present in the RTP
 * capabilities of mediasoup, and ignored in those of an endpoint.
 */
public enum RtpHeaderExtensionDirection {
    /** mediasoup can send and receive it. */
    SENDRECV("sendrecv"),
    /** mediasoup can send it but not receive it. */
    SENDONLY("sendonly"),
    /** mediasoup can receive it but not send it. */
    RECVONLY("recvonly"),
    /** mediasoup can neither send nor receive it. */
    INACTIVE("inactive");

    private final String value;

    RtpHeaderExtensionDirection(String value) {
        this.value = value;
    }

    /**
     * Returns the value used by mediasoup.
     *
     * @return {@code sendrecv}, {@code sendonly}, {@code recvonly} or {@code inactive}
     */
    @JsonValue
    public String value() {
        return value;
    }

    /**
     * Returns the direction for a value used by mediasoup.
     *
     * @param value {@code sendrecv}, {@code sendonly}, {@code recvonly} or {@code inactive}
     * @return the direction
     * @throws IllegalArgumentException if the value is not a direction
     */
    @JsonCreator
    public static RtpHeaderExtensionDirection fromValue(String value) {
        for (RtpHeaderExtensionDirection direction : values()) {
            if (direction.value.equals(value)) {
                return direction;
            }
        }
        throw new IllegalArgumentException("Unknown RTP header extension direction: " + value);
    }
}

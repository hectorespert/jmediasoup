/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.rtp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Priority of an encoding, for bandwidth allocation and for the network (DSCP). */
public enum Priority {
    /** Very low priority. */
    VERY_LOW("very-low"),
    /** Low priority, the default in WebRTC. */
    LOW("low"),
    /** Medium priority. */
    MEDIUM("medium"),
    /** High priority. */
    HIGH("high");

    private final String value;

    Priority(String value) {
        this.value = value;
    }

    /**
     * Returns the value used by mediasoup.
     *
     * @return {@code very-low}, {@code low}, {@code medium} or {@code high}
     */
    @JsonValue
    public String value() {
        return value;
    }

    /**
     * Returns the priority for a value used by mediasoup.
     *
     * @param value {@code very-low}, {@code low}, {@code medium} or {@code high}
     * @return the priority
     * @throws IllegalArgumentException if the value is not a priority
     */
    @JsonCreator
    public static Priority fromValue(String value) {
        for (Priority priority : values()) {
            if (priority.value.equals(value)) {
                return priority;
            }
        }
        throw new IllegalArgumentException("Unknown priority: " + value);
    }
}

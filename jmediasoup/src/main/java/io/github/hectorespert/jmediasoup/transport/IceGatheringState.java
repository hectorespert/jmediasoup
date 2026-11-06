/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.transport;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** ICE candidate gathering state of a transport. */
public enum IceGatheringState {
    /** Gathering has not started. */
    NEW("new"),
    /** Candidates are being gathered. */
    GATHERING("gathering"),
    /** Gathering has finished. */
    COMPLETE("complete");

    private final String value;

    IceGatheringState(String value) {
        this.value = value;
    }

    /**
     * Returns the value used by mediasoup-client.
     *
     * @return {@code new}, {@code gathering}, {@code complete}
     */
    @JsonValue
    public String value() {
        return value;
    }

    /**
     * Returns the ICE gathering state for a value used by mediasoup-client.
     *
     * @param value {@code new}, {@code gathering}, {@code complete}
     * @return the ICE gathering state
     * @throws IllegalArgumentException if the value is not a ICE gathering state
     */
    @JsonCreator
    public static IceGatheringState fromValue(String value) {
        for (IceGatheringState constant : values()) {
            if (constant.value.equals(value)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("Unknown ICE gathering state: " + value);
    }
}

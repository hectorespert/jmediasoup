/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.transport;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Connection state of a transport. */
public enum ConnectionState {
    /** Not connected yet. */
    NEW("new"),
    /** ICE or DTLS are connecting. */
    CONNECTING("connecting"),
    /** ICE and DTLS are connected. */
    CONNECTED("connected"),
    /** The connection failed. */
    FAILED("failed"),
    /** The connection was lost, and may recover. */
    DISCONNECTED("disconnected"),
    /** The transport is closed. */
    CLOSED("closed");

    private final String value;

    ConnectionState(String value) {
        this.value = value;
    }

    /**
     * Returns the value used by mediasoup.
     *
     * @return {@code new}, {@code connecting}, {@code connected}, {@code failed}, {@code disconnected}, {@code closed}
     */
    @JsonValue
    public String value() {
        return value;
    }

    /**
     * Returns the connection state for a value used by mediasoup.
     *
     * @param value {@code new}, {@code connecting}, {@code connected}, {@code failed}, {@code disconnected}, {@code closed}
     * @return the connection state
     * @throws IllegalArgumentException if the value is not a connection state
     */
    @JsonCreator
    public static ConnectionState fromValue(String value) {
        for (ConnectionState constant : values()) {
            if (constant.value.equals(value)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("Unknown connection state: " + value);
    }
}

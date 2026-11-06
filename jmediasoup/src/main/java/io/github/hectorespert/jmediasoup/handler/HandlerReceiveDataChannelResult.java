/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.handler;

import static java.util.Objects.requireNonNull;

/**
 * The data channel a handler opens for receiving.
 *
 * @param dataChannel the channel
 */
public record HandlerReceiveDataChannelResult(DataChannel dataChannel) {

    /** Checks the fields. */
    public HandlerReceiveDataChannelResult {
        requireNonNull(dataChannel, "dataChannel");
    }
}

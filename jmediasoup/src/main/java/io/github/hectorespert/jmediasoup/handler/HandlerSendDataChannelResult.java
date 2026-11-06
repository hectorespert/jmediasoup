/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.handler;

import static java.util.Objects.requireNonNull;

import io.github.hectorespert.jmediasoup.sctp.SctpStreamParameters;

/**
 * The data channel a handler opens for sending.
 *
 * @param dataChannel the channel
 * @param sctpStreamParameters the parameters to give mediasoup when producing data, with the stream
 *     id the handler chose
 */
public record HandlerSendDataChannelResult(DataChannel dataChannel, SctpStreamParameters sctpStreamParameters) {

    /** Checks the fields. */
    public HandlerSendDataChannelResult {
        requireNonNull(dataChannel, "dataChannel");
        requireNonNull(sctpStreamParameters, "sctpStreamParameters");
    }
}

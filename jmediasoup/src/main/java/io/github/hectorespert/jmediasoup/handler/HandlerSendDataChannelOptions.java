/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.handler;

import static java.util.Objects.requireNonNull;

import io.github.hectorespert.jmediasoup.sctp.SctpStreamParameters;

/**
 * What data channel to open for sending.
 *
 * @param sctpStreamParameters the reliability, label and protocol of the channel; the stream id is
 *     chosen by the handler
 */
public record HandlerSendDataChannelOptions(SctpStreamParameters sctpStreamParameters) {

    /** Checks the fields. */
    public HandlerSendDataChannelOptions {
        requireNonNull(sctpStreamParameters, "sctpStreamParameters");
    }
}

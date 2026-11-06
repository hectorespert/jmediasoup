/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.handler;

import static java.util.Objects.requireNonNull;

import io.github.hectorespert.jmediasoup.sctp.SctpStreamParameters;
import org.jspecify.annotations.Nullable;

/**
 * What data channel to open for receiving.
 *
 * @param maxMessageSize the maximum message size that mediasoup sends, in bytes, or {@code null}
 * @param sctpStreamParameters the stream id and reliability that mediasoup uses
 * @param label the label, or {@code null} for none
 * @param protocol the sub-protocol, or {@code null} for none
 */
public record HandlerReceiveDataChannelOptions(
        @Nullable Integer maxMessageSize,
        SctpStreamParameters sctpStreamParameters,
        @Nullable String label,
        @Nullable String protocol) {

    /** Checks the fields. */
    public HandlerReceiveDataChannelOptions {
        requireNonNull(sctpStreamParameters, "sctpStreamParameters");
    }
}

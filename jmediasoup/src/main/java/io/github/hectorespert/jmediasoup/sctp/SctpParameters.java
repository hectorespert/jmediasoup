/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.sctp;

import io.github.hectorespert.jmediasoup.internal.Checks;
import org.jspecify.annotations.Nullable;

/**
 * The SCTP parameters of a mediasoup transport.
 *
 * <p>mediasoup up to 3.19 sends {@code OS}, {@code MIS} and {@code maxMessageSize}, and newer
 * versions add {@code maxSendMessageSize} and {@code maxReceiveMessageSize}. Every field except the
 * port is optional so that both work.
 *
 * @param port the SCTP port, always 5000
 * @param os the number of outgoing SCTP streams initially requested ({@code OS}), or {@code null} if
 *     absent
 * @param mis the maximum number of incoming SCTP streams ({@code MIS}), or {@code null} if absent
 * @param maxMessageSize the maximum size of an SCTP message, or {@code null} if absent
 * @param maxSendMessageSize the maximum size of an SCTP message mediasoup sends, or {@code null} if
 *     absent
 * @param maxReceiveMessageSize the maximum size of an SCTP message mediasoup receives, or
 *     {@code null} if absent
 */
public record SctpParameters(
        int port,
        @Nullable Integer os,
        @Nullable Integer mis,
        @Nullable Integer maxMessageSize,
        @Nullable Integer maxSendMessageSize,
        @Nullable Integer maxReceiveMessageSize) {

    /** Checks the fields. */
    public SctpParameters {
        Checks.range(port, 0, 65535, "port");
        Checks.range(os, 1, 65535, "os");
        Checks.range(mis, 1, 65535, "mis");
        Checks.range(maxMessageSize, 1, Integer.MAX_VALUE, "maxMessageSize");
        Checks.range(maxSendMessageSize, 1, Integer.MAX_VALUE, "maxSendMessageSize");
        Checks.range(maxReceiveMessageSize, 1, Integer.MAX_VALUE, "maxReceiveMessageSize");
    }
}

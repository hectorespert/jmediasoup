/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.sctp;

import static java.util.Objects.requireNonNull;

/**
 * The SCTP capabilities of an endpoint.
 *
 * @param numStreams the number of SCTP streams it supports
 */
public record SctpCapabilities(NumSctpStreams numStreams) {

    /** Checks the fields. */
    public SctpCapabilities {
        requireNonNull(numStreams, "numStreams");
    }
}

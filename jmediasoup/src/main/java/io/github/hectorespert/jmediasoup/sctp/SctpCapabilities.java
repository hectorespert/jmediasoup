/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.sctp;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The SCTP capabilities of an endpoint.
 *
 * @param numStreams the number of SCTP streams it supports
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SctpCapabilities(NumSctpStreams numStreams) {

    /** Checks the fields. */
    public SctpCapabilities {
        requireNonNull(numStreams, "numStreams");
    }
}

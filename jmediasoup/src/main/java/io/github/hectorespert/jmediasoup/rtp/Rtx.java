/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.rtp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.hectorespert.jmediasoup.internal.Checks;

/**
 * The RTX stream of an encoding. The equivalent of {@code RTX} in pymediasoup.
 *
 * @param ssrc the SSRC of the RTX stream, between 0 and 2<sup>32</sup>-1
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Rtx(@JsonProperty(required = true) long ssrc) {

    /** Checks the SSRC. */
    public Rtx {
        Checks.ssrc(ssrc, "ssrc");
    }
}

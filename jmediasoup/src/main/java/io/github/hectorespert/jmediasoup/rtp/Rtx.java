/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.rtp;

import io.github.hectorespert.jmediasoup.internal.Checks;

/**
 * The RTX stream of an encoding. The equivalent of {@code RTX} in pymediasoup.
 *
 * @param ssrc the SSRC of the RTX stream, between 0 and 2<sup>32</sup>-1
 */
public record Rtx(long ssrc) {

    /** Checks the SSRC. */
    public Rtx {
        Checks.ssrc(ssrc, "ssrc");
    }
}

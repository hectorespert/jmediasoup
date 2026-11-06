/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.rtp;

import io.github.hectorespert.jmediasoup.internal.Checks;
import java.util.List;

/**
 * The codecs and RTP header extensions that both the local endpoint and mediasoup support. Device
 * computes them; they are only needed to implement a handler.
 *
 * @param codecs the codecs both sides support
 * @param headerExtensions the RTP header extensions both sides support
 */
public record ExtendedRtpCapabilities(List<ExtendedCodec> codecs, List<ExtendedHeaderExtension> headerExtensions) {

    /** Copies the lists, with absent ones as empty. */
    public ExtendedRtpCapabilities {
        codecs = Checks.list(codecs, "codecs");
        headerExtensions = Checks.list(headerExtensions, "headerExtensions");
    }
}

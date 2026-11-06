/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.rtp;

import io.github.hectorespert.jmediasoup.internal.Checks;
import java.util.List;

/**
 * What mediasoup or an endpoint can receive at media level.
 *
 * @param codecs the supported media and RTX codecs
 * @param headerExtensions the supported RTP header extensions
 * @param fecMechanisms the supported FEC mechanisms; only old versions of mediasoup send them
 */
public record RtpCapabilities(
        List<RtpCodecCapability> codecs, List<RtpHeaderExtension> headerExtensions, List<String> fecMechanisms) {

    /** Copies the lists, with absent ones as empty. */
    public RtpCapabilities {
        codecs = Checks.list(codecs, "codecs");
        headerExtensions = Checks.list(headerExtensions, "headerExtensions");
        fecMechanisms = Checks.list(fecMechanisms, "fecMechanisms");
    }
}

/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.sctp;

import io.github.hectorespert.jmediasoup.internal.Checks;

/**
 * The number of SCTP streams. In JSON the fields are {@code OS} and {@code MIS}.
 *
 * @param os the number of outgoing SCTP streams initially requested ({@code OS}), between 1 and
 *     65535
 * @param mis the maximum number of incoming SCTP streams ({@code MIS}), between 1 and 65535
 */
public record NumSctpStreams(int os, int mis) {

    /** Checks the fields. */
    public NumSctpStreams {
        Checks.range(os, 1, 65535, "os");
        Checks.range(mis, 1, 65535, "mis");
    }
}

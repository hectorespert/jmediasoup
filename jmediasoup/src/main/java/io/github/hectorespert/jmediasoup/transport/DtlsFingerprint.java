/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.transport;

import static java.util.Objects.requireNonNull;

import io.github.hectorespert.jmediasoup.internal.Checks;

/**
 * The fingerprint of a DTLS certificate.
 *
 * @param algorithm the hash function
 * @param value the fingerprint, as colon-separated hexadecimal bytes (RFC 4572 section 5)
 */
public record DtlsFingerprint(FingerprintAlgorithm algorithm, String value) {

    /** Checks the fields. */
    public DtlsFingerprint {
        requireNonNull(algorithm, "algorithm");
        Checks.notEmpty(value, "value");
    }
}

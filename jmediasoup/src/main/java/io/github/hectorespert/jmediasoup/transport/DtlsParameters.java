/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.transport;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.hectorespert.jmediasoup.internal.Checks;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The DTLS parameters of an endpoint.
 *
 * @param role the DTLS role, or {@code null} if absent ({@link DtlsRole#AUTO})
 * @param fingerprints the fingerprints of its certificate
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record DtlsParameters(@Nullable DtlsRole role, List<DtlsFingerprint> fingerprints) {

    /** Copies the fingerprints, with absent ones as empty. */
    public DtlsParameters {
        fingerprints = Checks.list(fingerprints, "fingerprints");
    }
}

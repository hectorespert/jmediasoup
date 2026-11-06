/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.transport;

import io.github.hectorespert.jmediasoup.internal.Checks;
import org.jspecify.annotations.Nullable;

/**
 * The ICE parameters of a mediasoup transport.
 *
 * @param usernameFragment the ICE username fragment
 * @param password the ICE password
 * @param iceLite whether mediasoup uses ICE Lite, or {@code null} if absent
 */
public record IceParameters(
        String usernameFragment, String password, @Nullable Boolean iceLite) {

    /** Checks the fields. */
    public IceParameters {
        Checks.notEmpty(usernameFragment, "usernameFragment");
        Checks.notEmpty(password, "password");
    }
}

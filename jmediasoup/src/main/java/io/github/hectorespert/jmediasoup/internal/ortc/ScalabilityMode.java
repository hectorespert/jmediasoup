/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.ortc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The number of spatial and temporal layers of a scalability mode such as {@code L1T3} or
 * {@code S3T3_KEY}, as defined in webrtc-svc.
 *
 * @param spatialLayers the number of spatial layers
 * @param temporalLayers the number of temporal layers
 */
public record ScalabilityMode(int spatialLayers, int temporalLayers) {

    // Only the prefix matters, so suffixes such as _KEY or h are accepted. pymediasoup escapes \d
    // twice in a raw string, so its regex never matches and it always returns one layer of each.
    private static final Pattern PATTERN = Pattern.compile("[LS]([1-9]\\d?)T([1-9]\\d?)");

    private static final ScalabilityMode SINGLE_LAYER = new ScalabilityMode(1, 1);

    /**
     * Parses a scalability mode.
     *
     * @param scalabilityMode the mode, or {@code null}
     * @return its layers, or one spatial and one temporal layer if the mode is absent or not valid
     */
    public static ScalabilityMode parse(@Nullable String scalabilityMode) {
        if (scalabilityMode == null) {
            return SINGLE_LAYER;
        }
        Matcher matcher = PATTERN.matcher(scalabilityMode);
        if (!matcher.lookingAt()) {
            return SINGLE_LAYER;
        }
        return new ScalabilityMode(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }
}

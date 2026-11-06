/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.ortc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ScalabilityModeTest {

    // The cases of mediasoup-client's test.ts and those of TODO.md 2.2.3. pymediasoup returns 1, 1
    // for all of them.
    @ParameterizedTest(name = "{0} -> L{1}T{2}")
    @CsvSource(
            nullValues = "null",
            value = {
                "L1T3, 1, 3",
                "S3T3, 3, 3",
                "L3T3_KEY, 3, 3",
                "L3T2_KEY, 3, 2",
                "S2T3, 2, 3",
                "L20T3, 20, 3",
                "L1T3h, 1, 3",
                "foo, 1, 1",
                "'', 1, 1",
                "null, 1, 1",
                "S0T3, 1, 1",
                "S1T0, 1, 1",
                "S200T3, 1, 1",
                "l1t3, 1, 1",
                "xL1T3, 1, 1"
            })
    void parsesTheLayers(String scalabilityMode, int spatialLayers, int temporalLayers) {
        assertEquals(new ScalabilityMode(spatialLayers, temporalLayers), ScalabilityMode.parse(scalabilityMode));
    }
}

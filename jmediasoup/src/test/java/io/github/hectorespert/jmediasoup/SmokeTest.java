/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SmokeTest {
    @Test
    void testsRunInTheRootPackage() {
        assertEquals("io.github.hectorespert.jmediasoup", getClass().getPackageName());
    }
}

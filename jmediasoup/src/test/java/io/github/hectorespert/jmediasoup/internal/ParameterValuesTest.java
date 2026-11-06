/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParameterValuesTest {

    @Test
    void sameNumberInAnyRepresentationIsTheSame() {
        assertTrue(ParameterValues.same(100, 100L));
        assertTrue(ParameterValues.same(100, "100"));
        assertTrue(ParameterValues.same(100.0, BigInteger.valueOf(100)));
        assertFalse(ParameterValues.same(100, 101));
        assertFalse(ParameterValues.same(100, "100a"));
        assertTrue(ParameterValues.same(Double.NaN, Double.NaN));
        assertFalse(ParameterValues.same(Double.NaN, "NaN"));
    }

    @Test
    void textsCompareExactly() {
        assertTrue(ParameterValues.same("42e01f", "42e01f"));
        assertFalse(ParameterValues.same("42e01f", "42E01F"));
    }

    @Test
    void absentIsOnlyTheSameAsAbsent() {
        assertTrue(ParameterValues.same(null, null));
        assertFalse(ParameterValues.same(null, 0));
    }

    @Test
    void readsIntegersFromNumbersAndTexts() {
        Map<String, Object> parameters = Map.of("apt", 100L, "profile-id", "2", "ratio", 1.5, "name", "x");

        assertEquals(100, ParameterValues.intValue(parameters, "apt"));
        assertEquals(2, ParameterValues.intValue(parameters, "profile-id"));
        assertNull(ParameterValues.intValue(parameters, "ratio"));
        assertNull(ParameterValues.intValue(parameters, "name"));
        assertNull(ParameterValues.intValue(parameters, "missing"));
    }
}

/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal;

import java.math.BigDecimal;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Reads and compares the values of codec parameters, which keep the type they arrived with. The
 * same number can be an {@code Integer}, a {@code Long}, a {@code Double} or a text, depending on
 * the server and the user's {@code ObjectMapper}, so a plain {@code equals} is not enough.
 */
public final class ParameterValues {

    private ParameterValues() {}

    /**
     * Compares two values, treating numbers and numeric texts by their numeric value.
     *
     * @return whether both are absent, or both represent the same number or the same text
     */
    public static boolean same(@Nullable Object a, @Nullable Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        BigDecimal numberA = number(a);
        BigDecimal numberB = number(b);
        if (numberA != null && numberB != null) {
            return numberA.compareTo(numberB) == 0;
        }
        return a.equals(b);
    }

    /**
     * Reads a parameter as an integer.
     *
     * @return the value, or {@code null} if it is absent or is not an integer
     */
    public static @Nullable Integer intValue(Map<String, Object> parameters, String key) {
        BigDecimal number = number(parameters.get(key));
        if (number == null) {
            return null;
        }
        try {
            return number.intValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    private static @Nullable BigDecimal number(@Nullable Object value) {
        String text;
        if (value instanceof Number number) {
            text = number.toString();
        } else if (value instanceof String string) {
            text = string.trim();
        } else {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            // NaN, infinities and non-numeric texts.
            return null;
        }
    }
}

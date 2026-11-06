/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.sdp;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The conversions of JavaScript that sdp-transform relies on, applied to the values of a parsed SDP.
 * A {@code null} value is JavaScript's {@code undefined}, as a missing key is.
 */
final class JavaScript {

    private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?");
    private static final Pattern INFINITY = Pattern.compile("[+-]?Infinity");
    private static final Pattern RADIX = Pattern.compile("0([xXoObB])([0-9a-fA-F]+)");

    private JavaScript() {}

    /**
     * Converts a value as {@code Number(value)} does.
     *
     * @return the number, or {@link Double#NaN} if it is not one
     */
    static double number(@Nullable Object value) {
        return switch (value) {
            case null -> Double.NaN;
            case Number number -> number.doubleValue();
            case Boolean bool -> bool ? 1 : 0;
            case String text -> number(text);
            default -> Double.NaN;
        };
    }

    /**
     * Converts a value as {@code String(value)} does. Integers are written exactly, even beyond the
     * 53 bits that a JavaScript number keeps.
     */
    static String string(@Nullable Object value) {
        return switch (value) {
            case null -> "undefined";
            case String text -> text;
            case Integer number -> number.toString();
            case Long number -> number.toString();
            case BigInteger number -> number.toString();
            case Number number -> numberToString(number.doubleValue());
            case List<?> list ->
                list.stream()
                        .map(element -> element == null ? "" : string(element))
                        .collect(Collectors.joining(","));
            case Map<?, ?> map -> "[object Object]";
            default -> value.toString();
        };
    }

    /** Writes a number as JavaScript does: the shortest digits, with an exponent only if needed. */
    static String numberToString(double number) {
        if (Double.isNaN(number)) {
            return "NaN";
        }
        if (Double.isInfinite(number)) {
            return number > 0 ? "Infinity" : "-Infinity";
        }
        if (number == 0) {
            return "0";
        }
        // Since Java 19, Double.toString gives the shortest digits, like JavaScript.
        BigDecimal shortest = new BigDecimal(Double.toString(number)).stripTrailingZeros();
        double magnitude = Math.abs(number);
        if (magnitude >= 1e-6 && magnitude < 1e21) {
            return shortest.toPlainString();
        }
        String digits = shortest.unscaledValue().abs().toString();
        int exponent = digits.length() - 1 - shortest.scale();
        return (number < 0 ? "-" : "")
                + digits.charAt(0)
                + (digits.length() > 1 ? "." + digits.substring(1) : "")
                + "e"
                + (exponent >= 0 ? "+" : "-")
                + Math.abs(exponent);
    }

    private static double number(String str) {
        String text = str.strip();
        if (text.isEmpty()) {
            return 0;
        }
        if (DECIMAL.matcher(text).matches()) {
            return Double.parseDouble(text);
        }
        if (INFINITY.matcher(text).matches()) {
            return text.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        Matcher radix = RADIX.matcher(text);
        if (radix.matches()) {
            int base = switch (Character.toLowerCase(radix.group(1).charAt(0))) {
                case 'x' -> 16;
                case 'o' -> 8;
                default -> 2;
            };
            try {
                return new BigInteger(radix.group(2), base).doubleValue();
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }
}

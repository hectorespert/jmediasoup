/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Argument checks and defensive copies for the constructors of the public models.
 *
 * <p>They check what does not depend on other fields: required values, ranges and formats. Rules
 * that relate several fields belong to ORTC.
 */
public final class Checks {

    private static final Pattern MIME_TYPE = Pattern.compile("(audio|video)/[^/\\s]+", Pattern.CASE_INSENSITIVE);
    private static final long MAX_UINT32 = 0xFFFF_FFFFL;

    private Checks() {}

    /** Copies a list into an unmodifiable one, with an absent list as empty. */
    public static <T> List<T> list(@Nullable List<? extends T> list, String name) {
        if (list == null) {
            return List.of();
        }
        var copy = new ArrayList<T>(list.size());
        for (int i = 0; i < list.size(); i++) {
            T element = list.get(i);
            if (element == null) {
                throw new NullPointerException(name + "[" + i + "]");
            }
            copy.add(element);
        }
        return Collections.unmodifiableList(copy);
    }

    /**
     * Copies codec or header extension parameters into an unmodifiable map that keeps their order,
     * with an absent map as empty. mediasoup only accepts text and numbers as values.
     */
    public static Map<String, Object> parameters(@Nullable Map<String, ?> parameters, String name) {
        if (parameters == null) {
            return Map.of();
        }
        var copy = new LinkedHashMap<String, Object>();
        parameters.forEach((key, value) -> {
            requireNonNull(key, () -> name + " key");
            if (!(value instanceof String) && !(value instanceof Number)) {
                throw new IllegalArgumentException(name + "." + key + " must be a string or a number: " + value);
            }
            copy.put(key, value);
        });
        return Collections.unmodifiableMap(copy);
    }

    /** Checks that a value is within a closed range. */
    public static int range(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max + ": " + value);
        }
        return value;
    }

    /** Checks that an optional value, if present, is within a closed range. */
    public static @Nullable Integer range(@Nullable Integer value, int min, int max, String name) {
        return value == null ? null : range(value.intValue(), min, max, name);
    }

    /** Checks that an optional value, if present, is not lower than a minimum. */
    public static @Nullable Double atLeast(@Nullable Double value, double min, String name) {
        if (value != null && !(value >= min)) {
            throw new IllegalArgumentException(name + " must be at least " + min + ": " + value);
        }
        return value;
    }

    /** Checks that a value fits in 32 unsigned bits, as SSRCs and ICE priorities do. */
    public static long uint32(long value, String name) {
        if (value < 0 || value > MAX_UINT32) {
            throw new IllegalArgumentException(name + " must be between 0 and " + MAX_UINT32 + ": " + value);
        }
        return value;
    }

    /** Checks that an SSRC fits in 32 unsigned bits. */
    public static long ssrc(long value, String name) {
        return uint32(value, name);
    }

    /** Checks that an optional SSRC, if present, fits in 32 unsigned bits. */
    public static @Nullable Long ssrc(@Nullable Long value, String name) {
        return value == null ? null : uint32(value.longValue(), name);
    }

    /**
     * Copies {@code appData} into an unmodifiable map that keeps its order and allows {@code null}
     * values, with an absent map as empty.
     */
    public static Map<String, @Nullable Object> appData(@Nullable Map<String, ?> appData) {
        if (appData == null) {
            return Map.of();
        }
        var copy = new LinkedHashMap<String, @Nullable Object>();
        appData.forEach((key, value) -> copy.put(requireNonNull(key, "appData key"), value));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Checks the reliability settings of an SCTP stream: at most one of the two limits, and none of
     * them if it is explicitly ordered.
     */
    public static void reliability(
            @Nullable Boolean ordered, @Nullable Integer maxPacketLifeTime, @Nullable Integer maxRetransmits) {
        range(maxPacketLifeTime, 0, 65535, "maxPacketLifeTime");
        range(maxRetransmits, 0, 65535, "maxRetransmits");
        if (maxPacketLifeTime != null && maxRetransmits != null) {
            throw new IllegalArgumentException("cannot provide both maxPacketLifeTime and maxRetransmits");
        }
        if (Boolean.TRUE.equals(ordered) && (maxPacketLifeTime != null || maxRetransmits != null)) {
            throw new IllegalArgumentException("cannot be ordered with maxPacketLifeTime or maxRetransmits");
        }
    }

    /**
     * Checks that a MIME type is {@code audio/subtype} or {@code video/subtype}, in any case, as
     * mediasoup-client accepts it.
     */
    public static String mimeType(String mimeType) {
        requireNonNull(mimeType, "mimeType");
        if (!MIME_TYPE.matcher(mimeType).matches()) {
            throw new IllegalArgumentException("mimeType must be audio/subtype or video/subtype: " + mimeType);
        }
        return mimeType;
    }

    /** Checks that a required text is present and not empty. */
    public static String notEmpty(String value, String name) {
        requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }
}

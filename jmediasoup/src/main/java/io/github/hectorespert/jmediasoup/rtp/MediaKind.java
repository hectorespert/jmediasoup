/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.rtp;

/** Media kind. */
public enum MediaKind {
    /** Audio. */
    AUDIO("audio"),
    /** Video. */
    VIDEO("video");

    private final String value;

    MediaKind(String value) {
        this.value = value;
    }

    /**
     * Returns the value used by mediasoup.
     *
     * @return {@code audio} or {@code video}
     */
    public String value() {
        return value;
    }

    /**
     * Returns the kind for a value used by mediasoup.
     *
     * @param value {@code audio} or {@code video}
     * @return the kind
     * @throws IllegalArgumentException if the value is not a media kind
     */
    public static MediaKind fromValue(String value) {
        for (MediaKind kind : values()) {
            if (kind.value.equals(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown media kind: " + value);
    }
}

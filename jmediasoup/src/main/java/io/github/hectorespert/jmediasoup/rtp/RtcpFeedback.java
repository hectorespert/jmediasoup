/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.rtp;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

/**
 * An RTCP feedback message supported by a codec, either a transport layer one or a codec-specific
 * one.
 *
 * @param type the feedback type, such as {@code nack}, {@code ccm} or {@code transport-cc}
 * @param parameter the feedback parameter, such as {@code pli} or {@code fir}, or {@code null} if it
 *     has none
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record RtcpFeedback(String type, @Nullable String parameter) {

    /** Checks the fields. */
    public RtcpFeedback {
        requireNonNull(type, "type");
    }
}

/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.rtp;

import org.jspecify.annotations.Nullable;

/**
 * The RTCP settings of the RTP parameters.
 *
 * @param cname the canonical name (CNAME) used by RTCP, such as in SDES messages, or {@code null} if
 *     absent
 * @param reducedSize whether reduced size RTCP (RFC 5506) is used instead of compound RTCP (RFC
 *     3550), or {@code null} if absent (reduced size)
 * @param mux whether RTCP-mux is used, or {@code null} if absent (used)
 */
public record RtcpParameters(
        @Nullable String cname,
        @Nullable Boolean reducedSize,
        @Nullable Boolean mux) {}

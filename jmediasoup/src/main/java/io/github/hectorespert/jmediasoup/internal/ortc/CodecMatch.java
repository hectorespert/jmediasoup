/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.ortc;

import java.util.Map;

/**
 * Two codecs that match, with their parameters after the negotiation. For H264 in strict mode both
 * carry the profile-level-id of the answer, which is what the {@code modify} option of the original
 * writes into the codecs; for any other codec they are the original parameters.
 *
 * @param aParameters the negotiated parameters of the first codec
 * @param bParameters the negotiated parameters of the second codec
 */
public record CodecMatch(Map<String, Object> aParameters, Map<String, Object> bParameters) {}

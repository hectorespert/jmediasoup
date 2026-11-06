/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.sdp;

import io.github.hectorespert.jmediasoup.MediasoupClientException;
import io.github.hectorespert.jmediasoup.internal.sdptransform.JavaScript;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpEncodingParameters;
import io.github.hectorespert.jmediasoup.rtp.Rtx;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** The functions of {@code unifiedPlanUtils.ts} of mediasoup-client. */
public final class UnifiedPlanUtils {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private UnifiedPlanUtils() {}

    /**
     * Returns the encodings of a media section of an offer, from its {@code a=ssrc} lines and its
     * {@code a=ssrc-group:FID} lines, in the order of the SSRCs.
     *
     * @param offerMediaObject the parsed media section
     * @param codecs the codecs to send; an encoding only gets its RTX SSRC if there is more than one
     * @return one encoding for each media SSRC
     * @throws MediasoupClientException if there are no {@code a=ssrc} lines
     * @throws IllegalArgumentException if an SSRC does not fit in 32 unsigned bits
     */
    public static List<RtpEncodingParameters> getRtpEncodings(
            Map<String, Object> offerMediaObject, List<RtpCodecParameters> codecs) throws MediasoupClientException {
        var ssrcs = new LinkedHashSet<Long>();

        for (Map<String, Object> line : CommonUtils.list(offerMediaObject.get("ssrcs"))) {
            Object ssrc = line.get("id");
            if (JavaScript.truthy(ssrc)) {
                ssrcs.add(ssrc(ssrc));
            }
        }

        if (ssrcs.isEmpty()) {
            throw new MediasoupClientException("no a=ssrc lines found");
        }

        var ssrcToRtxSsrc = new LinkedHashMap<Long, @Nullable Long>();

        // First assume RTX is used.
        for (Map<String, Object> line : CommonUtils.list(offerMediaObject.get("ssrcGroups"))) {
            if (!"FID".equals(line.get("semantics"))) {
                continue;
            }

            String[] ssrcsStr = WHITESPACE.split(JavaScript.string(line.get("ssrcs")), -1);
            Long ssrc = number(ssrcsStr[0]);
            Long rtxSsrc = ssrcsStr.length > 1 ? number(ssrcsStr[1]) : null;

            if (ssrc != null && ssrcs.contains(ssrc)) {
                // Remove both the SSRC and RTX SSRC from the set so later we know that they are
                // already handled.
                ssrcs.remove(ssrc);
                ssrcs.remove(rtxSsrc);

                // Add to the map.
                ssrcToRtxSsrc.put(ssrc, rtxSsrc);
            }
        }

        // If the set of SSRCs is not empty it means that RTX is not being used, so take media SSRCs
        // from there.
        for (Long ssrc : ssrcs) {
            // Add to the map.
            ssrcToRtxSsrc.put(ssrc, null);
        }

        var encodings = new ArrayList<RtpEncodingParameters>();

        ssrcToRtxSsrc.forEach((ssrc, rtxSsrc) -> {
            // Only add RTX SSRC if there are more than 1 codec.
            boolean rtx = rtxSsrc != null && rtxSsrc != 0 && codecs.size() > 1;
            encodings.add(RtpEncodingParameters.builder()
                    .ssrc(ssrc)
                    .rtx(rtx ? new Rtx(rtxSsrc) : null)
                    .build());
        });

        return encodings;
    }

    private static long ssrc(Object value) {
        if (value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        throw new IllegalArgumentException("invalid SSRC in SDP: " + JavaScript.string(value));
    }

    // Number() of an SSRC in a group, or null if it is not an integer that fits in a long.
    private static @Nullable Long number(String text) {
        double value = JavaScript.number(text);
        if (Double.isNaN(value) || value != Math.rint(value) || Math.abs(value) >= 0x1p63) {
            return null;
        }
        return (long) value;
    }
}

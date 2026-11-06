/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.ortc;

import io.github.hectorespert.jmediasoup.internal.ParameterValues;
import io.github.hectorespert.jmediasoup.internal.h264.H264ProfileLevelId;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtcpFeedback;
import io.github.hectorespert.jmediasoup.rtp.RtpCodec;
import io.github.hectorespert.jmediasoup.rtp.RtpHeaderExtension;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The functions of {@code ortc.py}. Where pymediasoup and mediasoup-client differ, they follow
 * mediasoup-client, whose algorithm is the current one.
 *
 * <p>The models are immutable, so the functions that modify their input in the original return the
 * result instead. Defaults that mediasoup-client applies when it normalizes the capabilities, such as
 * one channel for audio codecs, are applied here where the values are compared.
 */
public final class Ortc {

    /** The MID of the RTP probator. */
    public static final String RTP_PROBATOR_MID = "probator";

    /** The SSRC of the RTP probator. */
    public static final long RTP_PROBATOR_SSRC = 1234;

    /** The payload type of the RTP probator codec. */
    public static final int RTP_PROBATOR_CODEC_PAYLOAD_TYPE = 127;

    private static final Pattern RTX_MIME_TYPE = Pattern.compile(".+/rtx", Pattern.CASE_INSENSITIVE);

    private Ortc() {}

    /**
     * Returns whether a codec is RTX.
     *
     * @param codec the codec, or {@code null}
     * @return whether its MIME type is {@code audio/rtx} or {@code video/rtx}, in any case
     */
    public static boolean isRtxCodec(@Nullable RtpCodec codec) {
        return codec != null && RTX_MIME_TYPE.matcher(codec.mimeType()).matches();
    }

    /**
     * Matches two codecs: the same MIME type (in any case), clock rate and channels and, in strict
     * mode, the same H264 packetization mode and profile, or the same VP9 profile.
     *
     * <p>For H264 in strict mode, the result carries the profile-level-id of the answer in the
     * parameters of both codecs, or none if neither had one.
     *
     * @param a the first codec
     * @param b the second codec
     * @param strict whether to compare the codec-specific parameters
     * @return the negotiated parameters, or {@code null} if the codecs do not match
     */
    public static @Nullable CodecMatch matchCodecs(RtpCodec a, RtpCodec b, boolean strict) {
        String aMimeType = a.mimeType().toLowerCase(Locale.ROOT);
        String bMimeType = b.mimeType().toLowerCase(Locale.ROOT);
        if (!aMimeType.equals(bMimeType)) {
            return null;
        }
        if (a.clockRate() != b.clockRate()) {
            return null;
        }
        if (!Objects.equals(channels(a), channels(b))) {
            return null;
        }

        if (strict && aMimeType.equals("video/h264")) {
            if (!ParameterValues.same(
                    a.parameters().getOrDefault("packetization-mode", 0),
                    b.parameters().getOrDefault("packetization-mode", 0))) {
                return null;
            }
            if (!H264ProfileLevelId.isSameProfile(a.parameters(), b.parameters())) {
                return null;
            }
            String profileLevelId;
            try {
                profileLevelId =
                        H264ProfileLevelId.generateProfileLevelIdStringForAnswer(a.parameters(), b.parameters());
            } catch (IllegalArgumentException e) {
                return null;
            }
            return new CodecMatch(
                    withProfileLevelId(a.parameters(), profileLevelId),
                    withProfileLevelId(b.parameters(), profileLevelId));
        }
        if (strict && aMimeType.equals("video/vp9")) {
            if (!ParameterValues.same(
                    a.parameters().getOrDefault("profile-id", 0), b.parameters().getOrDefault("profile-id", 0))) {
                return null;
            }
        }
        return new CodecMatch(a.parameters(), b.parameters());
    }

    /**
     * Returns the RTCP feedback of the first codec that the second one also supports.
     *
     * @return the matching feedback of the second codec, in the order of the first
     */
    public static List<RtcpFeedback> reduceRtcpFeedback(RtpCodec a, RtpCodec b) {
        var reduced = new ArrayList<RtcpFeedback>();
        for (RtcpFeedback aFeedback : a.rtcpFeedback()) {
            b.rtcpFeedback().stream()
                    .filter(bFeedback -> bFeedback.type().equals(aFeedback.type())
                            && (Objects.equals(bFeedback.parameter(), aFeedback.parameter())
                                    || (isEmpty(bFeedback.parameter()) && isEmpty(aFeedback.parameter()))))
                    .findFirst()
                    .ifPresent(reduced::add);
        }
        return List.copyOf(reduced);
    }

    /**
     * Matches two RTP header extensions: the same URI and, if both have one, the same kind.
     *
     * @return whether they match
     */
    public static boolean matchHeaderExtensions(RtpHeaderExtension a, RtpHeaderExtension b) {
        if (a.kind() != null && b.kind() != null && a.kind() != b.kind()) {
            return false;
        }
        return a.uri().equals(b.uri());
    }

    // mediasoup-client sets one channel on audio codecs without channels and removes them from video
    // codecs before comparing.
    private static @Nullable Integer channels(RtpCodec codec) {
        if (!codec.mimeType().toLowerCase(Locale.ROOT).startsWith(MediaKind.AUDIO.value() + "/")) {
            return null;
        }
        return codec.channels() != null ? codec.channels() : 1;
    }

    private static Map<String, Object> withProfileLevelId(
            Map<String, Object> parameters, @Nullable String profileLevelId) {
        var result = new LinkedHashMap<String, Object>(parameters);
        if (profileLevelId != null) {
            result.put("profile-level-id", profileLevelId);
        } else {
            result.remove("profile-level-id");
        }
        return Collections.unmodifiableMap(result);
    }

    private static boolean isEmpty(@Nullable String text) {
        return text == null || text.isEmpty();
    }
}

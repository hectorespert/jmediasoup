/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.ortc;

import io.github.hectorespert.jmediasoup.internal.ParameterValues;
import io.github.hectorespert.jmediasoup.internal.h264.H264ProfileLevelId;
import io.github.hectorespert.jmediasoup.rtp.ExtendedCodec;
import io.github.hectorespert.jmediasoup.rtp.ExtendedHeaderExtension;
import io.github.hectorespert.jmediasoup.rtp.ExtendedRtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtcpFeedback;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.RtpCodec;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecCapability;
import io.github.hectorespert.jmediasoup.rtp.RtpHeaderExtension;
import io.github.hectorespert.jmediasoup.rtp.RtpHeaderExtensionDirection;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
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

    /**
     * Returns the codecs and RTP header extensions that both sides support. Device computes them
     * once for sending and once for receiving, each with the native capabilities of that direction.
     *
     * <p>Codecs are matched in strict mode and carry the negotiated parameters; an RTX payload type
     * is only set if both sides have RTX for the codec. Header extensions keep the order of the
     * remote side, and their direction is the opposite of the remote one.
     *
     * @param localCaps the native capabilities of the local endpoint
     * @param remoteCaps the capabilities of the router
     * @param preferLocalCodecsOrder whether codecs keep the order of the local side instead of that
     *     of the router
     * @return the extended capabilities
     * @throws IllegalArgumentException if a matching codec, or the RTX codec of one, has no
     *     preferred payload type
     */
    public static ExtendedRtpCapabilities getExtendedRtpCapabilities(
            RtpCapabilities localCaps, RtpCapabilities remoteCaps, boolean preferLocalCodecsOrder) {
        var codecs = new ArrayList<ExtendedCodec>();
        if (preferLocalCodecsOrder) {
            for (RtpCodecCapability localCodec : localCaps.codecs()) {
                if (isRtxCodec(localCodec)) {
                    continue;
                }
                for (RtpCodecCapability remoteCodec : remoteCaps.codecs()) {
                    CodecMatch match = matchCodecs(remoteCodec, localCodec, true);
                    if (match != null) {
                        codecs.add(extendedCodec(
                                localCaps,
                                remoteCaps,
                                localCodec,
                                remoteCodec,
                                match.bParameters(),
                                match.aParameters()));
                        break;
                    }
                }
            }
        } else {
            for (RtpCodecCapability remoteCodec : remoteCaps.codecs()) {
                if (isRtxCodec(remoteCodec)) {
                    continue;
                }
                for (RtpCodecCapability localCodec : localCaps.codecs()) {
                    CodecMatch match = matchCodecs(localCodec, remoteCodec, true);
                    if (match != null) {
                        codecs.add(extendedCodec(
                                localCaps,
                                remoteCaps,
                                localCodec,
                                remoteCodec,
                                match.aParameters(),
                                match.bParameters()));
                        break;
                    }
                }
            }
        }

        var headerExtensions = new ArrayList<ExtendedHeaderExtension>();
        for (RtpHeaderExtension remoteExt : remoteCaps.headerExtensions()) {
            localCaps.headerExtensions().stream()
                    .filter(localExt -> matchHeaderExtensions(localExt, remoteExt))
                    .findFirst()
                    .ifPresent(localExt -> headerExtensions.add(new ExtendedHeaderExtension(
                            remoteExt.kind(),
                            remoteExt.uri(),
                            localExt.preferredId(),
                            remoteExt.preferredId(),
                            Boolean.TRUE.equals(localExt.preferredEncrypt()),
                            reverse(remoteExt.direction()))));
        }

        return new ExtendedRtpCapabilities(codecs, headerExtensions);
    }

    /**
     * Returns the capabilities for receiving media: the codecs with the payload types of the router,
     * and the header extensions that can be received.
     *
     * @param extendedRtpCapabilities the extended capabilities for receiving
     * @return the receiving capabilities
     */
    public static RtpCapabilities getRecvRtpCapabilities(ExtendedRtpCapabilities extendedRtpCapabilities) {
        return getRtpCapabilities(extendedRtpCapabilities, false);
    }

    /**
     * Returns the capabilities for sending media: the codecs with the local payload types, and the
     * header extensions that can be sent.
     *
     * @param extendedRtpCapabilities the extended capabilities for sending
     * @return the sending capabilities
     */
    public static RtpCapabilities getSendRtpCapabilities(ExtendedRtpCapabilities extendedRtpCapabilities) {
        return getRtpCapabilities(extendedRtpCapabilities, true);
    }

    /**
     * Returns whether media of a kind can be sent.
     *
     * @param kind the media kind
     * @param rtpCapabilities the sending capabilities
     * @return whether they have a codec of that kind
     */
    public static boolean canSend(MediaKind kind, RtpCapabilities rtpCapabilities) {
        return rtpCapabilities.codecs().stream().anyMatch(codec -> codec.kind() == kind);
    }

    /**
     * Returns whether the RTP parameters of a consumer can be received.
     *
     * @param rtpParameters the RTP parameters of the consumer
     * @param rtpCapabilities the receiving capabilities
     * @return whether the payload type of their first codec is in the capabilities
     */
    public static boolean canReceive(RtpParameters rtpParameters, RtpCapabilities rtpCapabilities) {
        if (rtpParameters.codecs().isEmpty()) {
            return false;
        }
        int payloadType = rtpParameters.codecs().getFirst().payloadType();
        return rtpCapabilities.codecs().stream()
                .anyMatch(codec -> Objects.equals(codec.preferredPayloadType(), payloadType));
    }

    private static ExtendedCodec extendedCodec(
            RtpCapabilities localCaps,
            RtpCapabilities remoteCaps,
            RtpCodecCapability localCodec,
            RtpCodecCapability remoteCodec,
            Map<String, Object> localParameters,
            Map<String, Object> remoteParameters) {
        int localPayloadType = payloadType(localCodec, "local");
        int remotePayloadType = payloadType(remoteCodec, "remote");
        RtpCodecCapability localRtxCodec = rtxCodec(localCaps, localPayloadType);
        RtpCodecCapability remoteRtxCodec = rtxCodec(remoteCaps, remotePayloadType);
        boolean rtx = localRtxCodec != null && remoteRtxCodec != null;
        return new ExtendedCodec(
                localCodec.kind(),
                localCodec.mimeType(),
                localPayloadType,
                rtx ? payloadType(localRtxCodec, "local") : null,
                remotePayloadType,
                rtx ? payloadType(remoteRtxCodec, "remote") : null,
                localCodec.clockRate(),
                localCodec.channels(),
                localParameters,
                remoteParameters,
                reduceRtcpFeedback(localCodec, remoteCodec));
    }

    private static @Nullable RtpCodecCapability rtxCodec(RtpCapabilities caps, int payloadType) {
        return caps.codecs().stream()
                .filter(codec -> isRtxCodec(codec)
                        && Objects.equals(ParameterValues.intValue(codec.parameters(), "apt"), payloadType))
                .findFirst()
                .orElse(null);
    }

    private static int payloadType(RtpCodecCapability codec, String side) {
        Integer payloadType = codec.preferredPayloadType();
        if (payloadType == null) {
            throw new IllegalArgumentException(
                    "the " + side + " codec " + codec.mimeType() + " has no preferredPayloadType");
        }
        return payloadType;
    }

    // The direction in which the local endpoint can use a header extension that the router supports
    // in the given one. mediasoup-client leaves an absent direction as sendrecv.
    private static RtpHeaderExtensionDirection reverse(@Nullable RtpHeaderExtensionDirection direction) {
        return switch (direction) {
            case null -> RtpHeaderExtensionDirection.SENDRECV;
            case SENDRECV -> RtpHeaderExtensionDirection.SENDRECV;
            case RECVONLY -> RtpHeaderExtensionDirection.SENDONLY;
            case SENDONLY -> RtpHeaderExtensionDirection.RECVONLY;
            case INACTIVE -> RtpHeaderExtensionDirection.INACTIVE;
        };
    }

    private static RtpCapabilities getRtpCapabilities(
            ExtendedRtpCapabilities extendedRtpCapabilities, boolean sending) {
        var codecs = new ArrayList<RtpCodecCapability>();
        for (ExtendedCodec extendedCodec : extendedRtpCapabilities.codecs()) {
            int payloadType = sending ? extendedCodec.localPayloadType() : extendedCodec.remotePayloadType();
            codecs.add(new RtpCodecCapability(
                    extendedCodec.kind(),
                    extendedCodec.mimeType(),
                    payloadType,
                    extendedCodec.clockRate(),
                    extendedCodec.channels(),
                    extendedCodec.localParameters(),
                    extendedCodec.rtcpFeedback()));

            Integer localRtxPayloadType = extendedCodec.localRtxPayloadType();
            Integer remoteRtxPayloadType = extendedCodec.remoteRtxPayloadType();
            if (localRtxPayloadType == null || remoteRtxPayloadType == null) {
                continue;
            }
            codecs.add(new RtpCodecCapability(
                    extendedCodec.kind(),
                    extendedCodec.kind().value() + "/rtx",
                    sending ? localRtxPayloadType : remoteRtxPayloadType,
                    extendedCodec.clockRate(),
                    null,
                    Map.of("apt", payloadType),
                    List.of()));
        }

        RtpHeaderExtensionDirection direction =
                sending ? RtpHeaderExtensionDirection.SENDONLY : RtpHeaderExtensionDirection.RECVONLY;
        var headerExtensions = new ArrayList<RtpHeaderExtension>();
        for (ExtendedHeaderExtension extendedExtension : extendedRtpCapabilities.headerExtensions()) {
            RtpHeaderExtensionDirection extensionDirection = extendedExtension.direction();
            // Leave out the header extensions that are not valid in this direction.
            if (extensionDirection != null
                    && extensionDirection != RtpHeaderExtensionDirection.SENDRECV
                    && extensionDirection != direction) {
                continue;
            }
            headerExtensions.add(new RtpHeaderExtension(
                    extendedExtension.kind(),
                    extendedExtension.uri(),
                    sending ? extendedExtension.sendId() : extendedExtension.recvId(),
                    extendedExtension.encrypt(),
                    extensionDirection));
        }

        return new RtpCapabilities(codecs, headerExtensions, List.of());
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

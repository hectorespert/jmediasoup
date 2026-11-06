/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.ortc;

import io.github.hectorespert.jmediasoup.UnsupportedException;
import io.github.hectorespert.jmediasoup.internal.ParameterValues;
import io.github.hectorespert.jmediasoup.internal.h264.H264ProfileLevelId;
import io.github.hectorespert.jmediasoup.rtp.ExtendedCodec;
import io.github.hectorespert.jmediasoup.rtp.ExtendedHeaderExtension;
import io.github.hectorespert.jmediasoup.rtp.ExtendedRtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtcpFeedback;
import io.github.hectorespert.jmediasoup.rtp.RtcpParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.RtpCodec;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecCapability;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpEncodingParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpHeaderExtension;
import io.github.hectorespert.jmediasoup.rtp.RtpHeaderExtensionDirection;
import io.github.hectorespert.jmediasoup.rtp.RtpHeaderExtensionParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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

    private static final String TRANSPORT_WIDE_CC_URI =
            "http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01";
    private static final String ABS_SEND_TIME_URI = "http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time";
    private static final String TRANSPORT_CC = "transport-cc";
    private static final String GOOG_REMB = "goog-remb";

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

    /**
     * Returns the RTP parameters for sending media of a kind, with the local payload types and
     * codec parameters. The MID and the encodings are left empty, and so is the RTCP.
     *
     * @param kind the media kind
     * @param extendedRtpCapabilities the extended capabilities for sending
     * @return the codecs, each followed by its RTX codec if it has one, and the header extensions
     *     that can be sent
     */
    public static RtpParameters getSendingRtpParameters(
            MediaKind kind, ExtendedRtpCapabilities extendedRtpCapabilities) {
        return sendingRtpParameters(kind, extendedRtpCapabilities, false);
    }

    /**
     * Returns the RTP parameters for sending media of a kind as the remote SDP answer needs them:
     * like {@link #getSendingRtpParameters}, but with the codec parameters of the router, and with
     * the RTCP feedback reduced to one congestion control mechanism: transport-cc if the
     * transport-wide-cc header extension can be sent, otherwise REMB if abs-send-time can, and
     * otherwise none.
     *
     * @param kind the media kind
     * @param extendedRtpCapabilities the extended capabilities for sending
     * @return the RTP parameters for the remote SDP answer
     */
    public static RtpParameters getSendingRemoteRtpParameters(
            MediaKind kind, ExtendedRtpCapabilities extendedRtpCapabilities) {
        RtpParameters rtpParameters = sendingRtpParameters(kind, extendedRtpCapabilities, true);
        Set<String> uris = rtpParameters.headerExtensions().stream()
                .map(RtpHeaderExtensionParameters::uri)
                .collect(Collectors.toSet());
        Set<String> removed;
        if (uris.contains(TRANSPORT_WIDE_CC_URI)) {
            removed = Set.of(GOOG_REMB);
        } else if (uris.contains(ABS_SEND_TIME_URI)) {
            removed = Set.of(TRANSPORT_CC);
        } else {
            removed = Set.of(TRANSPORT_CC, GOOG_REMB);
        }
        List<RtpCodecParameters> codecs = rtpParameters.codecs().stream()
                .map(codec -> new RtpCodecParameters(
                        codec.mimeType(),
                        codec.payloadType(),
                        codec.clockRate(),
                        codec.channels(),
                        codec.parameters(),
                        codec.rtcpFeedback().stream()
                                .filter(feedback -> !removed.contains(feedback.type()))
                                .toList()))
                .toList();
        return new RtpParameters(
                rtpParameters.mid(),
                codecs,
                rtpParameters.headerExtensions(),
                rtpParameters.encodings(),
                rtpParameters.rtcp(),
                rtpParameters.msid());
    }

    /**
     * Reduces the codecs of {@link #getSendingRtpParameters} or {@link
     * #getSendingRemoteRtpParameters} to the one to send, followed by its RTX codec if it has one.
     *
     * @param codecs the codecs, each followed by its RTX codec if it has one
     * @param capCodec the codec the user chose, compared in strict mode, or {@code null} to take the
     *     first one
     * @return the codec to send and its RTX codec
     * @throws UnsupportedException if there are no codecs or none matches the chosen one
     */
    public static List<RtpCodecParameters> reduceCodecs(
            List<RtpCodecParameters> codecs, @Nullable RtpCodecCapability capCodec) throws UnsupportedException {
        for (int i = 0; i < codecs.size(); i++) {
            if (capCodec == null || matchCodecs(codecs.get(i), capCodec, true) != null) {
                if (i + 1 < codecs.size() && isRtxCodec(codecs.get(i + 1))) {
                    return List.of(codecs.get(i), codecs.get(i + 1));
                }
                return List.of(codecs.get(i));
            }
        }
        throw new UnsupportedException("no matching codec found");
    }

    /**
     * Returns the RTP parameters of the consumer for the RTP probator: the first codec of the
     * video, with the probator payload type, and its header extensions.
     *
     * @param videoRtpParameters the RTP parameters of a video producer
     * @return the RTP parameters with the probator MID, SSRC and CNAME
     * @throws IllegalArgumentException if the video RTP parameters have no codecs
     */
    public static RtpParameters generateProbatorRtpParameters(RtpParameters videoRtpParameters) {
        if (videoRtpParameters.codecs().isEmpty()) {
            throw new IllegalArgumentException("the video RTP parameters have no codecs");
        }
        RtpCodecParameters codec = videoRtpParameters.codecs().getFirst();
        return new RtpParameters(
                RTP_PROBATOR_MID,
                List.of(new RtpCodecParameters(
                        codec.mimeType(),
                        RTP_PROBATOR_CODEC_PAYLOAD_TYPE,
                        codec.clockRate(),
                        codec.channels(),
                        codec.parameters(),
                        codec.rtcpFeedback())),
                videoRtpParameters.headerExtensions(),
                List.of(RtpEncodingParameters.builder().ssrc(RTP_PROBATOR_SSRC).build()),
                new RtcpParameters("probator", null, null),
                null);
    }

    private static RtpParameters sendingRtpParameters(
            MediaKind kind, ExtendedRtpCapabilities extendedRtpCapabilities, boolean remote) {
        var codecs = new ArrayList<RtpCodecParameters>();
        for (ExtendedCodec extendedCodec : extendedRtpCapabilities.codecs()) {
            if (extendedCodec.kind() != kind) {
                continue;
            }
            codecs.add(new RtpCodecParameters(
                    extendedCodec.mimeType(),
                    extendedCodec.localPayloadType(),
                    extendedCodec.clockRate(),
                    extendedCodec.channels(),
                    remote ? extendedCodec.remoteParameters() : extendedCodec.localParameters(),
                    extendedCodec.rtcpFeedback()));

            Integer rtxPayloadType = extendedCodec.localRtxPayloadType();
            if (rtxPayloadType != null) {
                codecs.add(new RtpCodecParameters(
                        kind.value() + "/rtx",
                        rtxPayloadType,
                        extendedCodec.clockRate(),
                        null,
                        Map.of("apt", extendedCodec.localPayloadType()),
                        List.of()));
            }
        }

        var headerExtensions = new ArrayList<RtpHeaderExtensionParameters>();
        for (ExtendedHeaderExtension extendedExtension : extendedRtpCapabilities.headerExtensions()) {
            // Leave out the header extensions of another kind and those that cannot be sent.
            MediaKind extensionKind = extendedExtension.kind();
            if ((extensionKind != null && extensionKind != kind)
                    || !isValid(extendedExtension.direction(), RtpHeaderExtensionDirection.SENDONLY)) {
                continue;
            }
            headerExtensions.add(new RtpHeaderExtensionParameters(
                    extendedExtension.uri(), extendedExtension.sendId(), extendedExtension.encrypt(), Map.of()));
        }

        return new RtpParameters(null, codecs, headerExtensions, List.of(), new RtcpParameters(null, null, null), null);
    }

    // Whether a header extension in the given direction is valid when only sending or only
    // receiving; an absent direction is sendrecv.
    private static boolean isValid(
            @Nullable RtpHeaderExtensionDirection extensionDirection, RtpHeaderExtensionDirection direction) {
        return extensionDirection == null
                || extensionDirection == RtpHeaderExtensionDirection.SENDRECV
                || extensionDirection == direction;
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
            if (!isValid(extensionDirection, direction)) {
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

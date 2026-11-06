/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.sdp;

import io.github.hectorespert.jmediasoup.MediasoupClientException;
import io.github.hectorespert.jmediasoup.internal.sdptransform.JavaScript;
import io.github.hectorespert.jmediasoup.internal.sdptransform.Parser;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtcpFeedback;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecCapability;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpHeaderExtension;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
import io.github.hectorespert.jmediasoup.transport.DtlsFingerprint;
import io.github.hectorespert.jmediasoup.transport.DtlsParameters;
import io.github.hectorespert.jmediasoup.transport.DtlsRole;
import io.github.hectorespert.jmediasoup.transport.FingerprintAlgorithm;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The functions of {@code commonUtils.ts} of mediasoup-client, which read the parsed SDP of the
 * local endpoint.
 */
public final class CommonUtils {

    private static final Pattern RTX_MIME_TYPE = Pattern.compile(".+/rtx$", Pattern.CASE_INSENSITIVE);

    private CommonUtils() {}

    /**
     * Extracts the RTP capabilities from an SDP.
     *
     * <p>BUNDLE is assumed so, as per spec, all media sections in the SDP must share the same ids for
     * codecs and RTP extensions. Codecs are indexed by payload type, so a later section replaces the
     * codec of an earlier one; header extensions are indexed by kind and id. An {@code rtcp-fb} with
     * payload {@code *} applies to all the codecs of its kind except RTX, and encrypted header
     * extensions are left out.
     *
     * <p>Codec parameters without a value are {@code ''}, as mediasoup-client leaves them when it
     * validates the capabilities, and {@code profile-level-id} is always a text.
     *
     * @param sdpObject the parsed SDP
     * @return the capabilities of its audio and video sections
     * @throws IllegalArgumentException if a codec or header extension does not fit the models, such
     *     as a codec without clock rate
     */
    public static RtpCapabilities extractRtpCapabilities(Map<String, Object> sdpObject) {
        // Codecs indexed by payload type.
        var codecsMap = new LinkedHashMap<Integer, Codec>();
        // RTP header extensions indexed by kind and preferred id.
        //
        // NOTE: It must be indexed by kind and id rather than just by id. Since all m= sections are
        // bundled, the browser gives the same id to a given extension in every m= section that
        // supports it, so indexing just by id would collapse the audio and the video entry of such
        // an extension into a single one with the kind of the last processed m= section, making the
        // extension vanish for the other kind.
        var headerExtensionMap = new LinkedHashMap<String, RtpHeaderExtension>();

        for (Map<String, Object> m : list(sdpObject.get("media"))) {
            Object type = m.get("type");
            if (!"audio".equals(type) && !"video".equals(type)) {
                continue;
            }
            MediaKind kind = MediaKind.fromValue((String) type);

            // Get codecs.
            for (Map<String, Object> rtp : list(m.get("rtp"))) {
                int payloadType = integer(rtp.get("payload"), "payload type");
                codecsMap.put(
                        payloadType,
                        new Codec(
                                kind,
                                kind.value() + "/" + JavaScript.string(rtp.get("codec")),
                                payloadType,
                                rtp.get("rate"),
                                rtp.get("encoding")));
            }

            // Get codec parameters.
            for (Map<String, Object> fmtp : list(m.get("fmtp"))) {
                Map<String, @Nullable Object> parameters = Parser.parseParams(JavaScript.string(fmtp.get("config")));
                Codec codec = fmtp.get("payload") instanceof Integer payload ? codecsMap.get(payload) : null;
                if (codec == null) {
                    continue;
                }
                // Specials cases to convert parameter value to string.
                if (parameters.containsKey("profile-level-id")) {
                    parameters.put("profile-level-id", JavaScript.string(parameters.get("profile-level-id")));
                }
                codec.parameters = parameters;
            }

            // Get RTCP feedback for each codec.
            for (Map<String, Object> fb : list(m.get("rtcpFb"))) {
                Object subtype = fb.get("subtype");
                var feedback = new RtcpFeedback(
                        JavaScript.string(fb.get("type")),
                        JavaScript.truthy(subtype) ? JavaScript.string(subtype) : null);

                if (!"*".equals(fb.get("payload"))) {
                    // rtcp-fb payload is not '*', so just apply it to its corresponding codec.
                    double payload = JavaScript.number(fb.get("payload"));
                    Codec codec = payload == (int) payload ? codecsMap.get((int) payload) : null;
                    if (codec != null) {
                        codec.rtcpFeedback.add(feedback);
                    }
                } else {
                    // If rtcp-fb payload is '*' it must be applied to all codecs with same kind
                    // (with some exceptions such as RTX codec).
                    for (Codec codec : codecsMap.values()) {
                        if (codec.kind == kind
                                && !RTX_MIME_TYPE.matcher(codec.mimeType).find()) {
                            codec.rtcpFeedback.add(feedback);
                        }
                    }
                }
            }

            // Get RTP header extensions.
            for (Map<String, Object> ext : list(m.get("ext"))) {
                // Ignore encrypted extensions (not yet supported in mediasoup).
                if (JavaScript.truthy(ext.get("encrypt-uri"))) {
                    continue;
                }
                int preferredId = integer(ext.get("value"), "header extension id");
                headerExtensionMap.put(
                        kind.value() + ":" + preferredId,
                        new RtpHeaderExtension(kind, JavaScript.string(ext.get("uri")), preferredId, null, null));
            }
        }

        List<RtpCodecCapability> codecs =
                codecsMap.values().stream().map(Codec::toCapability).toList();
        return new RtpCapabilities(codecs, List.copyOf(headerExtensionMap.values()), List.of());
    }

    /**
     * Extracts the DTLS parameters from an SDP: the {@code a=setup} and {@code a=fingerprint} of the
     * session or, if missing there, of the first media section with a port.
     *
     * @param sdpObject the parsed SDP
     * @return the role ({@code null} if the setup is not {@code active}, {@code passive} or {@code
     *     actpass}) and the fingerprint
     * @throws MediasoupClientException if there is no setup or no fingerprint
     * @throws IllegalArgumentException if the fingerprint algorithm is unknown
     */
    public static DtlsParameters extractDtlsParameters(Map<String, Object> sdpObject) throws MediasoupClientException {
        Object setup = sdpObject.get("setup");
        Object fingerprint = sdpObject.get("fingerprint");

        if (!JavaScript.truthy(setup) || !JavaScript.truthy(fingerprint)) {
            Map<String, Object> mediaObject = list(sdpObject.get("media")).stream()
                    .filter(m -> !Integer.valueOf(0).equals(m.get("port")))
                    .findFirst()
                    .orElse(null);
            if (mediaObject != null) {
                setup = setup != null ? setup : mediaObject.get("setup");
                fingerprint = fingerprint != null ? fingerprint : mediaObject.get("fingerprint");
            }
        }

        if (!JavaScript.truthy(setup)) {
            throw new MediasoupClientException("no a=setup found at SDP session or media level");
        } else if (!JavaScript.truthy(fingerprint)) {
            throw new MediasoupClientException("no a=fingerprint found at SDP session or media level");
        }

        DtlsRole role = switch (JavaScript.string(setup)) {
            case "active" -> DtlsRole.CLIENT;
            case "passive" -> DtlsRole.SERVER;
            case "actpass" -> DtlsRole.AUTO;
            default -> null;
        };
        Map<?, ?> fingerprintObject = (Map<?, ?>) fingerprint;
        return new DtlsParameters(
                role,
                List.of(new DtlsFingerprint(
                        FingerprintAlgorithm.fromValue(JavaScript.string(fingerprintObject.get("type"))),
                        JavaScript.string(fingerprintObject.get("hash")))));
    }

    /**
     * Returns the CNAME of a media section of an offer.
     *
     * @param offerMediaObject the parsed media section
     * @return the value of its first {@code a=ssrc:... cname:...}, or {@code ''} if there is none
     */
    public static String getCname(Map<String, Object> offerMediaObject) {
        return list(offerMediaObject.get("ssrcs")).stream()
                .filter(line -> "cname".equals(line.get("attribute")))
                .findFirst()
                .map(line -> JavaScript.string(line.get("value")))
                .orElse("");
    }

    /**
     * Modifies the codec parameters of an answer according to those of the offer. Only Opus needs it:
     * the answer receives in stereo if the offer sends in stereo ({@code sprop-stereo}).
     *
     * @param offerRtpParameters the RTP parameters of the offer
     * @param answerMediaObject the parsed media section of the answer, which is modified
     */
    @SuppressWarnings("unchecked")
    public static void applyCodecParameters(RtpParameters offerRtpParameters, Map<String, Object> answerMediaObject) {
        for (RtpCodecParameters codec : offerRtpParameters.codecs()) {
            String mimeType = codec.mimeType().toLowerCase(Locale.ROOT);

            // Avoid parsing codec parameters for unhandled codecs.
            if (!mimeType.equals("audio/opus")) {
                continue;
            }

            boolean hasRtp =
                    list(answerMediaObject.get("rtp")).stream().anyMatch(rtp -> payloadIs(rtp, codec.payloadType()));

            if (!hasRtp) {
                continue;
            }

            // Just in case.
            answerMediaObject.putIfAbsent("fmtp", new ArrayList<Map<String, Object>>());
            List<Map<String, Object>> fmtps = (List<Map<String, Object>>) answerMediaObject.get("fmtp");

            Map<String, Object> fmtp = fmtps.stream()
                    .filter(f -> payloadIs(f, codec.payloadType()))
                    .findFirst()
                    .orElse(null);

            if (fmtp == null) {
                fmtp = new LinkedHashMap<>();
                fmtp.put("payload", codec.payloadType());
                fmtp.put("config", "");
                fmtps.add(fmtp);
            }

            Map<String, @Nullable Object> parameters = Parser.parseParams(JavaScript.string(fmtp.get("config")));

            Object spropStereo = codec.parameters().get("sprop-stereo");

            if (spropStereo != null) {
                double value = JavaScript.number(spropStereo);
                parameters.put("stereo", value != 0 && !Double.isNaN(value) ? 1 : 0);
            }

            // Write the codec fmtp.config back.
            var config = new StringBuilder();

            for (Map.Entry<String, @Nullable Object> parameter : parameters.entrySet()) {
                if (!config.isEmpty()) {
                    config.append(';');
                }

                // ⚠️ The original writes "key=undefined" for a parameter without value; write the key alone.
                config.append(parameter.getKey());
                if (parameter.getValue() != null) {
                    config.append('=').append(JavaScript.string(parameter.getValue()));
                }
            }

            fmtp.put("config", config.toString());
        }
    }

    private static boolean payloadIs(Map<String, Object> line, int payloadType) {
        return line.get("payload") instanceof Number payload && payload.doubleValue() == payloadType;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(@Nullable Object value) {
        return value == null ? List.of() : (List<Map<String, Object>>) value;
    }

    private static int integer(@Nullable Object value, String name) {
        if (value instanceof Integer integer) {
            return integer;
        }
        throw new IllegalArgumentException("invalid " + name + " in SDP: " + JavaScript.string(value));
    }

    // A codec while it is being read, which the following lines of its section complete.
    private static final class Codec {

        private final MediaKind kind;
        private final String mimeType;
        private final int preferredPayloadType;
        private final @Nullable Object clockRate;
        private final @Nullable Object channels;
        private Map<String, @Nullable Object> parameters = Map.of();
        private final List<RtcpFeedback> rtcpFeedback = new ArrayList<>();

        Codec(
                MediaKind kind,
                String mimeType,
                int preferredPayloadType,
                @Nullable Object clockRate,
                @Nullable Object channels) {
            this.kind = kind;
            this.mimeType = mimeType;
            this.preferredPayloadType = preferredPayloadType;
            this.clockRate = clockRate;
            this.channels = channels;
        }

        RtpCodecCapability toCapability() {
            if (!(clockRate instanceof Integer rate)) {
                throw new IllegalArgumentException("codec " + mimeType + " has no valid clock rate in SDP");
            }
            if (channels != null && !(channels instanceof Integer)) {
                throw new IllegalArgumentException("codec " + mimeType + " has invalid channels in SDP: " + channels);
            }
            var values = new LinkedHashMap<String, Object>();
            // mediasoup-client turns parameters without a value into '' when it validates them.
            parameters.forEach((key, value) -> values.put(key, value == null ? "" : value));
            return new RtpCodecCapability(
                    kind, mimeType, preferredPayloadType, rate, (Integer) channels, values, rtcpFeedback);
        }
    }
}

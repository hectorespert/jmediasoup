/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.sdp;

import static java.util.Objects.requireNonNull;

import io.github.hectorespert.jmediasoup.ProducerCodecOptions;
import io.github.hectorespert.jmediasoup.internal.sdptransform.JavaScript;
import io.github.hectorespert.jmediasoup.rtp.RtcpFeedback;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpHeaderExtensionParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
import io.github.hectorespert.jmediasoup.sctp.SctpParameters;
import io.github.hectorespert.jmediasoup.transport.DtlsParameters;
import io.github.hectorespert.jmediasoup.transport.DtlsRole;
import io.github.hectorespert.jmediasoup.transport.IceCandidate;
import io.github.hectorespert.jmediasoup.transport.IceParameters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A media section of the remote answer to a local offer, for sending: mediasoup receives what the
 * local endpoint offers to send.
 */
public final class RemoteAnswerMediaSection extends RemoteMediaSection {

    // The RTP parameters of the offer, with the codec options applied.
    private final @Nullable RtpParameters offerRtpParameters;

    /**
     * Creates the answer to a media section of the local offer.
     *
     * @param iceParameters the ICE parameters of the mediasoup transport, or {@code null}
     * @param iceCandidates the ICE candidates of the mediasoup transport, or {@code null}
     * @param dtlsParameters the DTLS parameters of the mediasoup transport, or {@code null}
     * @param offerMediaObject the parsed media section of the local offer
     * @param answerRtpParameters the RTP parameters that mediasoup receives, required for audio and
     *     video sections
     */
    public RemoteAnswerMediaSection(
            @Nullable IceParameters iceParameters,
            @Nullable List<IceCandidate> iceCandidates,
            @Nullable DtlsParameters dtlsParameters,
            Map<String, Object> offerMediaObject,
            @Nullable RtpParameters answerRtpParameters) {
        this(iceParameters, iceCandidates, dtlsParameters, null, offerMediaObject, answerRtpParameters);
    }

    /**
     * Creates the answer to a media section of the local offer, which may be the SCTP association.
     *
     * @param iceParameters the ICE parameters of the mediasoup transport, or {@code null}
     * @param iceCandidates the ICE candidates of the mediasoup transport, or {@code null}
     * @param dtlsParameters the DTLS parameters of the mediasoup transport, or {@code null}
     * @param sctpParameters the SCTP parameters of the mediasoup transport, required for application
     *     sections
     * @param offerMediaObject the parsed media section of the local offer
     * @param answerRtpParameters the RTP parameters that mediasoup receives, required for audio and
     *     video sections
     */
    public RemoteAnswerMediaSection(
            @Nullable IceParameters iceParameters,
            @Nullable List<IceCandidate> iceCandidates,
            @Nullable DtlsParameters dtlsParameters,
            @Nullable SctpParameters sctpParameters,
            Map<String, Object> offerMediaObject,
            @Nullable RtpParameters answerRtpParameters) {
        this(
                iceParameters,
                iceCandidates,
                dtlsParameters,
                sctpParameters,
                offerMediaObject,
                null,
                answerRtpParameters,
                null);
    }

    /**
     * Creates the answer to a media section of the local offer, with the codec options that the
     * producer gives.
     *
     * <p>The codec options change the parameters of the answer, and some of them also those of the
     * offer that mediasoup receives in {@code produce}, which {@link #offerRtpParameters()} returns:
     * {@code opusStereo} ({@code sprop-stereo}), {@code opusFec}, {@code opusDtx}, {@code opusPtime},
     * and the Opus NACK, which both lose unless {@code opusNack} is {@code true}.
     *
     * @param iceParameters the ICE parameters of the mediasoup transport, or {@code null}
     * @param iceCandidates the ICE candidates of the mediasoup transport, or {@code null}
     * @param dtlsParameters the DTLS parameters of the mediasoup transport, or {@code null}
     * @param sctpParameters the SCTP parameters of the mediasoup transport, required for application
     *     sections
     * @param offerMediaObject the parsed media section of the local offer
     * @param offerRtpParameters the RTP parameters that the local endpoint sends, required with codec
     *     options
     * @param answerRtpParameters the RTP parameters that mediasoup receives, required for audio and
     *     video sections
     * @param codecOptions the codec options, or {@code null}
     */
    @SuppressWarnings("unchecked")
    public RemoteAnswerMediaSection(
            @Nullable IceParameters iceParameters,
            @Nullable List<IceCandidate> iceCandidates,
            @Nullable DtlsParameters dtlsParameters,
            @Nullable SctpParameters sctpParameters,
            Map<String, Object> offerMediaObject,
            @Nullable RtpParameters offerRtpParameters,
            @Nullable RtpParameters answerRtpParameters,
            @Nullable ProducerCodecOptions codecOptions) {
        super(iceParameters, iceCandidates, dtlsParameters);

        mediaObject.put("mid", JavaScript.string(offerMediaObject.get("mid")));
        mediaObject.put("type", offerMediaObject.get("type"));
        mediaObject.put("protocol", offerMediaObject.get("protocol"));
        mediaObject.put("connection", defaultConnection());
        mediaObject.put("port", 7);

        Object type = offerMediaObject.get("type");
        if ("audio".equals(type) || "video".equals(type)) {
            requireNonNull(answerRtpParameters, "answerRtpParameters");
            mediaObject.put("direction", "recvonly");
            mediaObject.put("rtp", new ArrayList<Map<String, Object>>());
            mediaObject.put("rtcpFb", new ArrayList<Map<String, Object>>());
            mediaObject.put("fmtp", new ArrayList<Map<String, Object>>());

            // The codecs of the offer, by payload type, which the codec options may change.
            var offerCodecs = new LinkedHashMap<Integer, RtpCodecParameters>();
            if (codecOptions != null) {
                requireNonNull(offerRtpParameters, "offerRtpParameters");
                for (RtpCodecParameters offerCodec : offerRtpParameters.codecs()) {
                    offerCodecs.putIfAbsent(offerCodec.payloadType(), offerCodec);
                }
            }

            for (RtpCodecParameters codec : answerRtpParameters.codecs()) {
                var codecParameters = new LinkedHashMap<String, Object>(codec.parameters());
                List<RtcpFeedback> codecRtcpFeedback = codec.rtcpFeedback();

                if (codecOptions != null) {
                    RtpCodecParameters offerCodec = offerCodecs.get(codec.payloadType());

                    switch (codec.mimeType().toLowerCase(Locale.ROOT)) {
                        case "audio/opus", "audio/multiopus" -> {
                            requireNonNull(offerCodec, "offer codec");
                            var offerCodecParameters = new LinkedHashMap<String, Object>(offerCodec.parameters());
                            List<RtcpFeedback> offerCodecRtcpFeedback = offerCodec.rtcpFeedback();

                            if (codecOptions.opusStereo() != null) {
                                offerCodecParameters.put("sprop-stereo", codecOptions.opusStereo() ? 1 : 0);
                                codecParameters.put("stereo", codecOptions.opusStereo() ? 1 : 0);
                            }

                            if (codecOptions.opusFec() != null) {
                                offerCodecParameters.put("useinbandfec", codecOptions.opusFec() ? 1 : 0);
                                codecParameters.put("useinbandfec", codecOptions.opusFec() ? 1 : 0);
                            }

                            if (codecOptions.opusDtx() != null) {
                                offerCodecParameters.put("usedtx", codecOptions.opusDtx() ? 1 : 0);
                                codecParameters.put("usedtx", codecOptions.opusDtx() ? 1 : 0);
                            }

                            if (codecOptions.opusMaxPlaybackRate() != null) {
                                codecParameters.put("maxplaybackrate", codecOptions.opusMaxPlaybackRate());
                            }

                            if (codecOptions.opusMaxAverageBitrate() != null) {
                                codecParameters.put("maxaveragebitrate", codecOptions.opusMaxAverageBitrate());
                            }

                            if (codecOptions.opusPtime() != null) {
                                offerCodecParameters.put("ptime", codecOptions.opusPtime());
                                codecParameters.put("ptime", codecOptions.opusPtime());
                            }

                            // If opusNack is not set, we must remove NACK support for OPUS.
                            // Otherwise it would be enabled for those handlers that artificially
                            // announce it in their RTP capabilities.
                            if (!Boolean.TRUE.equals(codecOptions.opusNack())) {
                                offerCodecRtcpFeedback = withoutNack(offerCodecRtcpFeedback);
                                codecRtcpFeedback = withoutNack(codecRtcpFeedback);
                            }

                            offerCodecs.put(
                                    offerCodec.payloadType(),
                                    new RtpCodecParameters(
                                            offerCodec.mimeType(),
                                            offerCodec.payloadType(),
                                            offerCodec.clockRate(),
                                            offerCodec.channels(),
                                            offerCodecParameters,
                                            offerCodecRtcpFeedback));
                        }
                        case "video/vp8", "video/vp9", "video/h264", "video/h265", "video/av1" -> {
                            if (codecOptions.videoGoogleStartBitrate() != null) {
                                codecParameters.put("x-google-start-bitrate", codecOptions.videoGoogleStartBitrate());
                            }

                            if (codecOptions.videoGoogleMaxBitrate() != null) {
                                codecParameters.put("x-google-max-bitrate", codecOptions.videoGoogleMaxBitrate());
                            }

                            if (codecOptions.videoGoogleMinBitrate() != null) {
                                codecParameters.put("x-google-min-bitrate", codecOptions.videoGoogleMinBitrate());
                            }
                        }
                        default -> {}
                    }
                }

                addCodec(codec, codecParameters, codecRtcpFeedback);
            }

            setPayloads(answerRtpParameters.codecs());

            var ext = new ArrayList<Map<String, Object>>();
            var offerExt = CommonUtils.list(offerMediaObject.get("ext"));
            for (RtpHeaderExtensionParameters headerExtension : answerRtpParameters.headerExtensions()) {
                // Don't add a header extension if not present in the offer.
                boolean found = offerExt.stream()
                        .anyMatch(localExt -> headerExtension.uri().equals(localExt.get("uri")));
                if (!found) {
                    continue;
                }
                var extObject = new LinkedHashMap<String, Object>();
                extObject.put("uri", headerExtension.uri());
                extObject.put("value", headerExtension.id());
                ext.add(extObject);
            }
            mediaObject.put("ext", ext);

            // Allow both 1 byte and 2 bytes length header extensions since mediasoup can receive
            // both at any time.
            if ("extmap-allow-mixed".equals(offerMediaObject.get("extmapAllowMixed"))) {
                mediaObject.put("extmapAllowMixed", "extmap-allow-mixed");
            }

            // Simulcast.
            if (offerMediaObject.get("simulcast") instanceof Map<?, ?> simulcast) {
                var answerSimulcast = new LinkedHashMap<String, Object>();
                answerSimulcast.put("dir1", "recv");
                answerSimulcast.put("list1", simulcast.get("list1"));
                mediaObject.put("simulcast", answerSimulcast);

                mediaObject.put("rids", receivingRids(offerMediaObject));
            }
            // Simulcast (draft version 03).
            else if (offerMediaObject.get("simulcast_03") instanceof Map<?, ?> simulcast03) {
                var answerSimulcast03 = new LinkedHashMap<String, Object>();
                answerSimulcast03.put(
                        "value", JavaScript.string(simulcast03.get("value")).replace("send", "recv"));
                mediaObject.put("simulcast_03", answerSimulcast03);

                mediaObject.put("rids", receivingRids(offerMediaObject));
            }

            mediaObject.put("rtcpMux", "rtcp-mux");
            mediaObject.put("rtcpRsize", "rtcp-rsize");

            this.offerRtpParameters = offerRtpParameters == null || codecOptions == null
                    ? offerRtpParameters
                    : new RtpParameters(
                            offerRtpParameters.mid(),
                            offerRtpParameters.codecs().stream()
                                    .map(offerCodec -> offerCodecs.getOrDefault(offerCodec.payloadType(), offerCodec))
                                    .toList(),
                            offerRtpParameters.headerExtensions(),
                            offerRtpParameters.encodings(),
                            offerRtpParameters.rtcp(),
                            offerRtpParameters.msid());
        } else if ("application".equals(type)) {
            this.offerRtpParameters = offerRtpParameters;
            requireNonNull(sctpParameters, "sctpParameters");
            // New spec.
            if (offerMediaObject.get("sctpPort") instanceof Number) {
                mediaObject.put("payloads", "webrtc-datachannel");
                mediaObject.put("sctpPort", sctpParameters.port());
                putIfPresent("maxMessageSize", sctpParameters.maxMessageSize());
            }
            // Old spec.
            else if (offerMediaObject.get("sctpmap") != null) {
                mediaObject.put("payloads", String.valueOf(sctpParameters.port()));
                mediaObject.put("sctpmap", sctpmap(sctpParameters));
            }
        } else {
            this.offerRtpParameters = offerRtpParameters;
        }
    }

    /**
     * Returns the RTP parameters of the offer, with the changes of the codec options.
     *
     * @return the RTP parameters that mediasoup receives in {@code produce}, or {@code null} if none
     *     were given
     */
    public @Nullable RtpParameters offerRtpParameters() {
        return offerRtpParameters;
    }

    @Override
    public void setDtlsRole(@Nullable DtlsRole role) {
        if (role == null) {
            return;
        }
        mediaObject.put(
                "setup",
                switch (role) {
                    case CLIENT -> "active";
                    case SERVER -> "passive";
                    case AUTO -> "actpass";
                });
    }

    @Override
    public void resume() {
        mediaObject.put("direction", "recvonly");
    }

    // The rids that the offer sends, which the answer receives.
    private static List<Map<String, Object>> receivingRids(Map<String, Object> offerMediaObject) {
        var rids = new ArrayList<Map<String, Object>>();

        for (Map<String, Object> rid : CommonUtils.list(offerMediaObject.get("rids"))) {
            if (!"send".equals(rid.get("direction"))) {
                continue;
            }

            var answerRid = new LinkedHashMap<String, Object>();
            answerRid.put("id", rid.get("id"));
            answerRid.put("direction", "recv");
            rids.add(answerRid);
        }

        return rids;
    }

    // Keeps the NACK feedback with a parameter, such as nack pli, and removes the plain one.
    private static List<RtcpFeedback> withoutNack(List<RtcpFeedback> rtcpFeedback) {
        return rtcpFeedback.stream()
                .filter(fb -> !"nack".equals(fb.type())
                        || (fb.parameter() != null && !fb.parameter().isEmpty()))
                .toList();
    }
}

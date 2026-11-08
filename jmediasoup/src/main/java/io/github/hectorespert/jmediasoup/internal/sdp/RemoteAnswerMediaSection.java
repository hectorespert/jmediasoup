/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.sdp;

import static java.util.Objects.requireNonNull;

import io.github.hectorespert.jmediasoup.internal.sdptransform.JavaScript;
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
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A media section of the remote answer to a local offer, for sending: mediasoup receives what the
 * local endpoint offers to send.
 *
 * <p>Codec options and simulcast arrive with the phase that uses them.
 */
public final class RemoteAnswerMediaSection extends RemoteMediaSection {

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
    @SuppressWarnings("unchecked")
    public RemoteAnswerMediaSection(
            @Nullable IceParameters iceParameters,
            @Nullable List<IceCandidate> iceCandidates,
            @Nullable DtlsParameters dtlsParameters,
            @Nullable SctpParameters sctpParameters,
            Map<String, Object> offerMediaObject,
            @Nullable RtpParameters answerRtpParameters) {
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

            for (RtpCodecParameters codec : answerRtpParameters.codecs()) {
                addCodec(codec, codec.parameters(), codec.rtcpFeedback());
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

            mediaObject.put("rtcpMux", "rtcp-mux");
            mediaObject.put("rtcpRsize", "rtcp-rsize");
        } else if ("application".equals(type)) {
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
        }
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
}

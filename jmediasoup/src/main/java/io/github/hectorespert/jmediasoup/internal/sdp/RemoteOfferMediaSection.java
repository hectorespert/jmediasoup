/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.sdp;

import static java.util.Objects.requireNonNull;

import io.github.hectorespert.jmediasoup.rtp.RtpCodecParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpEncodingParameters;
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
 * A media section of a remote offer, for receiving: mediasoup offers to send what a consumer
 * receives, or the SCTP association of the data consumers.
 */
public final class RemoteOfferMediaSection extends RemoteMediaSection {

    /**
     * Creates a media section of the remote offer.
     *
     * @param iceParameters the ICE parameters of the mediasoup transport, or {@code null}
     * @param iceCandidates the ICE candidates of the mediasoup transport, or {@code null}
     * @param dtlsParameters the DTLS parameters of the mediasoup transport, or {@code null}
     * @param mid the MID of the section
     * @param kind {@code audio}, {@code video} or {@code application}
     * @param offerRtpParameters the RTP parameters that mediasoup sends, required for audio and video
     * @param streamId the stream of the {@code a=msid}, or {@code null}
     * @param trackId the track of the {@code a=msid}, or {@code null}
     */
    public RemoteOfferMediaSection(
            @Nullable IceParameters iceParameters,
            @Nullable List<IceCandidate> iceCandidates,
            @Nullable DtlsParameters dtlsParameters,
            String mid,
            String kind,
            @Nullable RtpParameters offerRtpParameters,
            @Nullable String streamId,
            @Nullable String trackId) {
        this(
                iceParameters,
                iceCandidates,
                dtlsParameters,
                null,
                mid,
                kind,
                offerRtpParameters,
                streamId,
                trackId,
                false);
    }

    /**
     * Creates a media section of the remote offer, which may be the SCTP association.
     *
     * @param iceParameters the ICE parameters of the mediasoup transport, or {@code null}
     * @param iceCandidates the ICE candidates of the mediasoup transport, or {@code null}
     * @param dtlsParameters the DTLS parameters of the mediasoup transport, or {@code null}
     * @param sctpParameters the SCTP parameters of the mediasoup transport, required for application
     *     sections, which they make {@code UDP/DTLS/SCTP}
     * @param mid the MID of the section
     * @param kind {@code audio}, {@code video} or {@code application}
     * @param offerRtpParameters the RTP parameters that mediasoup sends, required for audio and video
     * @param streamId the stream of the {@code a=msid}, or {@code null}
     * @param trackId the track of the {@code a=msid}, or {@code null}
     * @param oldDataChannelSpec whether to write the SCTP association with the old {@code a=sctpmap}
     *     instead of {@code a=sctp-port}
     */
    @SuppressWarnings("unchecked")
    public RemoteOfferMediaSection(
            @Nullable IceParameters iceParameters,
            @Nullable List<IceCandidate> iceCandidates,
            @Nullable DtlsParameters dtlsParameters,
            @Nullable SctpParameters sctpParameters,
            String mid,
            String kind,
            @Nullable RtpParameters offerRtpParameters,
            @Nullable String streamId,
            @Nullable String trackId,
            boolean oldDataChannelSpec) {
        super(iceParameters, iceCandidates, dtlsParameters);

        mediaObject.put("mid", mid);
        mediaObject.put("type", kind);
        mediaObject.put("connection", defaultConnection());
        mediaObject.put("protocol", sctpParameters == null ? "UDP/TLS/RTP/SAVPF" : "UDP/DTLS/SCTP");
        mediaObject.put("port", 7);

        // Allow both 1 byte and 2 bytes length header extensions since mediasoup can send both at
        // any time.
        mediaObject.put("extmapAllowMixed", "extmap-allow-mixed");

        if (kind.equals("audio") || kind.equals("video")) {
            requireNonNull(offerRtpParameters, "offerRtpParameters");
            mediaObject.put("direction", "sendonly");
            mediaObject.put("rtp", new ArrayList<Map<String, Object>>());
            mediaObject.put("rtcpFb", new ArrayList<Map<String, Object>>());
            mediaObject.put("fmtp", new ArrayList<Map<String, Object>>());
            var msid = new LinkedHashMap<String, Object>();
            if (streamId != null) {
                msid.put("id", streamId);
            }
            if (trackId != null) {
                msid.put("appdata", trackId);
            }
            mediaObject.put("msid", new ArrayList<>(List.of(msid)));

            for (RtpCodecParameters codec : offerRtpParameters.codecs()) {
                addCodec(codec, codec.parameters(), codec.rtcpFeedback());
            }

            setPayloads(offerRtpParameters.codecs());

            var ext = new ArrayList<Map<String, Object>>();
            for (RtpHeaderExtensionParameters headerExtension : offerRtpParameters.headerExtensions()) {
                var extObject = new LinkedHashMap<String, Object>();
                extObject.put("uri", headerExtension.uri());
                extObject.put("value", headerExtension.id());
                ext.add(extObject);
            }
            mediaObject.put("ext", ext);

            mediaObject.put("rtcpMux", "rtcp-mux");
            mediaObject.put("rtcpRsize", "rtcp-rsize");

            RtpEncodingParameters encoding = offerRtpParameters.encodings().getFirst();
            Long ssrc = encoding.ssrc();
            Long rtxSsrc = encoding.rtx() != null ? encoding.rtx().ssrc() : null;
            String cname = offerRtpParameters.rtcp() != null
                    ? offerRtpParameters.rtcp().cname()
                    : null;

            var ssrcs = new ArrayList<Map<String, Object>>();
            var ssrcGroups = new ArrayList<Map<String, Object>>();

            if (isSet(ssrc) && isSet(cname)) {
                ssrcs.add(ssrcLine(ssrc, cname));
            }

            if (isSet(rtxSsrc)) {
                if (isSet(cname)) {
                    ssrcs.add(ssrcLine(rtxSsrc, cname));
                }

                // Associate original and retransmission SSRCs.
                if (isSet(ssrc)) {
                    var group = new LinkedHashMap<String, Object>();
                    group.put("semantics", "FID");
                    group.put("ssrcs", ssrc + " " + rtxSsrc);
                    ssrcGroups.add(group);
                }
            }

            mediaObject.put("ssrcs", ssrcs);
            mediaObject.put("ssrcGroups", ssrcGroups);
        } else if (kind.equals("application")) {
            requireNonNull(sctpParameters, "sctpParameters");
            // New spec.
            if (!oldDataChannelSpec) {
                mediaObject.put("payloads", "webrtc-datachannel");
                mediaObject.put("sctpPort", sctpParameters.port());
                putIfPresent("maxMessageSize", sctpParameters.maxMessageSize());
            }
            // Old spec.
            else {
                mediaObject.put("payloads", String.valueOf(sctpParameters.port()));
                mediaObject.put("sctpmap", sctpmap(sctpParameters));
            }
        }
    }

    @Override
    public void setDtlsRole(@Nullable DtlsRole role) {
        // Always 'actpass'.
        mediaObject.put("setup", "actpass");
    }

    @Override
    public void resume() {
        mediaObject.put("direction", "sendonly");
    }

    private static Map<String, Object> ssrcLine(long ssrc, String cname) {
        var line = new LinkedHashMap<String, Object>();
        line.put("id", ssrc);
        line.put("attribute", "cname");
        line.put("value", cname);
        return line;
    }

    // Whether a value is truthy: an SSRC of 0 or an empty CNAME count as absent, as in the original.
    private static boolean isSet(@Nullable Object value) {
        return value instanceof Long number ? number != 0 : value instanceof String text && !text.isEmpty();
    }
}

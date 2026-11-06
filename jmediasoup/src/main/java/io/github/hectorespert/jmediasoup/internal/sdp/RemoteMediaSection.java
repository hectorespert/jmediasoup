/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.sdp;

import io.github.hectorespert.jmediasoup.internal.sdptransform.JavaScript;
import io.github.hectorespert.jmediasoup.rtp.RtcpFeedback;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecParameters;
import io.github.hectorespert.jmediasoup.transport.DtlsParameters;
import io.github.hectorespert.jmediasoup.transport.DtlsRole;
import io.github.hectorespert.jmediasoup.transport.IceCandidate;
import io.github.hectorespert.jmediasoup.transport.IceParameters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A media section of the remote SDP that the local endpoint negotiates with, as {@code
 * RemoteMediaSection} of mediasoup-client. It keeps the section as a parsed SDP media object, which
 * {@code RemoteSdp} writes.
 */
public abstract class RemoteMediaSection {

    private static final Pattern MIME_TYPE = Pattern.compile("^(audio|video)/(.+)", Pattern.CASE_INSENSITIVE);

    /** The SDP media object. */
    protected final Map<String, Object> mediaObject = new LinkedHashMap<>();

    /**
     * Creates the media section with the ICE and DTLS parameters of the mediasoup transport.
     *
     * @param iceParameters the ICE parameters, or {@code null}
     * @param iceCandidates the ICE candidates, or {@code null} to write none
     * @param dtlsParameters the DTLS parameters, or {@code null}
     */
    protected RemoteMediaSection(
            @Nullable IceParameters iceParameters,
            @Nullable List<IceCandidate> iceCandidates,
            @Nullable DtlsParameters dtlsParameters) {
        mediaObject.put("type", "");
        mediaObject.put("port", 0);
        mediaObject.put("protocol", "");
        mediaObject.put("payloads", "");
        mediaObject.put("rtp", new ArrayList<Map<String, Object>>());
        mediaObject.put("fmtp", new ArrayList<Map<String, Object>>());

        if (iceParameters != null) {
            setIceParameters(iceParameters);
        }

        if (iceCandidates != null) {
            var candidates = new ArrayList<Map<String, Object>>();
            for (IceCandidate candidate : iceCandidates) {
                var candidateObject = new LinkedHashMap<String, Object>();
                candidateObject.put("foundation", candidate.foundation());
                // mediasoup does mandates rtcp-mux so candidates component is always RTP (1).
                candidateObject.put("component", 1);
                // Be ready for new candidate.address field in mediasoup server side field and keep
                // backward compatibility with deprecated candidate.ip.
                candidateObject.put("ip", candidate.effectiveAddress());
                candidateObject.put("port", candidate.port());
                candidateObject.put("priority", candidate.priority());
                candidateObject.put("transport", candidate.protocol().value());
                candidateObject.put("type", candidate.type().value());
                if (candidate.tcpType() != null) {
                    candidateObject.put("tcptype", candidate.tcpType().value());
                }
                candidates.add(candidateObject);
            }
            mediaObject.put("candidates", candidates);
            mediaObject.put("endOfCandidates", "end-of-candidates");
            mediaObject.put("iceOptions", "renomination");
        }

        if (dtlsParameters != null) {
            setDtlsRole(dtlsParameters.role());
        }
    }

    /**
     * Sets the {@code a=setup} of the section for the DTLS role of mediasoup.
     *
     * @param role the role of mediasoup, or {@code null}
     */
    public abstract void setDtlsRole(@Nullable DtlsRole role);

    /**
     * Returns the MID.
     *
     * @return the {@code a=mid} of the section, as text
     */
    public String mid() {
        return JavaScript.string(mediaObject.get("mid"));
    }

    /**
     * Returns whether the section is closed.
     *
     * @return whether the port of its {@code m=} line is 0
     */
    public boolean closed() {
        return Integer.valueOf(0).equals(mediaObject.get("port"));
    }

    /**
     * Returns the SDP media object, which the caller may change.
     *
     * @return the parsed media section
     */
    public Map<String, Object> getObject() {
        return mediaObject;
    }

    /**
     * Sets the ICE username fragment and password.
     *
     * @param iceParameters the ICE parameters of the mediasoup transport
     */
    public void setIceParameters(IceParameters iceParameters) {
        mediaObject.put("iceUfrag", iceParameters.usernameFragment());
        mediaObject.put("icePwd", iceParameters.password());
    }

    /** Makes the section {@code inactive}. */
    public void pause() {
        mediaObject.put("direction", "inactive");
    }

    /** Restores the direction of the section. */
    public abstract void resume();

    /** Disables the section, so that it can be reused later. */
    public void disable() {
        pause();
    }

    /** Closes the section: it is disabled, its port set to 0 and its ICE, RTP and SSRC lines removed. */
    public void close() {
        disable();

        // Set port in m= line to 0, which means that the media section is closed.
        mediaObject.put("port", 0);

        // NOTE: Do not remove header extensions since it's controversial in the spec.
        for (String key : List.of(
                "candidates",
                "endOfCandidates",
                "iceUfrag",
                "icePwd",
                "iceOptions",
                "rtcp",
                "rtcpFb",
                "ssrcs",
                "ssrcGroups",
                "simulcast",
                "simulcast_03",
                "rids",
                "extmapAllowMixed")) {
            mediaObject.remove(key);
        }
        mediaObject.put("rtp", new ArrayList<Map<String, Object>>());
        mediaObject.put("fmtp", new ArrayList<Map<String, Object>>());
    }

    /**
     * Returns the name of a codec in {@code a=rtpmap}.
     *
     * @param codec the codec
     * @return the subtype of its MIME type, such as {@code opus} for {@code audio/opus}
     * @throws IllegalArgumentException if the MIME type is not audio or video
     */
    static String getCodecName(RtpCodecParameters codec) {
        Matcher mimeTypeMatch = MIME_TYPE.matcher(codec.mimeType());
        if (!mimeTypeMatch.find()) {
            throw new IllegalArgumentException("invalid codec.mimeType");
        }
        return mimeTypeMatch.group(2);
    }

    /**
     * Adds the {@code a=rtpmap}, {@code a=fmtp} and {@code a=rtcp-fb} lines of a codec.
     *
     * @param codec the codec
     * @param parameters the parameters to write in {@code a=fmtp}, which is left out if there are none
     * @param rtcpFeedback the feedback to write in {@code a=rtcp-fb}
     */
    @SuppressWarnings("unchecked")
    protected void addCodec(RtpCodecParameters codec, Map<String, Object> parameters, List<RtcpFeedback> rtcpFeedback) {
        var rtp = new LinkedHashMap<String, Object>();
        rtp.put("payload", codec.payloadType());
        rtp.put("codec", getCodecName(codec));
        rtp.put("rate", codec.clockRate());
        if (codec.channels() != null && codec.channels() > 1) {
            rtp.put("encoding", codec.channels());
        }
        ((List<Map<String, Object>>) mediaObject.get("rtp")).add(rtp);

        var config = new StringBuilder();
        parameters.forEach((key, value) -> {
            if (!config.isEmpty()) {
                config.append(';');
            }
            config.append(key).append('=').append(JavaScript.string(value));
        });
        if (!config.isEmpty()) {
            var fmtp = new LinkedHashMap<String, Object>();
            fmtp.put("payload", codec.payloadType());
            fmtp.put("config", config.toString());
            ((List<Map<String, Object>>) mediaObject.get("fmtp")).add(fmtp);
        }

        for (RtcpFeedback fb : rtcpFeedback) {
            var rtcpFb = new LinkedHashMap<String, Object>();
            rtcpFb.put("payload", codec.payloadType());
            rtcpFb.put("type", fb.type());
            if (fb.parameter() != null) {
                rtcpFb.put("subtype", fb.parameter());
            }
            ((List<Map<String, Object>>) mediaObject.get("rtcpFb")).add(rtcpFb);
        }
    }

    /**
     * Sets the {@code m=} payloads from the payload types of the codecs.
     *
     * @param codecs the codecs
     */
    protected void setPayloads(List<RtpCodecParameters> codecs) {
        mediaObject.put(
                "payloads",
                String.join(
                        " ",
                        codecs.stream()
                                .map(codec -> String.valueOf(codec.payloadType()))
                                .toList()));
    }

    /**
     * Returns the connection of a section that mediasoup reaches through ICE, which is not used.
     *
     * @return {@code IN IP4 127.0.0.1}
     */
    protected static Map<String, Object> defaultConnection() {
        var connection = new LinkedHashMap<String, Object>();
        connection.put("ip", "127.0.0.1");
        connection.put("version", 4);
        return connection;
    }
}

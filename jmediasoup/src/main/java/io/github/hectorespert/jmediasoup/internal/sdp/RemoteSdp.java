/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.sdp;

import static java.util.Objects.requireNonNull;

import io.github.hectorespert.jmediasoup.MediasoupClientException;
import io.github.hectorespert.jmediasoup.ProducerCodecOptions;
import io.github.hectorespert.jmediasoup.internal.sdptransform.Writer;
import io.github.hectorespert.jmediasoup.rtp.RtpEncodingParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
import io.github.hectorespert.jmediasoup.sctp.SctpParameters;
import io.github.hectorespert.jmediasoup.transport.DtlsFingerprint;
import io.github.hectorespert.jmediasoup.transport.DtlsParameters;
import io.github.hectorespert.jmediasoup.transport.DtlsRole;
import io.github.hectorespert.jmediasoup.transport.IceCandidate;
import io.github.hectorespert.jmediasoup.transport.IceParameters;
import java.lang.module.ModuleDescriptor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The SDP of the mediasoup side of a transport, as {@code RemoteSdp} of mediasoup-client: the
 * answers to the local offers when sending, and the offers when receiving.
 *
 * <p>It is not thread-safe; the transport serializes the calls of its handler.
 */
public final class RemoteSdp {

    private static final Logger logger = LoggerFactory.getLogger(RemoteSdp.class);

    private static final List<String> DEPENDENCY_DESCRIPTOR_CODECS = List.of("av1", "h264");
    private static final String DEPENDENCY_DESCRIPTOR_URI =
            "https://aomediacodec.github.io/av1-rtp-spec/#dependency-descriptor-rtp-header-extension";

    // Remote ICE parameters.
    private @Nullable IceParameters iceParameters;
    // Remote ICE candidates.
    private final @Nullable List<IceCandidate> iceCandidates;
    // Remote DTLS parameters.
    private @Nullable DtlsParameters dtlsParameters;
    // Remote SCTP parameters.
    private final @Nullable SctpParameters sctpParameters;
    // RemoteMediaSection instances with same order as in the SDP.
    private final List<RemoteMediaSection> mediaSections = new ArrayList<>();
    // RemoteMediaSection indices indexed by MID.
    private final Map<String, Integer> midToIndex = new HashMap<>();
    // First MID.
    private @Nullable String firstMid;
    // SDP object.
    private final Map<String, Object> sdpObject = new LinkedHashMap<>();
    private final Map<String, Object> origin = new LinkedHashMap<>();

    /**
     * The index where the next media section goes, and the MID of the closed section it replaces.
     *
     * @param idx the index
     * @param reuseMid the MID of the closed section at that index, or {@code null} if it is a new one
     */
    public record MediaSectionIdx(int idx, @Nullable String reuseMid) {}

    /**
     * Creates the SDP for a mediasoup transport.
     *
     * @param iceParameters the ICE parameters, or {@code null}
     * @param iceCandidates the ICE candidates, or {@code null}
     * @param dtlsParameters the DTLS parameters, or {@code null}; with them the SDP is for WebRTC and
     *     BUNDLE
     */
    public RemoteSdp(
            @Nullable IceParameters iceParameters,
            @Nullable List<IceCandidate> iceCandidates,
            @Nullable DtlsParameters dtlsParameters) {
        this(iceParameters, iceCandidates, dtlsParameters, null);
    }

    /**
     * Creates the SDP for a mediasoup transport, which may have SCTP.
     *
     * @param iceParameters the ICE parameters, or {@code null}
     * @param iceCandidates the ICE candidates, or {@code null}
     * @param dtlsParameters the DTLS parameters, or {@code null}; with them the SDP is for WebRTC and
     *     BUNDLE
     * @param sctpParameters the SCTP parameters, or {@code null} if the transport has no SCTP
     */
    public RemoteSdp(
            @Nullable IceParameters iceParameters,
            @Nullable List<IceCandidate> iceCandidates,
            @Nullable DtlsParameters dtlsParameters,
            @Nullable SctpParameters sctpParameters) {
        this.iceParameters = iceParameters;
        this.iceCandidates = iceCandidates;
        this.dtlsParameters = dtlsParameters;
        this.sctpParameters = sctpParameters;

        origin.put("address", "0.0.0.0");
        origin.put("ipVer", 4);
        origin.put("netType", "IN");
        origin.put("sessionId", "10000");
        origin.put("sessionVersion", 0);
        origin.put("username", username());
        sdpObject.put("version", 0);
        sdpObject.put("origin", origin);
        sdpObject.put("name", "-");
        sdpObject.put("timing", new LinkedHashMap<>(Map.of("start", 0, "stop", 0)));
        sdpObject.put("media", new ArrayList<Map<String, Object>>());

        // Indicate support of RFC 8445 (ICE bis / ice2).
        sdpObject.put("iceOptions", "ice2");

        // If ICE parameters are given, add ICE-Lite indicator.
        if (iceParameters != null && Boolean.TRUE.equals(iceParameters.iceLite())) {
            sdpObject.put("icelite", "ice-lite");
        }

        // If DTLS parameters are given, assume WebRTC and BUNDLE.
        if (dtlsParameters != null) {
            // NOTE: This is not standard anymore (it was removed in RFC 8830), however some WebRTC
            // clients still rely on it.
            sdpObject.put("msidSemantic", new LinkedHashMap<>(Map.of("semantic", "WMS", "token", "*")));

            // NOTE: We take the latest fingerprint.
            DtlsFingerprint fingerprint = dtlsParameters.fingerprints().getLast();
            var fingerprintObject = new LinkedHashMap<String, Object>();
            fingerprintObject.put("type", fingerprint.algorithm().value());
            fingerprintObject.put("hash", fingerprint.value());
            sdpObject.put("fingerprint", fingerprintObject);

            var bundle = new LinkedHashMap<String, Object>();
            bundle.put("type", "BUNDLE");
            bundle.put("mids", "");
            sdpObject.put("groups", new ArrayList<>(List.of(bundle)));
        }
    }

    /**
     * Replaces the ICE parameters, after an ICE restart.
     *
     * @param iceParameters the new ICE parameters
     */
    public void updateIceParameters(IceParameters iceParameters) {
        logger.debug("updateIceParameters() [iceParameters:{}]", iceParameters);

        this.iceParameters = iceParameters;
        if (Boolean.TRUE.equals(iceParameters.iceLite())) {
            sdpObject.put("icelite", "ice-lite");
        } else {
            sdpObject.remove("icelite");
        }

        for (RemoteMediaSection mediaSection : mediaSections) {
            mediaSection.setIceParameters(iceParameters);
        }
    }

    /**
     * Sets the DTLS role of mediasoup.
     *
     * @param role the role
     * @throws NullPointerException if the SDP has no DTLS parameters
     */
    public void updateDtlsRole(DtlsRole role) {
        logger.debug("updateDtlsRole() [role:{}]", role);

        DtlsParameters current = requireNonNull(dtlsParameters, "dtlsParameters");
        dtlsParameters = new DtlsParameters(role, current.fingerprints());

        for (RemoteMediaSection mediaSection : mediaSections) {
            mediaSection.setDtlsRole(role);
        }
    }

    /** Sets {@code a=extmap-allow-mixed} at session level. */
    public void setSessionExtmapAllowMixed() {
        logger.debug("setSessionExtmapAllowMixed()");

        sdpObject.put("extmapAllowMixed", "extmap-allow-mixed");
    }

    /**
     * Returns where the next media section goes.
     *
     * @return the index of the first closed section and its MID, or the index after the last
     *     section if none is closed
     */
    public MediaSectionIdx getNextMediaSectionIdx() {
        // If a closed media section is found, return its index.
        for (int idx = 0; idx < mediaSections.size(); idx++) {
            RemoteMediaSection mediaSection = mediaSections.get(idx);
            if (mediaSection.closed()) {
                return new MediaSectionIdx(idx, mediaSection.mid());
            }
        }

        // If no closed media section is found, return next one.
        return new MediaSectionIdx(mediaSections.size(), null);
    }

    /**
     * Adds or replaces the answer to a media section of the local offer for sending.
     *
     * <p>The Dependency Descriptor header extension is removed unless mediasoup supports it for one
     * of the codecs (AV1 and H264).
     *
     * @param offerMediaObject the parsed media section of the local offer
     * @param reuseMid the MID of the closed section to replace, or {@code null}
     * @param answerRtpParameters the RTP parameters that mediasoup receives
     * @throws MediasoupClientException if there is no section with {@code reuseMid}
     */
    public void send(Map<String, Object> offerMediaObject, @Nullable String reuseMid, RtpParameters answerRtpParameters)
            throws MediasoupClientException {
        addAnswer(offerMediaObject, reuseMid, null, answerRtpParameters, null);
    }

    /**
     * Adds or replaces the answer to a media section of the local offer for sending, with the codec
     * options of the producer.
     *
     * <p>The Dependency Descriptor header extension is removed unless mediasoup supports it for one
     * of the codecs (AV1 and H264).
     *
     * @param offerMediaObject the parsed media section of the local offer
     * @param reuseMid the MID of the closed section to replace, or {@code null}
     * @param offerRtpParameters the RTP parameters that the local endpoint sends
     * @param answerRtpParameters the RTP parameters that mediasoup receives
     * @param codecOptions the codec options, or {@code null}
     * @return the RTP parameters of the offer, with the changes that the codec options make to them,
     *     which are the ones to give to mediasoup
     * @throws MediasoupClientException if there is no section with {@code reuseMid}
     */
    public RtpParameters send(
            Map<String, Object> offerMediaObject,
            @Nullable String reuseMid,
            RtpParameters offerRtpParameters,
            RtpParameters answerRtpParameters,
            @Nullable ProducerCodecOptions codecOptions)
            throws MediasoupClientException {
        requireNonNull(offerRtpParameters, "offerRtpParameters");
        return requireNonNull(
                addAnswer(offerMediaObject, reuseMid, offerRtpParameters, answerRtpParameters, codecOptions)
                        .offerRtpParameters());
    }

    @SuppressWarnings("unchecked")
    private RemoteAnswerMediaSection addAnswer(
            Map<String, Object> offerMediaObject,
            @Nullable String reuseMid,
            @Nullable RtpParameters offerRtpParameters,
            RtpParameters answerRtpParameters,
            @Nullable ProducerCodecOptions codecOptions)
            throws MediasoupClientException {
        var mediaSection = new RemoteAnswerMediaSection(
                iceParameters,
                iceCandidates,
                dtlsParameters,
                null,
                offerMediaObject,
                offerRtpParameters,
                answerRtpParameters,
                codecOptions);

        Map<String, Object> mediaObject = mediaSection.getObject();

        // Remove Dependency Descriptor extension unless there is support for the codec in mediasoup.
        boolean ddCodec = CommonUtils.list(mediaObject.get("rtp")).stream()
                .anyMatch(rtp -> DEPENDENCY_DESCRIPTOR_CODECS.contains(
                        String.valueOf(rtp.get("codec")).toLowerCase(Locale.ROOT)));

        if (!ddCodec && mediaObject.get("ext") != null) {
            ((List<Map<String, Object>>) mediaObject.get("ext"))
                    .removeIf(extmap -> DEPENDENCY_DESCRIPTOR_URI.equals(extmap.get("uri")));
        }

        if (reuseMid != null) {
            // Unified-Plan with closed media section replacement.
            replaceMediaSection(mediaSection, reuseMid);
        } else if (!midToIndex.containsKey(mediaSection.mid())) {
            // Unified-Plan or Plan-B with different media kind.
            addMediaSection(mediaSection);
        } else {
            // Plan-B with same media kind.
            replaceMediaSection(mediaSection, null);
        }

        return mediaSection;
    }

    /**
     * Adds a media section to the offer for receiving, reusing a closed section of the same kind if
     * there is one.
     *
     * @param mid the MID of the section
     * @param kind {@code audio} or {@code video}
     * @param offerRtpParameters the RTP parameters that mediasoup sends
     * @param streamId the stream of the {@code a=msid}
     * @param trackId the track of the {@code a=msid}
     * @throws MediasoupClientException if the closed section cannot be replaced
     */
    public void receive(String mid, String kind, RtpParameters offerRtpParameters, String streamId, String trackId)
            throws MediasoupClientException {
        // Allow both 1 byte and 2 bytes length header extensions since mediasoup can send both at any
        // time.
        setSessionExtmapAllowMixed();

        var mediaSection = new RemoteOfferMediaSection(
                iceParameters, iceCandidates, dtlsParameters, mid, kind, offerRtpParameters, streamId, trackId);

        // Let's try to recycle a closed media section (if any).
        // NOTE: We cannot recycle a closed m=audio section as m=video (or vice versa). Firefox rejects
        // the SDP when the media type of a recycled m-line changes.
        RemoteMediaSection oldMediaSection = mediaSections.stream()
                .filter(m -> m.closed() && kind.equals(m.getObject().get("type")))
                .findFirst()
                .orElse(null);

        if (oldMediaSection != null) {
            replaceMediaSection(mediaSection, oldMediaSection.mid());
        } else {
            addMediaSection(mediaSection);
        }
    }

    /**
     * Adds the answer to the {@code m=application} section of the local offer, the SCTP association
     * for sending data.
     *
     * @param offerMediaObject the parsed application section of the local offer
     * @throws NullPointerException if the SDP has no SCTP parameters
     */
    public void sendSctpAssociation(Map<String, Object> offerMediaObject) {
        var mediaSection = new RemoteAnswerMediaSection(
                iceParameters,
                iceCandidates,
                dtlsParameters,
                requireNonNull(sctpParameters, "sctpParameters"),
                offerMediaObject,
                null);

        addMediaSection(mediaSection);
    }

    /**
     * Adds the {@code m=application} section to the offer, the SCTP association for receiving data,
     * with the MID {@code datachannel}.
     *
     * @param oldDataChannelSpec whether to write it with the old {@code a=sctpmap}
     * @throws NullPointerException if the SDP has no SCTP parameters
     */
    public void receiveSctpAssociation(boolean oldDataChannelSpec) {
        var mediaSection = new RemoteOfferMediaSection(
                iceParameters,
                iceCandidates,
                dtlsParameters,
                requireNonNull(sctpParameters, "sctpParameters"),
                "datachannel",
                "application",
                null,
                null,
                null,
                oldDataChannelSpec);

        addMediaSection(mediaSection);
    }

    /**
     * Makes a media section {@code inactive}.
     *
     * @param mid the MID of the section
     * @throws MediasoupClientException if there is no section with that MID
     */
    public void pauseMediaSection(String mid) throws MediasoupClientException {
        findMediaSection(mid).pause();
    }

    /**
     * Restores the direction of a media section for sending.
     *
     * @param mid the MID of the section
     * @throws MediasoupClientException if there is no section with that MID
     */
    public void resumeSendingMediaSection(String mid) throws MediasoupClientException {
        findMediaSection(mid).resume();
    }

    /**
     * Restores the direction of a media section for receiving.
     *
     * @param mid the MID of the section
     * @throws MediasoupClientException if there is no section with that MID
     */
    public void resumeReceivingMediaSection(String mid) throws MediasoupClientException {
        findMediaSection(mid).resume();
    }

    /**
     * Disables a media section.
     *
     * @param mid the MID of the section
     * @throws MediasoupClientException if there is no section with that MID
     */
    public void disableMediaSection(String mid) throws MediasoupClientException {
        findMediaSection(mid).disable();
    }

    /**
     * Marks the simulcast streams of a section for sending as paused or not, by whether their encoding
     * is active.
     *
     * @param mid the MID of the section
     * @param encodings the encodings that are sent, with their rid and whether they are active
     * @throws MediasoupClientException if there is no section with that MID, or it is not for sending
     */
    public void muxMediaSectionSimulcast(String mid, List<RtpEncodingParameters> encodings)
            throws MediasoupClientException {
        if (!(findMediaSection(mid) instanceof RemoteAnswerMediaSection mediaSection)) {
            throw new MediasoupClientException("media section with mid '" + mid + "' is not for sending");
        }

        mediaSection.muxSimulcastStreams(encodings);

        replaceMediaSection(mediaSection, null);
    }

    /**
     * Closes a media section, so that a later one can reuse it.
     *
     * <p>Closing the first media section is a pain since it invalidates the bundled transport, so
     * instead of closing it, it is just disabled.
     *
     * @param mid the MID of the section
     * @return whether the section has been closed; {@code false} if it is the first one
     * @throws MediasoupClientException if there is no section with that MID
     */
    public boolean closeMediaSection(String mid) throws MediasoupClientException {
        RemoteMediaSection mediaSection = findMediaSection(mid);

        // NOTE: Closing the first m section is a pain since it invalidates the bundled transport, so
        // let's avoid it.
        if (mid.equals(firstMid)) {
            logger.debug("closeMediaSection() | cannot close first media section, disabling it instead [mid:{}]", mid);

            disableMediaSection(mid);

            return false;
        }

        mediaSection.close();

        // Regenerate BUNDLE mids.
        regenerateBundleMids();

        return true;
    }

    /**
     * Writes the SDP, increasing its version.
     *
     * @return the SDP
     */
    public String getSdp() {
        // Increase SDP version.
        origin.put("sessionVersion", ((Integer) origin.get("sessionVersion")) + 1);

        return Writer.write(sdpObject);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> media() {
        return (List<Map<String, Object>>) sdpObject.get("media");
    }

    private void addMediaSection(RemoteMediaSection newMediaSection) {
        if (firstMid == null) {
            firstMid = newMediaSection.mid();
        }

        // Add to the vector.
        mediaSections.add(newMediaSection);

        // Add to the map.
        midToIndex.put(newMediaSection.mid(), mediaSections.size() - 1);

        // Add to the SDP object.
        media().add(newMediaSection.getObject());

        // Regenerate BUNDLE mids.
        regenerateBundleMids();
    }

    private void replaceMediaSection(RemoteMediaSection newMediaSection, @Nullable String reuseMid)
            throws MediasoupClientException {
        if (reuseMid != null) {
            // Store it in the map.
            Integer idx = midToIndex.get(reuseMid);
            if (idx == null) {
                throw new MediasoupClientException("no media section found for reuseMid '" + reuseMid + "'");
            }

            RemoteMediaSection oldMediaSection = mediaSections.get(idx);

            // Replace the index in the vector with the new media section.
            mediaSections.set(idx, newMediaSection);

            // Update the map.
            midToIndex.remove(oldMediaSection.mid());
            midToIndex.put(newMediaSection.mid(), idx);

            // Update the SDP object.
            media().set(idx, newMediaSection.getObject());

            // Regenerate BUNDLE mids.
            regenerateBundleMids();
        } else {
            Integer idx = midToIndex.get(newMediaSection.mid());
            if (idx == null) {
                throw new MediasoupClientException("no media section found with mid '" + newMediaSection.mid() + "'");
            }

            // Replace the index in the vector with the new media section.
            mediaSections.set(idx, newMediaSection);

            // Update the SDP object.
            media().set(idx, newMediaSection.getObject());
        }
    }

    private RemoteMediaSection findMediaSection(String mid) throws MediasoupClientException {
        Integer idx = midToIndex.get(mid);
        if (idx == null) {
            throw new MediasoupClientException("no media section found with mid '" + mid + "'");
        }
        return mediaSections.get(idx);
    }

    @SuppressWarnings("unchecked")
    private void regenerateBundleMids() {
        if (dtlsParameters == null) {
            return;
        }

        var groups = (List<Map<String, Object>>) sdpObject.get("groups");
        groups.getFirst()
                .put(
                        "mids",
                        String.join(
                                " ",
                                mediaSections.stream()
                                        .filter(mediaSection -> !mediaSection.closed())
                                        .map(RemoteMediaSection::mid)
                                        .toList()));
    }

    // The library and its version, which the module descriptor records.
    private static String username() {
        ModuleDescriptor descriptor = RemoteSdp.class.getModule().getDescriptor();
        return descriptor == null
                ? "jmediasoup"
                : descriptor
                        .rawVersion()
                        .map(version -> "jmediasoup-v" + version)
                        .orElse("jmediasoup");
    }
}

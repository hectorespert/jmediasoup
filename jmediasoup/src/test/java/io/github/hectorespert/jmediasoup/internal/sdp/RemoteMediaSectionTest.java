/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.sdp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hectorespert.jmediasoup.ProducerCodecOptions;
import io.github.hectorespert.jmediasoup.fake.FakeParameters;
import io.github.hectorespert.jmediasoup.internal.sdptransform.Writer;
import io.github.hectorespert.jmediasoup.rtp.RtcpFeedback;
import io.github.hectorespert.jmediasoup.rtp.RtcpParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpCodecParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpEncodingParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpHeaderExtensionParameters;
import io.github.hectorespert.jmediasoup.rtp.RtpParameters;
import io.github.hectorespert.jmediasoup.transport.DtlsParameters;
import io.github.hectorespert.jmediasoup.transport.DtlsRole;
import io.github.hectorespert.jmediasoup.transport.IceCandidate;
import io.github.hectorespert.jmediasoup.transport.IceCandidateTcpType;
import io.github.hectorespert.jmediasoup.transport.IceCandidateType;
import io.github.hectorespert.jmediasoup.transport.IceParameters;
import io.github.hectorespert.jmediasoup.transport.TransportOptions;
import io.github.hectorespert.jmediasoup.transport.TransportProtocol;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The expected lines follow {@code RemoteMediaSection.ts} of mediasoup-client 3.26 and the order in
 * which sdp-transform writes them.
 */
class RemoteMediaSectionTest {

    private static final TransportOptions TRANSPORT = FakeParameters.transportRemoteParameters();
    private static final String ICE_LINES = String.join(
            "\r\n",
            "a=ice-ufrag:h3hk1iz6qqlnqlne",
            "a=ice-pwd:yku5ej8nvfaor28lvtrabcx0wkrpkztz",
            "a=candidate:udpcandidate 1 udp 1078862079 9.9.9.9 40533 typ host",
            "a=candidate:udpcandidate 1 udp 1078862089 9.9.9.9 41333 typ host",
            "a=end-of-candidates",
            "a=ice-options:renomination");

    // Writes a section alone, without the session lines.
    private static String write(RemoteMediaSection section) {
        var session = new LinkedHashMap<String, Object>();
        session.put("media", List.of(section.getObject()));
        String sdp = Writer.write(session);
        return sdp.substring(sdp.indexOf("m="));
    }

    private static String lines(String... lines) {
        return String.join("\r\n", lines) + "\r\n";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> offerSection(String file, int index) {
        return ((List<Map<String, Object>>) CommonUtilsTest.offer(file).get("media")).get(index);
    }

    private static RemoteOfferMediaSection offerSection(RtpParameters rtpParameters) {
        return new RemoteOfferMediaSection(
                TRANSPORT.iceParameters(),
                TRANSPORT.iceCandidates(),
                TRANSPORT.dtlsParameters(),
                "0",
                "video",
                rtpParameters,
                "stream",
                "track");
    }

    private static RtpParameters vp8() {
        return FakeParameters.consumerRemoteParameters("video/VP8").rtpParameters();
    }

    private static RtpParameters opus() {
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("minptime", 10);
        parameters.put("useinbandfec", 1);
        return new RtpParameters(
                null,
                List.of(new RtpCodecParameters(
                        "audio/opus", 111, 48000, 2, parameters, List.of(new RtcpFeedback("transport-cc", null)))),
                List.of(
                        new RtpHeaderExtensionParameters("urn:ietf:params:rtp-hdrext:sdes:mid", 4, null, null),
                        new RtpHeaderExtensionParameters("urn:3gpp:video-orientation", 13, null, null)),
                null,
                null,
                null);
    }

    @Test
    void offerSectionForAVideoConsumer() {
        assertEquals(
                lines(
                        "m=video 7 UDP/TLS/RTP/SAVPF 101 102",
                        "c=IN IP4 127.0.0.1",
                        "a=rtpmap:101 VP8/90000",
                        "a=rtpmap:102 rtx/90000",
                        "a=fmtp:101 x-google-start-bitrate=1500",
                        "a=fmtp:102 apt=101",
                        "a=rtcp-fb:101 nack",
                        "a=rtcp-fb:101 nack pli",
                        "a=rtcp-fb:101 ccm fir",
                        "a=rtcp-fb:101 goog-remb",
                        "a=rtcp-fb:101 transport-cc",
                        "a=extmap:1 urn:ietf:params:rtp-hdrext:sdes:mid",
                        "a=extmap:4 http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time",
                        "a=extmap:5 http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01",
                        "a=extmap:11 urn:3gpp:video-orientation",
                        "a=extmap:12 urn:ietf:params:rtp-hdrext:toffset",
                        "a=extmap-allow-mixed",
                        "a=setup:actpass",
                        "a=mid:0",
                        "a=msid:stream track",
                        "a=sendonly",
                        ICE_LINES,
                        "a=ssrc:99991111 cname:wB4Ql4lrsxYLjzuN",
                        "a=ssrc:99991112 cname:wB4Ql4lrsxYLjzuN",
                        "a=ssrc-group:FID 99991111 99991112",
                        "a=rtcp-mux",
                        "a=rtcp-rsize"),
                write(offerSection(vp8())));
    }

    @Test
    void offerSectionWithoutRtxOrCnameHasFewerSsrcLines() {
        RtpParameters vp8 = vp8();
        var withoutRtx = new RtpParameters(
                null,
                vp8.codecs(),
                vp8.headerExtensions(),
                List.of(RtpEncodingParameters.builder().ssrc(1L).build()),
                vp8.rtcp(),
                null);
        var withoutCname = new RtpParameters(
                null,
                vp8.codecs(),
                vp8.headerExtensions(),
                vp8.encodings(),
                new RtcpParameters(null, null, null),
                null);

        String written = write(offerSection(withoutRtx));
        assertTrue(written.contains("a=ssrc:1 cname:wB4Ql4lrsxYLjzuN\r\n"), written);
        assertFalse(written.contains("a=ssrc-group"), written);

        written = write(offerSection(withoutCname));
        assertFalse(written.contains("a=ssrc:"), written);
        assertTrue(written.contains("a=ssrc-group:FID 99991111 99991112\r\n"), written);
    }

    @Test
    void offerSectionsAlwaysOfferActpass() {
        RemoteOfferMediaSection section = offerSection(vp8());

        section.setDtlsRole(DtlsRole.CLIENT);

        assertEquals("actpass", section.getObject().get("setup"));
    }

    @Test
    void answerSectionForAnAudioProducer() {
        var section = new RemoteAnswerMediaSection(
                TRANSPORT.iceParameters(),
                TRANSPORT.iceCandidates(),
                new DtlsParameters(DtlsRole.CLIENT, TRANSPORT.dtlsParameters().fingerprints()),
                offerSection("offer-sendonly.sdp", 0),
                opus());

        // The video-orientation extension is left out because the offer does not have it.
        assertEquals(
                lines(
                        "m=audio 7 UDP/TLS/RTP/SAVPF 111",
                        "c=IN IP4 127.0.0.1",
                        "a=rtpmap:111 opus/48000/2",
                        "a=fmtp:111 minptime=10;useinbandfec=1",
                        "a=rtcp-fb:111 transport-cc",
                        "a=extmap:4 urn:ietf:params:rtp-hdrext:sdes:mid",
                        "a=setup:active",
                        "a=mid:0",
                        "a=recvonly",
                        ICE_LINES,
                        "a=rtcp-mux",
                        "a=rtcp-rsize"),
                write(section));
    }

    @Test
    void answerSectionsKeepExtmapAllowMixedOfTheOffer() {
        Map<String, Object> offer = new LinkedHashMap<>(offerSection("offer-sendonly.sdp", 0));
        offer.put("extmapAllowMixed", "extmap-allow-mixed");

        var section = new RemoteAnswerMediaSection(null, null, null, offer, opus());

        assertEquals("extmap-allow-mixed", section.getObject().get("extmapAllowMixed"));
    }

    @Test
    void answerSectionsTakeTheDtlsRoleOfMediasoup() {
        var section = new RemoteAnswerMediaSection(null, null, null, offerSection("offer-sendonly.sdp", 0), opus());

        section.setDtlsRole(null);
        assertFalse(section.getObject().containsKey("setup"));
        section.setDtlsRole(DtlsRole.SERVER);
        assertEquals("passive", section.getObject().get("setup"));
        section.setDtlsRole(DtlsRole.AUTO);
        assertEquals("actpass", section.getObject().get("setup"));
    }

    @Test
    void sectionsWithoutIceHaveNoIceLines() {
        var section = new RemoteAnswerMediaSection(null, null, null, offerSection("offer-sendonly.sdp", 0), opus());

        String written = write(section);

        assertFalse(written.contains("a=ice-"), written);
        assertFalse(written.contains("a=candidate"), written);
        assertFalse(written.contains("a=setup"), written);
    }

    @Test
    void candidatesWriteTheirAddressAndTcpType() {
        var candidate = new IceCandidate(
                "tcpcandidate",
                1,
                "1.2.3.4",
                null,
                TransportProtocol.TCP,
                443,
                IceCandidateType.HOST,
                IceCandidateTcpType.PASSIVE);

        var section = new RemoteAnswerMediaSection(
                null, List.of(candidate), null, offerSection("offer-sendonly.sdp", 0), opus());

        assertTrue(
                write(section).contains("a=candidate:tcpcandidate 1 tcp 1 1.2.3.4 443 typ host tcptype passive\r\n"));
    }

    // Opus with the NACK that some handlers announce, and the nack pli that no Opus has, to check
    // that only the plain NACK goes.
    private static RtpParameters opusWithNack() {
        RtpCodecParameters codec = opus().codecs().getFirst();
        return new RtpParameters(
                null,
                List.of(new RtpCodecParameters(
                        codec.mimeType(),
                        codec.payloadType(),
                        codec.clockRate(),
                        codec.channels(),
                        codec.parameters(),
                        List.of(
                                new RtcpFeedback("transport-cc", null),
                                new RtcpFeedback("nack", null),
                                new RtcpFeedback("nack", "pli")))),
                opus().headerExtensions(),
                null,
                null,
                null);
    }

    private static RemoteAnswerMediaSection answerWith(
            int index, RtpParameters rtpParameters, ProducerCodecOptions codecOptions) {
        return new RemoteAnswerMediaSection(
                null,
                null,
                null,
                null,
                offerSection("offer-sendonly.sdp", index),
                rtpParameters,
                rtpParameters,
                codecOptions);
    }

    @Test
    void opusOptionsChangeTheAnswerAndTheOffer() {
        var codecOptions = ProducerCodecOptions.builder()
                .opusStereo(true)
                .opusFec(false)
                .opusDtx(true)
                .opusMaxPlaybackRate(24000)
                .opusMaxAverageBitrate(64000)
                .opusPtime(20)
                .build();

        RemoteAnswerMediaSection section = answerWith(0, opusWithNack(), codecOptions);

        String written = write(section);
        assertTrue(
                written.contains("a=fmtp:111 minptime=10;useinbandfec=0;stereo=1;usedtx=1;maxplaybackrate=24000;"
                        + "maxaveragebitrate=64000;ptime=20\r\n"),
                written);
        assertTrue(written.contains("a=rtcp-fb:111 transport-cc\r\na=rtcp-fb:111 nack pli\r\na=extmap"), written);

        // The offer gets the options that tell how it sends, and loses the NACK as well.
        RtpCodecParameters offerCodec = section.offerRtpParameters().codecs().getFirst();
        assertEquals(
                Map.of("minptime", 10, "useinbandfec", 0, "sprop-stereo", 1, "usedtx", 1, "ptime", 20),
                offerCodec.parameters());
        assertEquals(
                List.of(new RtcpFeedback("transport-cc", null), new RtcpFeedback("nack", "pli")),
                offerCodec.rtcpFeedback());
    }

    @Test
    void opusNackKeepsTheNack() {
        RemoteAnswerMediaSection section = answerWith(
                0, opusWithNack(), ProducerCodecOptions.builder().opusNack(true).build());

        assertTrue(write(section).contains("a=rtcp-fb:111 nack\r\n"));
        assertEquals(opusWithNack(), section.offerRtpParameters());
    }

    @Test
    void withoutCodecOptionsTheOfferIsUnchangedAndKeepsTheNack() {
        RtpParameters offer = opusWithNack();

        var section = new RemoteAnswerMediaSection(
                null, null, null, null, offerSection("offer-sendonly.sdp", 0), offer, offer, null);

        assertTrue(write(section).contains("a=rtcp-fb:111 nack\r\n"));
        assertSame(offer, section.offerRtpParameters());
    }

    @Test
    void videoOptionsOnlyChangeTheAnswer() {
        var codecOptions = ProducerCodecOptions.builder()
                .videoGoogleStartBitrate(1000)
                .videoGoogleMaxBitrate(3000)
                .videoGoogleMinBitrate(500)
                // Options of other codecs are left out.
                .opusStereo(true)
                .build();

        RemoteAnswerMediaSection section = answerWith(1, vp8(), codecOptions);

        String written = write(section);
        assertTrue(
                written.contains("a=fmtp:101 x-google-start-bitrate=1000;x-google-max-bitrate=3000;"
                        + "x-google-min-bitrate=500\r\na=fmtp:102 apt=101\r\n"),
                written);
        assertTrue(written.contains("a=rtcp-fb:101 nack\r\n"), written);
        assertEquals(vp8(), section.offerRtpParameters());
    }

    @Test
    void codecOptionsNeedTheRtpParametersOfTheOffer() {
        assertThrows(
                NullPointerException.class,
                () -> new RemoteAnswerMediaSection(
                        null,
                        null,
                        null,
                        null,
                        offerSection("offer-sendonly.sdp", 0),
                        null,
                        opus(),
                        ProducerCodecOptions.builder().build()));
    }

    @Test
    void answerSectionsReceiveTheSimulcastOfTheOffer() {
        var section = new RemoteAnswerMediaSection(null, null, null, offerSection("offer-simulcast.sdp", 0), vp8());

        String written = write(section);
        assertTrue(
                written.contains("a=rid:r0 recv\r\na=rid:r1 recv\r\na=rid:r2 recv\r\na=simulcast:recv r0;r1;r2\r\n"),
                written);
    }

    @Test
    void answerSectionsReceiveTheSimulcastOfDraft03() {
        Map<String, Object> offer = new LinkedHashMap<>(offerSection("offer-simulcast.sdp", 0));
        offer.remove("simulcast");
        offer.put("simulcast_03", Map.of("value", "send rid=r0;r1"));
        offer.put(
                "rids",
                List.of(
                        Map.of("id", "r0", "direction", "send"),
                        Map.of("id", "r1", "direction", "send"),
                        Map.of("id", "x", "direction", "recv")));

        var section = new RemoteAnswerMediaSection(null, null, null, offer, vp8());

        String written = write(section);
        assertTrue(written.contains("a=rid:r0 recv\r\na=rid:r1 recv\r\na=simulcast: recv rid=r0;r1\r\n"), written);
        assertFalse(written.contains("a=rid:x"), written);
    }

    private static RtpEncodingParameters stream(String rid, Boolean active) {
        return RtpEncodingParameters.builder().rid(rid).active(active).build();
    }

    @Test
    void muxingTheSimulcastStreamsPausesTheInactiveOnes() {
        var section = new RemoteAnswerMediaSection(null, null, null, offerSection("offer-simulcast.sdp", 0), vp8());

        section.muxSimulcastStreams(List.of(stream("r0", true), stream("r1", true), stream("r2", false)));
        assertTrue(write(section).contains("a=simulcast:recv r0;r1;~r2\r\n"));

        // An encoding that is not given, or without active, is paused too.
        section.muxSimulcastStreams(List.of(stream("r0", true), stream("r1", null)));
        assertTrue(write(section).contains("a=simulcast:recv r0;~r1;~r2\r\n"));

        section.muxSimulcastStreams(List.of(stream("r0", true), stream("r1", true), stream("r2", true)));
        assertTrue(write(section).contains("a=simulcast:recv r0;r1;r2\r\n"));
    }

    @Test
    void muxingASectionWithoutSimulcastDoesNothing() {
        var section = new RemoteAnswerMediaSection(null, null, null, offerSection("offer-sendonly.sdp", 1), vp8());

        section.muxSimulcastStreams(List.of(stream("r0", false)));

        assertFalse(section.getObject().containsKey("simulcast"));
    }

    @Test
    void closingAnAnswerSectionRemovesItsSimulcast() {
        var section = new RemoteAnswerMediaSection(null, null, null, offerSection("offer-simulcast.sdp", 0), vp8());

        section.close();

        assertFalse(section.getObject().containsKey("simulcast"));
        assertFalse(section.getObject().containsKey("rids"));
    }

    @Test
    void monoCodecsHaveNoEncodingAndCodecsWithoutParametersNoFmtp() {
        var rtpParameters = new RtpParameters(
                null, List.of(new RtpCodecParameters("audio/PCMU", 0, 8000, 1, null, null)), null, null, null, null);

        String written = write(
                new RemoteAnswerMediaSection(null, null, null, offerSection("offer-sendonly.sdp", 0), rtpParameters));

        assertTrue(written.contains("a=rtpmap:0 PCMU/8000\r\n"), written);
        assertFalse(written.contains("a=fmtp"), written);
    }

    @Test
    void midAndClosed() {
        RemoteOfferMediaSection section = offerSection(vp8());

        assertEquals("0", section.mid());
        assertFalse(section.closed());
    }

    @Test
    void pauseAndResumeChangeTheDirection() {
        RemoteOfferMediaSection offer = offerSection(vp8());
        var answer = new RemoteAnswerMediaSection(null, null, null, offerSection("offer-sendonly.sdp", 0), opus());

        offer.pause();
        answer.disable();
        assertEquals("inactive", offer.getObject().get("direction"));
        assertEquals("inactive", answer.getObject().get("direction"));

        offer.resume();
        answer.resume();
        assertEquals("sendonly", offer.getObject().get("direction"));
        assertEquals("recvonly", answer.getObject().get("direction"));
    }

    @Test
    void setIceParametersReplacesTheCredentials() {
        RemoteOfferMediaSection section = offerSection(vp8());

        section.setIceParameters(new IceParameters("ufrag", "pwd", null));

        assertEquals("ufrag", section.getObject().get("iceUfrag"));
        assertEquals("pwd", section.getObject().get("icePwd"));
    }

    @Test
    void closingASectionKeepsOnlyItsHeaderAndExtensions() {
        RemoteOfferMediaSection section = offerSection(vp8());

        section.close();

        assertTrue(section.closed());
        assertEquals(
                lines(
                        "m=video 0 UDP/TLS/RTP/SAVPF 101 102",
                        "c=IN IP4 127.0.0.1",
                        "a=extmap:1 urn:ietf:params:rtp-hdrext:sdes:mid",
                        "a=extmap:4 http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time",
                        "a=extmap:5 http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01",
                        "a=extmap:11 urn:3gpp:video-orientation",
                        "a=extmap:12 urn:ietf:params:rtp-hdrext:toffset",
                        "a=setup:actpass",
                        "a=mid:0",
                        "a=msid:stream track",
                        "a=inactive",
                        "a=rtcp-mux",
                        "a=rtcp-rsize"),
                write(section));
        assertEquals(new ArrayList<>(), section.getObject().get("rtp"));
    }

    @Test
    void codecNamesComeFromTheMimeType() {
        assertEquals(
                "VP8",
                RemoteMediaSection.getCodecName(new RtpCodecParameters("video/VP8", 96, 90000, null, null, null)));
        assertEquals(
                "multiopus",
                RemoteMediaSection.getCodecName(new RtpCodecParameters("audio/multiopus", 96, 48000, 6, null, null)));
    }

    @Test
    void audioAndVideoOfferSectionsNeedRtpParameters() {
        assertThrows(
                NullPointerException.class,
                () -> new RemoteOfferMediaSection(null, null, null, "0", "audio", null, null, null));
    }

    private static RemoteOfferMediaSection sctpOfferSection(boolean oldDataChannelSpec) {
        return new RemoteOfferMediaSection(
                null,
                null,
                null,
                TRANSPORT.sctpParameters(),
                "datachannel",
                "application",
                null,
                null,
                null,
                oldDataChannelSpec);
    }

    @Test
    void theOfferOfTheSctpAssociationHasItsPort() {
        assertEquals(
                lines(
                        "m=application 7 UDP/DTLS/SCTP webrtc-datachannel",
                        "c=IN IP4 127.0.0.1",
                        "a=extmap-allow-mixed",
                        "a=mid:datachannel",
                        "a=sctp-port:5000",
                        "a=max-message-size:2000000"),
                write(sctpOfferSection(false)));
    }

    @Test
    void theOfferOfTheSctpAssociationCanUseTheOldSpec() {
        assertEquals(
                lines(
                        "m=application 7 UDP/DTLS/SCTP 5000",
                        "c=IN IP4 127.0.0.1",
                        "a=extmap-allow-mixed",
                        "a=mid:datachannel",
                        "a=sctpmap:5000 webrtc-datachannel 2000000"),
                write(sctpOfferSection(true)));
    }

    @Test
    void theAnswerToTheSctpAssociationOfWebRtcJavaHasThePortOfMediasoup() {
        var section = new RemoteAnswerMediaSection(
                null, null, null, TRANSPORT.sctpParameters(), offerSection("offer-datachannel.sdp", 0), null);

        assertEquals(
                lines(
                        "m=application 7 UDP/DTLS/SCTP webrtc-datachannel",
                        "c=IN IP4 127.0.0.1",
                        "a=mid:0",
                        "a=sctp-port:5000",
                        "a=max-message-size:2000000"),
                write(section));
    }

    @Test
    void theAnswerToAnOldSpecOfferUsesTheOldSpec() {
        var offer = new LinkedHashMap<String, Object>();
        offer.put("type", "application");
        offer.put("protocol", "DTLS/SCTP");
        offer.put("mid", "data");
        offer.put("sctpmap", new LinkedHashMap<>(Map.of("app", "webrtc-datachannel", "sctpmapNumber", 5000)));

        var section = new RemoteAnswerMediaSection(null, null, null, TRANSPORT.sctpParameters(), offer, null);

        assertEquals(
                lines(
                        "m=application 7 DTLS/SCTP 5000",
                        "c=IN IP4 127.0.0.1",
                        "a=mid:data",
                        "a=sctpmap:5000 webrtc-datachannel 2000000"),
                write(section));
    }

    @Test
    void applicationSectionsNeedSctpParameters() {
        assertThrows(NullPointerException.class, () -> sctpOfferSectionWithoutSctp());
        assertThrows(
                NullPointerException.class,
                () -> new RemoteAnswerMediaSection(
                        null, null, null, null, offerSection("offer-datachannel.sdp", 0), null));
    }

    private static RemoteOfferMediaSection sctpOfferSectionWithoutSctp() {
        return new RemoteOfferMediaSection(
                null, null, null, null, "datachannel", "application", null, null, null, false);
    }
}

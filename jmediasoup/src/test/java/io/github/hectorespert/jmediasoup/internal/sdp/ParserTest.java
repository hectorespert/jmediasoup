/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.sdp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Port of {@code test/parse.test.js} of sdp-transform, without the checks of the writer. */
class ParserTest {

    static String read(String file) {
        try (InputStream in = ParserTest.class.getResourceAsStream("/sdp/" + file)) {
            assertNotNull(in, file);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Map<String, Object> parse(String file) {
        Map<String, Object> session = Parser.parse(read(file));
        assertTrue(session.get("media") instanceof List<?>, "got media");
        return session;
    }

    // Follows a path of map keys and list indexes, like session.media[0].rtp[1].codec.
    static @Nullable Object at(@Nullable Object root, Object... path) {
        Object current = root;
        for (Object step : path) {
            if (current == null) {
                return null;
            }
            current = step instanceof Integer index ? ((List<?>) current).get(index) : ((Map<?, ?>) current).get(step);
        }
        return current;
    }

    // A map that allows null values, which JavaScript has as undefined.
    static Map<String, @Nullable Object> map(@Nullable Object... keysAndValues) {
        var map = new LinkedHashMap<String, @Nullable Object>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static int size(@Nullable Object list) {
        return ((List<?>) list).size();
    }

    @Test
    void normalSdp() {
        Map<String, Object> session = parse("normal.sdp");
        Object origin = session.get("origin");

        assertEquals("-", at(origin, "username"), "origin username");
        assertEquals(20518, at(origin, "sessionId"), "origin sessionId");
        assertEquals(0, at(origin, "sessionVersion"), "origin sessionVersion");
        assertEquals("IN", at(origin, "netType"), "origin netType");
        assertEquals(4, at(origin, "ipVer"), "origin ipVer");
        assertEquals("203.0.113.1", at(origin, "address"), "origin address");

        assertEquals("203.0.113.1", at(session, "connection", "ip"), "session connect ip");
        assertEquals(4, at(session, "connection", "version"), "session connect ip ver");

        // global ICE and fingerprint
        assertEquals("F7gI", session.get("iceUfrag"), "global ufrag");
        assertEquals("x9cml/YzichV2+XlhiMu8g", session.get("icePwd"), "global pwd");

        Object audio = at(session, "media", 0);
        assertEquals("audio", at(audio, "type"), "audio type");
        assertEquals(54400, at(audio, "port"), "audio port");
        assertEquals("RTP/SAVPF", at(audio, "protocol"), "audio protocol");
        assertEquals("sendrecv", at(audio, "direction"), "audio direction");
        assertEquals(0, at(audio, "rtp", 0, "payload"), "audio rtp 0 payload");
        assertEquals("PCMU", at(audio, "rtp", 0, "codec"), "audio rtp 0 codec");
        assertEquals(8000, at(audio, "rtp", 0, "rate"), "audio rtp 0 rate");
        assertEquals(96, at(audio, "rtp", 1, "payload"), "audio rtp 1 payload");
        assertEquals("opus", at(audio, "rtp", 1, "codec"), "audio rtp 1 codec");
        assertEquals(48000, at(audio, "rtp", 1, "rate"), "audio rtp 1 rate");
        assertEquals(map("value", 1, "uri", "URI-toffset"), at(audio, "ext", 0), "audio extension 0");
        assertEquals(
                map("value", 2, "direction", "recvonly", "uri", "URI-gps-string"),
                at(audio, "ext", 1),
                "audio extension 1");
        assertEquals("extmap-allow-mixed", at(audio, "extmapAllowMixed"), "extmap-allow-mixed present");

        Object video = at(session, "media", 1);
        assertEquals("video", at(video, "type"), "video type");
        assertEquals(55400, at(video, "port"), "video port");
        assertEquals("RTP/SAVPF", at(video, "protocol"), "video protocol");
        assertEquals("sendrecv", at(video, "direction"), "video direction");
        assertEquals(97, at(video, "rtp", 0, "payload"), "video rtp 0 payload");
        assertEquals("H264", at(video, "rtp", 0, "codec"), "video rtp 0 codec");
        assertEquals(90000, at(video, "rtp", 0, "rate"), "video rtp 0 rate");
        assertEquals(97, at(video, "fmtp", 0, "payload"), "video fmtp 0 payload");
        Map<String, @Nullable Object> vidFmtp = Parser.parseParams((String) at(video, "fmtp", 0, "config"));
        assertEquals("4d0028", vidFmtp.get("profile-level-id"), "video fmtp 0 profile-level-id");
        assertEquals(1, vidFmtp.get("packetization-mode"), "video fmtp 0 packetization-mode");
        assertEquals(
                "Z0IAH5WoFAFuQA==,aM48gA==", vidFmtp.get("sprop-parameter-sets"), "video fmtp 0 sprop-parameter-sets");
        assertEquals(98, at(video, "fmtp", 1, "payload"), "video fmtp 1 payload");
        Map<String, @Nullable Object> vidFmtp2 = Parser.parseParams((String) at(video, "fmtp", 1, "config"));
        assertEquals(10, vidFmtp2.get("minptime"), "video fmtp 1 minptime");
        assertEquals(1, vidFmtp2.get("useinbandfec"), "video fmtp 1 useinbandfec");
        assertEquals(98, at(video, "rtp", 1, "payload"), "video rtp 1 payload");
        assertEquals("VP8", at(video, "rtp", 1, "codec"), "video rtp 1 codec");
        assertEquals(90000, at(video, "rtp", 1, "rate"), "video rtp 1 rate");
        assertEquals("*", at(video, "rtcpFb", 0, "payload"), "video rtcp-fb 0 payload");
        assertEquals("nack", at(video, "rtcpFb", 0, "type"), "video rtcp-fb 0 type");
        assertEquals(98, at(video, "rtcpFb", 1, "payload"), "video rtcp-fb 0 payload");
        assertEquals("nack", at(video, "rtcpFb", 1, "type"), "video rtcp-fb 0 type");
        assertEquals("rpsi", at(video, "rtcpFb", 1, "subtype"), "video rtcp-fb 0 subtype");
        assertEquals(98, at(video, "rtcpFbTrrInt", 0, "payload"), "video rtcp-fb trr-int 0 payload");
        assertEquals(100, at(video, "rtcpFbTrrInt", 0, "value"), "video rtcp-fb trr-int 0 value");
        assertEquals(1, at(video, "crypto", 0, "id"), "video crypto 0 id");
        assertEquals("AES_CM_128_HMAC_SHA1_32", at(video, "crypto", 0, "suite"), "video crypto 0 suite");
        assertEquals(
                "inline:keNcG3HezSNID7LmfDa9J4lfdUL8W1F7TNJKcbuy|2^20|1:32",
                at(video, "crypto", 0, "config"),
                "video crypto 0 config");
        assertEquals(3, size(at(video, "ssrcs")), "video got 3 ssrc lines");
        // test ssrc with attr:value
        assertEquals(
                map("id", 1399694169, "attribute", "foo", "value", "bar"),
                at(video, "ssrcs", 0),
                "video 1st ssrc line attr:value");
        // test ssrc with attr only
        assertEquals(map("id", 1399694169, "attribute", "baz"), at(video, "ssrcs", 1), "video 2nd ssrc line attr only");
        // test ssrc with at-tr:value
        assertEquals(
                map("id", 1399694169, "attribute", "foo-bar", "value", "baz"),
                at(video, "ssrcs", 2),
                "video 3rd ssrc line attr with dash");

        // ICE candidates (same for both audio and video in this case)
        for (int i = 0; i < 2; i++) {
            Object cs = at(i == 0 ? audio : video, "candidates");
            String str = i == 0 ? "audio " : "video ";
            int port = i == 0 ? 54400 : 55400;
            assertEquals(4, size(cs), str + "got 4 candidates");
            assertEquals(0, at(cs, 0, "foundation"), str + "ice candidate 0 foundation");
            assertEquals(1, at(cs, 0, "component"), str + "ice candidate 0 component");
            assertEquals("UDP", at(cs, 0, "transport"), str + "ice candidate 0 transport");
            assertEquals(2113667327, at(cs, 0, "priority"), str + "ice candidate 0 priority");
            assertEquals("203.0.113.1", at(cs, 0, "ip"), str + "ice candidate 0 ip");
            assertEquals(port, at(cs, 0, "port"), str + "ice candidate 0 port");
            assertEquals("host", at(cs, 0, "type"), str + "ice candidate 0 type");
            assertEquals(1, at(cs, 1, "foundation"), str + "ice candidate 1 foundation");
            assertEquals(2, at(cs, 1, "component"), str + "ice candidate 1 component");
            assertEquals("UDP", at(cs, 1, "transport"), str + "ice candidate 1 transport");
            assertEquals(2113667326, at(cs, 1, "priority"), str + "ice candidate 1 priority");
            assertEquals("203.0.113.1", at(cs, 1, "ip"), str + "ice candidate 1 ip");
            assertEquals(port + 1, at(cs, 1, "port"), str + "ice candidate 1 port");
            assertEquals("host", at(cs, 1, "type"), str + "ice candidate 1 type");
            assertEquals(2, at(cs, 2, "foundation"), str + "ice candidate 2 foundation");
            assertEquals(1, at(cs, 2, "component"), str + "ice candidate 2 component");
            assertEquals("UDP", at(cs, 2, "transport"), str + "ice candidate 2 transport");
            assertEquals(1686052607, at(cs, 2, "priority"), str + "ice candidate 2 priority");
            assertEquals("203.0.113.1", at(cs, 2, "ip"), str + "ice candidate 2 ip");
            assertEquals(port + 2, at(cs, 2, "port"), str + "ice candidate 2 port");
            assertEquals("srflx", at(cs, 2, "type"), str + "ice candidate 2 type");
            assertEquals("192.168.1.145", at(cs, 2, "raddr"), str + "ice candidate 2 raddr");
            assertEquals(port + 2, at(cs, 2, "rport"), str + "ice candidate 2 rport");
            assertEquals(0, at(cs, 2, "generation"), str + "ice candidate 2 generation");
            assertEquals(3, at(cs, 2, "network-id"), str + "ice candidate 2 network-id");
            assertEquals(i == 0 ? 10 : null, at(cs, 2, "network-cost"), str + "ice candidate 2 network-cost");
            assertEquals(3, at(cs, 3, "foundation"), str + "ice candidate 3 foundation");
            assertEquals(2, at(cs, 3, "component"), str + "ice candidate 3 component");
            assertEquals("UDP", at(cs, 3, "transport"), str + "ice candidate 3 transport");
            assertEquals(1686052606, at(cs, 3, "priority"), str + "ice candidate 3 priority");
            assertEquals("203.0.113.1", at(cs, 3, "ip"), str + "ice candidate 3 ip");
            assertEquals(port + 3, at(cs, 3, "port"), str + "ice candidate 3 port");
            assertEquals("srflx", at(cs, 3, "type"), str + "ice candidate 3 type");
            assertEquals("192.168.1.145", at(cs, 3, "raddr"), str + "ice candidate 3 raddr");
            assertEquals(port + 3, at(cs, 3, "rport"), str + "ice candidate 3 rport");
            assertEquals(0, at(cs, 3, "generation"), str + "ice candidate 3 generation");
            assertEquals(3, at(cs, 3, "network-id"), str + "ice candidate 3 network-id");
            assertEquals(i == 0 ? 10 : null, at(cs, 3, "network-cost"), str + "ice candidate 3 network-cost");
        }

        assertEquals(2, size(session.get("media")), "got 2 m-lines");
    }

    /*
     * Test for an sdp that started out as something from chrome
     * it's since been hacked to include tests for other stuff
     * ignore the name
     */
    @Test
    void hackySdp() {
        Map<String, Object> session = parse("hacky.sdp");
        Object media = session.get("media");

        assertEquals("3710604898417546434", at(session, "origin", "sessionId"), "origin sessionId");
        assertEquals(1, size(session.get("groups")), "one grouping");
        assertEquals("BUNDLE", at(session, "groups", 0, "type"), "grouping is BUNDLE");
        assertEquals("audio video", at(session, "groups", 0, "mids"), "bundling audio video");
        assertEquals("WMS", at(session, "msidSemantic", "semantic"), "webrtc semantic");
        assertEquals("Jvlam5X3SX1OP6pn20zWogvaKJz5Hjf9OnlV", at(session, "msidSemantic", "token"), "semantic token");

        // verify a=rtcp:65179 IN IP4 193.84.77.194
        assertEquals(1, at(media, 0, "rtcp", "port"), "rtcp port");
        assertEquals("IN", at(media, 0, "rtcp", "netType"), "rtcp netType");
        assertEquals(4, at(media, 0, "rtcp", "ipVer"), "rtcp ipVer");
        assertEquals("0.0.0.0", at(media, 0, "rtcp", "address"), "rtcp address");

        // verify ice tcp types
        Object candidates = at(media, 0, "candidates");
        assertNull(at(candidates, 0, "tcptype"), "no tcptype");
        assertEquals("active", at(candidates, 1, "tcptype"), "active tcptype");
        assertEquals("tcp", at(candidates, 1, "transport"), "tcp transport");
        assertEquals(0, at(candidates, 1, "generation"), "generation 0");
        assertEquals("host", at(candidates, 1, "type"), "tcp host");
        assertNull(at(candidates, 2, "generation"), "no generation");
        assertEquals("host", at(candidates, 2, "type"), "tcp host");
        assertEquals("active", at(candidates, 2, "tcptype"), "active tcptype");
        assertEquals("passive", at(candidates, 3, "tcptype"), "passive tcptype");
        assertEquals("so", at(candidates, 4, "tcptype"), "so tcptype");
        // raddr + rport + tcptype + generation
        assertEquals("srflx", at(candidates, 5, "type"), "tcp srflx");
        assertEquals(9, at(candidates, 5, "rport"), "tcp rport");
        assertEquals("10.0.1.1", at(candidates, 5, "raddr"), "tcp raddr");
        assertEquals("active", at(candidates, 5, "tcptype"), "active tcptype");
        assertEquals("passive", at(candidates, 6, "tcptype"), "passive tcptype");
        assertEquals(8998, at(candidates, 6, "rport"), "tcp rport");
        assertEquals("10.0.1.1", at(candidates, 6, "raddr"), "tcp raddr");
        assertEquals(5, at(candidates, 6, "generation"), "tcp generation");

        // and verify it works without specifying the ip
        assertEquals(12312, at(media, 1, "rtcp", "port"), "rtcp port");
        assertNull(at(media, 1, "rtcp", "netType"), "rtcp netType");
        assertNull(at(media, 1, "rtcp", "ipVer"), "rtcp ipVer");
        assertNull(at(media, 1, "rtcp", "address"), "rtcp address");

        // verify a=rtpmap:126 telephone-event/8000
        int lastRtp = size(at(media, 0, "rtp")) - 1;
        assertEquals("telephone-event", at(media, 0, "rtp", lastRtp, "codec"), "dtmf codec");
        assertEquals(8000, at(media, 0, "rtp", lastRtp, "rate"), "dtmf rate");

        assertEquals("google-ice", at(media, 0, "iceOptions"), "ice options parsed");
        assertEquals(0.125, at(media, 0, "ptime"), "audio packet duration");
        assertEquals(60, at(media, 0, "maxptime"), "maxptime parsed");
        assertEquals("rtcp-mux", at(media, 0, "rtcpMux"), "rtcp-mux present");

        assertEquals("opus", at(media, 0, "rtp", 0, "codec"), "audio rtp 0 codec");
        assertEquals(2, at(media, 0, "rtp", 0, "encoding"), "audio rtp 0 encoding");

        assertEquals(4, size(at(media, 0, "ssrcs")), "got 4 ssrc lines");
        assertEquals(
                map("id", 2754920552L, "attribute", "cname", "value", "t9YU8M1UxTF8Y1A1"),
                at(media, 0, "ssrcs", 0),
                "1st ssrc line");
        assertEquals(
                map(
                        "id",
                        2754920552L,
                        "attribute",
                        "msid",
                        "value",
                        "Jvlam5X3SX1OP6pn20zWogvaKJz5Hjf9OnlV Jvlam5X3SX1OP6pn20zWogvaKJz5Hjf9OnlVa0"),
                at(media, 0, "ssrcs", 1),
                "2nd ssrc line");
        assertEquals(
                map("id", 2754920552L, "attribute", "mslabel", "value", "Jvlam5X3SX1OP6pn20zWogvaKJz5Hjf9OnlV"),
                at(media, 0, "ssrcs", 2),
                "3rd ssrc line");
        assertEquals(
                map("id", 2754920552L, "attribute", "label", "value", "Jvlam5X3SX1OP6pn20zWogvaKJz5Hjf9OnlVa0"),
                at(media, 0, "ssrcs", 3),
                "4th ssrc line");

        // verify a=sctpmap:5000 webrtc-datachannel 1024
        assertEquals(5000, at(media, 2, "sctpmap", "sctpmapNumber"), "sctpmap number is 5000");
        assertEquals("webrtc-datachannel", at(media, 2, "sctpmap", "app"), "sctpmap app is webrtc-datachannel");
        assertEquals(1024, at(media, 2, "sctpmap", "maxMessageSize"), "sctpmap maxMessageSize is 1024");

        // verify a=framerate:29.97
        assertEquals(29.97, at(media, 2, "framerate"), "framerate is 29.97");

        // verify a=label:1
        assertEquals(1, at(media, 0, "label"), "label is 1");
    }

    @Test
    void iceliteSdp() {
        Map<String, Object> session = parse("icelite.sdp");

        assertEquals("ice-lite", session.get("icelite"), "icelite parsed");
    }

    @Test
    void invalidSdp() {
        Object media = parse("invalid.sdp").get("media");

        // verify a=rtcp:65179 IN IP4 193.84.77.194
        assertEquals(1, at(media, 0, "rtcp", "port"), "rtcp port");
        assertEquals("IN", at(media, 0, "rtcp", "netType"), "rtcp netType");
        assertEquals(7, at(media, 0, "rtcp", "ipVer"), "rtcp ipVer");
        assertEquals("X", at(media, 0, "rtcp", "address"), "rtcp address");
        assertEquals(1, size(at(media, 0, "invalid")), "found exactly 1 invalid line"); // f= lost
        assertEquals("goo:hithere", at(media, 0, "invalid", 0, "value"), "copied verbatim");
    }

    @Test
    void jssipSdp() {
        Object audCands = at(parse("jssip.sdp"), "media", 0, "candidates");

        assertEquals(6, size(audCands), "6 candidates");

        // testing ice optionals:
        assertEquals(
                map(
                        "foundation", 1162875081,
                        "component", 1,
                        "transport", "udp",
                        "priority", 2113937151,
                        "ip", "192.168.34.75",
                        "port", 60017,
                        "type", "host",
                        "generation", 0),
                at(audCands, 0),
                "audio candidate 0");
        assertEquals(
                map(
                        "foundation",
                        3289912957L,
                        "component",
                        1,
                        "transport",
                        "udp",
                        "priority",
                        1845501695,
                        "ip",
                        "193.84.77.194",
                        "port",
                        60017,
                        "type",
                        "srflx",
                        "raddr",
                        "192.168.34.75",
                        "rport",
                        60017,
                        "generation",
                        0),
                at(audCands, 2),
                "audio candidate 2 (raddr rport)");
        assertEquals(
                map(
                        "foundation", 198437945,
                        "component", 1,
                        "transport", "tcp",
                        "priority", 1509957375,
                        "ip", "192.168.34.75",
                        "port", 0,
                        "type", "host",
                        "generation", 0),
                at(audCands, 4),
                "audio candidate 4 (tcp)");
    }

    @Test
    void jsepSdp() {
        Object media = parse("jsep.sdp").get("media");
        assertEquals(2, size(media), "got media");

        Object video = at(media, 1);
        assertEquals(1, size(at(video, "ssrcGroups")), "1 ssrc grouping");
        assertEquals(
                map("semantics", "FID", "ssrcs", "1366781083 1366781084"), at(video, "ssrcGroups", 0), "ssrc-group");

        assertEquals(
                List.of(
                        map(
                                "id",
                                "61317484-2ed4-49d7-9eb7-1414322a7aae",
                                "appdata",
                                "f30bdb4a-5db8-49b5-bcdc-e0c9a23172e0"),
                        map(
                                "id",
                                "93e8b9bb-ad32-417e-9d2d-42c215f50713",
                                "appdata",
                                "f30bdb4a-5db8-49b5-bcdc-e0c9a23172e0")),
                at(video, "msid"),
                "msid");

        assertNotNull(at(video, "rtcpRsize"), "rtcp-rsize present");
        assertNotNull(at(video, "bundleOnly"), "bundle-only present");
        assertNotNull(at(video, "endOfCandidates"), "have end of candidates marker");
    }

    @Test
    void alacSdp() {
        Object audio = at(parse("alac.sdp"), "media", 0);

        assertEquals("audio", at(audio, "type"), "audio type");
        assertEquals("RTP/AVP", at(audio, "protocol"), "audio protocol");
        assertEquals(96, at(audio, "fmtp", 0, "payload"), "audio fmtp 0 payload");
        assertEquals("352 0 16 40 10 14 2 255 0 0 44100", at(audio, "fmtp", 0, "config"), "audio fmtp 0 config");
        assertEquals(96, at(audio, "rtp", 0, "payload"), "audio rtp 0 payload");
        assertEquals("AppleLossless", at(audio, "rtp", 0, "codec"), "audio rtp 0 codec");
        assertNull(at(audio, "rtp", 0, "rate"), "audio rtp 0 rate");
        assertNull(at(audio, "rtp", 0, "encoding"), "audio rtp 0 encoding");
    }

    @Test
    void onvifSdp() {
        Object media = parse("onvif.sdp").get("media");

        Object audio = at(media, 0);
        assertEquals("audio", at(audio, "type"), "audio type");
        assertEquals(0, at(audio, "port"), "audio port");
        assertEquals("RTP/AVP", at(audio, "protocol"), "audio protocol");
        assertEquals("rtsp://example.com/onvif_camera/audio", at(audio, "control"), "audio control");
        assertEquals(0, at(audio, "payloads"), "audio payloads");

        Object video = at(media, 1);
        assertEquals("video", at(video, "type"), "video type");
        assertEquals(0, at(video, "port"), "video port");
        assertEquals("RTP/AVP", at(video, "protocol"), "video protocol");
        assertEquals("rtsp://example.com/onvif_camera/video", at(video, "control"), "video control");
        assertEquals(26, at(video, "payloads"), "video payloads");

        Object application = at(media, 2);
        assertEquals("application", at(application, "type"), "application type");
        assertEquals(0, at(application, "port"), "application port");
        assertEquals("RTP/AVP", at(application, "protocol"), "application protocol");
        assertEquals("rtsp://example.com/onvif_camera/metadata", at(application, "control"), "application control");
        assertEquals(107, at(application, "payloads"), "application payloads");
        assertEquals("recvonly", at(application, "direction"), "application direction");
        assertEquals(107, at(application, "rtp", 0, "payload"), "application rtp 0 payload");
        assertEquals("vnd.onvif.metadata", at(application, "rtp", 0, "codec"), "application rtp 0 codec");
        assertEquals(90000, at(application, "rtp", 0, "rate"), "application rtp 0 rate");
        assertNull(at(application, "rtp", 0, "encoding"), "application rtp 0 encoding");
    }

    @Test
    void ssrcSdp() {
        Object video = at(parse("ssrc.sdp"), "media", 1);

        assertEquals(2, size(at(video, "ssrcGroups")), "video got 2 ssrc-group lines");
        assertEquals(
                List.of(
                        map("semantics", "FID", "ssrcs", "3004364195 1126032854"),
                        map("semantics", "FEC-FR", "ssrcs", "3004364195 1080772241")),
                at(video, "ssrcGroups"),
                "video ssrc-group obj");
    }

    @Test
    void simulcastSdp() {
        Object video = at(parse("simulcast.sdp"), "media", 1);
        assertEquals("video", at(video, "type"), "video type");

        // test rid lines
        assertEquals(5, size(at(video, "rids")), "video got 5 rid lines");
        assertEquals(
                map("id", 1, "direction", "send", "params", "pt=97;max-width=1280;max-height=720;max-fps=30"),
                at(video, "rids", 0),
                "video 1st rid line");
        assertEquals(map("id", 2, "direction", "send", "params", "pt=98"), at(video, "rids", 1), "video 2nd rid line");
        assertEquals(map("id", 3, "direction", "send", "params", "pt=99"), at(video, "rids", 2), "video 3rd rid line");
        assertEquals(map("id", 4, "direction", "send", "params", "pt=100"), at(video, "rids", 3), "video 4th rid line");
        assertEquals(
                map("id", "c", "direction", "recv", "params", "pt=97"), at(video, "rids", 4), "video 5th rid line");
        // test rid params
        assertEquals(
                map("pt", 97, "max-width", 1280, "max-height", 720, "max-fps", 30),
                Parser.parseParams((String) at(video, "rids", 0, "params")),
                "video 1st rid params");
        assertEquals(
                map("pt", 98), Parser.parseParams((String) at(video, "rids", 1, "params")), "video 2nd rid params");
        assertEquals(
                map("pt", 99), Parser.parseParams((String) at(video, "rids", 2, "params")), "video 3rd rid params");
        assertEquals(
                map("pt", 100), Parser.parseParams((String) at(video, "rids", 3, "params")), "video 4th rid params");
        assertEquals(
                map("pt", 97), Parser.parseParams((String) at(video, "rids", 4, "params")), "video 5th rid params");

        // test imageattr lines
        assertEquals(5, size(at(video, "imageattrs")), "video got 5 imageattr lines");
        assertEquals(
                map(
                        "pt", 97,
                        "dir1", "send",
                        "attrs1", "[x=1280,y=720]",
                        "dir2", "recv",
                        "attrs2", "[x=1280,y=720] [x=320,y=180] [x=160,y=90]"),
                at(video, "imageattrs", 0),
                "video 1st imageattr line");
        assertEquals(
                map("pt", 98, "dir1", "send", "attrs1", "[x=320,y=180]"),
                at(video, "imageattrs", 1),
                "video 2nd imageattr line");
        assertEquals(
                map("pt", 99, "dir1", "send", "attrs1", "[x=160,y=90]"),
                at(video, "imageattrs", 2),
                "video 3rd imageattr line");
        assertEquals(
                map(
                        "pt", 100,
                        "dir1", "recv",
                        "attrs1", "[x=1280,y=720] [x=320,y=180]",
                        "dir2", "send",
                        "attrs2", "[x=1280,y=720]"),
                at(video, "imageattrs", 3),
                "video 4th imageattr line");
        assertEquals(
                map("pt", "*", "dir1", "recv", "attrs1", "*"), at(video, "imageattrs", 4), "video 5th imageattr line");
        // test imageattr params
        assertEquals(
                List.of(map("x", 1280, "y", 720)),
                Parser.parseImageAttributes((String) at(video, "imageattrs", 0, "attrs1")),
                "video 1st imageattr send params");
        assertEquals(
                List.of(map("x", 1280, "y", 720), map("x", 320, "y", 180), map("x", 160, "y", 90)),
                Parser.parseImageAttributes((String) at(video, "imageattrs", 0, "attrs2")),
                "video 1st imageattr recv params");
        assertEquals(
                List.of(map("x", 320, "y", 180)),
                Parser.parseImageAttributes((String) at(video, "imageattrs", 1, "attrs1")),
                "video 2nd imageattr send params");
        assertEquals(
                List.of(map("x", 160, "y", 90)),
                Parser.parseImageAttributes((String) at(video, "imageattrs", 2, "attrs1")),
                "video 3rd imageattr send params");
        assertEquals(
                List.of(map("x", 1280, "y", 720), map("x", 320, "y", 180)),
                Parser.parseImageAttributes((String) at(video, "imageattrs", 3, "attrs1")),
                "video 4th imageattr recv params");
        assertEquals(
                List.of(map("x", 1280, "y", 720)),
                Parser.parseImageAttributes((String) at(video, "imageattrs", 3, "attrs2")),
                "video 4th imageattr send params");
        assertEquals("*", at(video, "imageattrs", 4, "attrs1"), "video 5th imageattr recv params");

        // test simulcast line
        assertEquals(
                map("dir1", "send", "list1", "1,~4;2;3", "dir2", "recv", "list2", "c"),
                at(video, "simulcast"),
                "video simulcast line");
        assertEquals(
                List.of(
                        List.of(map("scid", 1, "paused", false), map("scid", 4, "paused", true)),
                        List.of(map("scid", 2, "paused", false)),
                        List.of(map("scid", 3, "paused", false))),
                Parser.parseSimulcastStreamList((String) at(video, "simulcast", "list1")),
                "video simulcast send streams");
        assertEquals(
                List.of(List.of(map("scid", "c", "paused", false))),
                Parser.parseSimulcastStreamList((String) at(video, "simulcast", "list2")),
                "video simulcast recv streams");

        // test simulcast version 03 line
        assertEquals(
                map("value", "send rid=1,4;2;3 paused=4 recv rid=c"),
                at(video, "simulcast_03"),
                "video simulcast draft 03 line");
    }

    @Test
    void st2022_6() {
        Object sourceFilter = at(parse("st2022-6.sdp"), "media", 0, "sourceFilter");

        assertEquals("incl", at(sourceFilter, "filterMode"), "filter-mode is \"incl\"");
        assertEquals("IN", at(sourceFilter, "netType"), "nettype is \"IN\"");
        assertEquals("IP4", at(sourceFilter, "addressTypes"), "address-type is \"IP4\"");
        assertEquals("239.0.0.1", at(sourceFilter, "destAddress"), "dest-address is \"239.0.0.1\"");
        assertEquals("192.168.20.20", at(sourceFilter, "srcList"), "src-list is \"192.168.20.20\"");
    }

    @Test
    void st2110_20() {
        Object video = at(parse("st2110-20.sdp"), "media", 0);
        Object sourceFilter = at(video, "sourceFilter");

        assertEquals("incl", at(sourceFilter, "filterMode"), "filter-mode is \"incl\"");
        assertEquals("IN", at(sourceFilter, "netType"), "nettype is \"IN\"");
        assertEquals("IP4", at(sourceFilter, "addressTypes"), "address-type is \"IP4\"");
        assertEquals("239.100.9.10", at(sourceFilter, "destAddress"), "dest-address is \"239.100.9.10\"");
        assertEquals("192.168.100.2", at(sourceFilter, "srcList"), "src-list is \"192.168.100.2\"");

        assertEquals("video", at(video, "type"), "video type");
        assertEquals(
                map(
                        "sampling", "YCbCr-4:2:2",
                        "width", 1280,
                        "height", 720,
                        "interlace", null,
                        "exactframerate", "60000/1001",
                        "depth", 10,
                        "TCS", "SDR",
                        "colorimetry", "BT709",
                        "PM", "2110GPM",
                        "SSN", "ST2110-20:2017"),
                Parser.parseParams((String) at(video, "fmtp", 0, "config")),
                "video 5th rid params");
    }

    @Test
    void sctpDtls26() {
        Map<String, Object> session = parse("sctp-dtls-26.sdp");
        Object media = session.get("media");

        assertEquals("5636137646675714991", at(session, "origin", "sessionId"), "origin sessionId");
        assertEquals(1, size(session.get("groups")), "one grouping");
        assertEquals("BUNDLE", at(session, "groups", 0, "type"), "grouping is BUNDLE");
        assertEquals("data", at(session, "groups", 0, "mids"), "bundling data");
        assertEquals("WMS", at(session, "msidSemantic", "semantic"), "webrtc semantic");

        // verify media is data application
        assertEquals("application", at(media, 0, "type"), "media type application");
        assertEquals("data", at(media, 0, "mid"), "media id application");

        // verify protocol and ports
        assertEquals("UDP/DTLS/SCTP", at(media, 0, "protocol"), "protocol is UDP/DTLS/SCTP");
        assertEquals(9, at(media, 0, "port"), "the UDP port value is 9");
        assertEquals(5000, at(media, 0, "sctpPort"), "the offerer/answer SCTP port value is 5000");

        // verify maxMessageSize
        assertEquals(10000, at(media, 0, "maxMessageSize"), "maximum message size is 10000");
    }

    @Test
    void extmapEncryptSdp() {
        Map<String, Object> session = parse("extmap-encrypt.sdp");
        Object origin = session.get("origin");

        assertEquals("-", at(origin, "username"), "origin username");
        assertEquals(20518, at(origin, "sessionId"), "origin sessionId");
        assertEquals(0, at(origin, "sessionVersion"), "origin sessionVersion");
        assertEquals("IN", at(origin, "netType"), "origin netType");
        assertEquals(4, at(origin, "ipVer"), "origin ipVer");
        assertEquals("203.0.113.1", at(origin, "address"), "origin address");

        assertEquals("203.0.113.1", at(session, "connection", "ip"), "session connect ip");
        assertEquals(4, at(session, "connection", "version"), "session connect ip ver");

        Object audio = at(session, "media", 0);
        assertEquals("audio", at(audio, "type"), "audio type");
        assertEquals(54400, at(audio, "port"), "audio port");
        assertEquals("RTP/SAVPF", at(audio, "protocol"), "audio protocol");
        assertEquals(96, at(audio, "rtp", 0, "payload"), "audio rtp 0 payload");
        assertEquals("opus", at(audio, "rtp", 0, "codec"), "audio rtp 0 codec");
        assertEquals(48000, at(audio, "rtp", 0, "rate"), "audio rtp 0 rate");

        // extmap and encrypted extmap
        assertEquals(
                map("value", 1, "direction", "sendonly", "uri", "URI-toffset"),
                at(audio, "ext", 0),
                "audio extension 0");
        assertEquals(
                map("value", 2, "uri", "urn:ietf:params:rtp-hdrext:toffset"), at(audio, "ext", 1), "audio extension 1");
        assertEquals(
                map(
                        "value", 3,
                        "encrypt-uri", "urn:ietf:params:rtp-hdrext:encrypt",
                        "uri", "urn:ietf:params:rtp-hdrext:smpte-tc",
                        "config", "25@600/24"),
                at(audio, "ext", 2),
                "audio extension 2");
        assertEquals(
                map(
                        "value", 4,
                        "direction", "recvonly",
                        "encrypt-uri", "urn:ietf:params:rtp-hdrext:encrypt",
                        "uri", "URI-gps-string"),
                at(audio, "ext", 3),
                "audio extension 3");

        assertEquals(1, size(session.get("media")), "got 1 m-lines");
    }

    @Test
    void danteAes67() {
        Map<String, Object> session = parse("dante-aes67.sdp");
        Object origin = session.get("origin");
        assertEquals(1, size(session.get("media")), "got single media");

        assertEquals("-", at(origin, "username"), "origin username");
        assertEquals(1423986, at(origin, "sessionId"), "origin sessionId");
        assertEquals(1423994, at(origin, "sessionVersion"), "origin sessionVersion");
        assertEquals("IN", at(origin, "netType"), "origin netType");
        assertEquals(4, at(origin, "ipVer"), "origin ipVer");
        assertEquals("169.254.98.63", at(origin, "address"), "origin address");

        assertEquals("AOIP44-serial-1614 : 2", session.get("name"), "Session Name");
        assertEquals("Dante", session.get("keywords"), "Keywords");

        assertEquals("239.65.125.63/32", at(session, "connection", "ip"), "session connect ip");
        assertEquals(4, at(session, "connection", "version"), "session connect ip ver");

        Object audio = at(session, "media", 0);
        assertEquals("audio", at(audio, "type"), "audio type");
        assertEquals(5004, at(audio, "port"), "audio port");
        assertEquals("RTP/AVP", at(audio, "protocol"), "audio protocol");
        assertEquals("recvonly", at(audio, "direction"), "audio direction");
        assertEquals("2 channels: TxChan 0, TxChan 1", at(audio, "description"), "audio description");
        assertEquals(1, at(audio, "ptime"), "audio packet duration");
        assertEquals(97, at(audio, "rtp", 0, "payload"), "audio rtp payload type");
        assertEquals("L24", at(audio, "rtp", 0, "codec"), "audio rtp codec");
        assertEquals(48000, at(audio, "rtp", 0, "rate"), "audio sample rate");
        assertEquals(2, at(audio, "rtp", 0, "encoding"), "audio channels");
    }

    @Test
    void bfcp() {
        Map<String, Object> session = parse("bfcp.sdp");
        Object media = session.get("media");
        assertEquals(4, size(media), "got 4 media");

        assertEquals("-", at(session, "origin", "username"), "origin username");

        assertEquals("audio", at(media, 0, "type"), "audio type");

        Object video = at(media, 1);
        assertEquals("video", at(video, "type"), "main video type");
        assertEquals("sendrecv", at(video, "direction"), "main video direction");
        assertEquals("main", at(video, "content"), "main video content");
        assertEquals(1, at(video, "label"), "main video label");

        Object app = at(media, 2);
        assertEquals("application", at(app, "type"), "application type");
        assertEquals(3238, at(app, "port"), "application port");
        assertEquals("UDP/BFCP", at(app, "protocol"), "bfcp protocol");
        assertEquals("*", at(app, "payloads"), "bfcp payloads");
        assertEquals("new", at(app, "connectionType"), "connection type");
        assertEquals("s-only", at(app, "bfcpFloorCtrl"), "bfcp Floor Control");
        assertEquals(1, at(app, "bfcpConfId"), "bfcp ConfId");
        assertEquals(1, at(app, "bfcpUserId"), "bfcp UserId");
        assertEquals(1, at(app, "bfcpFloorId", "id"), "bfcp FloorId");
        assertEquals(3, at(app, "bfcpFloorId", "mStream"), "bfcp Floor Stream");

        Object video2 = at(media, 3);
        assertEquals("video", at(video2, "type"), "2nd video type");
        assertEquals("sendrecv", at(video2, "direction"), "2nd video direction");
        assertEquals("slides", at(video2, "content"), "2nd video content");
        assertEquals(3, at(video2, "label"), "2nd video label");
    }

    @Test
    void tcpActive() {
        Map<String, Object> session = parse("tcp-active.sdp");
        Object origin = session.get("origin");
        assertEquals(1, size(session.get("media")), "got single media");

        assertEquals("-", at(origin, "username"), "origin username");
        assertEquals(1562876543, at(origin, "sessionId"), "origin sessionId");
        assertEquals(11, at(origin, "sessionVersion"), "origin sessionVersion");
        assertEquals("IN", at(origin, "netType"), "origin netType");
        assertEquals(4, at(origin, "ipVer"), "origin ipVer");
        assertEquals("192.0.2.3", at(origin, "address"), "origin address");

        Object image = at(session, "media", 0);
        assertEquals("image", at(image, "type"), "image type");
        assertEquals(9, at(image, "port"), "port");
        assertEquals(4, at(image, "connection", "version"), "Connection is IPv4");
        assertEquals("192.0.2.3", at(image, "connection", "ip"), "Connection address");
        assertEquals("TCP", at(image, "protocol"), "TCP protocol");
        assertEquals("t38", at(image, "payloads"), "TCP payload");
        assertEquals("active", at(image, "setup"), "setup active");
        assertEquals("new", at(image, "connectionType"), "new connection");
    }

    @Test
    void tcpPassive() {
        Map<String, Object> session = parse("tcp-passive.sdp");
        Object origin = session.get("origin");
        assertEquals(1, size(session.get("media")), "got single media");

        assertEquals("-", at(origin, "username"), "origin username");
        assertEquals(1562876543, at(origin, "sessionId"), "origin sessionId");
        assertEquals(11, at(origin, "sessionVersion"), "origin sessionVersion");
        assertEquals("IN", at(origin, "netType"), "origin netType");
        assertEquals(4, at(origin, "ipVer"), "origin ipVer");
        assertEquals("192.0.2.2", at(origin, "address"), "origin address");

        Object image = at(session, "media", 0);
        assertEquals("image", at(image, "type"), "image type");
        assertEquals(54111, at(image, "port"), "port");
        assertEquals(4, at(image, "connection", "version"), "Connection is IPv4");
        assertEquals("192.0.2.2", at(image, "connection", "ip"), "Connection address");
        assertEquals("TCP", at(image, "protocol"), "TCP protocol");
        assertEquals("t38", at(image, "payloads"), "TCP payload");
        assertEquals("passive", at(image, "setup"), "setup passive");
        assertEquals("existing", at(image, "connectionType"), "existing connection");
    }

    @Test
    void mediaclkAvbtp() {
        Map<String, Object> session = parse("mediaclk-avbtp.sdp");
        assertEquals(1, size(session.get("media")), "got single media");

        Object mediaClk = at(session, "media", 0, "mediaClk");
        assertEquals("IEEE1722", at(mediaClk, "mediaClockName"), "IEEE1722 Media Clock");
        assertEquals("38-D6-6D-8E-D2-78-13-2F", at(mediaClk, "mediaClockValue"), "AVB stream ID");
    }

    @Test
    void mediaclkPtpV2WithRate() {
        Map<String, Object> session = parse("mediaclk-ptp-v2-w-rate.sdp");
        assertEquals(1, size(session.get("media")), "got single media");

        Object mediaClk = at(session, "media", 0, "mediaClk");
        assertEquals("direct", at(mediaClk, "mediaClockName"), "Direct Media Clock");
        assertEquals(963214424, at(mediaClk, "mediaClockValue"), "offset");
        assertEquals(1000, at(mediaClk, "rateNumerator"), "rate numerator");
        assertEquals(1001, at(mediaClk, "rateDenominator"), "rate denominator");
    }

    @Test
    void mediaclkPtpV2() {
        Map<String, Object> session = parse("mediaclk-ptp-v2.sdp");
        assertEquals(1, size(session.get("media")), "got single media");

        Object mediaClk = at(session, "media", 0, "mediaClk");
        assertEquals("direct", at(mediaClk, "mediaClockName"), "Direct Media Clock");
        assertEquals(963214424, at(mediaClk, "mediaClockValue"), "offset");
    }

    @Test
    void mediaclkRtp() {
        Map<String, Object> session = parse("mediaclk-rtp.sdp");
        assertEquals(1, size(session.get("media")), "got single media");

        Object mediaClk = at(session, "media", 0, "mediaClk");
        assertEquals("MDA6NjA6MmI6MjA6MTI6MWY=", at(mediaClk, "id"), "Media Clock ID");
        assertEquals("sender", at(mediaClk, "mediaClockName"), "sender type");
    }

    @Test
    void tsRefclkMedia() {
        Map<String, Object> session = parse("ts-refclk-media.sdp");

        Object sessTsRefClocks = session.get("tsRefClocks");
        assertEquals(1, size(sessTsRefClocks), "got one TS Ref Clock");
        assertEquals("local", at(sessTsRefClocks, 0, "clksrc"), "local Clock Source at Session Level");

        Object media = session.get("media");
        assertEquals(2, size(media), "got two media");

        Object audTsRefClocks = at(media, 0, "tsRefClocks");
        assertEquals(2, size(audTsRefClocks), "got two audio TS Ref Clocks");
        assertEquals("ntp", at(audTsRefClocks, 0, "clksrc"), "NTP Clock Source");
        assertEquals("203.0.113.10", at(audTsRefClocks, 0, "clksrcExt"), "IPv4 address");
        assertEquals("ntp", at(audTsRefClocks, 1, "clksrc"), "NTP Clock Source");
        assertEquals("198.51.100.22", at(audTsRefClocks, 1, "clksrcExt"), "IPv4 address");

        Object vidTsRefClocks = at(media, 1, "tsRefClocks");
        assertEquals(1, size(vidTsRefClocks), "got one video TS Ref Clocks");
        assertEquals("ptp", at(vidTsRefClocks, 0, "clksrc"), "PTP Clock Source");
        assertEquals("IEEE802.1AS-2011:39-A7-94-FF-FE-07-CB-D0", at(vidTsRefClocks, 0, "clksrcExt"), "PTP config");
    }

    @Test
    void tsRefclkSess() {
        Object sessTsRefClocks = parse("ts-refclk-sess.sdp").get("tsRefClocks");

        assertEquals(1, size(sessTsRefClocks), "got one TS Ref Clock at Session Level");
        assertEquals("ntp", at(sessTsRefClocks, 0, "clksrc"), "NTP Clock Source");
        assertEquals("/traceable/", at(sessTsRefClocks, 0, "clksrcExt"), "traceable Clock Source");
    }
}

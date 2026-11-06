/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.sdp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The grammar of sdp-transform: for each type of SDP line, the rules that parse and write it, tried
 * in order.
 *
 * <p>The regular expressions are those of the original. Java and JavaScript read them the same way,
 * except that {@code \s} and {@code \S} only cover ASCII whitespace in Java.
 */
public final class Grammar {

    /**
     * A rule of the grammar.
     *
     * <p>Where the values go depends on {@code name}, {@code push} and {@code names}: with only a
     * {@code name}, the first group is stored under it; with a {@code name} and {@code names}, the
     * groups are stored in a map under the name; with a {@code push}, in a map added to the list
     * under it; and with only {@code names}, directly in the session or media section.
     *
     * @param name the key of the value or map, or {@code null}
     * @param push the key of the list where each line adds a map, or {@code null}
     * @param reg the regular expression, searched in the line content after {@code x=}
     * @param names the keys of the groups, or empty if there is a single value under {@code name}
     * @param format the format of the written line, from the map with the values; it uses {@code %s}
     *     for a text, {@code %d} for a number and {@code %v} to skip a value
     */
    public record Rule(
            @Nullable String name,
            @Nullable String push,
            Pattern reg,
            List<String> names,
            Function<Map<String, Object>, String> format) {}

    private static final Pattern ANY = Pattern.compile("(.*)");

    private static final Map<Character, List<Rule>> RULES = rules();

    private Grammar() {}

    /**
     * Returns the rules of a type of line.
     *
     * @param type the type, the letter before {@code =}
     * @return the rules in the order they are tried, or an empty list if the type is unknown
     */
    public static List<Rule> rules(char type) {
        return RULES.getOrDefault(type, List.of());
    }

    // A single value under a name.
    private static Rule value(String name, @Nullable String reg, String format) {
        return new Rule(name, null, pattern(reg), List.of(), o -> format);
    }

    // A map of values under a name.
    private static Rule object(
            String name, String reg, List<String> names, Function<Map<String, Object>, String> format) {
        return new Rule(name, null, pattern(reg), names, format);
    }

    // A map of values added to a list.
    private static Rule push(
            String push, @Nullable String reg, List<String> names, Function<Map<String, Object>, String> format) {
        return new Rule(null, push, pattern(reg), names, format);
    }

    private static Pattern pattern(@Nullable String reg) {
        return reg == null ? ANY : Pattern.compile(reg);
    }

    private static Function<Map<String, Object>, String> fixed(String format) {
        return o -> format;
    }

    // Whether a value is truthy in JavaScript, as the original formats check with (o.x).
    static boolean truthy(@Nullable Object value) {
        return switch (value) {
            case null -> false;
            case String text -> !text.isEmpty();
            case Double number -> number != 0 && !number.isNaN();
            case Number number -> number.doubleValue() != 0;
            case Boolean bool -> bool;
            default -> true;
        };
    }

    private static Map<Character, List<Rule>> rules() {
        var rules = new LinkedHashMap<Character, List<Rule>>();
        rules.put('v', List.of(value("version", "^(\\d*)$", "%s")));
        rules.put(
                'o',
                List.of(object(
                        // o=- 20518 0 IN IP4 203.0.113.1
                        // NB: sessionId will be a String in most cases because it is huge
                        "origin",
                        "^(\\S*) (\\d*) (\\d*) (\\S*) IP(\\d) (\\S*)",
                        List.of("username", "sessionId", "sessionVersion", "netType", "ipVer", "address"),
                        fixed("%s %s %d %s IP%d %s"))));
        // default parsing of these only (though some of these feel outdated)
        rules.put('s', List.of(value("name", null, "%s")));
        rules.put('i', List.of(value("description", null, "%s")));
        rules.put('u', List.of(value("uri", null, "%s")));
        rules.put('e', List.of(value("email", null, "%s")));
        rules.put('p', List.of(value("phone", null, "%s")));
        rules.put('z', List.of(value("timezones", null, "%s")));
        rules.put('r', List.of(value("repeats", null, "%s")));
        rules.put(
                't',
                List.of(object(
                        // t=0 0
                        "timing", "^(\\d*) (\\d*)", List.of("start", "stop"), fixed("%d %d"))));
        rules.put(
                'c',
                List.of(object(
                        // c=IN IP4 10.47.197.26
                        "connection", "^IN IP(\\d) (\\S*)", List.of("version", "ip"), fixed("IN IP%d %s"))));
        rules.put(
                'b',
                List.of(push(
                        // b=AS:4000
                        "bandwidth", "^(TIAS|AS|CT|RR|RS):(\\d*)", List.of("type", "limit"), fixed("%s:%s"))));
        rules.put(
                'm',
                List.of(new Rule(
                        // m=video 51744 RTP/AVP 126 97 98 34 31
                        // NB: special - pushes to session
                        null,
                        null,
                        pattern("^(\\w*) (\\d*) ([\\w/]*)(?: (.*))?"),
                        List.of("type", "port", "protocol", "payloads"),
                        fixed("%s %d %s %s"))));
        rules.put('a', attributes());
        return rules;
    }

    private static List<Rule> attributes() {
        return List.of(
                push(
                        // a=rtpmap:110 opus/48000/2
                        "rtp",
                        "^rtpmap:(\\d*) ([\\w\\-.]*)(?:\\s*\\/(\\d*)(?:\\s*\\/(\\S*))?)?",
                        List.of("payload", "codec", "rate", "encoding"),
                        o -> truthy(o.get("encoding"))
                                ? "rtpmap:%d %s/%s/%s"
                                : truthy(o.get("rate")) ? "rtpmap:%d %s/%s" : "rtpmap:%d %s"),
                push(
                        // a=fmtp:108 profile-level-id=24;object=23;bitrate=64000
                        // a=fmtp:111 minptime=10; useinbandfec=1
                        "fmtp", "^fmtp:(\\d*) ([\\S| ]*)", List.of("payload", "config"), fixed("fmtp:%d %s")),
                // a=control:streamid=0
                value("control", "^control:(.*)", "control:%s"),
                object(
                        // a=rtcp:65179 IN IP4 193.84.77.194
                        "rtcp",
                        "^rtcp:(\\d*)(?: (\\S*) IP(\\d) (\\S*))?",
                        List.of("port", "netType", "ipVer", "address"),
                        o -> o.get("address") != null ? "rtcp:%d %s IP%d %s" : "rtcp:%d"),
                push(
                        // a=rtcp-fb:98 trr-int 100
                        "rtcpFbTrrInt",
                        "^rtcp-fb:(\\*|\\d*) trr-int (\\d*)",
                        List.of("payload", "value"),
                        fixed("rtcp-fb:%s trr-int %d")),
                push(
                        // a=rtcp-fb:98 nack rpsi
                        "rtcpFb",
                        "^rtcp-fb:(\\*|\\d*) ([\\w-_]*)(?: ([\\w-_]*))?",
                        List.of("payload", "type", "subtype"),
                        o -> o.get("subtype") != null ? "rtcp-fb:%s %s %s" : "rtcp-fb:%s %s"),
                push(
                        // a=extmap:2 urn:ietf:params:rtp-hdrext:toffset
                        // a=extmap:1/recvonly URI-gps-string
                        // a=extmap:3 urn:ietf:params:rtp-hdrext:encrypt urn:ietf:params:rtp-hdrext:smpte-tc 25@600/24
                        "ext",
                        "^extmap:(\\d+)(?:\\/(\\w+))?(?: (urn:ietf:params:rtp-hdrext:encrypt))? (\\S*)(?: (\\S*))?",
                        List.of("value", "direction", "encrypt-uri", "uri", "config"),
                        o -> "extmap:%d"
                                + (truthy(o.get("direction")) ? "/%s" : "%v")
                                + (truthy(o.get("encrypt-uri")) ? " %s" : "%v")
                                + " %s"
                                + (truthy(o.get("config")) ? " %s" : "")),
                // a=extmap-allow-mixed
                value("extmapAllowMixed", "^(extmap-allow-mixed)", "%s"),
                push(
                        // a=crypto:1 AES_CM_128_HMAC_SHA1_80 inline:PS1uQCVeeCFCanVmcjkpPywjNWhcYD0mXXtxaVBR|2^20|1:32
                        "crypto",
                        "^crypto:(\\d*) ([\\w_]*) (\\S*)(?: (\\S*))?",
                        List.of("id", "suite", "config", "sessionConfig"),
                        o -> o.get("sessionConfig") != null ? "crypto:%d %s %s %s" : "crypto:%d %s %s"),
                // a=setup:actpass
                value("setup", "^setup:(\\w*)", "setup:%s"),
                // a=connection:new
                value("connectionType", "^connection:(new|existing)", "connection:%s"),
                // a=mid:1
                value("mid", "^mid:([^\\s]*)", "mid:%s"),
                push(
                        // a=msid:0c8b064d-d807-43b4-b434-f92a889d8587 98178685-d409-46e0-8e16-7ef0db0db64a
                        "msid", "^msid:([\\w-]+)(?: ([\\w-]+))?", List.of("id", "appdata"), fixed("msid:%s %s")),
                // a=ptime:20
                value("ptime", "^ptime:(\\d*(?:\\.\\d*)*)", "ptime:%d"),
                // a=maxptime:60
                value("maxptime", "^maxptime:(\\d*(?:\\.\\d*)*)", "maxptime:%d"),
                // a=sendrecv
                value("direction", "^(sendrecv|recvonly|sendonly|inactive)", "%s"),
                // a=ice-lite
                value("icelite", "^(ice-lite)", "%s"),
                // a=ice-ufrag:F7gI
                value("iceUfrag", "^ice-ufrag:(\\S*)", "ice-ufrag:%s"),
                // a=ice-pwd:x9cml/YzichV2+XlhiMu8g
                value("icePwd", "^ice-pwd:(\\S*)", "ice-pwd:%s"),
                object(
                        // a=fingerprint:SHA-1 00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33
                        "fingerprint",
                        "^fingerprint:(\\S*) (\\S*)",
                        List.of("type", "hash"),
                        fixed("fingerprint:%s %s")),
                push(
                        // a=candidate:0 1 UDP 2113667327 203.0.113.1 54400 typ host
                        // a=candidate:1162875081 1 udp 2113937151 192.168.34.75 60017 typ host generation 0 network-id
                        // 3 network-cost 10
                        // a=candidate:3289912957 2 udp 1845501695 193.84.77.194 60017 typ srflx raddr 192.168.34.75
                        // rport 60017 generation 0 network-id 3 network-cost 10
                        // a=candidate:229815620 1 tcp 1518280447 192.168.150.19 60017 typ host tcptype active
                        // generation 0 network-id 3 network-cost 10
                        // a=candidate:3289912957 2 tcp 1845501695 193.84.77.194 60017 typ srflx raddr 192.168.34.75
                        // rport 60017 tcptype passive generation 0 network-id 3 network-cost 10
                        "candidates",
                        "^candidate:(\\S*) (\\d*) (\\S*) (\\d*) (\\S*) (\\d*) typ (\\S*)(?: raddr (\\S*) rport (\\d*))?"
                                + "(?: tcptype (\\S*))?(?: generation (\\d*))?(?: network-id (\\d*))?"
                                + "(?: network-cost (\\d*))?",
                        List.of(
                                "foundation",
                                "component",
                                "transport",
                                "priority",
                                "ip",
                                "port",
                                "type",
                                "raddr",
                                "rport",
                                "tcptype",
                                "generation",
                                "network-id",
                                "network-cost"),
                        o -> {
                            var str = "candidate:%s %d %s %d %s %d typ %s";
                            str += o.get("raddr") != null ? " raddr %s rport %d" : "%v%v";
                            // NB: candidate has three optional chunks, so %void middles one if it's missing
                            str += o.get("tcptype") != null ? " tcptype %s" : "%v";
                            if (o.get("generation") != null) {
                                str += " generation %d";
                            }
                            str += o.get("network-id") != null ? " network-id %d" : "%v";
                            str += o.get("network-cost") != null ? " network-cost %d" : "%v";
                            return str;
                        }),
                // a=end-of-candidates (keep after the candidates line for readability)
                value("endOfCandidates", "^(end-of-candidates)", "%s"),
                // a=remote-candidates:1 203.0.113.1 54400 2 203.0.113.1 54401 ...
                value("remoteCandidates", "^remote-candidates:(.*)", "remote-candidates:%s"),
                // a=ice-options:google-ice
                value("iceOptions", "^ice-options:(\\S*)", "ice-options:%s"),
                push(
                        // a=ssrc:2566107569 cname:t9YU8M1UxTF8Y1A1
                        "ssrcs", "^ssrc:(\\d*) ([\\w_-]*)(?::(.*))?", List.of("id", "attribute", "value"), o -> {
                            var str = "ssrc:%d";
                            if (o.get("attribute") != null) {
                                str += " %s";
                                if (o.get("value") != null) {
                                    str += ":%s";
                                }
                            }
                            return str;
                        }),
                push(
                        // a=ssrc-group:FEC 1 2
                        // a=ssrc-group:FEC-FR 3004364195 1080772241
                        // token-char = %x21 / %x23-27 / %x2A-2B / %x2D-2E / %x30-39 / %x41-5A / %x5E-7E
                        "ssrcGroups",
                        "^ssrc-group:([\\x21\\x23\\x24\\x25\\x26\\x27\\x2A\\x2B\\x2D\\x2E\\w]*) (.*)",
                        List.of("semantics", "ssrcs"),
                        fixed("ssrc-group:%s %s")),
                object(
                        // a=msid-semantic: WMS Jvlam5X3SX1OP6pn20zWogvaKJz5Hjf9OnlV
                        "msidSemantic",
                        "^msid-semantic:\\s?(\\w*) (\\S*)",
                        List.of("semantic", "token"),
                        // space after ':' is not accidental
                        fixed("msid-semantic: %s %s")),
                push(
                        // a=group:BUNDLE audio video
                        "groups", "^group:(\\w*) (.*)", List.of("type", "mids"), fixed("group:%s %s")),
                // a=rtcp-mux
                value("rtcpMux", "^(rtcp-mux)", "%s"),
                // a=rtcp-rsize
                value("rtcpRsize", "^(rtcp-rsize)", "%s"),
                object(
                        // a=sctpmap:5000 webrtc-datachannel 1024
                        "sctpmap",
                        "^sctpmap:([\\w_/]*) (\\S*)(?: (\\S*))?",
                        List.of("sctpmapNumber", "app", "maxMessageSize"),
                        o -> o.get("maxMessageSize") != null ? "sctpmap:%s %s %s" : "sctpmap:%s %s"),
                // a=x-google-flag:conference
                value("xGoogleFlag", "^x-google-flag:([^\\s]*)", "x-google-flag:%s"),
                push(
                        // a=rid:1 send max-width=1280;max-height=720;max-fps=30;depend=0
                        "rids",
                        "^rid:([\\d\\w]+) (\\w+)(?: ([\\S| ]*))?",
                        List.of("id", "direction", "params"),
                        o -> truthy(o.get("params")) ? "rid:%s %s %s" : "rid:%s %s"),
                push(
                        // a=imageattr:97 send [x=800,y=640,sar=1.1,q=0.6] [x=480,y=320] recv [x=330,y=250]
                        // a=imageattr:* send [x=800,y=640] recv *
                        // a=imageattr:100 recv [x=320,y=240]
                        "imageattrs",
                        // a=imageattr:97
                        "^imageattr:(\\d+|\\*)"
                                // send [x=800,y=640,sar=1.1,q=0.6] [x=480,y=320]
                                + "[\\s\\t]+(send|recv)[\\s\\t]+(\\*|\\[\\S+\\](?:[\\s\\t]+\\[\\S+\\])*)"
                                // recv [x=330,y=250]
                                + "(?:[\\s\\t]+(recv|send)[\\s\\t]+(\\*|\\[\\S+\\](?:[\\s\\t]+\\[\\S+\\])*))?",
                        List.of("pt", "dir1", "attrs1", "dir2", "attrs2"),
                        o -> "imageattr:%s %s %s" + (truthy(o.get("dir2")) ? " %s %s" : "")),
                object(
                        // a=simulcast:send 1,2,3;~4,~5 recv 6;~7,~8
                        // a=simulcast:recv 1;4,5 send 6;7
                        "simulcast",
                        // a=simulcast:
                        "^simulcast:"
                                // send 1,2,3;~4,~5
                                + "(send|recv) ([a-zA-Z0-9\\-_~;,]+)"
                                // space + recv 6;~7,~8
                                + "(?:\\s?(send|recv) ([a-zA-Z0-9\\-_~;,]+))?"
                                // end
                                + "$",
                        List.of("dir1", "list1", "dir2", "list2"),
                        o -> "simulcast:%s %s" + (truthy(o.get("dir2")) ? " %s %s" : "")),
                object(
                        // old simulcast draft 03 (implemented by Firefox)
                        //   https://tools.ietf.org/html/draft-ietf-mmusic-sdp-simulcast-03
                        // a=simulcast: recv pt=97;98 send pt=97
                        // a=simulcast: send rid=5;6;7 paused=6,7
                        "simulcast_03",
                        "^simulcast:[\\s\\t]+([\\S+\\s\\t]+)$",
                        List.of("value"),
                        fixed("simulcast: %s")),
                // a=framerate:25
                // a=framerate:29.97
                value("framerate", "^framerate:(\\d+(?:$|\\.\\d+))", "framerate:%s"),
                object(
                        // RFC4570
                        // a=source-filter: incl IN IP4 239.5.2.31 10.1.15.5
                        "sourceFilter",
                        "^source-filter: *(excl|incl) (\\S*) (IP4|IP6|\\*) (\\S*) (.*)",
                        List.of("filterMode", "netType", "addressTypes", "destAddress", "srcList"),
                        fixed("source-filter: %s %s %s %s %s")),
                // a=bundle-only
                value("bundleOnly", "^(bundle-only)", "%s"),
                // a=label:1
                value("label", "^label:(.+)", "label:%s"),
                // RFC version 26 for SCTP over DTLS
                // https://tools.ietf.org/html/draft-ietf-mmusic-sctp-sdp-26#section-5
                value("sctpPort", "^sctp-port:(\\d+)$", "sctp-port:%s"),
                // RFC version 26 for SCTP over DTLS
                // https://tools.ietf.org/html/draft-ietf-mmusic-sctp-sdp-26#section-6
                value("maxMessageSize", "^max-message-size:(\\d+)$", "max-message-size:%s"),
                push(
                        // RFC7273
                        // a=ts-refclk:ptp=IEEE1588-2008:39-A7-94-FF-FE-07-CB-D0:37
                        "tsRefClocks",
                        "^ts-refclk:([^\\s=]*)(?:=(\\S*))?",
                        List.of("clksrc", "clksrcExt"),
                        o -> "ts-refclk:%s" + (o.get("clksrcExt") != null ? "=%s" : "")),
                object(
                        // RFC7273
                        // a=mediaclk:direct=963214424
                        "mediaClk",
                        "^mediaclk:(?:id=(\\S*))? *([^\\s=]*)(?:=(\\S*))?(?: *rate=(\\d+)\\/(\\d+))?",
                        List.of("id", "mediaClockName", "mediaClockValue", "rateNumerator", "rateDenominator"),
                        o -> {
                            var str = "mediaclk:";
                            str += o.get("id") != null ? "id=%s %s" : "%v%s";
                            str += o.get("mediaClockValue") != null ? "=%s" : "";
                            str += o.get("rateNumerator") != null ? " rate=%s" : "";
                            str += o.get("rateDenominator") != null ? "/%s" : "";
                            return str;
                        }),
                // a=keywds:keywords
                value("keywords", "^keywds:(.+)$", "keywds:%s"),
                // a=content:main
                value("content", "^content:(.+)", "content:%s"),
                // BFCP https://tools.ietf.org/html/rfc4583
                // a=floorctrl:c-s
                value("bfcpFloorCtrl", "^floorctrl:(c-only|s-only|c-s)", "floorctrl:%s"),
                // a=confid:1
                value("bfcpConfId", "^confid:(\\d+)", "confid:%s"),
                // a=userid:1
                value("bfcpUserId", "^userid:(\\d+)", "userid:%s"),
                object(
                        // a=floorid:1
                        "bfcpFloorId",
                        "^floorid:(.+) (?:m-stream|mstrm):(.+)",
                        List.of("id", "mStream"),
                        fixed("floorid:%s mstrm:%s")),
                // any a= that we don't understand is kept verbatim on media.invalid
                push("invalid", null, List.of("value"), fixed("%s")));
    }
}

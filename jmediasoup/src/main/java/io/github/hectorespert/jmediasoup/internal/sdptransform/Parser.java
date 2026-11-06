/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.sdptransform;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The parser of sdp-transform.
 *
 * <p>A field becomes a number when it reads the same after converting it to a number and back, as
 * {@code String(Number(v)) === v} does in the original: {@code "100"} and {@code "29.97"} are
 * numbers, while {@code "007"}, {@code "1e3"}, {@code "-0"} and integers too big to be exact in a
 * double stay texts. Numbers are stored as Jackson reads them from JSON: an {@link Integer} if the
 * value fits, otherwise a {@link Long} or a {@link BigInteger}, and a {@link Double} if it has
 * decimals.
 */
public final class Parser {

    private static final Pattern LINE_BREAK = Pattern.compile("\r\n|\r|\n");
    private static final Pattern DECIMAL = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    private static final Pattern PARAM_SEPARATOR = Pattern.compile(";\\s?");
    private static final Pattern KEY_VALUE = Pattern.compile("=(.+)");

    private Parser() {}

    /**
     * Parses an SDP.
     *
     * <p>Lines that are not {@code x=...}, with {@code x} a lowercase letter, are ignored, and so are
     * those of an unknown type. Attributes that no rule understands are kept in {@code invalid}.
     *
     * @param sdp the SDP, with any line ending
     * @return the session, with its media sections in {@code media}; each section starts with empty
     *     {@code rtp} and {@code fmtp} lists
     */
    public static Map<String, Object> parse(String sdp) {
        var session = new LinkedHashMap<String, Object>();
        var media = new ArrayList<Map<String, Object>>();
        // points at where properties go under (one of the above)
        Map<String, Object> location = session;

        for (String line : LINE_BREAK.split(sdp)) {
            if (!isValidLine(line)) {
                continue;
            }
            char type = line.charAt(0);
            String content = line.substring(2);
            if (type == 'm') {
                var mediaSection = new LinkedHashMap<String, Object>();
                mediaSection.put("rtp", new ArrayList<>());
                mediaSection.put("fmtp", new ArrayList<>());
                media.add(mediaSection);
                location = mediaSection;
            }

            for (Grammar.Rule rule : Grammar.rules(type)) {
                Matcher matcher = rule.reg().matcher(content);
                if (matcher.find()) {
                    parseReg(rule, location, matcher);
                    break;
                }
            }
        }

        session.put("media", media);
        return session;
    }

    /**
     * Parses parameters such as the configuration of {@code a=fmtp}: {@code key=value} pairs
     * separated by {@code ;} and an optional space.
     *
     * @param str the parameters
     * @return the values by key, in their order; a key without a value of more than one character
     *     maps to {@code null}
     */
    public static Map<String, @Nullable Object> parseParams(String str) {
        var params = new LinkedHashMap<String, @Nullable Object>();
        for (String expr : PARAM_SEPARATOR.split(str, -1)) {
            addParam(params, expr);
        }
        return params;
    }

    /**
     * Parses the payloads of an {@code m=} line, converting each one as JavaScript's {@code Number}
     * does.
     *
     * @param payloads the payloads separated by spaces, as text or as a number if there is only one
     * @return the numbers, with {@link Double#NaN} for those that are not
     */
    public static List<Number> parsePayloads(Object payloads) {
        var result = new ArrayList<Number>();
        for (String payload : String.valueOf(payloads).split(" ", -1)) {
            result.add(jsNumber(payload));
        }
        return result;
    }

    /**
     * Parses the value of {@code a=remote-candidates}.
     *
     * @param str the candidates, as groups of component, IP and port separated by spaces
     * @return a map with {@code component}, {@code ip} and {@code port} for each candidate, with
     *     {@code null} for the values missing in the last one
     */
    public static List<Map<String, @Nullable Object>> parseRemoteCandidates(String str) {
        var candidates = new ArrayList<Map<String, @Nullable Object>>();
        String[] parts = str.split(" ", -1);
        for (int i = 0; i < parts.length; i += 3) {
            var candidate = new LinkedHashMap<String, @Nullable Object>();
            candidate.put("component", toIntIfInt(parts[i]));
            candidate.put("ip", i + 1 < parts.length ? toIntIfInt(parts[i + 1]) : null);
            candidate.put("port", i + 2 < parts.length ? toIntIfInt(parts[i + 2]) : null);
            candidates.add(candidate);
        }
        return candidates;
    }

    /**
     * Parses the attributes of {@code a=imageattr}, such as {@code [x=1280,y=720] [x=320,y=180]}.
     *
     * @param str the attributes, each between brackets, separated by spaces
     * @return the parameters of each attribute
     */
    public static List<Map<String, @Nullable Object>> parseImageAttributes(String str) {
        var attributes = new ArrayList<Map<String, @Nullable Object>>();
        for (String item : str.split(" ", -1)) {
            var params = new LinkedHashMap<String, @Nullable Object>();
            for (String expr : jsSubstring(item, 1, item.length() - 1).split(",", -1)) {
                addParam(params, expr);
            }
            attributes.add(params);
        }
        return attributes;
    }

    /**
     * Parses a stream list of {@code a=simulcast}, such as {@code 1,~2;3}.
     *
     * @param str the streams separated by {@code ;}, each with its alternative formats separated by
     *     {@code ,}
     * @return for each stream, a map with {@code scid} and {@code paused} for each format; a format
     *     that starts with {@code ~} is paused
     */
    public static List<List<Map<String, Object>>> parseSimulcastStreamList(String str) {
        var streams = new ArrayList<List<Map<String, Object>>>();
        for (String stream : str.split(";", -1)) {
            var formats = new ArrayList<Map<String, Object>>();
            for (String format : stream.split(",", -1)) {
                boolean paused = format.startsWith("~");
                var entry = new LinkedHashMap<String, Object>();
                entry.put("scid", toIntIfInt(paused ? format.substring(1) : format));
                entry.put("paused", paused);
                formats.add(entry);
            }
            streams.add(formats);
        }
        return streams;
    }

    /**
     * Converts a field to a number if it reads the same as the number, as {@code String(Number(v))
     * === v} does in JavaScript.
     *
     * @param value the field
     * @return the number, or the same text
     */
    static Object toIntIfInt(String value) {
        if (!DECIMAL.matcher(value).matches()) {
            return value;
        }
        double number = Double.parseDouble(value);
        double magnitude = Math.abs(number);
        // JavaScript writes these with an exponent, so they never read the same.
        if (Double.isInfinite(number) || (number != 0 && (magnitude < 1e-6 || magnitude >= 1e21))) {
            return value;
        }
        // Since Java 19, Double.toString gives the shortest digits, like JavaScript.
        BigDecimal shortest = new BigDecimal(Double.toString(number)).stripTrailingZeros();
        if (!shortest.toPlainString().equals(value)) {
            return value;
        }
        if (shortest.scale() > 0) {
            return number;
        }
        return integer(shortest.toBigIntegerExact());
    }

    private static boolean isValidLine(String line) {
        return line.length() >= 2 && line.charAt(0) >= 'a' && line.charAt(0) <= 'z' && line.charAt(1) == '=';
    }

    @SuppressWarnings("unchecked")
    private static void parseReg(Grammar.Rule rule, Map<String, Object> location, Matcher matcher) {
        String name = rule.name();
        String push = rule.push();
        boolean needsBlank = name != null && !rule.names().isEmpty();
        if (push != null && location.get(push) == null) {
            location.put(push, new ArrayList<Map<String, Object>>());
        } else if (needsBlank && location.get(name) == null) {
            location.put(name, new LinkedHashMap<String, Object>());
        }
        Map<String, Object> keyLocation;
        if (push != null) {
            // blank object that will be pushed
            keyLocation = new LinkedHashMap<>();
        } else if (needsBlank) {
            // named location
            keyLocation = (Map<String, Object>) location.get(name);
        } else {
            // root
            keyLocation = location;
        }

        attachProperties(matcher, keyLocation, rule.names(), name);

        if (push != null) {
            ((List<Map<String, Object>>) location.get(push)).add(keyLocation);
        }
    }

    private static void attachProperties(
            Matcher matcher, Map<String, Object> location, List<String> names, @Nullable String rawName) {
        if (rawName != null && names.isEmpty()) {
            String group = matcher.group(1);
            if (group != null) {
                location.put(rawName, toIntIfInt(group));
            }
            return;
        }
        for (int i = 0; i < names.size() && i < matcher.groupCount(); i++) {
            String group = matcher.group(i + 1);
            if (group != null) {
                location.put(names.get(i), toIntIfInt(group));
            }
        }
    }

    // Like expr.split(/=(.+)/, 2): the key before the first = followed by something, and the rest.
    private static void addParam(Map<String, @Nullable Object> params, String expr) {
        Matcher matcher = KEY_VALUE.matcher(expr);
        if (matcher.find()) {
            params.put(expr.substring(0, matcher.start()), toIntIfInt(matcher.group(1)));
        } else if (expr.length() > 1) {
            params.put(expr, null);
        }
    }

    // JavaScript's substring, which clamps the indexes and swaps them if they are reversed.
    private static String jsSubstring(String str, int start, int end) {
        int from = Math.clamp(start, 0, str.length());
        int to = Math.clamp(end, 0, str.length());
        return str.substring(Math.min(from, to), Math.max(from, to));
    }

    // JavaScript's Number applied to a text, with integers as Jackson reads them.
    private static Number jsNumber(String str) {
        double number = JavaScript.number(str);
        if (Double.isFinite(number)
                && number == Math.rint(number)
                && !(number == 0 && 1 / number < 0)
                && Math.abs(number) < 0x1p63) {
            return integer(BigInteger.valueOf((long) number));
        }
        return number;
    }

    private static Number integer(BigInteger value) {
        if (value.bitLength() < Integer.SIZE) {
            return value.intValue();
        }
        if (value.bitLength() < Long.SIZE) {
            return value.longValue();
        }
        return value;
    }
}

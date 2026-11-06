/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.sdptransform;

import static java.util.Objects.requireNonNull;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The writer of sdp-transform.
 *
 * <p>Lines are written in the order of the grammar, not in the order they were parsed, so writing a
 * parsed SDP gives the same session but not always the same text. Values are written as JavaScript
 * would: a missing value is {@code undefined}, and {@code %d} converts its value with {@code
 * Number()}.
 */
public final class Writer {

    private static final Pattern FORMAT = Pattern.compile("%[sdv%]");

    // RFC specified order
    private static final List<Character> DEFAULT_OUTER_ORDER =
            List.of('v', 'o', 's', 'i', 'u', 'e', 'p', 'c', 'b', 't', 'r', 'z', 'a');
    private static final List<Character> DEFAULT_INNER_ORDER = List.of('i', 'c', 'b', 'a');

    private Writer() {}

    /**
     * Writes an SDP with the lines in the order of RFC 4566.
     *
     * <p>Like the original, it adds what every SDP needs to the session: {@code version} 0, a
     * {@code name} of one space, and empty {@code payloads} in each media section.
     *
     * @param session the session, with its media sections in {@code media}
     * @return the SDP, with {@code \r\n} after each line
     */
    public static String write(Map<String, Object> session) {
        return write(session, DEFAULT_OUTER_ORDER, DEFAULT_INNER_ORDER);
    }

    /**
     * Writes an SDP with the lines in the given order of types.
     *
     * @param session the session, with its media sections in {@code media}
     * @param outerOrder the order of the types of the session lines
     * @param innerOrder the order of the types of the lines of each media section, after its
     *     {@code m=} line
     * @return the SDP, with {@code \r\n} after each line
     */
    @SuppressWarnings("unchecked")
    public static String write(Map<String, Object> session, List<Character> outerOrder, List<Character> innerOrder) {
        var media = (List<Map<String, Object>>) requireNonNull(session.get("media"), "media");
        // ensure certain properties exist
        if (session.get("version") == null) {
            // 'v=0' must be there (only defined version atm)
            session.put("version", 0);
        }
        if (session.get("name") == null) {
            // 's= ' must be there if no meaningful name set
            session.put("name", " ");
        }
        for (Map<String, Object> mLine : media) {
            if (mLine.get("payloads") == null) {
                mLine.put("payloads", "");
            }
        }

        var sdp = new ArrayList<String>();

        // loop through outerOrder for matching properties on session
        for (char type : outerOrder) {
            addLines(sdp, type, session);
        }

        // then for each media line, follow the innerOrder
        for (Map<String, Object> mLine : media) {
            sdp.add(makeLine('m', Grammar.rules('m').getFirst(), mLine));
            for (char type : innerOrder) {
                addLines(sdp, type, mLine);
            }
        }

        return String.join("\r\n", sdp) + "\r\n";
    }

    @SuppressWarnings("unchecked")
    private static void addLines(List<String> sdp, char type, Map<String, Object> location) {
        for (Grammar.Rule rule : Grammar.rules(type)) {
            String name = rule.name();
            String push = rule.push();
            if (name != null && location.get(name) != null) {
                sdp.add(makeLine(type, rule, location));
            } else if (push != null && location.get(push) != null) {
                for (Map<String, Object> element : (List<Map<String, Object>>) location.get(push)) {
                    sdp.add(makeLine(type, rule, element));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static String makeLine(char type, Grammar.Rule rule, Map<String, Object> location) {
        String name = rule.name();
        Map<String, Object> values;
        if (rule.push() != null || name == null) {
            values = location;
        } else if (location.get(name) instanceof Map<?, ?> map) {
            values = (Map<String, Object>) map;
        } else {
            // A single value: its format does not look at the values.
            values = Map.of();
        }
        String str = rule.format().apply(values);

        var args = new ArrayList<@Nullable Object>();
        if (!rule.names().isEmpty()) {
            for (String n : rule.names()) {
                // for mLine and push attributes, the values are in the location itself
                args.add(name != null ? values.get(n) : location.get(n));
            }
        } else {
            args.add(location.get(name));
        }
        return format(type + "=" + str, args);
    }

    // customized util.format - discards excess arguments and can void middle ones
    private static String format(String formatStr, List<@Nullable Object> args) {
        Matcher matcher = FORMAT.matcher(formatStr);
        var result = new StringBuilder();
        int i = 0;
        while (matcher.find()) {
            String x = matcher.group();
            String replacement;
            if (i >= args.size()) {
                // missing argument
                replacement = x;
            } else {
                Object arg = args.get(i);
                i += 1;
                replacement = switch (x) {
                    case "%%" -> "%";
                    case "%s" -> JavaScript.string(arg);
                    case "%d" -> number(arg);
                    default -> ""; // %v
                };
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        // NB: we discard excess arguments - they are typically undefined from makeLine
        return result.toString();
    }

    // Number(arg) as text, keeping integers exact.
    private static String number(@Nullable Object arg) {
        if (arg instanceof Integer || arg instanceof Long || arg instanceof BigInteger) {
            return arg.toString();
        }
        return JavaScript.numberToString(JavaScript.number(arg));
    }
}

/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.json;

import java.util.List;

/**
 * Reads and writes JSON with one major version of Jackson, so that the same tests run with the
 * {@code ObjectMapper} of Jackson 2 and the {@code JsonMapper} of Jackson 3, both with their
 * defaults.
 */
interface JsonCodec {

    <T> T read(String json, Class<T> type) throws Exception;

    String write(Object value) throws Exception;

    /** Parses JSON into a tree whose {@code equals} ignores the order of the fields. */
    Object tree(String json) throws Exception;

    static List<JsonCodec> all() {
        return List.of(new Jackson2(), new Jackson3());
    }

    final class Jackson2 implements JsonCodec {

        private final com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();

        @Override
        public <T> T read(String json, Class<T> type) throws Exception {
            return mapper.readValue(json, type);
        }

        @Override
        public String write(Object value) throws Exception {
            return mapper.writeValueAsString(value);
        }

        @Override
        public Object tree(String json) throws Exception {
            return mapper.readTree(json);
        }

        @Override
        public String toString() {
            return "Jackson 2";
        }
    }

    final class Jackson3 implements JsonCodec {

        private final tools.jackson.databind.json.JsonMapper mapper =
                tools.jackson.databind.json.JsonMapper.builder().build();

        @Override
        public <T> T read(String json, Class<T> type) {
            return mapper.readValue(json, type);
        }

        @Override
        public String write(Object value) {
            return mapper.writeValueAsString(value);
        }

        @Override
        public Object tree(String json) {
            return mapper.readTree(json);
        }

        @Override
        public String toString() {
            return "Jackson 3";
        }
    }
}

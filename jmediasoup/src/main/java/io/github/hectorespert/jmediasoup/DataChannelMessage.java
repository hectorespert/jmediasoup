/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import static java.util.Objects.requireNonNull;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * A message sent or received on a data channel: text or binary. It keeps its own copy of the bytes,
 * so it stays valid after the callback that delivered it.
 */
public final class DataChannelMessage {

    private final byte[] bytes;
    private final boolean binary;

    private DataChannelMessage(byte[] bytes, boolean binary) {
        this.bytes = bytes;
        this.binary = binary;
    }

    /**
     * Creates a text message.
     *
     * @param text the text, sent as UTF-8
     * @return the message
     */
    public static DataChannelMessage text(String text) {
        return new DataChannelMessage(requireNonNull(text, "text").getBytes(StandardCharsets.UTF_8), false);
    }

    /**
     * Creates a binary message.
     *
     * @param bytes the bytes, which are copied
     * @return the message
     */
    public static DataChannelMessage binary(byte[] bytes) {
        return new DataChannelMessage(requireNonNull(bytes, "bytes").clone(), true);
    }

    /**
     * Creates a message from the remaining bytes of a buffer, without changing its position.
     *
     * @param buffer the bytes, which are copied
     * @param binary whether the message is binary rather than UTF-8 text
     * @return the message
     */
    public static DataChannelMessage of(ByteBuffer buffer, boolean binary) {
        ByteBuffer source = requireNonNull(buffer, "buffer").duplicate();
        byte[] bytes = new byte[source.remaining()];
        source.get(bytes);
        return new DataChannelMessage(bytes, binary);
    }

    /**
     * Returns whether the message is binary.
     *
     * @return {@code true} for binary messages, {@code false} for text
     */
    public boolean isBinary() {
        return binary;
    }

    /**
     * Returns the content.
     *
     * @return a copy of the bytes
     */
    public byte[] bytes() {
        return bytes.clone();
    }

    /**
     * Returns the content without copying it.
     *
     * @return a read-only buffer over the bytes
     */
    public ByteBuffer asByteBuffer() {
        return ByteBuffer.wrap(bytes).asReadOnlyBuffer();
    }

    /**
     * Returns the content as text.
     *
     * @return the bytes decoded as UTF-8, also for binary messages
     */
    public String asText() {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Returns the size of the content.
     *
     * @return the number of bytes
     */
    public int size() {
        return bytes.length;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof DataChannelMessage message
                && binary == message.binary
                && Arrays.equals(bytes, message.bytes);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(bytes) + Boolean.hashCode(binary);
    }

    @Override
    public String toString() {
        return binary
                ? "DataChannelMessage[binary, " + bytes.length + " bytes]"
                : "DataChannelMessage[text=" + asText() + "]";
    }
}

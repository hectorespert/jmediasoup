/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import org.junit.jupiter.api.Test;

class DataChannelMessageTest {

    @Test
    void textIsSentAsUtf8() {
        var message = DataChannelMessage.text("¡hola!");

        assertFalse(message.isBinary());
        assertEquals("¡hola!", message.asText());
        assertEquals(7, message.size());
    }

    @Test
    void keepsItsOwnCopyOfTheBytes() {
        byte[] bytes = {1, 2, 3};
        var message = DataChannelMessage.binary(bytes);
        bytes[0] = 9;
        message.bytes()[1] = 9;

        assertTrue(message.isBinary());
        assertArrayEquals(new byte[] {1, 2, 3}, message.bytes());
        assertThrows(ReadOnlyBufferException.class, () -> message.asByteBuffer().put((byte) 0));
    }

    @Test
    void copiesTheRemainingBytesOfABufferWithoutMovingIt() {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[] {0, 1, 2, 3});
        buffer.position(1);

        var message = DataChannelMessage.of(buffer, true);

        assertArrayEquals(new byte[] {1, 2, 3}, message.bytes());
        assertEquals(1, buffer.position());
    }

    @Test
    void equalityComparesContentAndType() {
        assertEquals(DataChannelMessage.binary(new byte[] {104, 105}), DataChannelMessage.binary("hi".getBytes()));
        assertEquals(
                DataChannelMessage.text("hi").hashCode(),
                DataChannelMessage.text("hi").hashCode());
        assertNotEquals(DataChannelMessage.text("hi"), DataChannelMessage.binary("hi".getBytes()));
        assertEquals(
                "DataChannelMessage[binary, 2 bytes]",
                DataChannelMessage.binary(new byte[2]).toString());
    }
}

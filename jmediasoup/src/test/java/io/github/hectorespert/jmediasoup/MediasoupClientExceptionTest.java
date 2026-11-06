/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class MediasoupClientExceptionTest {
    @Test
    void isChecked() {
        assertFalse(RuntimeException.class.isAssignableFrom(MediasoupClientException.class));
    }

    @Test
    void specificExceptionsExtendTheBase() {
        assertInstanceOf(MediasoupClientException.class, new UnsupportedException("not a sending Transport"));
        assertInstanceOf(MediasoupClientException.class, new InvalidStateException("closed"));
    }

    @Test
    void keepsMessageAndCause() {
        var cause = new IllegalStateException("native failure");
        var exception = new InvalidStateException("closed", cause);

        assertEquals("closed", exception.getMessage());
        assertSame(cause, exception.getCause());
        assertNull(new UnsupportedException("not a video Producer").getCause());
    }
}

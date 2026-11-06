/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import org.jspecify.annotations.Nullable;

/**
 * Thrown when something is not supported, such as producing on a receiving transport or a media
 * kind the device cannot send. The equivalent of {@code UnsupportedError} in pymediasoup.
 */
public final class UnsupportedException extends MediasoupClientException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a message.
     *
     * @param message the detail message
     */
    public UnsupportedException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a message and the failure that caused it.
     *
     * @param message the detail message
     * @param cause the underlying failure, if any
     */
    public UnsupportedException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}

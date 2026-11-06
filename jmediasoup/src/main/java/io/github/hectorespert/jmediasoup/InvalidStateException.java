/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import org.jspecify.annotations.Nullable;

/**
 * Thrown when a method is called in a state that does not allow it, such as on a closed transport
 * or on a device that is not loaded. The equivalent of {@code InvalidStateError} in pymediasoup.
 */
public final class InvalidStateException extends MediasoupClientException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a message.
     *
     * @param message the detail message
     */
    public InvalidStateException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a message and the failure that caused it.
     *
     * @param message the detail message
     * @param cause the underlying failure, if any
     */
    public InvalidStateException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}

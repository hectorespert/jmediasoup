/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import org.jspecify.annotations.Nullable;

/**
 * Base class of the exceptions thrown by jmediasoup, the equivalent of {@code Error} in pymediasoup.
 *
 * <p>It is also thrown directly for failures that have no more specific type, such as a remote SDP
 * without the expected media section. Invalid arguments throw {@link IllegalArgumentException}
 * instead, and missing ones {@link NullPointerException}.
 */
public class MediasoupClientException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a message.
     *
     * @param message the detail message
     */
    public MediasoupClientException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a message and the failure that caused it.
     *
     * @param message the detail message
     * @param cause the underlying failure, if any
     */
    public MediasoupClientException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}

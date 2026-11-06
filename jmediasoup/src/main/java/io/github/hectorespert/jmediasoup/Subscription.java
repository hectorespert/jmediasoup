/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

/**
 * Registration of an event listener, returned by the {@code onXxx} methods.
 *
 * <p>Closing it stops the delivery to its listener, so it can be used in a try-with-resources block
 * to listen only while the block runs.
 */
public interface Subscription extends AutoCloseable {

    /**
     * Removes the listener. Once this method returns, the listener is not called again, even for an
     * event that is being delivered to other listeners at that moment. A call that is already running
     * on another thread is not interrupted. Closing an already closed subscription does nothing.
     */
    @Override
    void close();
}

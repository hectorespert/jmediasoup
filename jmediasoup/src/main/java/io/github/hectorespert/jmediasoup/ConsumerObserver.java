/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import io.github.hectorespert.jmediasoup.internal.event.RunnableListeners;
import org.slf4j.Logger;

/**
 * The events of a consumer meant for monitoring, as in the {@code observer} of mediasoup-client. Its
 * listeners run on the executor of the transport.
 */
public final class ConsumerObserver {

    private final RunnableListeners closeListeners;

    ConsumerObserver(Logger logger) {
        this.closeListeners = new RunnableListeners("observer close", logger);
    }

    /**
     * Listens for the consumer being closed, by {@link Consumer#close()} or because its transport
     * closed.
     *
     * @param listener called once
     * @return the subscription that removes the listener
     */
    public Subscription onClose(Runnable listener) {
        return closeListeners.add(listener);
    }

    void emitClose() {
        closeListeners.emit();
    }

    void clear() {
        closeListeners.clear();
    }
}

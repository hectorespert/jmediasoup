/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import io.github.hectorespert.jmediasoup.internal.event.RunnableListeners;
import org.slf4j.Logger;

/**
 * The events of a data producer meant for monitoring, as in the {@code observer} of mediasoup-client. Its
 * listeners run on the executor of the transport.
 */
public final class DataProducerObserver {

    private final RunnableListeners closeListeners;

    DataProducerObserver(Logger logger) {
        this.closeListeners = new RunnableListeners("observer close", logger);
    }

    /**
     * Listens for the data producer being closed, by {@link DataProducer#close()}, by its data channel or
     * because its transport closed.
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

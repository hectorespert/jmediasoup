/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import io.github.hectorespert.jmediasoup.internal.event.RunnableListeners;
import org.slf4j.Logger;

/**
 * The events of a producer meant for monitoring, as in the {@code observer} of mediasoup-client. Its
 * listeners run on the executor of the transport.
 */
public final class ProducerObserver {

    private final RunnableListeners closeListeners;
    private final RunnableListeners pauseListeners;
    private final RunnableListeners resumeListeners;

    ProducerObserver(Logger logger) {
        this.closeListeners = new RunnableListeners("observer close", logger);
        this.pauseListeners = new RunnableListeners("observer pause", logger);
        this.resumeListeners = new RunnableListeners("observer resume", logger);
    }

    /**
     * Listens for the producer being closed, by {@link Producer#close()} or because its transport
     * closed.
     *
     * @param listener called once
     * @return the subscription that removes the listener
     */
    public Subscription onClose(Runnable listener) {
        return closeListeners.add(listener);
    }

    /**
     * Listens for the producer being paused by {@link Producer#pause()}.
     *
     * @param listener called on each pause
     * @return the subscription that removes the listener
     */
    public Subscription onPause(Runnable listener) {
        return pauseListeners.add(listener);
    }

    /**
     * Listens for the producer being resumed by {@link Producer#resume()}.
     *
     * @param listener called on each resume
     * @return the subscription that removes the listener
     */
    public Subscription onResume(Runnable listener) {
        return resumeListeners.add(listener);
    }

    void emitClose() {
        closeListeners.emit();
    }

    void emitPause() {
        pauseListeners.emit();
    }

    void emitResume() {
        resumeListeners.emit();
    }

    void clear() {
        closeListeners.clear();
        pauseListeners.clear();
        resumeListeners.clear();
    }
}

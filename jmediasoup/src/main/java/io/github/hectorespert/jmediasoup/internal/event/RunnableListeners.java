/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.event;

import static java.util.Objects.requireNonNull;

import io.github.hectorespert.jmediasoup.Subscription;
import org.slf4j.Logger;

/** The listeners of one event without a value, such as {@code close}. Behaves like {@link Listeners}. */
public final class RunnableListeners {

    private final Listeners<Boolean> listeners;

    /**
     * @param event the event name, used in the log messages
     * @param logger the logger of the object that emits the event
     */
    public RunnableListeners(String event, Logger logger) {
        this.listeners = new Listeners<>(event, logger);
    }

    /**
     * Adds a listener. Adding the same listener twice registers it twice.
     *
     * @return the subscription that removes it
     */
    public Subscription add(Runnable listener) {
        requireNonNull(listener, "listener");
        return listeners.add(ignored -> listener.run());
    }

    /** Calls every listener, in the order they were added. */
    public void emit() {
        listeners.emit(Boolean.TRUE);
    }

    /** Removes every listener, like closing all their subscriptions. */
    public void clear() {
        listeners.clear();
    }
}

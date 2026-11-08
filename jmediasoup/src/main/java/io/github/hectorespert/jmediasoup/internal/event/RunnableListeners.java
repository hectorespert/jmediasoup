/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.event;

import static java.util.Objects.requireNonNull;

import io.github.hectorespert.jmediasoup.Subscription;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
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

    /**
     * Adds a listener of an event that happens at most once, such as the opening of a data channel,
     * which runs once: when the event is emitted or, if it has already happened when it is added, on
     * the executor right away, so that a listener added late does not miss it.
     *
     * @param listener the listener
     * @param happened whether the event has already happened
     * @param executor where the listener runs if the event has already happened
     * @return the subscription that removes it
     */
    public Subscription addOnce(Runnable listener, BooleanSupplier happened, Executor executor) {
        requireNonNull(listener, "listener");
        var ran = new AtomicBoolean();
        // The event may be emitted while checking whether it happened; the listener runs only once.
        Subscription subscription = listeners.add(ignored -> {
            if (ran.compareAndSet(false, true)) {
                listener.run();
            }
        });
        if (happened.getAsBoolean()) {
            executor.execute(() -> listeners.emitTo(subscription, Boolean.TRUE));
        }
        return subscription;
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

/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.event;

import static java.util.Objects.requireNonNull;

import io.github.hectorespert.jmediasoup.Subscription;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;

/**
 * The listeners of one event that carries a value, such as {@code connectionstatechange}.
 *
 * <p>Listeners can be added and removed from any thread, including from a listener while an event is
 * being delivered. A listener added during a delivery starts with the next event, and one removed
 * during a delivery is skipped if it has not been called yet. An exception thrown by a listener is
 * logged and does not stop the delivery to the others.
 *
 * @param <T> the type of the event value
 */
public final class Listeners<T> {

    private final String event;
    private final Logger logger;
    private final List<Registration> registrations = new CopyOnWriteArrayList<>();

    /**
     * @param event the event name, used in the log messages
     * @param logger the logger of the object that emits the event
     */
    public Listeners(String event, Logger logger) {
        this.event = requireNonNull(event, "event");
        this.logger = requireNonNull(logger, "logger");
    }

    /**
     * Adds a listener. Adding the same listener twice registers it twice.
     *
     * @return the subscription that removes it
     */
    public Subscription add(Consumer<? super T> listener) {
        var registration = new Registration(requireNonNull(listener, "listener"));
        registrations.add(registration);
        return registration;
    }

    /** Calls every listener with the value, in the order they were added. */
    public void emit(T value) {
        // The iterator of CopyOnWriteArrayList works on a snapshot, so listeners added meanwhile wait
        // for the next event.
        for (Registration registration : registrations) {
            registration.deliver(value);
        }
    }

    /**
     * Delivers a value to one listener only, if its subscription is still open.
     *
     * @param subscription the subscription that {@link #add} returned
     * @param value the value
     */
    public void emitTo(Subscription subscription, T value) {
        for (Registration registration : registrations) {
            if (registration == subscription) {
                registration.deliver(value);
                return;
            }
        }
    }

    /** Removes every listener, like closing all their subscriptions. */
    public void clear() {
        registrations.forEach(Registration::close);
    }

    private final class Registration implements Subscription {

        private final Consumer<? super T> listener;
        private volatile boolean active = true;

        Registration(Consumer<? super T> listener) {
            this.listener = listener;
        }

        void deliver(T value) {
            if (!active) {
                return;
            }
            try {
                listener.accept(value);
            } catch (Exception e) {
                logger.error("Listener of {} threw an exception", event, e);
            }
        }

        @Override
        public void close() {
            active = false;
            registrations.remove(this);
        }
    }
}

/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import io.github.hectorespert.jmediasoup.internal.event.Listeners;
import io.github.hectorespert.jmediasoup.internal.event.RunnableListeners;
import java.util.function.Consumer;
import org.slf4j.Logger;

/**
 * The events of a transport meant for monitoring, separate from those the application reacts to, as
 * in the {@code observer} of mediasoup-client. Its listeners run on the executor of the transport.
 */
public final class TransportObserver {

    private final RunnableListeners closeListeners;
    private final Listeners<Producer> newProducerListeners;

    TransportObserver(Logger logger) {
        this.closeListeners = new RunnableListeners("observer close", logger);
        this.newProducerListeners = new Listeners<>("observer newproducer", logger);
    }

    /**
     * Listens for the transport being closed.
     *
     * @param listener called once, after {@link Transport#close()}
     * @return the subscription that removes the listener
     */
    public Subscription onClose(Runnable listener) {
        return closeListeners.add(listener);
    }

    /**
     * Listens for new producers.
     *
     * @param listener called with each producer that {@link SendTransport#produce} creates
     * @return the subscription that removes the listener
     */
    public Subscription onNewProducer(Consumer<? super Producer> listener) {
        return newProducerListeners.add(listener);
    }

    void emitClose() {
        closeListeners.emit();
    }

    void emitNewProducer(Producer producer) {
        newProducerListeners.emit(producer);
    }

    void clear() {
        closeListeners.clear();
        newProducerListeners.clear();
    }
}

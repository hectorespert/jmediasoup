/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import io.github.hectorespert.jmediasoup.internal.event.Listeners;
import java.util.function.Consumer;
import org.slf4j.Logger;

/**
 * The events of a device meant for monitoring, as in the {@code observer} of mediasoup-client.
 */
public final class DeviceObserver {

    private final Listeners<Transport> newTransportListeners;

    DeviceObserver(Logger logger) {
        this.newTransportListeners = new Listeners<>("observer newtransport", logger);
    }

    /**
     * Listens for new transports.
     *
     * @param listener called with each transport the device creates, on the executor of that
     *     transport and before any of its other events
     * @return the subscription that removes the listener
     */
    public Subscription onNewTransport(Consumer<? super Transport> listener) {
        return newTransportListeners.add(listener);
    }

    void emitNewTransport(Transport transport) {
        newTransportListeners.emit(transport);
    }
}

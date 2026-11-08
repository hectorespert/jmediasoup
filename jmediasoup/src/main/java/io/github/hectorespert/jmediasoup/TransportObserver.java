/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import io.github.hectorespert.jmediasoup.internal.event.Listeners;
import io.github.hectorespert.jmediasoup.internal.event.RunnableListeners;
import org.slf4j.Logger;

/**
 * The events of a transport meant for monitoring, separate from those the application reacts to, as
 * in the {@code observer} of mediasoup-client. Its listeners run on the executor of the transport.
 */
public final class TransportObserver {

    private final RunnableListeners closeListeners;
    private final Listeners<Producer> newProducerListeners;
    private final Listeners<Consumer> newConsumerListeners;
    private final Listeners<DataProducer> newDataProducerListeners;
    private final Listeners<DataConsumer> newDataConsumerListeners;

    TransportObserver(Logger logger) {
        this.closeListeners = new RunnableListeners("observer close", logger);
        this.newProducerListeners = new Listeners<>("observer newproducer", logger);
        this.newConsumerListeners = new Listeners<>("observer newconsumer", logger);
        this.newDataProducerListeners = new Listeners<>("observer newdataproducer", logger);
        this.newDataConsumerListeners = new Listeners<>("observer newdataconsumer", logger);
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
    public Subscription onNewProducer(java.util.function.Consumer<? super Producer> listener) {
        return newProducerListeners.add(listener);
    }

    /**
     * Listens for new consumers.
     *
     * @param listener called with each consumer that {@link RecvTransport#consume} creates
     * @return the subscription that removes the listener
     */
    public Subscription onNewConsumer(java.util.function.Consumer<? super Consumer> listener) {
        return newConsumerListeners.add(listener);
    }

    /**
     * Listens for new data producers.
     *
     * @param listener called with each data producer that {@link SendTransport#produceData} creates
     * @return the subscription that removes the listener
     */
    public Subscription onNewDataProducer(java.util.function.Consumer<? super DataProducer> listener) {
        return newDataProducerListeners.add(listener);
    }

    /**
     * Listens for new data consumers.
     *
     * @param listener called with each data consumer that {@link RecvTransport#consumeData} creates
     * @return the subscription that removes the listener
     */
    public Subscription onNewDataConsumer(java.util.function.Consumer<? super DataConsumer> listener) {
        return newDataConsumerListeners.add(listener);
    }

    void emitClose() {
        closeListeners.emit();
    }

    void emitNewProducer(Producer producer) {
        newProducerListeners.emit(producer);
    }

    void emitNewConsumer(Consumer consumer) {
        newConsumerListeners.emit(consumer);
    }

    void emitNewDataProducer(DataProducer dataProducer) {
        newDataProducerListeners.emit(dataProducer);
    }

    void emitNewDataConsumer(DataConsumer dataConsumer) {
        newDataConsumerListeners.emit(dataConsumer);
    }

    void clear() {
        closeListeners.clear();
        newProducerListeners.clear();
        newConsumerListeners.clear();
        newDataProducerListeners.clear();
        newDataConsumerListeners.clear();
    }
}

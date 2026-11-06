/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hectorespert.jmediasoup.Subscription;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ListenersTest {

    private final Listeners<String> listeners =
            new Listeners<>("connectionstatechange", LoggerFactory.getLogger(ListenersTest.class));
    private final List<String> calls = new ArrayList<>();

    @Test
    void deliversInTheOrderTheListenersWereAdded() {
        listeners.add(value -> calls.add("1:" + value));
        listeners.add(value -> calls.add("2:" + value));
        listeners.add(value -> calls.add("3:" + value));

        listeners.emit("connected");

        assertEquals(List.of("1:connected", "2:connected", "3:connected"), calls);
    }

    @Test
    void sameListenerAddedTwiceIsCalledTwice() {
        Consumer<String> listener = calls::add;
        listeners.add(listener);
        listeners.add(listener);

        listeners.emit("connected");

        assertEquals(List.of("connected", "connected"), calls);
    }

    @Test
    void closedListenerIsNotCalledAgain() {
        Subscription subscription = listeners.add(calls::add);
        listeners.emit("connecting");

        subscription.close();
        subscription.close();
        listeners.emit("connected");

        assertEquals(List.of("connecting"), calls);
    }

    @Test
    void listenerClosedDuringDeliveryIsSkipped() {
        Subscription[] second = new Subscription[1];
        listeners.add(value -> {
            calls.add("1:" + value);
            second[0].close();
        });
        second[0] = listeners.add(value -> calls.add("2:" + value));

        listeners.emit("closed");

        assertEquals(List.of("1:closed"), calls);
    }

    @Test
    void listenerAddedDuringDeliveryStartsWithTheNextEvent() {
        listeners.add(value -> {
            calls.add("1:" + value);
            if (value.equals("connecting")) {
                listeners.add(next -> calls.add("2:" + next));
            }
        });

        listeners.emit("connecting");
        listeners.emit("connected");

        assertEquals(List.of("1:connecting", "1:connected", "2:connected"), calls);
    }

    @Test
    void failingListenerDoesNotStopTheOthers() {
        listeners.add(value -> {
            throw new IllegalStateException("listener bug");
        });
        listeners.add(calls::add);

        listeners.emit("connected");

        assertEquals(List.of("connected"), calls);
    }

    @Test
    void clearRemovesEveryListener() {
        listeners.add(calls::add);
        listeners.add(calls::add);

        listeners.clear();
        listeners.emit("connected");

        assertTrue(calls.isEmpty());
    }

    @Test
    void subscriptionWorksInTryWithResources() {
        try (Subscription ignored = listeners.add(calls::add)) {
            listeners.emit("connecting");
        }
        listeners.emit("connected");

        assertEquals(List.of("connecting"), calls);
    }

    @Test
    void listenersCanBeAddedAndRemovedWhileOtherThreadsEmit() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        listeners.add(received::add);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int thread = 0; thread < 2; thread++) {
                tasks.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < 1_000; i++) {
                        listeners.emit("event");
                    }
                    return null;
                }));
                tasks.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < 1_000; i++) {
                        listeners.add(value -> {}).close();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
        }

        assertEquals(2_000, received.size());
    }
}

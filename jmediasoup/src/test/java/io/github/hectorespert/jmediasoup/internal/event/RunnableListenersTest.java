/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.hectorespert.jmediasoup.Subscription;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class RunnableListenersTest {

    private final RunnableListeners listeners =
            new RunnableListeners("close", LoggerFactory.getLogger(RunnableListenersTest.class));
    private final List<String> calls = new ArrayList<>();

    @Test
    void deliversToEveryListenerUntilItIsClosed() {
        Subscription first = listeners.add(() -> calls.add("1"));
        listeners.add(() -> {
            throw new IllegalStateException("listener bug");
        });
        listeners.add(() -> calls.add("3"));

        listeners.emit();
        first.close();
        listeners.emit();

        assertEquals(List.of("1", "3", "3"), calls);
    }

    @Test
    void clearRemovesEveryListener() {
        listeners.add(() -> calls.add("1"));

        listeners.clear();
        listeners.emit();

        assertEquals(List.of(), calls);
    }

    @Test
    void aOnceListenerOfAnEventThatHappenedRunsOnTheExecutor() {
        var executor = new ArrayList<Runnable>();

        listeners.addOnce(() -> calls.add("late"), () -> true, executor::add);
        listeners.emit();
        executor.forEach(Runnable::run);

        // It ran when the event was emitted meanwhile, and not again on the executor.
        assertEquals(List.of("late"), calls);
    }

    @Test
    void aOnceListenerOfAnEventThatDidNotHappenWaitsForIt() {
        var executor = new ArrayList<Runnable>();

        listeners.addOnce(() -> calls.add("early"), () -> false, executor::add);
        listeners.emit();
        listeners.emit();

        assertEquals(List.of(), executor);
        assertEquals(List.of("early"), calls);
    }

    @Test
    void aClosedOnceListenerDoesNotRunOnTheExecutor() {
        var executor = new ArrayList<Runnable>();

        listeners.addOnce(() -> calls.add("closed"), () -> true, executor::add).close();
        executor.forEach(Runnable::run);

        assertEquals(List.of(), calls);
    }
}

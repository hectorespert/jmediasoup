/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hectorespert.jmediasoup.InvalidStateException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class OperationQueueTest {

    // Runs the operations as soon as they are due, on the calling thread.
    private final OperationQueue queue = new OperationQueue(Runnable::run);

    private static Throwable failure(CompletableFuture<?> result) {
        return assertThrows(
                        CompletionException.class,
                        () -> result.orTimeout(5, TimeUnit.SECONDS).join())
                .getCause();
    }

    @Test
    void anOperationStartsWhenThePreviousOneCompletes() {
        var first = new CompletableFuture<String>();
        var secondStarted = new AtomicBoolean();

        CompletableFuture<String> firstResult = queue.push("first", () -> first);
        CompletableFuture<String> secondResult = queue.push("second", () -> {
            secondStarted.set(true);
            return CompletableFuture.completedFuture("two");
        });

        assertFalse(secondStarted.get());
        first.complete("one");

        assertEquals("one", firstResult.join());
        assertEquals("two", secondResult.join());
        assertTrue(secondStarted.get());
    }

    @Test
    void aFailedOperationDoesNotBlockTheNextOnes() {
        var error = new IllegalStateException("negotiation failed");

        CompletableFuture<String> failed = queue.push("failed", () -> CompletableFuture.failedFuture(error));
        CompletableFuture<String> next = queue.push("next", () -> CompletableFuture.completedFuture("ok"));

        assertSame(error, failure(failed));
        assertEquals("ok", next.join());
    }

    @Test
    void theCauseOfADependentStageIsUnwrapped() {
        var error = new IllegalStateException("negotiation failed");

        CompletableFuture<String> failed = queue.push(
                "failed", () -> CompletableFuture.<String>failedFuture(error).thenApply(value -> value));

        assertSame(error, failure(failed));
    }

    @Test
    void anOperationThatThrowsOrReturnsNullFails() {
        var error = new IllegalArgumentException("bad options");

        CompletableFuture<String> throwing = queue.push("throwing", () -> {
            throw error;
        });
        CompletableFuture<String> returningNull = queue.push("null", () -> null);
        CompletableFuture<String> next = queue.push("next", () -> CompletableFuture.completedFuture("ok"));

        assertSame(error, failure(throwing));
        assertInstanceOf(NullPointerException.class, failure(returningNull));
        assertEquals("ok", next.join());
    }

    @Test
    void operationsStartOnTheExecutor() {
        List<Runnable> submitted = new ArrayList<>();
        var queue = new OperationQueue(submitted::add);
        var started = new AtomicBoolean();

        CompletableFuture<String> result = queue.push("op", () -> {
            started.set(true);
            return CompletableFuture.completedFuture("ok");
        });

        assertFalse(started.get());
        submitted.removeFirst().run();
        assertEquals("ok", result.join());
    }

    @Test
    void operationsRunOneAtATimeInPushOrderWithASerialExecutor() {
        var queue = new OperationQueue(new SerialExecutor());
        List<Integer> order = new CopyOnWriteArrayList<>();
        var running = new AtomicInteger();
        var maxRunning = new AtomicInteger();

        List<CompletableFuture<Integer>> results = IntStream.range(0, 50)
                .mapToObj(i -> queue.push("op" + i, () -> {
                    maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                    order.add(i);
                    // Completes later, on another thread.
                    return CompletableFuture.supplyAsync(() -> {
                        running.decrementAndGet();
                        return i;
                    });
                }))
                .toList();

        CompletableFuture.allOf(results.toArray(CompletableFuture[]::new))
                .orTimeout(5, TimeUnit.SECONDS)
                .join();
        assertEquals(IntStream.range(0, 50).boxed().toList(), order);
        assertEquals(1, maxRunning.get());
    }

    @Test
    void stopFailsTheRunningAndWaitingOperations() {
        var running = new CompletableFuture<String>();
        var waitingStarted = new AtomicBoolean();

        CompletableFuture<String> runningResult = queue.push("running", () -> running);
        CompletableFuture<String> waitingResult = queue.push("waiting", () -> {
            waitingStarted.set(true);
            return CompletableFuture.completedFuture("never");
        });
        queue.stop();

        assertEquals(
                "closed",
                assertInstanceOf(InvalidStateException.class, failure(runningResult))
                        .getMessage());
        assertInstanceOf(InvalidStateException.class, failure(waitingResult));
        // The running operation completing later changes nothing.
        running.complete("late");
        assertInstanceOf(InvalidStateException.class, failure(runningResult));
        assertFalse(waitingStarted.get());
    }

    @Test
    void operationsPushedAfterStopFailAtOnce() {
        var started = new AtomicBoolean();
        queue.stop();
        queue.stop();

        CompletableFuture<String> result = queue.push("late", () -> {
            started.set(true);
            return CompletableFuture.completedFuture("never");
        });

        assertTrue(result.isCompletedExceptionally());
        assertInstanceOf(InvalidStateException.class, failure(result));
        assertFalse(started.get());
    }

    @Test
    void completedOperationsAreNotFailedByStop() {
        CompletableFuture<String> done = queue.push("done", () -> CompletableFuture.completedFuture("ok"));

        queue.stop();

        assertEquals("ok", done.join());
    }

    @Test
    void nullsAreRejected() {
        assertThrows(NullPointerException.class, () -> new OperationQueue(null));
        assertThrows(NullPointerException.class, () -> queue.push(null, () -> null));
        assertThrows(NullPointerException.class, () -> queue.push("op", null));
    }
}

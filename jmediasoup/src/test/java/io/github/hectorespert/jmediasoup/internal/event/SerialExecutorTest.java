/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SerialExecutorTest {

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "timed out");
    }

    @Test
    void runsTasksInOrderOneAtATime() throws InterruptedException {
        var executor = new SerialExecutor();
        List<Integer> order = new ArrayList<>();
        var running = new AtomicInteger();
        var maxRunning = new AtomicInteger();
        var done = new CountDownLatch(100);

        IntStream.range(0, 100)
                .forEach(i -> executor.execute(() -> {
                    maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                    Thread.yield();
                    // Unsynchronized on purpose: tasks never overlap.
                    order.add(i);
                    running.decrementAndGet();
                    done.countDown();
                }));
        await(done);

        assertEquals(IntStream.range(0, 100).boxed().toList(), order);
        assertEquals(1, maxRunning.get());
    }

    @Test
    void runsOnVirtualThreads() {
        var executor = new SerialExecutor();

        Thread thread = CompletableFuture.supplyAsync(Thread::currentThread, executor)
                .orTimeout(5, TimeUnit.SECONDS)
                .join();

        assertTrue(thread.isVirtual());
        assertTrue(thread.getName().startsWith("jmediasoup-"), thread.getName());
    }

    @Test
    void aTaskThatThrowsDoesNotStopTheNextOnes() throws InterruptedException {
        var executor = new SerialExecutor();
        var done = new CountDownLatch(1);

        executor.execute(() -> {
            throw new IllegalStateException("listener bug");
        });
        executor.execute(done::countDown);

        await(done);
    }

    @Test
    void aBlockedTaskDelaysOnlyItsOwnExecutor() throws InterruptedException {
        var blocked = new SerialExecutor();
        var other = new SerialExecutor();
        var release = new CountDownLatch(1);
        var afterBlocked = new AtomicInteger();
        var otherDone = new CountDownLatch(1);

        blocked.execute(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        blocked.execute(afterBlocked::incrementAndGet);
        other.execute(otherDone::countDown);

        await(otherDone);
        assertEquals(0, afterBlocked.get());
        release.countDown();
    }

    @Test
    void usesTheGivenExecutor() {
        List<Runnable> submitted = new ArrayList<>();
        var executor = new SerialExecutor(submitted::add);
        List<String> ran = new ArrayList<>();

        executor.execute(() -> ran.add("first"));
        executor.execute(() -> ran.add("second"));

        // The second task waits until the first has run.
        assertEquals(1, submitted.size());
        submitted.removeFirst().run();
        assertEquals(List.of("first"), ran);
        assertEquals(1, submitted.size());
        submitted.removeFirst().run();
        assertEquals(List.of("first", "second"), ran);
        assertTrue(submitted.isEmpty());
    }

    @Test
    void nullsAreRejected() {
        assertThrows(NullPointerException.class, () -> new SerialExecutor(null));
        assertThrows(NullPointerException.class, () -> new SerialExecutor().execute(null));
    }
}

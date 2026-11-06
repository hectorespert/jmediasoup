/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.event;

import static java.util.Objects.requireNonNull;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs tasks one at a time, in the order they were submitted, on top of another executor. Each
 * transport has one, where the user's listeners and handlers run, so that they never run on a
 * native thread of the WebRTC stack nor at the same time.
 *
 * <p>It follows the {@code SerialExecutor} of the {@link Executor} Javadoc, with a lock instead of
 * {@code synchronized} so that a virtual thread is never pinned. A task that throws is logged and
 * does not stop the next ones.
 */
public final class SerialExecutor implements Executor {

    private static final Logger logger = LoggerFactory.getLogger(SerialExecutor.class);

    // One virtual thread per task, shared by every serial executor. Virtual threads are daemon threads,
    // so nothing has to be shut down.
    private static final Executor VIRTUAL_THREADS = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("jmediasoup-", 0).factory());

    private final Executor backing;
    private final ReentrantLock lock = new ReentrantLock();
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    // The task running or about to run on the backing executor, if any.
    private @Nullable Runnable active;

    /** Creates a serial executor that runs each task on a new virtual thread. */
    public SerialExecutor() {
        this(VIRTUAL_THREADS);
    }

    /**
     * Creates a serial executor on top of another executor.
     *
     * @param backing the executor that runs the tasks
     */
    public SerialExecutor(Executor backing) {
        this.backing = requireNonNull(backing, "backing");
    }

    @Override
    public void execute(Runnable task) {
        requireNonNull(task, "task");
        lock.lock();
        try {
            tasks.add(() -> {
                try {
                    task.run();
                } catch (RuntimeException | Error e) {
                    logger.error("Task threw an exception", e);
                } finally {
                    scheduleNext();
                }
            });
            if (active == null) {
                scheduleNext();
            }
        } finally {
            lock.unlock();
        }
    }

    private void scheduleNext() {
        lock.lock();
        try {
            active = tasks.poll();
            if (active != null) {
                backing.execute(active);
            }
        } finally {
            lock.unlock();
        }
    }
}

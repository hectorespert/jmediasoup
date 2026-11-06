/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.event;

import static java.util.Objects.requireNonNull;

import io.github.hectorespert.jmediasoup.InvalidStateException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs asynchronous operations one at a time, like the {@code AwaitQueue} of mediasoup-client. An
 * operation starts when the previous one has completed, whether it succeeded or failed, so that the
 * operations of a transport never negotiate with the WebRTC stack at the same time.
 *
 * <p>Each operation starts on the given executor, the {@link SerialExecutor} of the transport. An
 * operation must not wait for another one pushed after it, which would never start.
 */
public final class OperationQueue {

    private static final Logger logger = LoggerFactory.getLogger(OperationQueue.class);

    private final Executor executor;
    private final ReentrantLock lock = new ReentrantLock();
    // The results of the operations that have not completed, running or waiting, in order.
    private final Set<CompletableFuture<?>> pending = new LinkedHashSet<>();
    // The result of the last operation pushed; the next one starts when it completes.
    private CompletableFuture<?> last = CompletableFuture.completedFuture(null);
    private boolean stopped;

    /**
     * @param executor the executor where each operation starts
     */
    public OperationQueue(Executor executor) {
        this.executor = requireNonNull(executor, "executor");
    }

    /**
     * Adds an operation, which starts when the previous ones have completed.
     *
     * @param name the operation name, used in the log messages
     * @param operation starts the operation and returns its stage; if it throws or returns {@code
     *     null}, the operation fails
     * @return a future with the result of the operation; it fails with an {@link
     *     InvalidStateException} if the queue is stopped before the operation completes
     */
    public <T> CompletableFuture<T> push(String name, Supplier<? extends @Nullable CompletionStage<T>> operation) {
        requireNonNull(name, "name");
        requireNonNull(operation, "operation");
        var result = new CompletableFuture<T>();
        CompletableFuture<?> previous;
        lock.lock();
        try {
            if (stopped) {
                result.completeExceptionally(new InvalidStateException("closed"));
                return result;
            }
            pending.add(result);
            previous = last;
            last = result;
        } finally {
            lock.unlock();
        }
        result.whenComplete((value, error) -> remove(result));
        previous.whenCompleteAsync((value, error) -> start(name, operation, result), executor);
        return result;
    }

    /**
     * Stops the queue: the operations that have not completed fail with an {@link
     * InvalidStateException}, those waiting never start, and those pushed later fail at once. The
     * running operation is not interrupted, but its result is ignored. Calling it again does nothing.
     */
    public void stop() {
        List<CompletableFuture<?>> stopping;
        lock.lock();
        try {
            if (stopped) {
                return;
            }
            stopped = true;
            stopping = List.copyOf(pending);
        } finally {
            lock.unlock();
        }
        logger.debug("stop() | stopping {} operations", stopping.size());
        for (CompletableFuture<?> result : stopping) {
            result.completeExceptionally(new InvalidStateException("closed"));
        }
    }

    private <T> void start(
            String name, Supplier<? extends @Nullable CompletionStage<T>> operation, CompletableFuture<T> result) {
        if (isStopped()) {
            // Stop fails it, if it has not done so yet.
            return;
        }
        logger.debug("start() | running operation [name:{}]", name);
        CompletionStage<T> stage;
        try {
            stage = operation.get();
        } catch (RuntimeException e) {
            result.completeExceptionally(e);
            return;
        }
        if (stage == null) {
            result.completeExceptionally(new NullPointerException(name + " operation returned null"));
            return;
        }
        stage.whenComplete((value, error) -> {
            if (error != null) {
                // A stage that depends on a failed one fails with a CompletionException around the cause.
                result.completeExceptionally(
                        error instanceof CompletionException && error.getCause() != null ? error.getCause() : error);
            } else {
                result.complete(value);
            }
        });
    }

    private boolean isStopped() {
        lock.lock();
        try {
            return stopped;
        } finally {
            lock.unlock();
        }
    }

    private void remove(CompletableFuture<?> result) {
        lock.lock();
        try {
            pending.remove(result);
        } finally {
            lock.unlock();
        }
    }
}

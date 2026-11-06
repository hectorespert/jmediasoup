/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.event;

import io.github.hectorespert.jmediasoup.MediasoupClientException;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import org.jspecify.annotations.Nullable;

/**
 * Calls the handlers that the user passes when creating a transport, such as {@code connect} and
 * {@code produce}, which return a {@link CompletionStage}.
 *
 * <p>Every way a handler can fail ends in a {@link MediasoupClientException}: throwing, returning
 * {@code null} instead of a stage, or a stage that fails. The original failure is kept as the cause.
 * There is no timeout; the user applies one in the signalling if needed. The returned future
 * completes on the thread that completes the handler's stage.
 */
public final class AsyncHandlers {

    private AsyncHandlers() {}

    /**
     * Calls a handler whose stage has no value, such as {@code connect}.
     *
     * @param name the handler name, used in the error messages
     */
    public static CompletableFuture<@Nullable Void> run(
            String name, Callable<? extends @Nullable CompletionStage<?>> handler) {
        var result = new CompletableFuture<@Nullable Void>();
        CompletionStage<?> stage = start(name, handler, result);
        if (stage != null) {
            stage.whenComplete((value, error) -> {
                if (error != null) {
                    result.completeExceptionally(failed(name, error));
                } else {
                    result.complete(null);
                }
            });
        }
        return result;
    }

    /**
     * Calls a handler whose stage must complete with a value, such as {@code produce}. A {@code null}
     * value fails too.
     *
     * @param name the handler name, used in the error messages
     */
    public static <T> CompletableFuture<T> call(
            String name, Callable<? extends @Nullable CompletionStage<? extends @Nullable T>> handler) {
        var result = new CompletableFuture<T>();
        CompletionStage<? extends @Nullable T> stage = start(name, handler, result);
        if (stage != null) {
            stage.whenComplete((value, error) -> {
                if (error != null) {
                    result.completeExceptionally(failed(name, error));
                } else if (value == null) {
                    result.completeExceptionally(
                            new MediasoupClientException(name + " handler completed without a value"));
                } else {
                    result.complete(value);
                }
            });
        }
        return result;
    }

    /** Calls the handler, or fails the result and returns null if it throws or returns no stage. */
    private static <S extends CompletionStage<?>> @Nullable S start(
            String name, Callable<? extends @Nullable S> handler, CompletableFuture<?> result) {
        S stage;
        try {
            stage = handler.call();
        } catch (Exception e) {
            result.completeExceptionally(failed(name, e));
            return null;
        }
        if (stage == null) {
            result.completeExceptionally(
                    new MediasoupClientException(name + " handler returned null instead of a CompletionStage"));
        }
        return stage;
    }

    private static MediasoupClientException failed(String name, Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return new MediasoupClientException(name + " handler failed", cause);
    }
}

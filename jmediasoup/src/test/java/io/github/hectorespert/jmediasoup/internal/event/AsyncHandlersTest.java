/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.internal.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.hectorespert.jmediasoup.MediasoupClientException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class AsyncHandlersTest {

    @Test
    void callCompletesWithTheHandlerValue() {
        var pending = new CompletableFuture<String>();
        CompletableFuture<String> result = AsyncHandlers.call("produce", () -> pending);

        assertFalse(result.isDone());
        pending.complete("producer-id");

        assertEquals("producer-id", result.join());
    }

    @Test
    void runCompletesWhenTheHandlerStageCompletes() {
        var pending = new CompletableFuture<Void>();
        CompletableFuture<Void> result = AsyncHandlers.run("connect", () -> pending);

        assertFalse(result.isDone());
        pending.complete(null);

        assertNull(result.join());
    }

    @Test
    void handlerThatThrowsFails() {
        var error = new IllegalStateException("signalling down");

        MediasoupClientException failure = failure(AsyncHandlers.call("produce", () -> {
            throw error;
        }));

        assertEquals("produce handler failed", failure.getMessage());
        assertSame(error, failure.getCause());
    }

    @Test
    void handlerThatReturnsNullFails() {
        MediasoupClientException failure = failure(AsyncHandlers.run("connect", () -> null));

        assertEquals("connect handler returned null instead of a CompletionStage", failure.getMessage());
    }

    @Test
    void failedStageFailsWithTheOriginalCause() {
        var error = new IllegalStateException("timeout");
        CompletionStage<String> stage =
                CompletableFuture.<String>failedFuture(error).thenApply(id -> id);

        MediasoupClientException failure = failure(AsyncHandlers.call("produce", () -> stage));

        assertEquals("produce handler failed", failure.getMessage());
        assertSame(error, failure.getCause());
    }

    @Test
    void callFailsIfTheStageCompletesWithoutAValue() {
        MediasoupClientException failure =
                failure(AsyncHandlers.call("produce", () -> CompletableFuture.completedFuture(null)));

        assertEquals("produce handler completed without a value", failure.getMessage());
    }

    private static MediasoupClientException failure(CompletableFuture<?> result) {
        CompletionException thrown = assertThrows(CompletionException.class, result::join);
        return assertInstanceOf(MediasoupClientException.class, thrown.getCause());
    }
}

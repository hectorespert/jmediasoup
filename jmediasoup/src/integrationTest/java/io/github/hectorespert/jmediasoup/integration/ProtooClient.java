/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A client of <a href="https://protoo.versatica.com">protoo</a>, the signaling of the mediasoup demo: JSON
 * requests, responses and notifications over a WebSocket with the {@code protoo} subprotocol, in both
 * directions.
 */
final class ProtooClient implements AutoCloseable {

    /** How long a request waits for its response. */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** A request that the other side answered with an error. */
    static final class RequestFailedException extends RuntimeException {

        RequestFailedException(String method, int errorCode, String errorReason) {
            super(method + " failed with " + errorCode + ": " + errorReason);
        }
    }

    private final HttpClient http;
    private final Map<Long, PendingRequest> pending = new ConcurrentHashMap<>();
    private final Map<String, Function<JsonNode, ? extends CompletionStage<?>>> requestHandlers =
            new ConcurrentHashMap<>();
    private final Map<String, Consumer<JsonNode>> notificationListeners = new ConcurrentHashMap<>();
    private final ReentrantLock sendLock = new ReentrantLock();
    private CompletableFuture<?> lastSend = CompletableFuture.completedFuture(null);
    private WebSocket webSocket;

    private record PendingRequest(String method, CompletableFuture<JsonNode> response) {}

    private ProtooClient(HttpClient http) {
        this.http = http;
    }

    /**
     * Connects to a protoo server.
     *
     * @param url the WebSocket URL
     * @param origin the {@code Origin} header, which the mediasoup demo checks
     * @return the connected client
     */
    static ProtooClient connect(URI url, String origin) {
        HttpClient http = HttpClient.newHttpClient();
        var client = new ProtooClient(http);
        try {
            client.webSocket = http.newWebSocketBuilder()
                    .header("Origin", origin)
                    .subprotocols("protoo")
                    .connectTimeout(REQUEST_TIMEOUT)
                    .buildAsync(url, client.new Listener())
                    .join();
        } catch (RuntimeException e) {
            http.close();
            throw e;
        }
        return client;
    }

    /**
     * Sends a request.
     *
     * @param method the method
     * @param data the data, converted to JSON, or {@code null} for none
     * @return the data of the response, failed with {@link RequestFailedException} if the other side
     *     rejects it
     */
    CompletableFuture<JsonNode> request(String method, Object data) {
        var response = new CompletableFuture<JsonNode>();
        long id;
        do {
            id = ThreadLocalRandom.current().nextLong(1, 10_000_000);
        } while (pending.putIfAbsent(id, new PendingRequest(method, response)) != null);
        long requestId = id;

        ObjectNode message = MAPPER.createObjectNode();
        message.put("request", true);
        message.put("id", requestId);
        message.put("method", method);
        message.set("data", toData(data));
        send(message).exceptionally(error -> {
            response.completeExceptionally(error);
            return null;
        });
        return response.orTimeout(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((result, error) -> pending.remove(requestId));
    }

    /**
     * Sends a notification.
     *
     * @param method the method
     * @param data the data, converted to JSON, or {@code null} for none
     */
    void notify(String method, Object data) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("notification", true);
        message.put("method", method);
        message.set("data", toData(data));
        send(message);
    }

    /**
     * Answers the requests of the other side with this method. The response is sent when the returned
     * stage completes: with its value as data, or with an error if it fails. Requests without a handler
     * are rejected.
     *
     * @param method the method
     * @param handler gets the data of the request
     */
    void onRequest(String method, Function<JsonNode, ? extends CompletionStage<?>> handler) {
        requestHandlers.put(method, handler);
    }

    /**
     * Listens to the notifications of the other side with this method. The others are ignored.
     *
     * @param method the method
     * @param listener gets the data of the notification
     */
    void onNotification(String method, Consumer<JsonNode> listener) {
        notificationListeners.put(method, listener);
    }

    /** Closes the WebSocket and fails the pending requests. */
    @Override
    public void close() {
        try {
            webSocket
                    .sendClose(WebSocket.NORMAL_CLOSURE, "")
                    .orTimeout(1, TimeUnit.SECONDS)
                    .exceptionally(error -> null)
                    .join();
        } finally {
            webSocket.abort();
            failPending(new IllegalStateException("protoo client closed"));
            http.close();
        }
    }

    private static JsonNode toData(Object data) {
        return data == null ? MAPPER.createObjectNode() : MAPPER.valueToTree(data);
    }

    private CompletableFuture<?> send(JsonNode message) {
        String text = MAPPER.writeValueAsString(message);
        sendLock.lock();
        try {
            // A WebSocket sends one message at a time.
            lastSend = lastSend.exceptionally(error -> null).thenCompose(ignored -> webSocket.sendText(text, true));
            return lastSend;
        } finally {
            sendLock.unlock();
        }
    }

    private void failPending(Throwable error) {
        pending.values().forEach(request -> request.response().completeExceptionally(error));
    }

    private void receive(String text) {
        JsonNode message = MAPPER.readTree(text);
        if (message.path("response").asBoolean()) {
            PendingRequest request = pending.get(message.path("id").asLong());
            if (request == null) {
                return;
            }
            if (message.path("ok").asBoolean()) {
                request.response().complete(message.path("data"));
            } else {
                request.response()
                        .completeExceptionally(new RequestFailedException(
                                request.method(),
                                message.path("errorCode").asInt(),
                                message.path("errorReason").asString()));
            }
        } else if (message.path("request").asBoolean()) {
            answer(message);
        } else if (message.path("notification").asBoolean()) {
            Consumer<JsonNode> listener =
                    notificationListeners.get(message.path("method").asString());
            if (listener != null) {
                listener.accept(message.path("data"));
            }
        }
    }

    private void answer(JsonNode request) {
        String method = request.path("method").asString();
        Function<JsonNode, ? extends CompletionStage<?>> handler = requestHandlers.get(method);
        CompletionStage<?> result = handler == null
                ? CompletableFuture.failedFuture(new UnsupportedOperationException("unknown method " + method))
                : handler.apply(request.path("data"));
        result.whenComplete((data, error) -> {
            ObjectNode response = MAPPER.createObjectNode();
            response.put("response", true);
            response.put("id", request.path("id").asLong());
            if (error == null) {
                response.put("ok", true);
                response.set("data", toData(data));
            } else {
                response.put("ok", false);
                response.put("errorCode", 500);
                response.put("errorReason", String.valueOf(error.getMessage()));
            }
            send(response);
        });
    }

    private final class Listener implements WebSocket.Listener {

        private final StringBuilder text = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                String message = text.toString();
                text.setLength(0);
                receive(message);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            failPending(new IllegalStateException("WebSocket closed: " + statusCode + " " + reason));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            failPending(error);
        }
    }
}

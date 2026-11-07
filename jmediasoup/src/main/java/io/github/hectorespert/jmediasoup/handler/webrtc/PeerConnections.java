/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.handler.webrtc;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.RTCRtpSender;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.RTCStatsCollectorCallback;
import dev.onvoid.webrtc.RTCStatsReport;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import io.github.hectorespert.jmediasoup.MediasoupClientException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The asynchronous operations of a peer connection as futures.
 *
 * <p>webrtc-java calls the observers on the signalling thread of the factory, and blocking there stops
 * the whole factory. So the futures complete on a virtual thread, and the code that follows them never
 * runs on that thread.
 */
final class PeerConnections {

    // Where the futures complete, and the work that must not run on the caller starts.
    static final Executor EXECUTOR = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("jmediasoup-webrtc-", 0).factory());

    private PeerConnections() {}

    static CompletableFuture<RTCSessionDescription> createOffer(RTCPeerConnection pc, RTCOfferOptions options) {
        var result = new CompletableFuture<RTCSessionDescription>();
        pc.createOffer(options, descriptionObserver("createOffer", result));
        return result;
    }

    static CompletableFuture<RTCSessionDescription> createAnswer(RTCPeerConnection pc) {
        var result = new CompletableFuture<RTCSessionDescription>();
        pc.createAnswer(new RTCAnswerOptions(), descriptionObserver("createAnswer", result));
        return result;
    }

    static CompletableFuture<Void> setLocalDescription(RTCPeerConnection pc, RTCSessionDescription description) {
        var result = new CompletableFuture<Void>();
        pc.setLocalDescription(description, setObserver("setLocalDescription", result));
        return result;
    }

    static CompletableFuture<Void> setRemoteDescription(RTCPeerConnection pc, RTCSessionDescription description) {
        var result = new CompletableFuture<Void>();
        pc.setRemoteDescription(description, setObserver("setRemoteDescription", result));
        return result;
    }

    static CompletableFuture<RTCStatsReport> getStats(RTCPeerConnection pc) {
        var result = new CompletableFuture<RTCStatsReport>();
        pc.getStats(statsCallback(result));
        return result;
    }

    static CompletableFuture<RTCStatsReport> getStats(RTCPeerConnection pc, RTCRtpSender sender) {
        var result = new CompletableFuture<RTCStatsReport>();
        pc.getStats(sender, statsCallback(result));
        return result;
    }

    static CompletableFuture<RTCStatsReport> getStats(RTCPeerConnection pc, RTCRtpReceiver receiver) {
        var result = new CompletableFuture<RTCStatsReport>();
        pc.getStats(receiver, statsCallback(result));
        return result;
    }

    private static RTCStatsCollectorCallback statsCallback(CompletableFuture<RTCStatsReport> result) {
        return report -> EXECUTOR.execute(() -> result.complete(report));
    }

    private static CreateSessionDescriptionObserver descriptionObserver(
            String operation, CompletableFuture<RTCSessionDescription> result) {
        return new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription description) {
                EXECUTOR.execute(() -> result.complete(description));
            }

            @Override
            public void onFailure(String error) {
                EXECUTOR.execute(() -> result.completeExceptionally(failure(operation, error)));
            }
        };
    }

    private static SetSessionDescriptionObserver setObserver(String operation, CompletableFuture<Void> result) {
        return new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                EXECUTOR.execute(() -> result.complete(null));
            }

            @Override
            public void onFailure(String error) {
                EXECUTOR.execute(() -> result.completeExceptionally(failure(operation, error)));
            }
        };
    }

    private static MediasoupClientException failure(String operation, String error) {
        return new MediasoupClientException(operation + "() failed: " + error);
    }
}

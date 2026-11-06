/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.handler;

import dev.onvoid.webrtc.RTCRtpEncodingParameters;
import dev.onvoid.webrtc.RTCStatsReport;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import io.github.hectorespert.jmediasoup.transport.IceParameters;
import io.github.hectorespert.jmediasoup.transport.IceServer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * The WebRTC side of a transport: it sends and receives media and data through the stack, and builds
 * the SDP that the stack negotiates with mediasoup. A {@link HandlerFactory} creates it, and its
 * transport calls it one operation at a time.
 *
 * <p>The asynchronous methods report their failures through the returned future, with a {@link
 * io.github.hectorespert.jmediasoup.MediasoupClientException} as the cause, which is an {@link
 * io.github.hectorespert.jmediasoup.InvalidStateException} if the handler is closed.
 */
public interface Handler {

    /**
     * Returns the name of the handler.
     *
     * @return the name of its factory
     */
    String name();

    /** Closes the handler and releases its resources. Calling it again does nothing. */
    void close();

    /**
     * Replaces the STUN and TURN servers.
     *
     * @param iceServers the new servers
     * @return a future that completes when they are set
     */
    CompletableFuture<Void> updateIceServers(List<IceServer> iceServers);

    /**
     * Restarts ICE with new remote parameters.
     *
     * @param iceParameters the new ICE parameters of the mediasoup transport
     * @return a future that completes when the restart has been negotiated
     */
    CompletableFuture<Void> restartIce(IceParameters iceParameters);

    /**
     * Returns the statistics of the transport.
     *
     * @return a future with the report of the WebRTC stack
     */
    CompletableFuture<RTCStatsReport> getTransportStats();

    /**
     * Starts sending a track.
     *
     * @param options the track and how to send it
     * @return a future with the local id and the RTP parameters to give mediasoup
     */
    CompletableFuture<HandlerSendResult> send(HandlerSendOptions options);

    /**
     * Stops sending a track.
     *
     * @param localId the local id of the sending
     * @return a future that completes when it has stopped; it does nothing if the handler is closed
     */
    CompletableFuture<Void> stopSending(String localId);

    /**
     * Pauses a sending, so that the stack stops sending RTP for it.
     *
     * @param localId the local id of the sending
     * @return a future that completes when it is paused
     */
    CompletableFuture<Void> pauseSending(String localId);

    /**
     * Resumes a paused sending.
     *
     * @param localId the local id of the sending
     * @return a future that completes when it is resumed
     */
    CompletableFuture<Void> resumeSending(String localId);

    /**
     * Replaces the track of a sending without renegotiating.
     *
     * @param localId the local id of the sending
     * @param track the new track, or {@code null} to send nothing
     * @return a future that completes when the track is replaced
     */
    CompletableFuture<Void> replaceTrack(String localId, @Nullable MediaStreamTrack track);

    /**
     * Limits the spatial layers that a simulcast or SVC sending uses.
     *
     * @param localId the local id of the sending
     * @param spatialLayer the highest spatial layer to send, from 0
     * @return a future that completes when the limit is set
     */
    CompletableFuture<Void> setMaxSpatialLayer(String localId, int spatialLayer);

    /**
     * Changes the encoding parameters of a sending, such as the maximum bitrate.
     *
     * @param localId the local id of the sending
     * @param params the parameters to change; the fields that are {@code null} are left as they are
     * @return a future that completes when they are set
     */
    CompletableFuture<Void> setRtpEncodingParameters(String localId, RTCRtpEncodingParameters params);

    /**
     * Returns the statistics of a sending.
     *
     * @param localId the local id of the sending
     * @return a future with the report of the WebRTC stack
     */
    CompletableFuture<RTCStatsReport> getSenderStats(String localId);

    /**
     * Opens a data channel for sending.
     *
     * @param options the reliability, label and protocol of the channel
     * @return a future with the channel and the parameters to give mediasoup
     */
    CompletableFuture<HandlerSendDataChannelResult> sendDataChannel(HandlerSendDataChannelOptions options);

    /**
     * Starts receiving tracks, all in a single negotiation.
     *
     * @param optionsList what to receive
     * @return a future with the local id and track of each one, in the same order
     */
    CompletableFuture<List<HandlerReceiveResult>> receive(List<HandlerReceiveOptions> optionsList);

    /**
     * Stops receiving tracks.
     *
     * @param localIds the local ids of the receivings
     * @return a future that completes when they have stopped; it does nothing if the handler is closed
     */
    CompletableFuture<Void> stopReceiving(List<String> localIds);

    /**
     * Pauses receivings.
     *
     * @param localIds the local ids of the receivings
     * @return a future that completes when they are paused
     */
    CompletableFuture<Void> pauseReceiving(List<String> localIds);

    /**
     * Resumes paused receivings.
     *
     * @param localIds the local ids of the receivings
     * @return a future that completes when they are resumed
     */
    CompletableFuture<Void> resumeReceiving(List<String> localIds);

    /**
     * Returns the statistics of a receiving.
     *
     * @param localId the local id of the receiving
     * @return a future with the report of the WebRTC stack
     */
    CompletableFuture<RTCStatsReport> getReceiverStats(String localId);

    /**
     * Opens a data channel for receiving.
     *
     * @param options the stream, reliability, label and protocol that mediasoup uses
     * @return a future with the channel
     */
    CompletableFuture<HandlerReceiveDataChannelResult> receiveDataChannel(HandlerReceiveDataChannelOptions options);

    /**
     * Returns the maximum size of the messages that the data channels can send.
     *
     * @return the size in bytes, or {@code null} if it is not known yet
     */
    @Nullable
    Integer getDataChannelMaxMessageSize();
}

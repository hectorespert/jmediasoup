/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup;

import static java.util.Objects.requireNonNull;

import io.github.hectorespert.jmediasoup.handler.HandlerFactory;
import io.github.hectorespert.jmediasoup.internal.ortc.Ortc;
import io.github.hectorespert.jmediasoup.rtp.ExtendedRtpCapabilities;
import io.github.hectorespert.jmediasoup.rtp.MediaKind;
import io.github.hectorespert.jmediasoup.rtp.RtpCapabilities;
import io.github.hectorespert.jmediasoup.sctp.SctpCapabilities;
import io.github.hectorespert.jmediasoup.transport.TransportDirection;
import io.github.hectorespert.jmediasoup.transport.TransportOptions;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The entry point of jmediasoup: an endpoint that connects to a mediasoup router to send and receive
 * media.
 *
 * <p>A device is created with the {@link HandlerFactory} of a WebRTC stack and loaded with the RTP
 * capabilities of the router. Once loaded, it tells what it can send and receive, and creates the
 * transports.
 *
 * <p>It is thread-safe.
 */
public final class Device {

    private static final Logger logger = LoggerFactory.getLogger(Device.class);

    // RTC handler factory.
    private final HandlerFactory handlerFactory;
    // Handler name.
    private final String handlerName;
    // Whether a load is in progress or done.
    private final AtomicBoolean loading = new AtomicBoolean();
    // What the device computed when it loaded, or null if it is not loaded.
    private volatile @Nullable Loaded loaded;
    // Observer instance.
    private final DeviceObserver observer = new DeviceObserver(logger);

    // The capabilities of a loaded device.
    private record Loaded(
            RtpCapabilities recvRtpCapabilities,
            RtpCapabilities sendRtpCapabilities,
            Function<RtpCapabilities, ExtendedRtpCapabilities> getSendExtendedRtpCapabilities,
            Map<MediaKind, Boolean> canProduceByKind,
            SctpCapabilities sctpCapabilities) {}

    /**
     * Creates a device that uses a WebRTC stack.
     *
     * @param handlerFactory the factory of the handlers of the stack
     */
    public Device(HandlerFactory handlerFactory) {
        this.handlerFactory = requireNonNull(handlerFactory, "handlerFactory");
        this.handlerName = requireNonNull(handlerFactory.name(), "handlerFactory.name()");
        logger.debug("constructor() [handlerName:{}]", handlerName);
    }

    /**
     * Returns the name of the handler.
     *
     * @return the name of the handler factory
     */
    public String handlerName() {
        return handlerName;
    }

    /**
     * Returns whether the device is loaded.
     *
     * @return whether {@link #load} has completed
     */
    public boolean loaded() {
        return loaded != null;
    }

    /**
     * Returns the RTP capabilities for receiving media, which mediasoup needs to create consumers for
     * this device. They are the same as {@link #recvRtpCapabilities()}.
     *
     * @return the receiving capabilities
     * @throws InvalidStateException if the device is not loaded
     */
    public RtpCapabilities rtpCapabilities() throws InvalidStateException {
        return recvRtpCapabilities();
    }

    /**
     * Returns the RTP capabilities for receiving media.
     *
     * @return the codecs and header extensions that both the device and the router support for
     *     receiving, with the payload types of the router
     * @throws InvalidStateException if the device is not loaded
     */
    public RtpCapabilities recvRtpCapabilities() throws InvalidStateException {
        return assertLoaded().recvRtpCapabilities();
    }

    /**
     * Returns the RTP capabilities for sending media.
     *
     * @return the codecs and header extensions that both the device and the router support for
     *     sending, with the local payload types
     * @throws InvalidStateException if the device is not loaded
     */
    public RtpCapabilities sendRtpCapabilities() throws InvalidStateException {
        return assertLoaded().sendRtpCapabilities();
    }

    /**
     * Returns the SCTP capabilities, which mediasoup needs to create transports with data channels.
     *
     * @return the SCTP capabilities of the WebRTC stack
     * @throws InvalidStateException if the device is not loaded
     */
    public SctpCapabilities sctpCapabilities() throws InvalidStateException {
        return assertLoaded().sctpCapabilities();
    }

    /**
     * Returns whether the device can send media of a kind.
     *
     * @param kind the media kind
     * @return whether the device and the router share a codec of that kind for sending
     * @throws InvalidStateException if the device is not loaded
     */
    public boolean canProduce(MediaKind kind) throws InvalidStateException {
        requireNonNull(kind, "kind");
        return assertLoaded().canProduceByKind().get(kind);
    }

    /**
     * Returns the observer, whose events are meant for monitoring.
     *
     * @return the observer of this device
     */
    public DeviceObserver observer() {
        return observer;
    }

    /**
     * Loads the device with the RTP capabilities of the router, keeping the codec order of the
     * router.
     *
     * @param routerRtpCapabilities the RTP capabilities of the mediasoup router
     * @return a future that completes when the device is loaded; it fails with an {@link
     *     InvalidStateException} if the device is already loaded or loading
     * @see #load(RtpCapabilities, boolean)
     */
    public CompletableFuture<Void> load(RtpCapabilities routerRtpCapabilities) {
        return load(routerRtpCapabilities, false);
    }

    /**
     * Loads the device with the RTP capabilities of the router.
     *
     * <p>It asks the WebRTC stack for its native capabilities in each direction and computes what
     * the device and the router share. If it fails, the device stays unloaded and can be loaded
     * again.
     *
     * @param routerRtpCapabilities the RTP capabilities of the mediasoup router
     * @param preferLocalCodecsOrder whether the sending codecs keep the order of the WebRTC stack
     *     instead of that of the router; receiving always keeps that of the router
     * @return a future that completes when the device is loaded; it fails with an {@link
     *     InvalidStateException} if the device is already loaded or loading, with an {@link
     *     IllegalArgumentException} if the capabilities cannot be matched, or with the failure of the
     *     WebRTC stack
     */
    public CompletableFuture<Void> load(RtpCapabilities routerRtpCapabilities, boolean preferLocalCodecsOrder) {
        requireNonNull(routerRtpCapabilities, "routerRtpCapabilities");
        logger.debug("load() [routerRtpCapabilities:{}]", routerRtpCapabilities);

        if (!loading.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new InvalidStateException("already loaded"));
        }

        CompletableFuture<Void> result;
        try {
            CompletableFuture<RtpCapabilities> nativeRecv =
                    handlerFactory.getNativeRtpCapabilities(TransportDirection.RECV);
            result = nativeRecv.thenCompose(nativeRecvRtpCapabilities -> {
                logger.debug("load() | got native receiving RTP capabilities:{}", nativeRecvRtpCapabilities);
                return handlerFactory
                        .getNativeRtpCapabilities(TransportDirection.SEND)
                        .thenCompose(nativeSendRtpCapabilities -> {
                            logger.debug("load() | got native sending RTP capabilities:{}", nativeSendRtpCapabilities);
                            return handlerFactory
                                    .getNativeSctpCapabilities()
                                    .thenAccept(sctpCapabilities -> loaded = compute(
                                            routerRtpCapabilities,
                                            preferLocalCodecsOrder,
                                            nativeRecvRtpCapabilities,
                                            nativeSendRtpCapabilities,
                                            sctpCapabilities));
                        });
            });
        } catch (RuntimeException e) {
            result = CompletableFuture.failedFuture(e);
        }

        return result.whenComplete((ignored, error) -> {
            if (error != null) {
                logger.debug("load() failed", error);
                loading.set(false);
            } else {
                logger.debug("load() succeeded");
            }
        });
    }

    /**
     * Creates a transport for sending media and data.
     *
     * @param options the parameters of the mediasoup transport, as the server created it, and the
     *     local settings
     * @param connect connects the mediasoup transport, before the first produce
     * @param produce creates each producer on the server
     * @return the transport
     * @throws InvalidStateException if the device is not loaded
     */
    public SendTransport createSendTransport(TransportOptions options, ConnectHandler connect, ProduceHandler produce)
            throws InvalidStateException {
        logger.debug("createSendTransport()");
        requireNonNull(options, "options");
        requireNonNull(connect, "connect");
        requireNonNull(produce, "produce");
        Loaded current = assertLoaded();
        return emitNewTransport(
                new SendTransport(options, handlerFactory, current.getSendExtendedRtpCapabilities(), connect, produce));
    }

    /**
     * Creates a transport for receiving media and data.
     *
     * @param options the parameters of the mediasoup transport, as the server created it, and the
     *     local settings
     * @param connect connects the mediasoup transport, before the first consume
     * @return the transport
     * @throws InvalidStateException if the device is not loaded
     */
    public RecvTransport createRecvTransport(TransportOptions options, ConnectHandler connect)
            throws InvalidStateException {
        logger.debug("createRecvTransport()");
        requireNonNull(options, "options");
        requireNonNull(connect, "connect");
        Loaded current = assertLoaded();
        return emitNewTransport(
                new RecvTransport(options, handlerFactory, current.getSendExtendedRtpCapabilities(), connect));
    }

    private <T extends Transport> T emitNewTransport(T transport) {
        // On the executor of the transport, so before any of its events.
        transport.executor().execute(() -> observer.emitNewTransport(transport));
        return transport;
    }

    private static Loaded compute(
            RtpCapabilities routerRtpCapabilities,
            boolean preferLocalCodecsOrder,
            RtpCapabilities nativeRecvRtpCapabilities,
            RtpCapabilities nativeSendRtpCapabilities,
            SctpCapabilities sctpCapabilities) {
        Function<RtpCapabilities, ExtendedRtpCapabilities> getSendExtendedRtpCapabilities = nativeRtpCapabilities ->
                Ortc.getExtendedRtpCapabilities(nativeRtpCapabilities, routerRtpCapabilities, preferLocalCodecsOrder);

        ExtendedRtpCapabilities recvExtendedRtpCapabilities =
                Ortc.getExtendedRtpCapabilities(nativeRecvRtpCapabilities, routerRtpCapabilities, false);

        // Generate our receiving RTP capabilities for receiving media.
        RtpCapabilities recvRtpCapabilities = Ortc.getRecvRtpCapabilities(recvExtendedRtpCapabilities);
        logger.debug("load() | got receiving RTP capabilities:{}", recvRtpCapabilities);

        // Generate our sending RTP capabilities for sending media.
        RtpCapabilities sendRtpCapabilities =
                Ortc.getSendRtpCapabilities(getSendExtendedRtpCapabilities.apply(nativeSendRtpCapabilities));
        logger.debug("load() | got sending RTP capabilities:{}", sendRtpCapabilities);

        // Check whether we can produce audio/video.
        Map<MediaKind, Boolean> canProduceByKind = Map.of(
                MediaKind.AUDIO, Ortc.canSend(MediaKind.AUDIO, sendRtpCapabilities),
                MediaKind.VIDEO, Ortc.canSend(MediaKind.VIDEO, sendRtpCapabilities));

        logger.debug("load() | got native SCTP capabilities:{}", sctpCapabilities);

        return new Loaded(
                recvRtpCapabilities,
                sendRtpCapabilities,
                getSendExtendedRtpCapabilities,
                canProduceByKind,
                requireNonNull(sctpCapabilities, "sctpCapabilities"));
    }

    private Loaded assertLoaded() throws InvalidStateException {
        Loaded current = loaded;
        if (current == null) {
            throw new InvalidStateException("not loaded");
        }
        return current;
    }
}

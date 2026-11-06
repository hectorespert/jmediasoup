/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.transport;

import static java.util.Objects.requireNonNull;

import io.github.hectorespert.jmediasoup.internal.Checks;

/**
 * The address of a plain RTP endpoint.
 *
 * @param ip the IP address
 * @param ipVersion the IP version
 * @param port the port, between 0 and 65535
 */
public record PlainRtpParameters(String ip, IpVersion ipVersion, int port) {

    /** Checks the fields. */
    public PlainRtpParameters {
        Checks.notEmpty(ip, "ip");
        requireNonNull(ipVersion, "ipVersion");
        Checks.range(port, 0, 65535, "port");
    }
}

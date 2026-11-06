/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.transport;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.hectorespert.jmediasoup.internal.Checks;

/**
 * The address of a plain RTP endpoint.
 *
 * @param ip the IP address
 * @param ipVersion the IP version
 * @param port the port, between 0 and 65535
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlainRtpParameters(
        String ip,
        IpVersion ipVersion,
        @JsonProperty(required = true) int port) {

    /** Checks the fields. */
    public PlainRtpParameters {
        Checks.notEmpty(ip, "ip");
        requireNonNull(ipVersion, "ipVersion");
        Checks.range(port, 0, 65535, "port");
    }
}

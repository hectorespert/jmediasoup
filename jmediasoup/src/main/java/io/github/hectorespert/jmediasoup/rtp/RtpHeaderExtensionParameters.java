/*
 * Copyright 2026 Hector Espert
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.hectorespert.jmediasoup.rtp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.hectorespert.jmediasoup.internal.Checks;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * An RTP header extension in use within the RTP parameters.
 *
 * @param uri the URI of the header extension, as defined in RFC 5285
 * @param id the identifier in the RTP packet, between 1 and 255 and unique within the parameters
 * @param encrypt whether the header is encrypted as per RFC 6904, or {@code null} if absent (not
 *     encrypted)
 * @param parameters the configuration parameters of the header extension, text or numbers
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record RtpHeaderExtensionParameters(
        String uri,
        @JsonProperty(required = true) int id,
        @Nullable Boolean encrypt,
        Map<String, Object> parameters) {

    /** Checks the fields and copies the parameters, with absent ones as empty. */
    public RtpHeaderExtensionParameters {
        Checks.notEmpty(uri, "uri");
        Checks.range(id, 1, 255, "id");
        parameters = Checks.parameters(parameters, "parameters");
    }
}

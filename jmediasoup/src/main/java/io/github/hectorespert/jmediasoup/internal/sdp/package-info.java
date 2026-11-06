/**
 * Port of <a href="https://github.com/clux/sdp-transform">sdp-transform</a> 3.0.0 (MIT licence, see
 * NOTICE), the SDP parser and writer that mediasoup-client and pymediasoup use.
 *
 * <p>A parsed SDP is a tree of mutable {@link java.util.LinkedHashMap}s and {@link
 * java.util.ArrayList}s that keeps the order of the lines, like the object of sdp-transform.
 */
@NullMarked
package io.github.hectorespert.jmediasoup.internal.sdp;

import org.jspecify.annotations.NullMarked;

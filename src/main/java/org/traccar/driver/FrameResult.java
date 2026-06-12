// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

/**
 * Result returned by scripted frame extractors.
 *
 * @param length number of raw bytes to consume from the TCP stream
 * @param payload optional replacement payload to pass downstream; null keeps
 *                the consumed raw bytes as the frame
 */
public record FrameResult(int length, byte[] payload) {

    public FrameResult {
        if (length <= 0) {
            throw new IllegalArgumentException("Frame length must be positive");
        }
    }

    public static FrameResult raw(int length) {
        return new FrameResult(length, null);
    }

    public static FrameResult transformed(int length, byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Transformed frame payload is required");
        }
        return new FrameResult(length, payload.clone());
    }
}

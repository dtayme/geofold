// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

/**
 * Read-only view over the current TCP cumulation passed to scripted frame
 * extractors. All offsets are relative to the current reader index.
 */
public final class FrameBuffer {

    private final ByteBuf buf;
    private final int base;

    FrameBuffer(ByteBuf buf) {
        this.buf = buf;
        this.base = buf.readerIndex();
    }

    public int readableBytes() {
        return buf.readableBytes();
    }

    public int getUByte(int index) {
        return buf.getUnsignedByte(base + index);
    }

    public int getByte(int index) {
        return buf.getByte(base + index);
    }

    public int getUShort(int index) {
        return buf.getUnsignedShort(base + index);
    }

    public int getUShortLE(int index) {
        return buf.getUnsignedShortLE(base + index);
    }

    public long getUInt(int index) {
        return buf.getUnsignedInt(base + index);
    }

    public long getUIntLE(int index) {
        return buf.getUnsignedIntLE(base + index);
    }

    public int indexOf(int value) {
        return indexOf(value, 0);
    }

    public int indexOf(int value, int from) {
        int index = buf.indexOf(base + from, buf.writerIndex(), (byte) value);
        return index >= 0 ? index - base : -1;
    }

    public byte[] bytes(int offset, int length) {
        byte[] bytes = new byte[length];
        buf.getBytes(base + offset, bytes);
        return bytes;
    }

    public String ascii(int offset, int length) {
        return buf.toString(base + offset, length, StandardCharsets.US_ASCII);
    }
}

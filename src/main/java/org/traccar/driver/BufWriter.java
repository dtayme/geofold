// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Small binary packet builder for Groovy driver encode closures.
 */
public final class BufWriter {

    private final ByteBuf buf = Unpooled.buffer();

    public BufWriter writeByte(int value) {
        buf.writeByte(value);
        return this;
    }

    public BufWriter writeShort(int value) {
        buf.writeShort(value);
        return this;
    }

    public BufWriter writeShortLE(int value) {
        buf.writeShortLE(value);
        return this;
    }

    public BufWriter writeInt(int value) {
        buf.writeInt(value);
        return this;
    }

    public BufWriter writeIntLE(int value) {
        buf.writeIntLE(value);
        return this;
    }

    public BufWriter writeBytes(byte[] bytes) {
        buf.writeBytes(bytes);
        return this;
    }

    public BufWriter writeZero(int length) {
        buf.writeZero(length);
        return this;
    }

    public BufWriter writeString(String value) {
        return writeString(value, StandardCharsets.US_ASCII.name());
    }

    public BufWriter writeString(String value, String charset) {
        buf.writeCharSequence(value, Charset.forName(charset));
        return this;
    }

    public BufWriter writeHex(String hex) {
        String normalized = hex.replaceAll("\\s+", "");
        if ((normalized.length() & 1) != 0) {
            throw new IllegalArgumentException("Hex string must contain an even number of digits");
        }
        for (int i = 0; i < normalized.length(); i += 2) {
            buf.writeByte(Integer.parseInt(normalized.substring(i, i + 2), 16));
        }
        return this;
    }

    public BufWriter writeBcd(String digits) {
        return writeBcd(digits, 0x0f);
    }

    public BufWriter writeBcd(String digits, int padNibble) {
        for (int i = 0; i < digits.length(); i += 2) {
            int high = digit(digits.charAt(i));
            int low = i + 1 < digits.length() ? digit(digits.charAt(i + 1)) : padNibble & 0x0f;
            buf.writeByte((high << 4) | low);
        }
        return this;
    }

    public int size() {
        return buf.writerIndex();
    }

    public BufWriter setByte(int index, int value) {
        buf.setByte(index, value);
        return this;
    }

    public BufWriter setShort(int index, int value) {
        buf.setShort(index, value);
        return this;
    }

    public BufWriter setShortLE(int index, int value) {
        buf.setShortLE(index, value);
        return this;
    }

    public BufWriter setInt(int index, int value) {
        buf.setInt(index, value);
        return this;
    }

    public BufWriter setIntLE(int index, int value) {
        buf.setIntLE(index, value);
        return this;
    }

    public byte[] toByteArray() {
        byte[] bytes = new byte[buf.writerIndex()];
        buf.getBytes(0, bytes);
        return bytes;
    }

    private int digit(char value) {
        if (value < '0' || value > '9') {
            throw new IllegalArgumentException("BCD value contains non-decimal digit: " + value);
        }
        return value - '0';
    }
}

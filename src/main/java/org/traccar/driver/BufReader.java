// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import io.netty.buffer.ByteBuf;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Binary buffer reader passed as the first argument to a binary variant's
 * {@code decode} closure. Wraps a Netty {@link ByteBuf} with convenience
 * methods for common tracker protocol field types.
 *
 * <p>Example usage inside a driver script:
 * <pre>
 * decode { buf, ctx ->
 *     int type  = buf.readUByte()
 *     int len   = buf.readUShort()
 *     String id = buf.readBcd(15)
 *     // ...
 * }
 * </pre>
 */
public final class BufReader {

    private final ByteBuf buf;

    BufReader(ByteBuf buf) {
        this.buf = buf;
    }

    // -------------------------------------------------------------------------
    // Scalar reads — all advance the read position
    // -------------------------------------------------------------------------

    /** Reads one byte as an unsigned value (0–255). */
    public int readUByte() {
        return buf.readUnsignedByte();
    }

    /** Reads one byte as a signed value (-128–127). */
    public int readByte() {
        return buf.readByte();
    }

    /** Reads two bytes, big-endian, as unsigned (0–65535). */
    public int readUShort() {
        return buf.readUnsignedShort();
    }

    /** Reads two bytes, big-endian, as signed. */
    public int readShort() {
        return buf.readShort();
    }

    /** Reads two bytes, little-endian, as unsigned (0–65535). */
    public int readUShortLE() {
        return buf.readUnsignedShortLE();
    }

    /** Reads two bytes, little-endian, as signed. */
    public int readShortLE() {
        return buf.readShortLE();
    }

    /** Reads four bytes, big-endian, as unsigned (0–4294967295). */
    public long readUInt() {
        return buf.readUnsignedInt();
    }

    /** Reads four bytes, big-endian, as signed. */
    public int readInt() {
        return buf.readInt();
    }

    /** Reads four bytes, little-endian, as unsigned. */
    public long readUIntLE() {
        return buf.readUnsignedIntLE();
    }

    /** Reads four bytes, little-endian, as signed. */
    public int readIntLE() {
        return buf.readIntLE();
    }

    // -------------------------------------------------------------------------
    // Multi-byte reads
    // -------------------------------------------------------------------------

    /** Reads {@code n} bytes and returns them as a {@code byte[]}. */
    public byte[] readBytes(int n) {
        byte[] bytes = new byte[n];
        buf.readBytes(bytes);
        return bytes;
    }

    /**
     * Reads {@code n} bytes and returns them as a lowercase hex string
     * (e.g. {@code "0a1b2c"}).
     */
    public String readHex(int n) {
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02x", buf.readUnsignedByte()));
        }
        return sb.toString();
    }

    /**
     * Reads BCD-encoded decimal digits. Each byte contains two digits
     * (high nibble first). {@code digits} specifies the total number of
     * decimal digits to return; {@code ⌈digits/2⌉} bytes are consumed.
     *
     * <p>Example: {@code readBcd(15)} reads 8 bytes and returns the 15-digit
     * IMEI string, discarding the low nibble of the last byte (which is
     * a padding nibble).
     */
    public String readBcd(int digits) {
        int bytes = (digits + 1) / 2;
        StringBuilder sb = new StringBuilder(digits * 2);
        for (int i = 0; i < bytes; i++) {
            int b = buf.readUnsignedByte();
            sb.append((char) ('0' + ((b >> 4) & 0x0f)));
            sb.append((char) ('0' + (b & 0x0f)));
        }
        return sb.substring(0, digits);
    }

    /**
     * Reads {@code n} bytes as an ASCII (US-ASCII) string.
     * To use a different charset call {@link #readString(int, String)}.
     */
    public String readString(int n) {
        return buf.readCharSequence(n, StandardCharsets.US_ASCII).toString();
    }

    /** Reads {@code n} bytes as a string using the named charset. */
    public String readString(int n, String charset) {
        return buf.readCharSequence(n, Charset.forName(charset)).toString();
    }

    // -------------------------------------------------------------------------
    // Position / slice
    // -------------------------------------------------------------------------

    /** Returns the number of readable bytes remaining. */
    public int readableBytes() {
        return buf.readableBytes();
    }

    /** Alias for {@link #readableBytes()} — matches Groovy idioms. */
    public int remaining() {
        return buf.readableBytes();
    }

    /** Returns {@code true} if at least one byte remains. */
    public boolean isReadable() {
        return buf.isReadable();
    }

    /** Skips {@code n} bytes. */
    public void skip(int n) {
        buf.skipBytes(n);
    }

    /**
     * Returns a new {@link BufReader} over the next {@code n} bytes and
     * advances the read position by {@code n}. The slice has its own
     * independent read pointer.
     */
    public BufReader slice(int n) {
        return new BufReader(buf.readRetainedSlice(n));
    }

    // -------------------------------------------------------------------------
    // Absolute (index-based) reads — do NOT advance the read position
    // -------------------------------------------------------------------------

    /**
     * Returns the unsigned byte at {@code index} bytes ahead of the current
     * read position, without advancing the pointer.
     */
    public int getUByte(int index) {
        return buf.getUnsignedByte(buf.readerIndex() + index);
    }

    // -------------------------------------------------------------------------
    // Static helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if bit {@code bit} is set in {@code value}.
     * Bit 0 is the LSB.
     *
     * <p>Example: {@code BufReader.checkBit(status, 3)} tests bit 3.
     */
    public static boolean checkBit(int value, int bit) {
        return (value & (1 << bit)) != 0;
    }

    // -------------------------------------------------------------------------
    // Internal lifecycle
    // -------------------------------------------------------------------------

    void release() {
        if (buf.refCnt() > 0) {
            buf.release();
        }
    }
}

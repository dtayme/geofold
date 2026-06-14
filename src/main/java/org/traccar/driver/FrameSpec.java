// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import groovy.lang.Closure;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Describes how a driver variant delimits its frames on the wire.
 *
 * <p>Text framing modes:
 * <ul>
 *   <li>{@link Mode#READ_LINE}            — scan for {@code \n}, strip trailing {@code \r}
 *   <li>{@link Mode#READ_UNTIL_BYTES}     — scan for an arbitrary byte sequence terminator
 *   <li>{@link Mode#READ_UNTIL_ANY_BYTES} — scan for the first matching terminator among alternatives
 * </ul>
 *
 * <p>Binary framing modes (the decode closure receives a {@link BufReader}, not a {@code String}):
 * <ul>
 *   <li>{@link Mode#READ_FIXED}        — always read exactly {@code size} bytes
 *   <li>{@link Mode#READ_FIXED_ANY}    — read one of several fixed sizes
 *   <li>{@link Mode#READ_LENGTH_FIELD} — read a length field embedded in the header,
 *       then read that many bytes plus an optional adjustment (may be negative when the
 *       length field counts bytes already consumed, e.g. the field itself)
 * </ul>
 *
 * <p>For {@code READ_LENGTH_FIELD}, the total frame size is:
 * <pre>
 *   lengthFieldOffset + lengthFieldLength + fieldValue + lengthAdjustment
 * </pre>
 *
 * <p>Example — 2-byte total-length field (value counts the 2-byte field itself):
 * <pre>
 *   frame 0x00 as byte, readLengthField(0, 2, -2)
 * </pre>
 *
 * <p>Example — 2-byte magic header, 2-byte payload-length field, 1-byte checksum:
 * <pre>
 *   frame 0x78 as byte, readLengthField(2, 2, 1)
 * </pre>
 */
public final class FrameSpec {

    public enum Mode {
        READ_UNTIL_BYTES,        // scan for a byte-sequence terminator (text)
        READ_UNTIL_ANY_BYTES,    // scan for the first matching terminator among alternatives (text)
        READ_LINE,               // scan for \n, strip trailing \r (text)
        READ_FIXED,              // read exactly `size` bytes (binary)
        READ_FIXED_ANY,          // read one of several fixed frame sizes (binary)
        READ_LENGTH_FIELD,       // use embedded length field (binary)
        READ_ESCAPED_DELIMITER,  // delimiter-framed binary stream with escape replacements
        READ_SCRIPTED,           // custom frame extraction closure (binary)
    }

    // ---- shared ----
    private final Mode mode;

    // ---- text modes ----
    private final byte[] terminator;         // READ_UNTIL_BYTES / READ_LINE (single terminator)
    private final byte[][] terminators;      // READ_UNTIL_ANY_BYTES (multiple alternatives)
    private final boolean includeTerminator;

    // ---- READ_FIXED ----
    private final int size;
    private final int[] sizes;

    // ---- READ_LENGTH_FIELD ----
    private final int lengthFieldOffset;      // bytes before the length field
    private final int lengthFieldLength;      // width of the length field: 1, 2, or 4
    private final int lengthAdjustment;       // extra bytes after field value (may be negative)
    private final boolean lengthFieldLittleEndian;

    // ---- READ_ESCAPED_DELIMITER ----
    private final byte delimiter;
    private final byte escape;
    private final Map<Byte, Byte> escapeMap;

    // ---- READ_SCRIPTED ----
    private final Closure<?> frameClosure;

    private FrameSpec(Mode mode, byte[] terminator, byte[][] terminators, int size, int[] sizes,
                      int lengthFieldOffset, int lengthFieldLength, int lengthAdjustment,
                      boolean lengthFieldLittleEndian, boolean includeTerminator,
                      byte delimiter, byte escape, Map<Byte, Byte> escapeMap,
                      Closure<?> frameClosure) {
        this.mode = mode;
        this.terminator = terminator;
        this.terminators = terminators;
        this.size = size;
        this.sizes = sizes;
        this.lengthFieldOffset = lengthFieldOffset;
        this.lengthFieldLength = lengthFieldLength;
        this.lengthAdjustment = lengthAdjustment;
        this.lengthFieldLittleEndian = lengthFieldLittleEndian;
        this.includeTerminator = includeTerminator;
        this.delimiter = delimiter;
        this.escape = escape;
        this.escapeMap = escapeMap != null ? Map.copyOf(escapeMap) : null;
        this.frameClosure = frameClosure;
    }

    // -------------------------------------------------------------------------
    // Factory methods — exposed via DriverDSL so scripts never call these directly
    // -------------------------------------------------------------------------

    public static FrameSpec readUntil(String terminator) {
        return new FrameSpec(Mode.READ_UNTIL_BYTES, terminator.getBytes(StandardCharsets.UTF_8),
                null, 0, null, 0, 0, 0, false, false, (byte) 0, (byte) 0, null, null);
    }

    public static FrameSpec readUntilKeep(String terminator) {
        return new FrameSpec(Mode.READ_UNTIL_BYTES, terminator.getBytes(StandardCharsets.UTF_8),
                null, 0, null, 0, 0, 0, false, true, (byte) 0, (byte) 0, null, null);
    }

    /**
     * Text: scan for the first occurrence of any of the given terminators.
     * The frame ends immediately before the matched terminator (exclusive).
     * Requires at least two alternatives.
     *
     * <p>Example — newline, semicolon, or asterisk all end the frame:
     * <pre>
     *   frame readUntilAny("\r\n", "\n", ";", "*")
     * </pre>
     */
    public static FrameSpec readUntilAny(String... terminatorStrings) {
        if (terminatorStrings == null || terminatorStrings.length < 2) {
            throw new IllegalArgumentException("At least two terminators are required for readUntilAny");
        }
        byte[][] terminators = new byte[terminatorStrings.length][];
        for (int i = 0; i < terminatorStrings.length; i++) {
            terminators[i] = terminatorStrings[i].getBytes(StandardCharsets.UTF_8);
        }
        return new FrameSpec(Mode.READ_UNTIL_ANY_BYTES, null, terminators, 0, null,
                0, 0, 0, false, false, (byte) 0, (byte) 0, null, null);
    }

    public static FrameSpec readLine() {
        return new FrameSpec(Mode.READ_LINE, new byte[]{'\n'}, null, 0, null,
                0, 0, 0, false, false, (byte) 0, (byte) 0, null, null);
    }

    /** Binary: always read exactly {@code size} bytes per frame. */
    public static FrameSpec readFixed(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Fixed frame size must be positive");
        }
        return new FrameSpec(Mode.READ_FIXED, null, null, size, null,
                0, 0, 0, false, false, (byte) 0, (byte) 0, null, null);
    }

    /** Binary: read one of several fixed frame sizes. */
    public static FrameSpec readFixedAny(int... sizes) {
        if (sizes == null || sizes.length == 0) {
            throw new IllegalArgumentException("At least one fixed frame size is required");
        }
        int[] sorted = sizes.clone();
        java.util.Arrays.sort(sorted);
        int previous = 0;
        for (int size : sorted) {
            if (size <= 0) {
                throw new IllegalArgumentException("Fixed frame size must be positive");
            }
            if (size == previous) {
                throw new IllegalArgumentException("Duplicate fixed frame size: " + size);
            }
            previous = size;
        }
        return new FrameSpec(Mode.READ_FIXED_ANY, null, null, 0, sorted,
                0, 0, 0, false, false, (byte) 0, (byte) 0, null, null);
    }

    /**
     * Binary: read a length field at {@code lengthFieldOffset} bytes into the
     * frame. {@code lengthFieldLength} must be 1, 2, or 4.
     * Total frame = {@code lengthFieldOffset + lengthFieldLength + fieldValue}.
     */
    public static FrameSpec readLengthField(int lengthFieldOffset, int lengthFieldLength) {
        return readLengthField(lengthFieldOffset, lengthFieldLength, 0);
    }

    /**
     * Binary: like {@link #readLengthField(int, int)} but adds {@code lengthAdjustment}
     * extra bytes after the field value.
     * Total frame = {@code lengthFieldOffset + lengthFieldLength + fieldValue + lengthAdjustment}.
     *
     * <p>{@code lengthAdjustment} may be negative when the length field encodes the total frame
     * size including bytes already consumed. The computed total must still be positive at runtime.
     */
    public static FrameSpec readLengthField(int lengthFieldOffset, int lengthFieldLength, int lengthAdjustment) {
        validateLengthField(lengthFieldOffset, lengthFieldLength);
        return new FrameSpec(Mode.READ_LENGTH_FIELD, null, null, 0, null,
                lengthFieldOffset, lengthFieldLength, lengthAdjustment,
                false, false, (byte) 0, (byte) 0, null, null);
    }

    public static FrameSpec readLengthFieldLE(int lengthFieldOffset, int lengthFieldLength) {
        return readLengthFieldLE(lengthFieldOffset, lengthFieldLength, 0);
    }

    public static FrameSpec readLengthFieldLE(int lengthFieldOffset, int lengthFieldLength, int lengthAdjustment) {
        validateLengthField(lengthFieldOffset, lengthFieldLength);
        return new FrameSpec(Mode.READ_LENGTH_FIELD, null, null, 0, null,
                lengthFieldOffset, lengthFieldLength, lengthAdjustment,
                true, false, (byte) 0, (byte) 0, null, null);
    }

    public static FrameSpec readEscaped(byte delimiter, byte escape, Map<?, ?> replacements) {
        if (replacements == null || replacements.isEmpty()) {
            throw new IllegalArgumentException("At least one escape replacement is required");
        }
        Map<Byte, Byte> converted = new HashMap<>();
        for (Map.Entry<?, ?> entry : replacements.entrySet()) {
            converted.put(toByte(entry.getKey(), "escape replacement key"),
                    toByte(entry.getValue(), "escape replacement value"));
        }
        return new FrameSpec(Mode.READ_ESCAPED_DELIMITER, null, null, 0, null,
                0, 0, 0, false, false, delimiter, escape, converted, null);
    }

    public static FrameSpec readScripted(Closure<?> frameClosure) {
        if (frameClosure == null) {
            throw new IllegalArgumentException("Frame closure is required");
        }
        return new FrameSpec(Mode.READ_SCRIPTED, null, null, 0, null,
                0, 0, 0, false, false, (byte) 0, (byte) 0, null, frameClosure);
    }

    private static void validateLengthField(int lengthFieldOffset, int lengthFieldLength) {
        if (lengthFieldOffset < 0) {
            throw new IllegalArgumentException("Length field offset must be non-negative");
        }
        if (lengthFieldLength != 1 && lengthFieldLength != 2 && lengthFieldLength != 4) {
            throw new IllegalArgumentException("Length field must be 1, 2, or 4 bytes, got " + lengthFieldLength);
        }
    }

    private static byte toByte(Object value, String label) {
        if (value instanceof Number number) {
            return (byte) number.intValue();
        }
        if (value instanceof Character character) {
            return (byte) character.charValue();
        }
        if (value instanceof String string && string.length() == 1) {
            return (byte) string.charAt(0);
        }
        throw new IllegalArgumentException(label + " must be a byte-sized number or single character");
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public Mode getMode() {
        return mode;
    }

    /** Used by single-terminator text modes (READ_UNTIL_BYTES, READ_LINE). */
    public byte[] getTerminator() {
        return terminator;
    }

    /** Used by READ_UNTIL_ANY_BYTES. Each element is one alternative terminator byte sequence. */
    public byte[][] getTerminators() {
        return terminators;
    }

    /** Used by READ_FIXED. */
    public int getSize() {
        return size;
    }

    /** Used by READ_FIXED_ANY. Sizes are sorted ascending. */
    public int[] getSizes() {
        return sizes != null ? sizes.clone() : null;
    }

    /** Used by READ_LENGTH_FIELD: byte offset of the length field within the frame. */
    public int getLengthFieldOffset() {
        return lengthFieldOffset;
    }

    /** Used by READ_LENGTH_FIELD: byte width of the length field (1, 2, or 4). */
    public int getLengthFieldLength() {
        return lengthFieldLength;
    }

    /** Used by READ_LENGTH_FIELD: added to the field value to get trailing bytes (may be negative). */
    public int getLengthAdjustment() {
        return lengthAdjustment;
    }

    public boolean isLengthFieldLittleEndian() {
        return lengthFieldLittleEndian;
    }

    public boolean isIncludeTerminator() {
        return includeTerminator;
    }

    public byte getDelimiter() {
        return delimiter;
    }

    public byte getEscape() {
        return escape;
    }

    public Map<Byte, Byte> getEscapeMap() {
        return escapeMap;
    }

    public Closure<?> getFrameClosure() {
        return frameClosure;
    }

    /** Returns {@code true} if this spec describes a binary framing mode. */
    public boolean isBinary() {
        return mode == Mode.READ_FIXED
                || mode == Mode.READ_FIXED_ANY
                || mode == Mode.READ_LENGTH_FIELD
                || mode == Mode.READ_ESCAPED_DELIMITER
                || mode == Mode.READ_SCRIPTED;
    }
}

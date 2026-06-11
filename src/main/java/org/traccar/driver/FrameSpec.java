package org.traccar.driver;

/**
 * Describes how a driver variant delimits its frames on the wire.
 *
 * <p>Text framing modes:
 * <ul>
 *   <li>{@link Mode#READ_LINE}         — scan for {@code \n}, strip trailing {@code \r}
 *   <li>{@link Mode#READ_UNTIL_BYTES}  — scan for an arbitrary byte sequence terminator
 * </ul>
 *
 * <p>Binary framing modes (the decode closure receives a {@link BufReader}, not a {@code String}):
 * <ul>
 *   <li>{@link Mode#READ_FIXED}        — always read exactly {@code size} bytes
 *   <li>{@link Mode#READ_LENGTH_FIELD} — read a length field embedded in the header,
 *       then read that many bytes plus an optional adjustment
 * </ul>
 *
 * <p>For {@code READ_LENGTH_FIELD}, the total frame size is:
 * <pre>
 *   lengthFieldOffset + lengthFieldLength + fieldValue + lengthAdjustment
 * </pre>
 *
 * <p>Example — 2-byte magic header, 2-byte length field, body, 1-byte checksum:
 * <pre>
 *   frame 0x78 as byte, readLengthField(2, 2, 1)
 *   //            offset=2 (skip magic), fieldLen=2, adjustment=1 (checksum)
 * </pre>
 */
public final class FrameSpec {

    public enum Mode {
        READ_UNTIL_BYTES,   // scan for a byte-sequence terminator (text)
        READ_LINE,          // scan for \n, strip trailing \r (text)
        READ_FIXED,         // read exactly `size` bytes (binary)
        READ_LENGTH_FIELD,  // use embedded length field (binary)
    }

    // ---- shared ----
    private final Mode mode;

    // ---- text modes ----
    private final byte[] terminator;        // READ_UNTIL_BYTES / READ_LINE

    // ---- READ_FIXED ----
    private final int size;

    // ---- READ_LENGTH_FIELD ----
    private final int lengthFieldOffset;    // bytes before the length field
    private final int lengthFieldLength;    // width of the length field: 1, 2, or 4
    private final int lengthAdjustment;     // extra bytes after the field value

    private FrameSpec(Mode mode, byte[] terminator, int size,
                      int lengthFieldOffset, int lengthFieldLength, int lengthAdjustment) {
        this.mode = mode;
        this.terminator = terminator;
        this.size = size;
        this.lengthFieldOffset = lengthFieldOffset;
        this.lengthFieldLength = lengthFieldLength;
        this.lengthAdjustment = lengthAdjustment;
    }

    // -------------------------------------------------------------------------
    // Factory methods — exposed via DriverDSL so scripts never call these directly
    // -------------------------------------------------------------------------

    public static FrameSpec readUntil(String terminator) {
        return new FrameSpec(Mode.READ_UNTIL_BYTES, terminator.getBytes(), 0, 0, 0, 0);
    }

    public static FrameSpec readLine() {
        return new FrameSpec(Mode.READ_LINE, new byte[]{'\n'}, 0, 0, 0, 0);
    }

    /** Binary: always read exactly {@code size} bytes per frame. */
    public static FrameSpec readFixed(int size) {
        return new FrameSpec(Mode.READ_FIXED, null, size, 0, 0, 0);
    }

    /**
     * Binary: read a length field at {@code lengthFieldOffset} bytes into the
     * frame. {@code lengthFieldLength} must be 1, 2, or 4.
     * Total frame = {@code lengthFieldOffset + lengthFieldLength + fieldValue}.
     */
    public static FrameSpec readLengthField(int lengthFieldOffset, int lengthFieldLength) {
        return new FrameSpec(Mode.READ_LENGTH_FIELD, null, 0,
                lengthFieldOffset, lengthFieldLength, 0);
    }

    /**
     * Binary: like {@link #readLengthField(int, int)} but adds
     * {@code lengthAdjustment} extra bytes after the field value.
     * Total frame = {@code lengthFieldOffset + lengthFieldLength + fieldValue + lengthAdjustment}.
     */
    public static FrameSpec readLengthField(int lengthFieldOffset, int lengthFieldLength, int lengthAdjustment) {
        return new FrameSpec(Mode.READ_LENGTH_FIELD, null, 0,
                lengthFieldOffset, lengthFieldLength, lengthAdjustment);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public Mode getMode() {
        return mode;
    }

    /** Used by text modes (READ_UNTIL_BYTES, READ_LINE). */
    public byte[] getTerminator() {
        return terminator;
    }

    /** Used by READ_FIXED. */
    public int getSize() {
        return size;
    }

    /** Used by READ_LENGTH_FIELD: byte offset of the length field within the frame. */
    public int getLengthFieldOffset() {
        return lengthFieldOffset;
    }

    /** Used by READ_LENGTH_FIELD: byte width of the length field (1, 2, or 4). */
    public int getLengthFieldLength() {
        return lengthFieldLength;
    }

    /** Used by READ_LENGTH_FIELD: added to the field value to get trailing bytes. */
    public int getLengthAdjustment() {
        return lengthAdjustment;
    }

    /** Returns {@code true} if this spec describes a binary framing mode. */
    public boolean isBinary() {
        return mode == Mode.READ_FIXED || mode == Mode.READ_LENGTH_FIELD;
    }
}

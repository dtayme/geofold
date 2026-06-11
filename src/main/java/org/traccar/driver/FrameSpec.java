package org.traccar.driver;

/**
 * Describes how a driver variant delimits its frames on the wire.
 */
public final class FrameSpec {

    public enum Mode {
        READ_UNTIL_BYTES,  // scan for a byte sequence terminator (e.g. "#" or "##")
        READ_LINE,         // scan for \n, strip trailing \r
    }

    private final Mode mode;
    private final byte[] terminator;

    private FrameSpec(Mode mode, byte[] terminator) {
        this.mode = mode;
        this.terminator = terminator;
    }

    public static FrameSpec readUntil(String terminator) {
        return new FrameSpec(Mode.READ_UNTIL_BYTES, terminator.getBytes());
    }

    public static FrameSpec readLine() {
        return new FrameSpec(Mode.READ_LINE, new byte[]{'\n'});
    }

    public Mode getMode() {
        return mode;
    }

    public byte[] getTerminator() {
        return terminator;
    }
}

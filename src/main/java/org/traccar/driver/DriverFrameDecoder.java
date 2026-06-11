package org.traccar.driver;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.AttributeKey;

import java.util.List;

/**
 * Frame decoder for driver-based protocols. Matches the first byte of the
 * incoming buffer against each variant's {@code frameByteHint} to select the
 * correct framing strategy, then extracts one complete frame.
 *
 * <p>Falls back to newline framing when no variant hint matches.
 */
public class DriverFrameDecoder extends ByteToMessageDecoder {

    public static final AttributeKey<String> DRIVER_KEY  = AttributeKey.valueOf("driver.name");
    public static final AttributeKey<String> VARIANT_KEY = AttributeKey.valueOf("driver.variant");

    private final DriverRegistry registry;

    public DriverFrameDecoder(DriverRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf buf, List<Object> out) {
        if (!buf.isReadable()) {
            return;
        }

        byte first = buf.getByte(buf.readerIndex());
        FrameSpec spec = resolveSpec(first);

        switch (spec.getMode()) {
            case READ_UNTIL_BYTES -> {
                byte[] term = spec.getTerminator();
                int end = findSequence(buf, term);
                if (end < 0) {
                    return;
                }
                out.add(buf.readRetainedSlice(end - buf.readerIndex()));
                buf.skipBytes(term.length);
                while (buf.isReadable()
                        && (buf.getByte(buf.readerIndex()) == '\r'
                         || buf.getByte(buf.readerIndex()) == '\n')) {
                    buf.skipBytes(1);
                }
            }
            case READ_LINE -> {
                int end = buf.indexOf(buf.readerIndex(), buf.writerIndex(), (byte) '\n');
                if (end < 0) {
                    return;
                }
                int length = end - buf.readerIndex();
                if (length > 0 && buf.getByte(buf.readerIndex() + length - 1) == '\r') {
                    out.add(buf.readRetainedSlice(length - 1));
                    buf.skipBytes(2);
                } else {
                    out.add(buf.readRetainedSlice(length));
                    buf.skipBytes(1);
                }
            }
            default -> buf.skipBytes(buf.readableBytes());
        }
    }

    /**
     * Finds the variant whose {@code frameByteHint} matches {@code firstByte}, then
     * returns its {@link FrameSpec}. Falls back to {@link FrameSpec#readLine()} if
     * no variant declares a hint for this byte (covers legacy newline-terminated devices).
     */
    private FrameSpec resolveSpec(byte firstByte) {
        FrameSpec fallback = null;

        for (DriverDefinition driver : registry.all()) {
            for (VariantDefinition variant : driver.getVariants()) {
                FrameSpec spec = variant.getFrameSpec();
                if (spec == null) {
                    continue;
                }
                Byte hint = variant.getFrameByteHint();
                if (hint == null) {
                    if (fallback == null) {
                        fallback = spec; // first hintless variant is the fallback
                    }
                } else if (hint == firstByte) {
                    return spec;
                }
            }
        }

        return fallback != null ? fallback : FrameSpec.readLine();
    }

    private int findSequence(ByteBuf buf, byte[] seq) {
        int end = buf.writerIndex() - seq.length + 1;
        outer:
        for (int i = buf.readerIndex(); i < end; i++) {
            for (int j = 0; j < seq.length; j++) {
                if (buf.getByte(i + j) != seq[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}

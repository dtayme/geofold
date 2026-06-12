// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.util.AttributeKey;

import java.util.List;

/**
 * Frame decoder for driver-based protocols. Matches the first byte of the
 * incoming buffer against each variant's {@code frameByteHint} to select the
 * correct framing strategy, then extracts one complete frame.
 *
 * <p>For binary variants (those with {@link FrameSpec.Mode#READ_FIXED} or
 * {@link FrameSpec.Mode#READ_LENGTH_FIELD}), this handler also sets the
 * driver/variant channel attributes <em>before</em> firing the frame downstream.
 * This allows {@link DriverMessageAdapter} to determine whether to wrap the
 * extracted bytes as a {@link BufReader} (binary) or decode them as a
 * {@code String} (text).
 *
 * <p>Falls back to newline framing when no variant hint matches.
 */
public class DriverFrameDecoder extends ByteToMessageDecoder {

    public static final AttributeKey<String> DRIVER_KEY  = AttributeKey.valueOf("driver.name");
    public static final AttributeKey<String> VARIANT_KEY = AttributeKey.valueOf("driver.variant");

    private final DriverRegistry registry;
    private final int defaultMaxFrameLength;

    public DriverFrameDecoder(DriverRegistry registry, int defaultMaxFrameLength) {
        if (defaultMaxFrameLength <= 0) {
            throw new IllegalArgumentException("Default maximum frame length must be positive");
        }
        this.registry = registry;
        this.defaultMaxFrameLength = defaultMaxFrameLength;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf buf, List<Object> out) {
        if (!buf.isReadable()) {
            return;
        }

        byte first = buf.getByte(buf.readerIndex());
        SpecMatch match = resolveMatch(first);
        FrameSpec spec = match != null ? match.spec() : FrameSpec.readLine();
        int maxFrameLength = maxFrameLength(match);

        // Set channel attrs now for binary variants so DriverMessageAdapter can
        // determine the correct conversion before the next handler runs.
        if (match != null && match.variant().isBinary()) {
            ctx.channel().attr(DRIVER_KEY).set(match.driver().getName());
            ctx.channel().attr(VARIANT_KEY).set(match.variant().getName());
        }

        switch (spec.getMode()) {
            case READ_UNTIL_BYTES -> {
                byte[] term = spec.getTerminator();
                int end = findSequence(buf, term);
                if (end < 0) {
                    checkCumulationLength(buf, maxFrameLength);
                    return;
                }
                int frameLength = end - buf.readerIndex();
                checkFrameLength(frameLength, maxFrameLength);
                out.add(buf.readRetainedSlice(frameLength));
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
                    checkCumulationLength(buf, maxFrameLength);
                    return;
                }
                int length = end - buf.readerIndex();
                checkFrameLength(length, maxFrameLength);
                if (length > 0 && buf.getByte(buf.readerIndex() + length - 1) == '\r') {
                    out.add(buf.readRetainedSlice(length - 1));
                    buf.skipBytes(2);
                } else {
                    out.add(buf.readRetainedSlice(length));
                    buf.skipBytes(1);
                }
            }
            case READ_FIXED -> {
                int size = spec.getSize();
                checkFrameLength(size, maxFrameLength);
                if (buf.readableBytes() < size) {
                    return;
                }
                out.add(buf.readRetainedSlice(size));
            }
            case READ_LENGTH_FIELD -> {
                int lfo = spec.getLengthFieldOffset();
                int lfl = spec.getLengthFieldLength();
                int adj = spec.getLengthAdjustment();
                if (buf.readableBytes() < lfo + lfl) {
                    checkCumulationLength(buf, maxFrameLength);
                    return;
                }
                long fieldValue = readLengthFieldValue(buf, lfo, lfl);
                long totalSize = (long) lfo + lfl + fieldValue + adj;
                if (totalSize <= 0) {
                    throw new CorruptedFrameException("Invalid driver frame length: " + totalSize);
                }
                checkFrameLength(totalSize, maxFrameLength);
                if (buf.readableBytes() < totalSize) {
                    return;
                }
                out.add(buf.readRetainedSlice((int) totalSize));
            }
            default -> buf.skipBytes(buf.readableBytes());
        }
    }

    /**
     * Finds the variant whose {@code frameByteHint} matches {@code firstByte}, then
     * returns its {@link FrameSpec}. Falls back to the first hintless variant's spec,
     * or to newline framing if no variants are registered.
     */
    private SpecMatch resolveMatch(byte firstByte) {
        SpecMatch fallback = null;

        for (DriverDefinition driver : registry.all()) {
            for (VariantDefinition variant : driver.getVariants()) {
                FrameSpec spec = variant.getFrameSpec();
                if (spec == null) {
                    continue;
                }
                Byte hint = variant.getFrameByteHint();
                if (hint == null) {
                    if (fallback == null) {
                        fallback = new SpecMatch(driver, variant, spec);
                    }
                } else if (hint == firstByte) {
                    return new SpecMatch(driver, variant, spec);
                }
            }
        }

        return fallback;
    }

    private int maxFrameLength(SpecMatch match) {
        if (match != null && match.variant().getMaxFrameLength() != null) {
            return match.variant().getMaxFrameLength();
        }
        return defaultMaxFrameLength;
    }

    private void checkCumulationLength(ByteBuf buf, int maxFrameLength) {
        if (buf.readableBytes() > maxFrameLength) {
            throw new TooLongFrameException("Driver frame exceeded maximum length of " + maxFrameLength);
        }
    }

    private void checkFrameLength(long frameLength, int maxFrameLength) {
        if (frameLength > maxFrameLength) {
            throw new TooLongFrameException(
                    "Driver frame length " + frameLength + " exceeds maximum length of " + maxFrameLength);
        }
    }

    private long readLengthFieldValue(ByteBuf buf, int offset, int length) {
        int pos = buf.readerIndex() + offset;
        return switch (length) {
            case 1 -> buf.getUnsignedByte(pos);
            case 2 -> buf.getUnsignedShort(pos);
            case 4 -> buf.getUnsignedInt(pos);
            default -> throw new IllegalArgumentException("Length field must be 1, 2, or 4 bytes, got " + length);
        };
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

    /** Holds the resolved driver, variant, and frame spec for one incoming byte. */
    private record SpecMatch(DriverDefinition driver, VariantDefinition variant, FrameSpec spec) {
    }
}

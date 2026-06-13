// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * Frame decoder for driver-based protocols. Preferentially considers drivers
 * whose declared port matches the channel's local port, then matches the first
 * byte of the incoming buffer against each variant's {@code frameByteHint} to
 * select the correct framing strategy and extract one complete frame.
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

    private static final Logger LOGGER = LoggerFactory.getLogger(DriverFrameDecoder.class);

    public static final AttributeKey<String> DRIVER_KEY  = AttributeKey.valueOf("driver.name");
    public static final AttributeKey<String> VARIANT_KEY = AttributeKey.valueOf("driver.variant");

    private final DriverRegistry registry;
    private final int defaultMaxFrameLength;
    private final Integer scopedPort;

    public DriverFrameDecoder(DriverRegistry registry, int defaultMaxFrameLength) {
        this(registry, defaultMaxFrameLength, null);
    }

    public DriverFrameDecoder(DriverRegistry registry, int defaultMaxFrameLength, Integer scopedPort) {
        if (defaultMaxFrameLength <= 0) {
            throw new IllegalArgumentException("Default maximum frame length must be positive");
        }
        this.registry = registry;
        this.defaultMaxFrameLength = defaultMaxFrameLength;
        this.scopedPort = scopedPort;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf buf, List<Object> out) {
        if (!buf.isReadable()) {
            return;
        }

        Integer localPort = localPort(ctx);
        byte first = buf.getByte(buf.readerIndex());
        SpecMatch match = resolveMatch(first, localPort);
        if (match == null && localPort != null && hasPortScopedDrivers(localPort)) {
            if (!skipUntilPortHint(buf, localPort)) {
                return;
            }
            first = buf.getByte(buf.readerIndex());
            match = resolveMatch(first, localPort);
        }
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
                int frameLength = end - buf.readerIndex() + (spec.isIncludeTerminator() ? term.length : 0);
                checkFrameLength(frameLength, maxFrameLength);
                out.add(buf.readRetainedSlice(frameLength));
                if (!spec.isIncludeTerminator()) {
                    buf.skipBytes(term.length);
                }
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
            case READ_FIXED_ANY -> {
                int size = resolveFixedAnySize(buf.readableBytes(), spec.getSizes());
                if (size < 0) {
                    checkCumulationLength(buf, maxFrameLength);
                    return;
                }
                checkFrameLength(size, maxFrameLength);
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
                long fieldValue = readLengthFieldValue(buf, lfo, lfl, spec.isLengthFieldLittleEndian());
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
            case READ_ESCAPED_DELIMITER -> {
                int start = buf.readerIndex();
                if (buf.getByte(start) != spec.getDelimiter()) {
                    throw new CorruptedFrameException("Escaped driver frame does not start with delimiter");
                }
                int end = buf.indexOf(start + 1, buf.writerIndex(), spec.getDelimiter());
                if (end < 0) {
                    checkCumulationLength(buf, maxFrameLength);
                    return;
                }
                int rawLength = end - start + 1;
                checkFrameLength(rawLength, maxFrameLength);
                byte[] payload = unescapeDelimitedFrame(buf, start + 1, end, spec);
                buf.skipBytes(rawLength);
                out.add(Unpooled.wrappedBuffer(payload));
            }
            case READ_SCRIPTED -> {
                Object result = spec.getFrameClosure().call(new FrameBuffer(buf));
                if (result == null) {
                    checkCumulationLength(buf, maxFrameLength);
                    return;
                }
                FrameResult frameResult = toFrameResult(result);
                int length = frameResult.length();
                checkFrameLength(length, maxFrameLength);
                if (buf.readableBytes() < length) {
                    return;
                }
                if (frameResult.payload() != null) {
                    buf.skipBytes(length);
                    out.add(Unpooled.wrappedBuffer(frameResult.payload()));
                } else {
                    out.add(buf.readRetainedSlice(length));
                }
            }
            default -> buf.skipBytes(buf.readableBytes());
        }
    }

    /**
     * Finds the variant whose {@code frameByteHint} matches {@code firstByte}, then
     * returns its {@link FrameSpec}. If the channel port matches any driver, only
     * those drivers are considered. Otherwise this falls back to the first hintless
     * variant's spec, or to newline framing if no variants are registered.
     */
    protected Integer localPort(ChannelHandlerContext ctx) {
        if (scopedPort != null) {
            return scopedPort;
        }
        if (ctx.channel().localAddress() instanceof InetSocketAddress address) {
            return address.getPort();
        }
        return null;
    }

    private SpecMatch resolveMatch(byte firstByte, Integer localPort) {
        SpecMatch portMatch = resolveMatch(firstByte, localPort, true);
        if (portMatch != null) {
            return portMatch;
        }
        if (scopedPort != null || (localPort != null && hasPortScopedDrivers(localPort))) {
            return null;
        }
        return resolveMatch(firstByte, localPort, false);
    }

    private SpecMatch resolveMatch(byte firstByte, Integer localPort, boolean requirePortMatch) {
        SpecMatch fallback = null;

        for (DriverDefinition driver : registry.all()) {
            if (!driver.supportsTransport(DriverTransport.TCP)) {
                continue;
            }
            if (requirePortMatch && (localPort == null || driver.getDefaultPort() != localPort)) {
                continue;
            }
            if (!requirePortMatch && localPort != null && driver.getDefaultPort() == localPort) {
                continue;
            }
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

    private boolean hasPortScopedDrivers(int localPort) {
        for (DriverDefinition driver : registry.all()) {
            if (driver.supportsTransport(DriverTransport.TCP) && driver.getDefaultPort() == localPort) {
                return true;
            }
        }
        return false;
    }

    private boolean skipUntilPortHint(ByteBuf buf, int localPort) {
        for (int i = buf.readerIndex() + 1; i < buf.writerIndex(); i++) {
            byte candidate = buf.getByte(i);
            for (DriverDefinition driver : registry.all()) {
                if (!driver.supportsTransport(DriverTransport.TCP) || driver.getDefaultPort() != localPort) {
                    continue;
                }
                for (VariantDefinition variant : driver.getVariants()) {
                    Byte hint = variant.getFrameByteHint();
                    if (hint != null && hint == candidate) {
                        buf.skipBytes(i - buf.readerIndex());
                        return true;
                    }
                }
            }
        }
        int discarded = buf.readableBytes();
        buf.skipBytes(discarded);
        LOGGER.debug("Discarded {} unrecognised byte(s) on port {} — no frame-start hint matched", discarded, localPort);
        return false;
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

    private long readLengthFieldValue(ByteBuf buf, int offset, int length, boolean littleEndian) {
        int pos = buf.readerIndex() + offset;
        return switch (length) {
            case 1 -> buf.getUnsignedByte(pos);
            case 2 -> littleEndian ? buf.getUnsignedShortLE(pos) : buf.getUnsignedShort(pos);
            case 4 -> littleEndian ? buf.getUnsignedIntLE(pos) : buf.getUnsignedInt(pos);
            default -> throw new IllegalArgumentException("Length field must be 1, 2, or 4 bytes, got " + length);
        };
    }

    private byte[] unescapeDelimitedFrame(ByteBuf buf, int start, int end, FrameSpec spec) {
        ByteBuf payload = Unpooled.buffer(end - start);
        try {
            for (int i = start; i < end; i++) {
                byte value = buf.getByte(i);
                if (value == spec.getEscape() && i + 1 < end) {
                    byte escaped = buf.getByte(++i);
                    Byte replacement = spec.getEscapeMap().get(escaped);
                    if (replacement != null) {
                        payload.writeByte(replacement);
                    } else {
                        payload.writeByte(value);
                        payload.writeByte(escaped);
                    }
                } else {
                    payload.writeByte(value);
                }
            }
            byte[] bytes = new byte[payload.readableBytes()];
            payload.readBytes(bytes);
            return bytes;
        } finally {
            payload.release();
        }
    }

    private FrameResult toFrameResult(Object result) {
        if (result instanceof FrameResult frameResult) {
            return frameResult;
        }
        if (result instanceof Number number) {
            return FrameResult.raw(number.intValue());
        }
        throw new IllegalArgumentException(
                "Scripted frame closure must return null, a positive length, or FrameResult");
    }

    private int resolveFixedAnySize(int readableBytes, int[] sizes) {
        if (sizes == null || sizes.length == 0 || readableBytes < sizes[0]) {
            return -1;
        }
        for (int size : sizes) {
            if (readableBytes == size) {
                return size;
            }
        }
        return sizes[0];
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

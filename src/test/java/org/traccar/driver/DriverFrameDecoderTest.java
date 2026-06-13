// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.TooLongFrameException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DriverFrameDecoderTest {

    @Test
    public void rejectsNegativeLengthAdjustment() {
        assertThrows(IllegalArgumentException.class,
                () -> FrameSpec.readLengthField(2, 2, -1));
        assertThrows(IllegalArgumentException.class,
                () -> FrameSpec.readLengthFieldLE(1, 2, -3));
    }

    @Test
    public void rejectsUnterminatedFallbackLineOverDefaultLimit() {
        DriverRegistry registry = mock(DriverRegistry.class);
        when(registry.all()).thenReturn(List.of());

        EmbeddedChannel channel = new EmbeddedChannel(new DriverFrameDecoder(registry, 4));

        assertThrows(TooLongFrameException.class, () ->
                channel.writeInbound(Unpooled.copiedBuffer("abcde", StandardCharsets.US_ASCII)));
    }

    @Test
    public void rejectsLengthFieldFrameOverVariantLimit() {
        DriverRegistry registry = mock(DriverRegistry.class);
        DriverDefinition driver = new DriverDefinition("test");
        VariantDefinition variant = new VariantDefinition("main");
        variant.setFrameByteHint((byte) 0x78);
        variant.setFrameSpec(FrameSpec.readLengthField(2, 2, 1));
        variant.setMaxFrameLength(6);
        driver.addVariant(variant);
        when(registry.all()).thenReturn(List.of(driver));

        EmbeddedChannel channel = new EmbeddedChannel(new DriverFrameDecoder(registry, 8192));

        assertThrows(TooLongFrameException.class, () ->
                channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {0x78, 0x78, 0x00, 0x04})));
    }

    @Test
    public void usesVariantOverrideForLargerFixedFrame() {
        DriverRegistry registry = mock(DriverRegistry.class);
        DriverDefinition driver = new DriverDefinition("test");
        VariantDefinition variant = new VariantDefinition("main");
        variant.setFrameByteHint((byte) 0x01);
        variant.setFrameSpec(FrameSpec.readFixed(6));
        variant.setMaxFrameLength(8);
        driver.addVariant(variant);
        when(registry.all()).thenReturn(List.of(driver));

        EmbeddedChannel channel = new EmbeddedChannel(new DriverFrameDecoder(registry, 4));

        assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {1, 2, 3, 4, 5, 6})));
        ByteBuf frame = channel.readInbound();
        assertEquals(6, frame.readableBytes());

        frame.release();
        channel.finishAndReleaseAll();
    }

    @Test
    public void readsAlternativeFixedFrameSize() {
        DriverRegistry registry = mock(DriverRegistry.class);
        DriverDefinition driver = new DriverDefinition("test");
        VariantDefinition variant = new VariantDefinition("main");
        variant.setFrameByteHint((byte) '$');
        variant.setFrameSpec(FrameSpec.readFixedAny(32, 45));
        variant.setMaxFrameLength(45);
        driver.addVariant(variant);
        when(registry.all()).thenReturn(List.of(driver));

        EmbeddedChannel channel = new EmbeddedChannel(new DriverFrameDecoder(registry, 8192));

        byte[] payload = new byte[45];
        payload[0] = '$';
        assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(payload)));
        ByteBuf frame = channel.readInbound();
        assertEquals(45, frame.readableBytes());

        frame.release();
        channel.finishAndReleaseAll();
    }

    @Test
    public void readsLittleEndianLengthFieldFrame() {
        DriverRegistry registry = mock(DriverRegistry.class);
        DriverDefinition driver = new DriverDefinition("test");
        VariantDefinition variant = new VariantDefinition("main");
        variant.setFrameByteHint((byte) 0x68);
        variant.setFrameSpec(FrameSpec.readLengthFieldLE(1, 2, 1));
        driver.addVariant(variant);
        when(registry.all()).thenReturn(List.of(driver));

        EmbeddedChannel channel = new EmbeddedChannel(new DriverFrameDecoder(registry, 8192));

        assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {0x68, 0x02, 0x00, 0x11, 0x22, 0x33})));
        ByteBuf frame = channel.readInbound();
        assertEquals(6, frame.readableBytes());

        frame.release();
        channel.finishAndReleaseAll();
    }

    @Test
    public void keepsReadUntilTerminatorWhenRequested() {
        DriverRegistry registry = mock(DriverRegistry.class);
        DriverDefinition driver = new DriverDefinition("test");
        VariantDefinition variant = new VariantDefinition("main");
        variant.setFrameByteHint((byte) '#');
        variant.setFrameSpec(FrameSpec.readUntilKeep("##"));
        driver.addVariant(variant);
        when(registry.all()).thenReturn(List.of(driver));

        EmbeddedChannel channel = new EmbeddedChannel(new DriverFrameDecoder(registry, 8192));

        assertTrue(channel.writeInbound(Unpooled.copiedBuffer("#abc##\r\n", StandardCharsets.US_ASCII)));
        ByteBuf frame = channel.readInbound();
        assertEquals("#abc##", frame.toString(StandardCharsets.US_ASCII));
        assertNull(channel.readInbound());

        frame.release();
        channel.finishAndReleaseAll();
    }

    @Test
    public void unescapesDelimiterFramedBinaryFrame() {
        DriverRegistry registry = mock(DriverRegistry.class);
        DriverDefinition driver = new DriverDefinition("test");
        VariantDefinition variant = new VariantDefinition("main");
        variant.setFrameByteHint((byte) 0x7e);
        variant.setFrameSpec(FrameSpec.readEscaped(
                (byte) 0x7e, (byte) 0x7d, Map.of(0x02, 0x7e, 0x01, 0x7d)));
        driver.addVariant(variant);
        when(registry.all()).thenReturn(List.of(driver));

        EmbeddedChannel channel = new EmbeddedChannel(new DriverFrameDecoder(registry, 8192));

        assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {
                0x7e, 0x01, 0x7d, 0x02, 0x7d, 0x01, 0x02, 0x7e})));
        ByteBuf frame = channel.readInbound();
        assertEquals(4, frame.readableBytes());
        assertEquals(0x01, frame.readUnsignedByte());
        assertEquals(0x7e, frame.readUnsignedByte());
        assertEquals(0x7d, frame.readUnsignedByte());
        assertEquals(0x02, frame.readUnsignedByte());

        frame.release();
        channel.finishAndReleaseAll();
    }

    @Test
    public void scriptedFrameCanReplacePayload() {
        DriverRegistry registry = mock(DriverRegistry.class);
        DriverDefinition driver = new DriverDefinition("test");
        VariantDefinition variant = new VariantDefinition("main");
        variant.setFrameByteHint((byte) 0x55);
        variant.setFrameSpec(FrameSpec.readScripted(new groovy.lang.Closure<Object>(null) {
            public Object doCall(FrameBuffer frame) {
                if (frame.readableBytes() < 4) {
                    return null;
                }
                return FrameResult.transformed(4, new byte[] {
                        (byte) frame.getUByte(1), (byte) frame.getUByte(2)});
            }
        }));
        driver.addVariant(variant);
        when(registry.all()).thenReturn(List.of(driver));

        EmbeddedChannel channel = new EmbeddedChannel(new DriverFrameDecoder(registry, 8192));

        assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {0x55, 0x11, 0x22, 0x33})));
        ByteBuf frame = channel.readInbound();
        assertEquals(2, frame.readableBytes());
        assertEquals(0x11, frame.readUnsignedByte());
        assertEquals(0x22, frame.readUnsignedByte());

        frame.release();
        channel.finishAndReleaseAll();
    }

    @Test
    public void prefersDriverMatchingLocalPortAndSkipsLeadingNoise() throws Exception {
        DriverRegistry registry = mock(DriverRegistry.class);

        DriverDefinition h02 = new DriverDefinition("h02");
        h02.setDefaultPort(5013);
        VariantDefinition binary = new VariantDefinition("binary");
        binary.setFrameByteHint((byte) '$');
        binary.setFrameSpec(FrameSpec.readFixed(4));
        h02.addVariant(binary);

        DriverDefinition fallback = new DriverDefinition("fallback");
        VariantDefinition text = new VariantDefinition("text");
        text.setFrameSpec(FrameSpec.readLine());
        fallback.addVariant(text);

        when(registry.all()).thenReturn(List.of(h02, fallback));

        EmbeddedChannel channel = new EmbeddedChannel(new DriverFrameDecoder(registry, 8192) {
            @Override
            protected Integer localPort(io.netty.channel.ChannelHandlerContext ctx) {
                return 5013;
            }
        });

        assertTrue(channel.writeInbound(Unpooled.copiedBuffer("junk$abc", StandardCharsets.US_ASCII)));
        ByteBuf frame = channel.readInbound();
        assertEquals("$abc", frame.toString(StandardCharsets.US_ASCII));

        frame.release();
        channel.finishAndReleaseAll();
    }

    @Test
    public void scopedPortDoesNotFallbackToOtherDrivers() {
        DriverRegistry registry = mock(DriverRegistry.class);

        DriverDefinition other = new DriverDefinition("other");
        other.setDefaultPort(6000);
        VariantDefinition variant = new VariantDefinition("main");
        variant.setFrameByteHint((byte) '$');
        variant.setFrameSpec(FrameSpec.readFixed(4));
        other.addVariant(variant);

        when(registry.all()).thenReturn(List.of(other));

        EmbeddedChannel channel = new EmbeddedChannel(new DriverFrameDecoder(registry, 8192, 5000));

        assertFalse(channel.writeInbound(Unpooled.copiedBuffer("$abc", StandardCharsets.US_ASCII)));
        assertNull(channel.readInbound());
        channel.finishAndReleaseAll();
    }
}

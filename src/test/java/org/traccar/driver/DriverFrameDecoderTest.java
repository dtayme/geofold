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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DriverFrameDecoderTest {

    @Test
    public void rejectsUnterminatedFallbackLineOverDefaultLimit() {
        DriverRegistry registry = mock(DriverRegistry.class);
        when(registry.all()).thenReturn(List.of());

        EmbeddedChannel channel = new EmbeddedChannel(new DriverFrameDecoder(registry, 4));

        assertThrows(TooLongFrameException.class, () ->
                channel.writeInbound(Unpooled.copiedBuffer("abcde", StandardCharsets.US_ASCII)));

        channel.finishAndReleaseAll();
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

        channel.finishAndReleaseAll();
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
}

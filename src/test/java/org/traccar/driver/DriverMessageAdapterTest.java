// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DriverMessageAdapterTest {

    @Test
    public void textFrameIsConvertedToString() {
        DriverRegistry registry = mock(DriverRegistry.class);
        EmbeddedChannel channel = new EmbeddedChannel(new DriverMessageAdapter(registry));

        ByteBuf buf = Unpooled.copiedBuffer("hello world", StandardCharsets.UTF_8);
        channel.writeInbound(buf);

        Object result = channel.readInbound();
        assertEquals("hello world", result);

        channel.finishAndReleaseAll();
    }

    @Test
    public void binaryFrameIsWrappedAsBufReaderAndReleasedCorrectly() {
        DriverDefinition driver = new DriverDefinition("test");
        VariantDefinition variant = new VariantDefinition("binary");
        variant.setFrameSpec(FrameSpec.readFixed(4));
        driver.addVariant(variant);

        DriverRegistry registry = mock(DriverRegistry.class);
        when(registry.get("test")).thenReturn(driver);

        EmbeddedChannel channel = new EmbeddedChannel(new DriverMessageAdapter(registry));
        channel.attr(DriverFrameDecoder.DRIVER_KEY).set("test");
        channel.attr(DriverFrameDecoder.VARIANT_KEY).set("binary");

        ByteBuf buf = Unpooled.buffer(4);
        buf.writeBytes(new byte[]{0x01, 0x02, 0x03, 0x04});
        channel.writeInbound(buf);

        // The adapter must wrap binary frames in BufReader (not convert to String).
        BufReader reader = assertInstanceOf(BufReader.class, channel.readInbound());

        // BufReader retains the underlying ByteBuf, keeping refCnt at 1.
        assertEquals(1, buf.refCnt());

        // Releasing the BufReader must drop the refCnt back to 0.
        reader.release();
        assertEquals(0, buf.refCnt());

        channel.finishAndReleaseAll();
    }

    @Test
    public void missingChannelAttrsYieldsString() {
        // If DRIVER_KEY or VARIANT_KEY is not set, the adapter must fall back to String.
        DriverRegistry registry = mock(DriverRegistry.class);
        EmbeddedChannel channel = new EmbeddedChannel(new DriverMessageAdapter(registry));

        // No attrs set — only DRIVER_KEY present, VARIANT_KEY absent.
        channel.attr(DriverFrameDecoder.DRIVER_KEY).set("test");

        ByteBuf buf = Unpooled.copiedBuffer("data", StandardCharsets.UTF_8);
        channel.writeInbound(buf);

        assertInstanceOf(String.class, channel.readInbound());
        channel.finishAndReleaseAll();
    }

    @Test
    public void unknownDriverNameYieldsString() {
        DriverRegistry registry = mock(DriverRegistry.class);
        when(registry.get("ghost")).thenReturn(null);

        EmbeddedChannel channel = new EmbeddedChannel(new DriverMessageAdapter(registry));
        channel.attr(DriverFrameDecoder.DRIVER_KEY).set("ghost");
        channel.attr(DriverFrameDecoder.VARIANT_KEY).set("main");

        ByteBuf buf = Unpooled.copiedBuffer("data", StandardCharsets.UTF_8);
        channel.writeInbound(buf);

        assertInstanceOf(String.class, channel.readInbound());
        channel.finishAndReleaseAll();
    }
}
